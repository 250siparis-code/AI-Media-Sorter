package com.aimediasorter.app.domain.storage

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
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
    val orientationDegrees: Int
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
            val f = File(filePath)
            if (f.exists() && f.lastModified() > 0L) {
                return f.lastModified()
            }
        }
        val doc = DocumentFile.fromSingleUri(context, uri)
        val docModified = doc?.lastModified() ?: 0L
        return if (docModified > 0L) docModified else fallbackMs
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

        runCatching {
            val exif = if (!filePath.isNullOrBlank() && File(filePath).exists()) {
                ExifInterface(filePath)
            } else {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    ExifInterface(stream)
                }
            }
            if (exif != null) {
                width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                orientationDeg = exif.rotationDegrees
                val dateTimeOriginal = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                if (!dateTimeOriginal.isNullOrBlank()) {
                    exifDateMs = parseExifDateString(dateTimeOriginal)
                }
            }
        }

        return MediaTimestampMetadata(
            filesystemLastModifiedMs = filesystemLastModifiedMs,
            exifCaptureDateMs = exifDateMs,
            width = width,
            height = height,
            durationMs = 0L,
            orientationDegrees = orientationDeg
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

        // 2. POSIX futimens / utimensat via ParcelFileDescriptor for SAF URIs where provider permits fd attribute updates
        if (destinationUri != null) {
            runCatching {
                context.contentResolver.openFileDescriptor(destinationUri, "rw")?.use { pfd: ParcelFileDescriptor ->
                    val seconds = originalLastModifiedMs / 1000L
                    val nanos = (originalLastModifiedMs % 1000L) * 1_000_000L
                    val timespec = StructTimespec(seconds, nanos)
                    // Access via reflection or Os.futimens when available on API 29+
                    val futimensMethod = Os::class.java.methods.firstOrNull { it.name == "futimens" }
                    futimensMethod?.invoke(null, pfd.fileDescriptor, arrayOf(timespec, timespec))
                }
            }

            val doc = DocumentFile.fromSingleUri(context, destinationUri)
            val actualDocMs = doc?.lastModified() ?: 0L
            val preserved = actualDocMs > 0L && abs(actualDocMs - originalLastModifiedMs) <= TIMESTAMP_TOLERANCE_MS
            return TimestampVerificationResult(
                preserved = preserved,
                originalLastModifiedMs = originalLastModifiedMs,
                actualDestinationLastModifiedMs = actualDocMs,
                note = if (preserved) {
                    "Destination SAF URI timestamp verified within ${TIMESTAMP_TOLERANCE_MS}ms tolerance."
                } else {
                    "Android SAF storage provider restricted modification timestamp changes (Original: $originalLastModifiedMs, Provider assigned: $actualDocMs). Original timestamp recorded in operation database."
                }
            )
        }

        return TimestampVerificationResult(
            preserved = false,
            originalLastModifiedMs = originalLastModifiedMs,
            actualDestinationLastModifiedMs = 0L,
            note = "Destination handle unavailable for timestamp verification."
        )
    }

    private fun parseExifDateString(raw: String): Long? {
        return runCatching {
            val fmt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
            fmt.parse(raw)?.time
        }.getOrNull()
    }

    private fun parseIsoVideoDate(raw: String): Long? {
        return runCatching {
            val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            fmt.parse(raw)?.time
        }.getOrNull()
    }
}
