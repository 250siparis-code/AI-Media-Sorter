package com.aimediasorter.app.domain.storage

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import java.io.IOException
import java.io.InputStream

/** Byte verification must use original media, not a provider-generated compatibility transcode. */
class OriginalMediaAccess(private val context: Context) {
    fun originalUri(uri: Uri): Uri {
        if (uri.scheme != "content") return uri
        val type = context.contentResolver.getType(uri).orEmpty()
        if (!type.startsWith("image/") && !type.startsWith("video/")) return uri
        if (uri.authority == MediaStore.AUTHORITY) return MediaStore.setRequireOriginal(uri)
        if (uri.authority != "com.android.externalstorage.documents" &&
            uri.authority != "com.android.providers.media.documents") return uri
        var media = MediaStore.getMediaUri(context, uri)
        if (media == null) {
            // FileSystemProvider indexes on open. Passthrough media capabilities prevent transcoding.
            context.contentResolver.openFileDescriptor(uri, "r")?.use { }
            media = MediaStore.getMediaUri(context, uri)
        }
        return MediaStore.setRequireOriginal(media
            ?: throw IOException("Original media URI is unavailable; byte-preserving transfer is blocked."))
    }

    fun asset(uri: Uri): AssetFileDescriptor {
        val original = originalUri(uri)
        val options = Bundle()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            options.putBoolean(MediaStore.EXTRA_ACCEPT_ORIGINAL_MEDIA_FORMAT, true)
        }
        return if (original.scheme == "content") {
            context.contentResolver.openTypedAssetFileDescriptor(original, "*/*", options)
                ?: throw IOException("Original media access unavailable.")
        } else {
            context.contentResolver.openAssetFileDescriptor(original, "r")
                ?: throw IOException("Original file access unavailable.")
        }
    }

    fun input(uri: Uri): InputStream = asset(uri).createInputStream()
}
