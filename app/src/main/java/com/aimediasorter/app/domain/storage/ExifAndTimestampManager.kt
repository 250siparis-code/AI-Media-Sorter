package com.aimediasorter.app.domain.storage

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.system.Os
import android.system.StructTimespec
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

data class MediaTimestampMetadata(
    val filesystemLastModifiedMs: Long,
    val exifCaptureDateMs: Long?,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val orientationDegrees: Int,
    val isMirrored: Boolean = false
)

data class TimestampVerificationResult(
    val preserved: Boolean,
    val originalLastModifiedMs: Long,
    val actualDestinationLastModifiedMs: Long,
    val note: String
)

/**
 * Strictly separates filesystem modification timestamps (File.lastModified / DocumentFile.lastModified)
 * from embedded EXIF / video capture timestamps.
 * Provides pre-move recording, native & utimensat restoration, and post-move verification.
 */
class ExifAndTimestampManager(private val context: Context) {

    companion object {
        // 2000ms tolerance accounts for FAT32/exFAT 2-second timestamp granularity on SD cards / external storage
        const val TIMESTAMP_TOLERANCE_MS = 2000L
    }

    fun inspectMediaMetadata(
        uri: Uri,
        filePath: String?,
        isVideo: Boolean,
        fallbackLastModifiedMs: Long
    ): MediaTimestampMetadata {
        val trueLastModifiedMs = resolveFilesystemLastModified(uri, filePath, fallbackLastModifiedMs)

        if (isVideo) {
            return inspectVideoMetadata(uri, filePath, trueLastModifiedMs)
        }
        return inspectImageMetadata(uri, filePath, trueLastModifiedMs)
    }

    fun resolveFilesystemLastModified(uri: Uri, filePath: String?, fallbackMs: Long): Long {
        if (!filePath.isNullOrBlank()) {
            val file = File(filePath)
            if (file.exists()) return file.lastModified()
        }
        // Provider-reported dates are not interchangeable with filesystem mtime.
        if (uri.authority != "com.android.externalstorage.documents") return 0L
        return runCatching {
            OriginalMediaAccess(context).asset(uri).use {
                val stat = Os.fstat(it.parcelFileDescriptor.fileDescriptor)
                if (OsConstants.S_ISREG(stat.st_mode)) {
                    File("/proc/self/fd/" + it.parcelFileDescriptor.fd).lastModified().takeIf { value -> value > 0 } ?: stat.st_mtime * 1000L
                } else 0L
            } ?: 0L
        }.getOrDefault(0L)
    }

    private fun inspectImageMetadata(
        uri: Uri,
        filePath: String?,
        filesystemLastModifiedMs: Long
    ): MediaTimestampMetadata {
        var exifDateMs: Long? = null
        var width = 0
        var height = 0
        var orientationDeg = 0
        var mirrored = false

        runCatching {
            val exif = if (!filePath.isNullOrBlank() && File(filePath).exists()) {
                ExifInterface(filePath)
            } else {
                OriginalMediaAccess(context).input(uri).use { stream ->
                    ExifInterface(stream)
                }
            }
            if (exif != null) {
                width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                orientationDeg = exif.rotationDegrees
                mirrored = exif.isFlipped
                val dateTimeOriginal = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)

                if (!dateTimeOriginal.isNullOrBlank()) {
                    exifDateMs = parseExifDateString(dateTimeOriginal, exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
                }
            }
        }

        return MediaTimestampMetadata(
            filesystemLastModifiedMs = filesystemLastModifiedMs,
            exifCaptureDateMs = exifDateMs,
            width = width,
            height = height,
            durationMs = 0L,
            orientationDegrees = orientationDeg,
            isMirrored = mirrored
        )
    }

    private fun inspectVideoMetadata(
        uri: Uri,
        filePath: String?,
        filesystemLastModifiedMs: Long
    ): MediaTimestampMetadata {
        val retriever = MediaMetadataRetriever()
        var width = 0
        var height = 0
        var durationMs = 0L
        var rotation = 0
        var captureDateMs: Long? = null

        try {
            if (!filePath.isNullOrBlank() && File(filePath).exists()) {
                retriever.setDataSource(filePath)
            } else {
                retriever.setDataSource(context, uri)
            }
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val rawDate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
            if (!rawDate.isNullOrBlank()) {
                captureDateMs = parseIsoVideoDate(rawDate)
            }
        } catch (_: Exception) {
        } finally {
            runCatching { retriever.release() }
        }

        return MediaTimestampMetadata(
            filesystemLastModifiedMs = filesystemLastModifiedMs,
            exifCaptureDateMs = captureDateMs,
            width = width,
            height = height,
            durationMs = durationMs,
            orientationDegrees = rotation
        )
    }

