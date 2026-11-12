package io.github.akrishna87.mybooks

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

class FolioApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Catalog.init(this)
    }

    /**
     * Covers never change, but the Internet Archive says to keep them for only five minutes (and
     * Gutenberg doesn't say), so without this they'd download again on almost every visit. Keep
     * them on disk whatever the servers say.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .respectCacheHeaders(false)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.20).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("covers"))
                .maxSizeBytes(200L * 1024 * 1024)
                .build()
        }
        .build()
}
