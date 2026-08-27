package top.e404.emorepo

import android.app.Application
import android.content.Context
import android.os.Build
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import top.e404.emorepo.config.SettingsStore
import top.e404.emorepo.git.GitSyncScheduler

class EmoRepoApplication : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        GitSyncScheduler.updatePeriodic(this, SettingsStore(this).load())
    }

    override fun newImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .memoryCache {
            MemoryCache.Builder()
                .maxSizeBytes(ANIMATED_MEMORY_CACHE_BYTES)
                .build()
        }
        .components {
            if (Build.VERSION.SDK_INT >= 28) {
                add(AnimatedImageDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
        }
        .build()

    companion object {
        private const val ANIMATED_MEMORY_CACHE_BYTES = 12L * 1024L * 1024L
    }
}