    /**
     * Attempts to restore the original last-modified timestamp on the destination file or SAF URI
     * and immediately reads back the destination timestamp to verify whether the Android storage provider honored it.
     */
    fun restoreAndVerifyTimestamp(
        destinationFile: File?,
        destinationUri: Uri?,
        originalLastModifiedMs: Long
    ): TimestampVerificationResult {
        if (originalLastModifiedMs <= 0L) {
            return TimestampVerificationResult(
                preserved = false,
                originalLastModifiedMs = originalLastModifiedMs,
                actualDestinationLastModifiedMs = 0L,
                note = "Source file had no valid original modification timestamp (> 0)."
            )
        }

        // 1. Direct filesystem restoration if File handle is available
        if (destinationFile != null && destinationFile.exists()) {
            val setOk = destinationFile.setLastModified(originalLastModifiedMs)
            val actualAfterSet = destinationFile.lastModified()
            if (setOk && abs(actualAfterSet - originalLastModifiedMs) <= TIMESTAMP_TOLERANCE_MS) {
                return TimestampVerificationResult(
                    preserved = true,
                    originalLastModifiedMs = originalLastModifiedMs,
                    actualDestinationLastModifiedMs = actualAfterSet,
                    note = "Filesystem last-modified timestamp preserved and verified via File.setLastModified."
                )
            }
        }

        if (destinationUri != null) {
            var kernelModifiedMs = 0L
            var changed = false
            runCatching {
                context.contentResolver.openFileDescriptor(destinationUri, "rw")?.use { descriptor ->
                    val before = Os.fstat(descriptor.fileDescriptor)
                    if (OsConstants.S_ISREG(before.st_mode)) {
                        changed = File("/proc/self/fd/" + descriptor.fd).setLastModified(originalLastModifiedMs)
                        kernelModifiedMs = File("/proc/self/fd/" + descriptor.fd).lastModified().takeIf { it > 0 }
                            ?: Os.fstat(descriptor.fileDescriptor).st_mtime * 1000L
                    }
                }
            }
            val providerModifiedMs = DocumentFile.fromSingleUri(context, destinationUri)?.lastModified() ?: 0L
            val preserved = changed && kernelModifiedMs > 0 && providerModifiedMs > 0 &&
                abs(kernelModifiedMs - originalLastModifiedMs) <= TIMESTAMP_TOLERANCE_MS &&
                abs(providerModifiedMs - originalLastModifiedMs) <= TIMESTAMP_TOLERANCE_MS
            return TimestampVerificationResult(preserved, originalLastModifiedMs, kernelModifiedMs,
                if (preserved) "Descriptor filesystem mtime and provider metadata independently verified within 2000 ms."
                else "Filesystem modification time could not be verified through this provider. Provider date: " +
                    providerModifiedMs + "; descriptor mtime: " + kernelModifiedMs +
                    ". Embedded capture metadata is unchanged. The keep-original policy retains the source.")
        }

        return TimestampVerificationResult(
            preserved = false,
            originalLastModifiedMs = originalLastModifiedMs,
            actualDestinationLastModifiedMs = 0L,
            note = "Destination handle unavailable for timestamp verification."
        )
    }

    private fun parseExifDateString(raw: String, offset: String?): Long? {
        return runCatching {
            val fmt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply {
                isLenient = false
                if (offset != null && offset.matches(Regex("[+-][0-9]{2}:[0-9]{2}"))) timeZone = TimeZone.getTimeZone("GMT" + offset)
            }
            fmt.parse(raw)?.time
        }.getOrNull()
    }

    private fun parseIsoVideoDate(raw: String): Long? {
        runCatching { return java.time.Instant.parse(raw).toEpochMilli() }
        for (pattern in listOf("yyyyMMdd'T'HHmmss.SSS'Z'", "yyyyMMdd'T'HHmmss'Z'")) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(raw)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }
}
