package com.aimediasorter.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.aimediasorter.app.data.repository.MediaRepository

class AIMediaSorterApp : Application(), ImageLoaderFactory {

    lateinit var repository: MediaRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = MediaRepository(this)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .memoryCache { coil.memory.MemoryCache.Builder(this).maxSizePercent(0.15).build() }
            .diskCache(null) // Local private thumbnails remain in bounded memory only.
            .crossfade(false)
            .build()
    }
}
