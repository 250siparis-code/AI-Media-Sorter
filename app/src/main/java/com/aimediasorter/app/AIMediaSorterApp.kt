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
            .crossfade(true)
            .build()
    }
}
