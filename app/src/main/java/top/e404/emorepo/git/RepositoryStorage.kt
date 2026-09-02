package top.e404.emorepo.git

import java.io.File
import java.io.IOException
import kotlin.math.max

data class RepositoryStorageStats(
    val worktreeBytes: Long,
    val gitBytes: Long,
    val shallow: Boolean,
) {
    val totalBytes: Long
        get() = worktreeBytes + gitBytes

    val automaticThresholdBytes: Long
        get() = max(MINIMUM_AUTOMATIC_GIT_BYTES, worktreeBytes + worktreeBytes / 2L)

    val needsAutomaticMaintenance: Boolean
        get() = !shallow || gitBytes > automaticThresholdBytes
}

internal fun inspectRepositoryStorage(repositoryDirectory: File): RepositoryStorageStats {
    val root = repositoryDirectory.canonicalFile
    require(root.isDirectory) { "仓库目录不存在" }
    val gitDirectory = File(root, ".git").canonicalFile
    require(gitDirectory.isDirectory && gitDirectory.parentFile == root) { "Git 目录不存在" }
    return RepositoryStorageStats(
        worktreeBytes = directorySize(root, excludedRootChild = gitDirectory),
        gitBytes = directorySize(gitDirectory),
        shallow = File(gitDirectory, "shallow").isFile,
    )
}

/** 不跟随越出目标目录的链接，并拒绝目录环，避免统计范围失控。 */
private fun directorySize(directory: File, excludedRootChild: File? = null): Long {
    val canonicalRoot = directory.canonicalFile
    val visited = HashSet<String>()
    val pending = ArrayDeque<File>().apply { add(canonicalRoot) }
    var total = 0L
    while (pending.isNotEmpty()) {
        val current = pending.removeLast()
        val canonical = current.canonicalFile
        if (canonical != canonicalRoot && !canonical.path.startsWith(canonicalRoot.path + File.separator)) {
            throw IOException("仓库空间统计路径越界：${current.path}")
        }
        if (canonical == excludedRootChild) continue
        if (canonical.isDirectory) {
            if (!visited.add(canonical.path)) throw IOException("仓库空间统计发现目录环：${canonical.path}")
            canonical.listFiles()?.forEach(pending::add)
                ?: throw IOException("无法读取目录：${canonical.path}")
        } else if (canonical.isFile) {
            total = Math.addExact(total, canonical.length())
        }
    }
    return total
}

const val LOCAL_HISTORY_DEPTH = 5
const val MINIMUM_AUTOMATIC_GIT_BYTES = 512L * 1024L * 1024L
