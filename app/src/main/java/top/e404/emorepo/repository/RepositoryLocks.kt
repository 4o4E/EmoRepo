package top.e404.emorepo.repository

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

object RepositoryLocks {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    fun forRoot(rootDirectory: File): ReentrantLock =
        locks.computeIfAbsent(rootDirectory.canonicalPath) { ReentrantLock() }
}
