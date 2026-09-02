package top.e404.emorepo.git

import java.io.File
import java.io.FileOutputStream
import java.util.Date
import kotlin.concurrent.withLock
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.api.RebaseResult
import org.eclipse.jgit.lib.BranchConfig
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.lib.GcConfig
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.internal.storage.file.GC
import org.eclipse.jgit.storage.pack.PackConfig
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.TagOpt
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import top.e404.emorepo.config.AppSettings
import top.e404.emorepo.config.validateHttpsRemote
import top.e404.emorepo.protocol.ProtocolException
import top.e404.emorepo.repository.EmoticonRepository
import top.e404.emorepo.repository.RepositoryLocks

class JGitRepositoryService(
    private val remoteValidator: (String) -> Unit = ::validateHttpsRemote,
) : GitRepositoryService {
    override fun isValidRepository(repositoryDirectory: File): Boolean = runCatching {
        Git.open(repositoryDirectory).use { git -> git.repository.objectDatabase.exists() }
    }.getOrDefault(false)

    override fun cloneRepository(remoteUrl: String, token: String?, repositoryDirectory: File) {
        remoteValidator(remoteUrl)
        val provider = credentials(token)
        val defaultBranch = resolveRemoteDefaultBranch(remoteUrl, provider)
        val target = repositoryDirectory.canonicalFile
        val parent = requireNotNull(target.parentFile) { "仓库目录缺少父目录" }.canonicalFile
        parent.mkdirs()
        require(parent.isDirectory) { "无法创建仓库父目录" }
        val staging = File(parent, ".${target.name}.emorepo-clone").canonicalFile
        require(staging.parentFile == parent) { "克隆暂存目录越界" }
        if (staging.exists() && !staging.deleteRecursively()) {
            error("无法清理上次失败的克隆暂存目录")
        }
        if (target.exists()) {
            require(target.isDirectory && target.list().orEmpty().isEmpty()) { "仓库目录已存在且不为空" }
            require(target.delete()) { "无法准备仓库目录" }
        }

        try {
            val command = Git.cloneRepository()
                .setURI(remoteUrl)
                .setDirectory(staging)
                .setCloneAllBranches(false)
                .setBranch(defaultBranch)
                .setBranchesToClone(listOf(defaultBranch))
                .setNoTags()
                .setDepth(LOCAL_HISTORY_DEPTH)
            provider?.let(command::setCredentialsProvider)
            command.call().use { git ->
                require(git.repository.objectDatabase.exists()) { "克隆结果不是有效 Git 仓库" }
            }
            require(staging.renameTo(target)) { "无法将克隆结果切换为正式仓库" }
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    override fun sync(
        repositoryDirectory: File,
        settings: AppSettings,
        token: String?,
        observer: GitSyncObserver,
    ): GitSyncResult {
        val root = repositoryDirectory.canonicalFile
        val contentRepository = EmoticonRepository(root)
        return RepositoryLocks.forSync(root).withLock {
            Git.open(root).use { git ->
                val repository = git.repository
                require(!repository.isBare) { "不支持 bare 仓库" }
                var committed = contentRepository.withGitMutation {
                    if (repository.repositoryState.isRebasing) {
                        git.rebase().setOperation(RebaseCommand.Operation.ABORT).call()
                    }
                    traced(observer, GitSyncStage.PRECHECK) {
                        require(repository.repositoryState == RepositoryState.SAFE) {
                            "仓库处于未完成状态: ${repository.repositoryState.description}"
                        }
                        recoverStaleIndexLock(repository, observer)
                    }
                    commitLocalChanges(git, settings, observer)
                }
                val warnings = mutableListOf<String>()
                val provider = credentials(token)

                // fetch 只修改 Git 对象和远端引用，不能用网络等待占住仓库写入锁。
                traced(observer, GitSyncStage.FETCH) {
                    val fetch = git.fetch().setRemote(DEFAULT_REMOTE)
                    provider?.let(fetch::setCredentialsProvider)
                    fetch.call()
                }

                val branch = repository.branch
                val upstream = BranchConfig(repository.config, branch).trackingBranch
                    ?: "$REMOTE_PREFIX$branch"
                contentRepository.withGitMutation {
                    // fetch 期间允许本地写入；rebase 前补提一次，避免带脏工作树进入 rebase。
                    committed = commitLocalChanges(git, settings, observer) || committed
                    traced(observer, GitSyncStage.REBASE, mapOf("branch" to branch)) {
                        var result = git.rebase().setUpstream(upstream).call()
                        while (result.status == RebaseResult.Status.STOPPED) {
                            warnings += resolveStoppedRebase(git)
                            result = git.rebase().setOperation(RebaseCommand.Operation.CONTINUE).call()
                        }
                        if (!result.status.isSuccessful) {
                            git.rebase().setOperation(RebaseCommand.Operation.ABORT).call()
                            throw ProtocolException("rebase 失败: ${result.status}")
                        }
                    }
                    traced(observer, GitSyncStage.VALIDATE) {
                        // 根索引存在时必须与 rebase 后的实际表情包目录严格一致。
                        contentRepository.validateLivePacks()
                    }
                }

                // push 只上传已经提交的对象；期间新增的工作树修改由下一次同步处理。
                traced(observer, GitSyncStage.PUSH) {
                    val push = git.push().setRemote(DEFAULT_REMOTE)
                    provider?.let(push::setCredentialsProvider)
                    val rejected = push.call()
                        .flatMap { it.remoteUpdates }
                        .filterNot { it.status in SUCCESSFUL_PUSH_STATUSES }
                    if (rejected.isNotEmpty()) {
                        throw ProtocolException(
                            "push 失败: " + rejected.joinToString { "${it.remoteName}=${it.status}" },
                        )
                    }
                }
                GitSyncResult(committed = committed, warnings = warnings)
            }
        }
    }

    override fun inspectStorage(repositoryDirectory: File): RepositoryStorageStats =
        inspectRepositoryStorage(repositoryDirectory)

    override fun optimizeLocalHistory(
        repositoryDirectory: File,
        token: String?,
    ): GitMaintenanceResult {
        val root = repositoryDirectory.canonicalFile
        val before = inspectStorage(root)
        if (!before.needsAutomaticMaintenance) {
            return GitMaintenanceResult(false, before, before, "仓库空间未达到自动优化条件")
        }
        val requiredFreeBytes = minOf(before.gitBytes, before.worktreeBytes) +
            MAINTENANCE_FREE_SPACE_RESERVE_BYTES
        require(root.usableSpace >= requiredFreeBytes) {
            "可用空间不足，至少需要 ${requiredFreeBytes / (1024L * 1024L)} MiB"
        }
        val contentRepository = EmoticonRepository(root)
        return RepositoryLocks.forSync(root).withLock {
            contentRepository.withGitMutation {
                Git.open(root).use { git ->
                    val repository = git.repository
                    require(!repository.isBare) { "不支持 bare 仓库" }
                    require(repository.repositoryState == RepositoryState.SAFE) {
                        "仓库处于未完成状态: ${repository.repositoryState.description}"
                    }
                    require(git.status().call().isClean) { "仓库存在未提交修改，暂不优化历史" }
                    contentRepository.validateLivePacks()
                    val branch = repository.branch
                    val upstream = BranchConfig(repository.config, branch).trackingBranch
                        ?: "$REMOTE_PREFIX$branch"
                    requireSameCommit(repository, upstream)
                    val refSpec = RefSpec("+refs/heads/$branch:$upstream")
                    val fetch = git.fetch()
                        .setRemote(DEFAULT_REMOTE)
                        .setRefSpecs(refSpec)
                        .setTagOpt(TagOpt.NO_TAGS)
                        .setDepth(LOCAL_HISTORY_DEPTH)
                    credentials(token)?.let(fetch::setCredentialsProvider)
                    fetch.call()
                    requireSameCommit(repository, upstream)
                    contentRepository.validateLivePacks()

                    repository.config.apply {
                        setString("remote", DEFAULT_REMOTE, "tagOpt", TagOpt.NO_TAGS.option())
                        setStringList("remote", DEFAULT_REMOTE, "fetch", listOf(refSpec.toString()))
                        setInt("emorepo", null, "historyDepth", LOCAL_HISTORY_DEPTH)
                        setString("gc", null, "reflogExpire", "now")
                        setString("gc", null, "reflogExpireUnreachable", "now")
                        setString("gc", null, "pruneExpire", "now")
                        save()
                    }
                    removeUnusedLocalRefs(git, branch, upstream)
                    expireLocalReflogs(repository)
                    compactObjectDatabase(repository)
                    require(repository.repositoryState == RepositoryState.SAFE) { "GC 后仓库状态异常" }
                    require(git.status().call().isClean) { "GC 后工作区出现修改" }
                    requireSameCommit(repository, upstream)
                    contentRepository.validateLivePacks()
                }
            }
            val after = inspectStorage(root)
            require(after.shallow) { "优化完成后仓库仍不是 shallow" }
            GitMaintenanceResult(
                optimized = true,
                before = before,
                after = after,
                message = "Git 历史已保留最近 $LOCAL_HISTORY_DEPTH 个提交",
            )
        }
    }

    private fun requireSameCommit(repository: org.eclipse.jgit.lib.Repository, upstream: String) {
        val head = requireNotNull(repository.resolve(Constants.HEAD)) { "无法解析本地 HEAD" }
        val remote = requireNotNull(repository.resolve(upstream)) { "无法解析远端跟踪分支 $upstream" }
        require(head == remote) { "本地 HEAD 与远端跟踪分支不一致，暂不优化历史" }
    }

    private fun resolveRemoteDefaultBranch(
        remoteUrl: String,
        provider: CredentialsProvider?,
    ): String {
        val command = Git.lsRemoteRepository().setRemote(remoteUrl)
        provider?.let(command::setCredentialsProvider)
        val refs = command.call()
        val head = refs.firstOrNull { it.name == Constants.HEAD }
            ?: throw ProtocolException("远端没有 HEAD，无法确定默认分支")
        val symbolicTarget = head.takeIf { it.isSymbolic }?.target?.name
            ?.takeIf { it.startsWith(Constants.R_HEADS) }
        if (symbolicTarget != null) return symbolicTarget
        val matchingHeads = refs.filter { ref ->
            ref.name.startsWith(Constants.R_HEADS) && ref.objectId == head.objectId
        }
        if (matchingHeads.size == 1) return matchingHeads.single().name
        throw ProtocolException("远端 HEAD 没有唯一默认分支")
    }

    private fun expireLocalReflogs(repository: org.eclipse.jgit.lib.Repository) {
        val logs = File(repository.directory, "logs").canonicalFile
        require(logs.parentFile == repository.directory.canonicalFile) { "Git reflog 路径越界" }
        if (logs.exists() && !logs.deleteRecursively()) throw ProtocolException("无法清理本地 Git reflog")
    }

    private fun removeUnusedLocalRefs(git: Git, branch: String, upstream: String) {
        val repository = git.repository
        val currentBranch = Constants.R_HEADS + branch
        val otherLocalBranches = repository.refDatabase.getRefsByPrefix(Constants.R_HEADS)
            .map { it.name }
            .filterNot { it == currentBranch }
        require(otherLocalBranches.isEmpty()) {
            "仓库存在额外本地分支，拒绝清理历史：${otherLocalBranches.joinToString()}"
        }
        val originHead = Constants.R_REMOTES + "$DEFAULT_REMOTE/HEAD"
        repository.refDatabase.getRefsByPrefix(Constants.R_REMOTES + "$DEFAULT_REMOTE/")
            .map { it.name }
            .filterNot { it == upstream || it == originHead }
            .forEach { refName ->
                val result = repository.updateRef(refName).apply { isForceUpdate = true }.delete()
                require(result in setOf(
                    org.eclipse.jgit.lib.RefUpdate.Result.FORCED,
                    org.eclipse.jgit.lib.RefUpdate.Result.NO_CHANGE,
                )) {
                    "无法删除无用的本地远端引用：$refName"
                }
            }
        val tags = git.tagList().call().map { org.eclipse.jgit.lib.Repository.shortenRefName(it.name) }
        if (tags.isNotEmpty()) git.tagDelete().setTags(*tags.toTypedArray()).call()
    }

    /**
     * 公共 GarbageCollectCommand 没有 pack 过期时间入口；固定版本 JGit 的文件 GC
     * 可以在保留当前可达对象的前提下立即替换旧 pack，不手工删除 objects 文件。
     */
    @Suppress("DEPRECATION")
    private fun compactObjectDatabase(repository: org.eclipse.jgit.lib.Repository) {
        val fileRepository = repository as? FileRepository
            ?: throw ProtocolException("当前 Git 仓库不支持文件对象维护")
        val now = Date()
        val packConfig = PackConfig(repository).apply {
            setPreserveOldPacks(false)
            setPrunePreserved(true)
        }
        GC(fileRepository).apply {
            setPackConfig(packConfig)
            setGcConfig(GcConfig(repository.config))
            setExpire(now)
            setPackExpire(now)
        }.gc().get()
    }

    private fun commitLocalChanges(
        git: Git,
        settings: AppSettings,
        observer: GitSyncObserver,
    ): Boolean {
        val dirty = traced(observer, GitSyncStage.STATUS) { !git.status().call().isClean }
        if (!dirty) {
            observer.onEvent(GitSyncStageEvent(GitSyncStage.STAGE, GitSyncStageOutcome.SKIPPED))
            observer.onEvent(GitSyncStageEvent(GitSyncStage.COMMIT, GitSyncStageOutcome.SKIPPED))
            return false
        }
        traced(observer, GitSyncStage.STAGE) {
            git.add().addFilepattern(".").call()
            git.add().addFilepattern(".").setUpdate(true).call()
        }
        if (git.status().call().isClean) {
            observer.onEvent(GitSyncStageEvent(GitSyncStage.COMMIT, GitSyncStageOutcome.SKIPPED))
            return false
        }
        traced(observer, GitSyncStage.COMMIT) {
            val identity = PersonIdent(settings.authorName, settings.authorEmail)
            git.commit()
                .setMessage(settings.commitMessage)
                .setAuthor(identity)
                .setCommitter(identity)
                .call()
        }
        return true
    }

    private fun recoverStaleIndexLock(
        repository: org.eclipse.jgit.lib.Repository,
        observer: GitSyncObserver,
    ) {
        val lock = File(repository.directory, "index.lock")
        if (!lock.exists()) return
        require(repository.repositoryState == RepositoryState.SAFE) { "仓库状态不安全，拒绝清理索引锁" }
        repository.readDirCache()
        val ageMillis = (System.currentTimeMillis() - lock.lastModified()).coerceAtLeast(0L)
        if (!lock.delete()) throw ProtocolException("无法清理陈旧 Git 索引锁")
        observer.onEvent(
            GitSyncStageEvent(
                stage = GitSyncStage.PRECHECK,
                outcome = GitSyncStageOutcome.WARNING,
                fields = mapOf("recoveredStaleIndexLock" to "true", "lockAgeMillis" to ageMillis.toString()),
            ),
        )
    }

    private inline fun <T> traced(
        observer: GitSyncObserver,
        stage: GitSyncStage,
        fields: Map<String, String> = emptyMap(),
        operation: () -> T,
    ): T {
        observer.onEvent(GitSyncStageEvent(stage, GitSyncStageOutcome.STARTED, fields = fields))
        val started = System.nanoTime()
        return try {
            operation().also {
                observer.onEvent(
                    GitSyncStageEvent(
                        stage = stage,
                        outcome = GitSyncStageOutcome.SUCCEEDED,
                        durationMillis = (System.nanoTime() - started) / 1_000_000L,
                        fields = fields,
                    ),
                )
            }
        } catch (error: Throwable) {
            observer.onEvent(
                GitSyncStageEvent(
                    stage = stage,
                    outcome = GitSyncStageOutcome.FAILED,
                    durationMillis = (System.nanoTime() - started) / 1_000_000L,
                    fields = fields,
                    error = error,
                ),
            )
            throw error
        }
    }

    private fun resolveStoppedRebase(git: Git): List<String> {
        val repository = git.repository
        val conflicts = git.status().call().conflicting
        if (conflicts.isEmpty()) throw ProtocolException("rebase 已停止但没有可解析冲突")
        val warnings = mutableListOf<String>()
        conflicts.sorted().forEach { path ->
            val entries = repository.readDirCache().getEntries(path).associateBy(DirCacheEntry::getStage)
            // rebase 中 stage 2 是远端基线，stage 3 是正在重放的本地提交。
            val resolution = ProtocolConflictResolver.resolve(
                path = path,
                base = entries[1]?.let { repository.open(it.objectId).bytes },
                local = entries[3]?.let { repository.open(it.objectId).bytes },
                remote = entries[2]?.let { repository.open(it.objectId).bytes },
            )
            warnings += resolution.warnings
            val file = safeWorkTreeFile(repository.workTree, path)
            if (resolution.content == null) {
                if (file.exists() && !file.delete()) throw ProtocolException("无法删除冲突文件: $path")
                git.rm().addFilepattern(path).call()
            } else {
                file.parentFile?.mkdirs()
                FileOutputStream(file).use { output ->
                    output.write(resolution.content)
                    output.fd.sync()
                }
                git.add().addFilepattern(path).call()
            }
        }
        return warnings
    }

    private fun safeWorkTreeFile(root: File, path: String): File {
        val canonicalRoot = root.canonicalFile
        val file = File(canonicalRoot, path).canonicalFile
        require(file.path.startsWith(canonicalRoot.path + File.separator)) { "冲突路径越界: $path" }
        return file
    }

    private fun credentials(token: String?): CredentialsProvider? = token
        ?.takeIf { it.isNotBlank() }
        ?.let { UsernamePasswordCredentialsProvider(TOKEN_USERNAME, it) }

    companion object {
        private const val DEFAULT_REMOTE = Constants.DEFAULT_REMOTE_NAME
        private const val REMOTE_PREFIX = Constants.R_REMOTES + "$DEFAULT_REMOTE/"
        private const val TOKEN_USERNAME = "oauth2"
        private const val MAINTENANCE_FREE_SPACE_RESERVE_BYTES = 128L * 1024L * 1024L
        private val SUCCESSFUL_PUSH_STATUSES = setOf(
            RemoteRefUpdate.Status.OK,
            RemoteRefUpdate.Status.UP_TO_DATE,
        )
    }
}

private fun org.eclipse.jgit.dircache.DirCache.getEntries(path: String): List<DirCacheEntry> =
    (0 until entryCount).map(::getEntry).filter { it.pathString == path }
