@file:Suppress("DEPRECATION")

package top.e404.emorepo.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.scale
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import top.e404.emorepo.protocol.index.EmoticonRecord
import top.e404.emorepo.repository.EmoticonPack
import top.e404.emorepo.repository.EmoticonRepository

internal suspend fun preloadPackPreviews(
    context: android.content.Context,
    repository: EmoticonRepository,
    pack: EmoticonPack,
    limit: Int = 32,
) = coroutineScope {
    val cacheDirectory = File(context.cacheDir, THUMBNAIL_CACHE_DIRECTORY)
    pack.records.sortedWith(compareBy<EmoticonRecord> { it.order }.thenBy { it.md5 })
        .take(limit)
        .map { record ->
            async(Dispatchers.IO) {
                ThumbnailCache.load(
                    cacheDirectory = cacheDirectory,
                    file = repository.imageFile(pack.name, record.name),
                    key = "${record.md5}:256",
                    diskName = "${record.md5}-256.webp",
                    targetSize = 256,
                )
            }
        }
        .awaitAll()
}

@Composable
fun EmoticonPreview(
    file: File,
    md5: String,
    ext: String,
    targetSizePx: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    if (ext.equals("gif", ignoreCase = true)) {
        AnimatedGifPreview(
            file = file,
            md5 = md5,
            targetSizePx = targetSizePx,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )
    } else {
        FilteredThumbnail(
            file = file,
            md5 = md5,
            targetSizePx = targetSizePx,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )
    }
}

