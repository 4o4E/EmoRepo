package top.e404.emorepo.git

import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.withLock
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.api.RebaseResult
import org.eclipse.jgit.lib.BranchConfig
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import top.e404.emorepo.config.AppSettings
import top.e404.emorepo.config.validateHttpsRemote
import top.e404.emorepo.protocol.ProtocolException
import top.e404.emorepo.repository.EmoticonRepository
import top.e404.emorepo.repository.RepositoryLocks

class JGitRepositoryService : GitRepositoryService {
    override fun isValidRepository(repositoryDirectory: File): Boolean = runCatching {
        Git.open(repositoryDirectory).use { git -> git.repository.objectDatabase.exists() }
    }.getOrDefault(false)

    override fun cloneRepository(remoteUrl: String, token: String?, repositoryDirectory: File) {
        validateHttpsRemote(remoteUrl)
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
            credentials(token)?.let(command::setCredentialsProvider)
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
        return RepositoryLocks.forRoot(root).withLock {
            Git.open(root).use { git ->
                val repository = git.repository
                require(!repository.isBare) { "不支持 bare 仓库" }
                if (repository.repositoryState.isRebasing) {
                    git.rebase().setOperation(RebaseCommand.Operation.ABORT).call()
                }
                traced(observer, GitSyncStage.PRECHECK) {
                    require(repository.repositoryState == RepositoryState.SAFE) {
                        "仓库处于未完成状态: ${repository.repositoryState.description}"
                    }
                    recoverStaleIndexLock(repository, observer)
                }
                val warnings = mutableListOf<String>()
                val committed = commitLocalChanges(git, settings, observer)
                val provider = credentials(token)

                traced(observer, GitSyncStage.FETCH) {
                    val fetch = git.fetch().setRemote(DEFAULT_REMOTE)
                    provider?.let(fetch::setCredentialsProvider)
                    fetch.call()
                }

                val branch = repository.branch
                val upstream = BranchConfig(repository.config, branch).trackingBranch
                    ?: "$REMOTE_PREFIX$branch"
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
                    EmoticonRepository(root).listPacks()
                }

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
        private val SUCCESSFUL_PUSH_STATUSES = setOf(
            RemoteRefUpdate.Status.OK,
            RemoteRefUpdate.Status.UP_TO_DATE,
        )
    }
}

private fun org.eclipse.jgit.dircache.DirCache.getEntries(path: String): List<DirCacheEntry> =
    (0 until entryCount).map(::getEntry).filter { it.pathString == path }
