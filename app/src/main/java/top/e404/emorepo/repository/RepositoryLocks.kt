package top.e404.emorepo.repository

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

object RepositoryLocks {
    private val contentLocks = ConcurrentHashMap<String, ReentrantLock>()
    private val syncLocks = ConcurrentHashMap<String, ReentrantLock>()

    fun forRoot(rootDirectory: File): ReentrantLock =
        contentLocks.computeIfAbsent(rootDirectory.canonicalPath) { ReentrantLock() }

    /** 网络同步串行化不能复用内容锁，否则网络等待会阻塞 App 和 QQ 读取。 */
    fun forSync(rootDirectory: File): ReentrantLock =
        syncLocks.computeIfAbsent(rootDirectory.canonicalPath) { ReentrantLock() }
}
