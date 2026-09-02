package top.e404.emorepo.repository

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

object RepositoryLocks {
    private val mutationLocks = ConcurrentHashMap<String, ReentrantLock>()
    private val syncLocks = ConcurrentHashMap<String, ReentrantLock>()
    private val mutationVersions = ConcurrentHashMap<String, AtomicLong>()

    /** 只用于串行化写入；读取路径禁止获取此锁。 */
    fun forMutation(rootDirectory: File): ReentrantLock =
        mutationLocks.computeIfAbsent(rootDirectory.canonicalPath) { ReentrantLock() }

    /** 网络同步串行化不能复用写入锁，否则网络等待会阻塞本地修改。 */
    fun forSync(rootDirectory: File): ReentrantLock =
        syncLocks.computeIfAbsent(rootDirectory.canonicalPath) { ReentrantLock() }

    fun mutationVersion(rootDirectory: File): Long =
        mutationVersions.computeIfAbsent(rootDirectory.canonicalPath) { AtomicLong() }.get()

    internal fun beginMutation(rootDirectory: File) {
        val version = mutationVersions.computeIfAbsent(rootDirectory.canonicalPath) { AtomicLong() }
            .incrementAndGet()
        check(version and 1L == 1L) { "repository mutation version must become odd" }
    }

    internal fun endMutation(rootDirectory: File) {
        val version = mutationVersions.computeIfAbsent(rootDirectory.canonicalPath) { AtomicLong() }
            .incrementAndGet()
        check(version and 1L == 0L) { "repository mutation version must become even" }
    }
}
