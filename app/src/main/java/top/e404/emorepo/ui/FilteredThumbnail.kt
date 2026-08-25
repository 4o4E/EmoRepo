package top.e404.emorepo.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.LinkedHashMap

@Composable
fun FilteredThumbnail(
    file: File,
    md5: String,
    targetSizePx: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val image by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = file.path,
        key2 = md5,
        key3 = targetSizePx,
    ) {
        value = withContext(Dispatchers.IO) {
            ThumbnailCache.load(file, "$md5:$targetSizePx", targetSizePx)
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
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private object ThumbnailCache {
    private const val MAXIMUM_ENTRIES = 96
    private val entries = object : LinkedHashMap<String, ImageBitmap>(MAXIMUM_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
            size > MAXIMUM_ENTRIES
    }

    @Synchronized
    fun load(file: File, key: String, targetSize: Int): ImageBitmap? {
        entries[key]?.let { return it }
        val decoded = decodeFiltered(file, targetSize) ?: return null
        val image = decoded.asImageBitmap()
        entries[key] = image
        return image
    }

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
}
