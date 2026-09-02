@file:Suppress("DEPRECATION")

package top.e404.emorepo.experiment.lsposed

import android.annotation.TargetApi
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 保存轻量首帧：GIF 离屏后释放动画，回屏先显示首帧，再异步恢复动画。
 * 固定 128 px 使一个长表情包的首帧可以稳定保留在较小内存内。
 */
internal object QqPanelFirstFrameCache {
    private val entries = LinkedHashMap<String, Entry>(64, 0.75f, true)
    private val keyLocks = ConcurrentHashMap<String, Any>()
    private val diskLock = Any()
    private val diskWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "EmoRepo-FirstFrame-Disk").apply { isDaemon = true }
    }
    private var memoryBytes = 0L
    private var diskDirectoryPath: String? = null
    private var diskBytes = 0L

    @Synchronized
    fun get(itemId: String): Bitmap? = entries[itemId]?.bitmap

    fun load(cacheRoot: File, source: File, itemId: String): Bitmap? {
        return load(cacheRoot, itemId) { decodeFirstFrame(source) }
    }

    /** 预加载只读取 Provider 原文件生成首帧，不把完整原图复制进 QQ 文件缓存。 */
    fun preload(context: Context, item: PanelItem): Boolean = load(context.cacheDir, item.id) {
        val uri = QqPanelRepository.itemUri(item.packId, item.id)
        if (Build.VERSION.SDK_INT >= 28) {
            decodeFirstFrame(ImageDecoder.createSource(context.contentResolver, uri))
        } else {
            context.contentResolver.openFileDescriptor(uri, "r")?.use(::decodeFirstFrameLegacy)
        }
    } != null

    private fun load(
        cacheRoot: File,
        itemId: String,
        decoder: () -> Bitmap?,
    ): Bitmap? {
        get(itemId)?.let { return it }
        val keyLock = keyLocks.computeIfAbsent(itemId) { Any() }
        return try {
            synchronized(keyLock) {
                get(itemId)?.let { return@synchronized it }
                val directory = File(cacheRoot, CACHE_DIRECTORY)
                val diskFile = File(directory, "${safeDiskKey(itemId)}.thumb")
                readDisk(diskFile)?.let { bitmap -> return@synchronized remember(itemId, bitmap) }
                val bitmap = decoder() ?: return@synchronized null
                val remembered = remember(itemId, bitmap)
                diskWorker.execute { writeDisk(directory, diskFile, bitmap) }
                remembered
            }
        } finally {
            keyLocks.remove(itemId, keyLock)
        }
    }

    @Synchronized
    private fun remember(itemId: String, bitmap: Bitmap): Bitmap {
        val bytes = bitmap.allocationByteCount.toLong()
        if (bytes > MEMORY_MAXIMUM_BYTES) return bitmap
        entries.remove(itemId)?.let { memoryBytes -= it.bytes }
        entries[itemId] = Entry(bitmap, bytes)
        memoryBytes += bytes
        val iterator = entries.entries.iterator()
        while (memoryBytes > MEMORY_MAXIMUM_BYTES && iterator.hasNext()) {
            val removed = iterator.next().value
            memoryBytes -= removed.bytes
            iterator.remove()
        }
        return bitmap
    }

    private fun decodeFirstFrame(source: File): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            return decodeFirstFrame(ImageDecoder.createSource(source))
        }
        return decodeFirstFrameLegacy(source)
    }

    private fun decodeFirstFrameLegacy(source: File): Bitmap? {
        if (!source.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val decoded = BitmapFactory.decodeFile(
            source.path,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null
        return scaleFirstFrame(decoded)
    }

    private fun decodeFirstFrameLegacy(descriptor: ParcelFileDescriptor): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor, null, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_SET)
        val decoded = BitmapFactory.decodeFileDescriptor(
            descriptor.fileDescriptor,
            null,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null
        return scaleFirstFrame(decoded)
    }

    /** 与平台动画 Drawable 使用同一解码器，并固定输出 sRGB，避免 GIF 调色板蓝通道丢失。 */
    @TargetApi(28)
    private fun decodeFirstFrame(source: ImageDecoder.Source): Bitmap? =
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val scale = minOf(
                FIRST_FRAME_SIZE_PX.toFloat() / info.size.width,
                FIRST_FRAME_SIZE_PX.toFloat() / info.size.height,
                1f,
            )
            decoder.setTargetSize(
                (info.size.width * scale).toInt().coerceAtLeast(1),
                (info.size.height * scale).toInt().coerceAtLeast(1),
            )
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (
            width / (sample * 2) >= FIRST_FRAME_SIZE_PX &&
            height / (sample * 2) >= FIRST_FRAME_SIZE_PX
        ) {
            sample *= 2
        }
        return sample
    }

    private fun scaleFirstFrame(decoded: Bitmap): Bitmap {
        val scale = minOf(
            FIRST_FRAME_SIZE_PX.toFloat() / decoded.width,
            FIRST_FRAME_SIZE_PX.toFloat() / decoded.height,
            1f,
        )
        if (scale >= 1f) return decoded
        val width = (decoded.width * scale).toInt().coerceAtLeast(1)
        val height = (decoded.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(decoded, width, height, true).also { scaled ->
            if (scaled !== decoded) decoded.recycle()
        }
    }

    private fun readDisk(file: File): Bitmap? {
        if (!file.isFile) return null
        val bitmap = BitmapFactory.decodeFile(file.path)
        if (bitmap == null) {
            removeDiskFile(file)
            return null
        }
        file.setLastModified(System.currentTimeMillis())
        return bitmap
    }

    private fun writeDisk(directory: File, target: File, bitmap: Bitmap) {
        directory.mkdirs()
        if (!directory.isDirectory) return
        val temporary = File(directory, ".${target.name}.${Thread.currentThread().id}.tmp")
        val written = runCatching {
            FileOutputStream(temporary).use { output ->
                val (format, quality) = losslessEncoding()
                check(bitmap.compress(format, quality, output))
                output.flush()
                output.fd.sync()
            }
            if (target.exists() && !removeDiskFile(target)) return@runCatching false
            temporary.renameTo(target)
        }.getOrDefault(false)
        if (!written) {
            temporary.delete()
            return
        }
        synchronized(diskLock) {
            if (diskDirectoryPath != directory.path) initializeDiskSize(directory)
            else diskBytes += target.length()
            if (diskBytes > DISK_MAXIMUM_BYTES) trimDisk(directory, target)
        }
    }

    private fun initializeDiskSize(directory: File) {
        diskDirectoryPath = directory.path
        diskBytes = directory.listFiles().orEmpty().filter(File::isFile).sumOf(File::length)
    }

    private fun trimDisk(directory: File, protectedFile: File) {
        val files = directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it != protectedFile && !it.name.startsWith(".") }
            .map { file -> DiskEntry(file, file.lastModified(), file.length()) }
            .sortedWith(compareBy<DiskEntry> { it.lastModified }.thenBy { it.file.name })
        for (entry in files) {
            if (diskBytes <= DISK_TARGET_BYTES) break
            if (entry.file.delete()) diskBytes -= entry.length
        }
    }

    private fun removeDiskFile(file: File): Boolean {
        val length = file.length()
        if (!file.delete()) return false
        synchronized(diskLock) {
            if (diskDirectoryPath == file.parentFile?.path) {
                diskBytes = (diskBytes - length).coerceAtLeast(0L)
            }
        }
        return true
    }

    private fun safeDiskKey(itemId: String): String =
        if (itemId.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) itemId else sha256(itemId)

    private fun losslessEncoding(): Pair<Bitmap.CompressFormat, Int> =
        when (qqPanelPreviewEncoding(Build.VERSION.SDK_INT)) {
            QqPanelPreviewEncoding.LOSSLESS_WEBP -> losslessWebpEncoding()
            QqPanelPreviewEncoding.PNG -> Bitmap.CompressFormat.PNG to LOSSLESS_QUALITY
        }

    @TargetApi(30)
    private fun losslessWebpEncoding(): Pair<Bitmap.CompressFormat, Int> =
        Bitmap.CompressFormat.WEBP_LOSSLESS to LOSSLESS_QUALITY

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class Entry(val bitmap: Bitmap, val bytes: Long)
    private data class DiskEntry(
        val file: File,
        val lastModified: Long,
        val length: Long,
    )

    private const val CACHE_DIRECTORY = "emorepo-panel-first-frame-v3"
    private const val FIRST_FRAME_SIZE_PX = 128
    private const val MEMORY_MAXIMUM_BYTES = 24L * 1024L * 1024L
    private const val DISK_MAXIMUM_BYTES = 96L * 1024L * 1024L
    private const val DISK_TARGET_BYTES = 80L * 1024L * 1024L
    private const val LOSSLESS_QUALITY = 100
}
