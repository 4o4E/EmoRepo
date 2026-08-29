package top.e404.emorepo.experiment.lsposed

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 把 Provider 原文件复制到 QQ 私有缓存，并用租约保护正在使用的文件。 */
internal object QqPanelFileCache {
    private val fileLocks = ConcurrentHashMap<String, Any>()
    private val evictionLock = Any()
    private val activeLeases = mutableMapOf<String, Int>()
    private val trimWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "EmoRepo-Panel-Cache-Trim")
    }
    private val trimRunning = AtomicBoolean(false)
    private val trimPending = AtomicBoolean(false)

    fun acquire(
        context: Context,
        packId: String,
        item: PanelItem,
    ): CachedFileLease {
        val directory = File(context.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }
        check(directory.isDirectory) { "无法创建 EmoRepo QQ 缓存" }
        // itemId 是原图 MD5，仓库顺序和封面 revision 不应复制同一内容。
        val name = sha256("$packId\n${item.id}") + extensionOf(item.fileName)
        synchronized(evictionLock) {
            activeLeases[name] = activeLeases.getOrDefault(name, 0) + 1
        }
        try {
            val target = ensureCached(context, directory, name, packId, item.id)
            scheduleTrim(directory)
            return CachedFileLease(target) { release(directory, name) }
        } catch (error: Throwable) {
            release(directory, name)
            throw error
        }
    }

    private fun ensureCached(
        context: Context,
        directory: File,
        name: String,
        packId: String,
        itemId: String,
    ): File {
        val target = File(directory, name)
        val lock = fileLocks.computeIfAbsent(name) { Any() }
        try {
            synchronized(lock) {
                if (target.isFile && target.length() > 0L) {
                    target.setLastModified(System.currentTimeMillis())
                    return target
                }
                copyOriginal(context, packId, itemId, target)
                return target
            }
        } finally {
            fileLocks.remove(name, lock)
        }
    }

    private fun release(directory: File, name: String) {
        synchronized(evictionLock) {
            val count = activeLeases.getOrDefault(name, 0)
            if (count <= 1) activeLeases.remove(name) else activeLeases[name] = count - 1
        }
        scheduleTrim(directory)
    }

    private fun copyOriginal(context: Context, packId: String, itemId: String, target: File) {
        val temporary = File(target.parentFile, ".${target.name}.part")
        if (temporary.exists()) check(temporary.delete()) { "无法清理未完成的 QQ 表情缓存" }
        val descriptor = requireNotNull(
            context.contentResolver.openFileDescriptor(
                QqPanelRepository.itemUri(packId, itemId),
                "r",
            ),
        ) { "EmoRepo 未返回原文件" }
        try {
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output, COPY_BUFFER_BYTES)
                    output.fd.sync()
                }
            }
            check(temporary.length() > 0L) { "EmoRepo 原文件为空" }
            if (target.exists()) check(target.delete()) { "无法替换旧 QQ 表情缓存" }
            check(temporary.renameTo(target)) { "无法提交 QQ 表情缓存" }
            target.setLastModified(System.currentTimeMillis())
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    /** 调用方持有的所有租约都参与保护，允许缓存暂时超过上限直到释放。 */
    private fun scheduleTrim(directory: File) {
        trimPending.set(true)
        if (!trimRunning.compareAndSet(false, true)) return
        trimWorker.execute {
            try {
                do {
                    trimPending.set(false)
                    runCatching { trim(directory) }
                        .onFailure { error -> QqPanelIntegration.log("清理 QQ 表情缓存失败", error) }
                } while (trimPending.get())
            } finally {
                trimRunning.set(false)
                if (trimPending.get()) scheduleTrim(directory)
            }
        }
    }

    private fun trim(directory: File) {
        val files = directory.listFiles { file -> file.isFile && !file.name.endsWith(".part") }
            .orEmpty()
            .map { file -> CacheEntry(file, file.lastModified(), file.length()) }
            .sortedWith(compareBy<CacheEntry> { it.lastModified }.thenBy { it.file.name })
        var total = files.sumOf(CacheEntry::length)
        if (total <= MAXIMUM_CACHE_BYTES) return
        for (entry in files) {
            if (total <= MAXIMUM_CACHE_BYTES) break
            val deleted = synchronized(evictionLock) {
                !activeLeases.containsKey(entry.file.name) && entry.file.delete()
            }
            if (deleted) total -= entry.length
        }
    }

    private data class CacheEntry(
        val file: File,
        val lastModified: Long,
        val length: Long,
    )

    private fun extensionOf(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return if (extension.matches(Regex("[a-z0-9]{1,8}"))) ".$extension" else ".bin"
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private const val CACHE_DIRECTORY = "emorepo-panel-v1"
    private const val MAXIMUM_CACHE_BYTES = 128L * 1024L * 1024L
    private const val COPY_BUFFER_BYTES = 64 * 1024
}

internal class CachedFileLease(
    val file: File,
    private val onClose: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) onClose()
    }
}