@Composable
fun FilteredThumbnail(
    file: File,
    md5: String,
    targetSizePx: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val context = LocalContext.current
    val cacheDirectory = remember(context.cacheDir) {
        File(context.cacheDir, THUMBNAIL_CACHE_DIRECTORY)
    }
    val image by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = file.path,
        key2 = md5,
        key3 = targetSizePx,
    ) {
        value = withContext(Dispatchers.IO) {
            ThumbnailCache.load(
                cacheDirectory = cacheDirectory,
                file = file,
                key = "$md5:$targetSizePx",
                diskName = "$md5-$targetSizePx.webp",
                targetSize = targetSizePx,
            )
        }
    }
    Box(
        modifier = modifier.background(Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        image?.let { bitmap ->
            Image(
                painter = BitmapPainter(bitmap, filterQuality = FilterQuality.High),
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun AnimatedGifPreview(
    file: File,
    md5: String,
    targetSizePx: Int,
    contentDescription: String?,
    modifier: Modifier,
    contentScale: ContentScale,
) {
    val context = LocalContext.current
    val request = remember(file.path, md5, targetSizePx) {
        ImageRequest.Builder(context)
            .data(file)
            .size(targetSizePx, targetSizePx)
            .memoryCacheKey("gif:$md5:$targetSizePx")
            .diskCachePolicy(CachePolicy.DISABLED)
            .build()
    }
    var animationReady by remember(file.path, md5, targetSizePx) { mutableStateOf(false) }
    Box(modifier = modifier.background(Color.Transparent)) {
        if (!animationReady && targetSizePx <= MAXIMUM_PLACEHOLDER_SIZE) {
            FilteredThumbnail(
                file = file,
                md5 = md5,
                targetSizePx = targetSizePx,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        }
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(),
            onState = { state -> animationReady = state is AsyncImagePainter.State.Success },
        )
    }
}

private const val MAXIMUM_PLACEHOLDER_SIZE = 512

private const val THUMBNAIL_CACHE_DIRECTORY = "emoticon-thumbnails-v1"

private object ThumbnailCache {
    private const val MEMORY_MAXIMUM_BYTES = 12L * 1024L * 1024L
    private const val MEMORY_TARGET_BYTES = 10L * 1024L * 1024L
    private const val DISK_MAXIMUM_BYTES = 256L * 1024L * 1024L
    private const val DISK_TARGET_BYTES = 224L * 1024L * 1024L
    private const val MAXIMUM_DISK_THUMBNAIL_SIZE = 512
    private val entries = LinkedHashMap<String, CacheEntry>(64, 0.75f, true)
    private val keyLocks = ConcurrentHashMap<String, Any>()
    private val decodePermits = Semaphore(4, true)
    private val diskLock = Any()
    private var memoryBytes = 0L
    private var diskDirectoryPath: String? = null
    private var diskBytes = 0L

    fun load(
        cacheDirectory: File,
        file: File,
        key: String,
        diskName: String,
        targetSize: Int,
    ): ImageBitmap? {
        memoryEntry(key)?.let { return it }
        val keyLock = keyLocks.computeIfAbsent(key) { Any() }
        return synchronized(keyLock) {
            memoryEntry(key)?.let { return@synchronized it }
            val canPersist = targetSize <= MAXIMUM_DISK_THUMBNAIL_SIZE
            val diskFile = File(cacheDirectory, diskName)
            if (canPersist) {
                readDiskThumbnail(diskFile)?.let { bitmap ->
                    return@synchronized remember(key, bitmap.asImageBitmap())
                }
            }

            decodePermits.acquire()
            try {
                val decoded = decodeFiltered(file, targetSize) ?: return@synchronized null
                if (canPersist) writeDiskThumbnail(cacheDirectory, diskFile, decoded)
                val image = decoded.asImageBitmap()
                if (canPersist) remember(key, image) else image
            } finally {
                decodePermits.release()
            }
        }
    }

    @Synchronized
    private fun memoryEntry(key: String): ImageBitmap? = entries[key]?.image

    @Synchronized
    private fun remember(key: String, image: ImageBitmap): ImageBitmap {
        val bytes = image.width.toLong() * image.height.toLong() * BYTES_PER_PIXEL
        if (bytes > MEMORY_MAXIMUM_BYTES) return image
        entries.remove(key)?.let { previous -> memoryBytes -= previous.bytes }
        entries[key] = CacheEntry(image, bytes)
        memoryBytes += bytes
        if (memoryBytes > MEMORY_MAXIMUM_BYTES) {
            val iterator = entries.entries.iterator()
            while (memoryBytes > MEMORY_TARGET_BYTES && iterator.hasNext()) {
                val entry = iterator.next()
                memoryBytes -= entry.value.bytes
                iterator.remove()
            }
        }
        return image
    }

    private fun readDiskThumbnail(file: File): Bitmap? {
        if (!file.isFile) return null
        val bitmap = BitmapFactory.decodeFile(file.path)
        if (bitmap == null) {
            file.delete()
            return null
        }
        file.setLastModified(System.currentTimeMillis())
        return bitmap
    }

    private fun writeDiskThumbnail(directory: File, target: File, bitmap: Bitmap) {
        directory.mkdirs()
        if (!directory.isDirectory) return
        val temporary = File(directory, ".${target.name}.${Thread.currentThread().id}.tmp")
        val written = runCatching {
            FileOutputStream(temporary).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.WEBP, WEBP_QUALITY, output))
                output.flush()
                output.fd.sync()
            }
            if (target.exists() && !target.delete()) return@runCatching false
            temporary.renameTo(target)
        }.getOrDefault(false)
        if (!written) {
            temporary.delete()
            return
        }
        synchronized(diskLock) {
            if (diskDirectoryPath == directory.path) {
                diskBytes += target.length()
            } else {
                initializeDiskSize(directory)
            }
            if (diskBytes > DISK_MAXIMUM_BYTES) trimDisk(directory, protectedFile = target)
        }
    }

    private fun initializeDiskSize(directory: File) {
        if (diskDirectoryPath == directory.path) return
        diskDirectoryPath = directory.path
        diskBytes = directory.listFiles().orEmpty().filter(File::isFile).sumOf(File::length)
    }

    private fun trimDisk(directory: File, protectedFile: File) {
        directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it != protectedFile && !it.name.startsWith(".") }
            .sortedBy(File::lastModified)
            .forEach { file ->
                if (diskBytes <= DISK_TARGET_BYTES) return
                val length = file.length()
                if (file.delete()) diskBytes -= length
            }
    }

    private data class CacheEntry(val image: ImageBitmap, val bytes: Long)

    private fun decodeFiltered(file: File, targetSize: Int): Bitmap? {
        if (!file.isFile || targetSize <= 0) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetSize && bounds.outHeight / (sample * 2) >= targetSize) {
            sample *= 2
        }
        val source = BitmapFactory.decodeFile(
            file.path,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null
        val scale = minOf(
            targetSize.toFloat() / source.width,
            targetSize.toFloat() / source.height,
            1f,
        )
        if (scale >= 1f) return source
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        val filtered = source.scale(width, height, filter = true)
        if (filtered !== source) source.recycle()
        return filtered
    }

    private const val BYTES_PER_PIXEL = 4L
    private const val WEBP_QUALITY = 90
}
