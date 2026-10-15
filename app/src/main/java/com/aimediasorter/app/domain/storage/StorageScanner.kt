package com.aimediasorter.app.domain.storage

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.aimediasorter.app.data.model.DefaultCategories
import com.aimediasorter.app.data.model.MediaTypeFilter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.net.URLDecoder

data class ScannedMediaCandidate(
    val uri: Uri,
    val filePath: String?,
    val fileName: String,
    val extension: String,
    val isVideo: Boolean,
    val mimeType: String,
    val fileSizeBytes: Long,
    val lastModifiedMs: Long,
    val quickFingerprint: String
)

class StorageScanner(private val context: Context) {

    /**
     * Prevents recursive processing loops by verifying that the destination folder is neither
     * identical to nor nested inside the source folder.
     */
    fun isDestinationInsideSource(sourceUriStr: String, destinationUriStr: String): Boolean {
        if (sourceUriStr.isBlank() || destinationUriStr.isBlank()) return false
        val normalizedSrc = normalizeUriOrPath(sourceUriStr)
        val normalizedDst = normalizeUriOrPath(destinationUriStr)
        if (normalizedSrc.equals(normalizedDst, ignoreCase = true)) return true
        return normalizedDst.startsWith("$normalizedSrc/", ignoreCase = true)
    }

    fun normalizeUriOrPath(raw: String): String {
        val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        return decoded.trimEnd('/', '\\')
    }

    suspend fun scanDirectory(
        sourceUriStr: String,
        destinationUriStr: String,
        includeSubfolders: Boolean,
        mediaTypeFilter: MediaTypeFilter,
        enabledExtensions: Set<String>,
        onProgress: suspend (Int) -> Unit = {}
    ): List<ScannedMediaCandidate> {
        if (sourceUriStr.isBlank()) return emptyList()
        if (isDestinationInsideSource(sourceUriStr, destinationUriStr)) {
            throw IllegalArgumentException("Safety violation: Destination folder is inside or equal to the source folder.")
        }

        val normalizedExtensions = enabledExtensions.map { it.lowercase() }.toSet()
        val results = mutableListOf<ScannedMediaCandidate>()

        val directFile = File(sourceUriStr)
        if (directFile.exists() && directFile.isDirectory) {
            scanFilesystemDir(
                dir = directFile,
                destinationNormalized = normalizeUriOrPath(destinationUriStr),
                includeSubfolders = includeSubfolders,
                mediaTypeFilter = mediaTypeFilter,
                enabledExtensions = normalizedExtensions,
                results = results,
                onProgress = onProgress
            )
            return results
        }

        val treeUri = Uri.parse(sourceUriStr)
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("Revoked or invalid folder access for URI: $sourceUriStr")

        scanDocumentTree(
            dir = rootDoc,
            destinationNormalized = normalizeUriOrPath(destinationUriStr),
            includeSubfolders = includeSubfolders,
            mediaTypeFilter = mediaTypeFilter,
            enabledExtensions = normalizedExtensions,
            results = results,
            onProgress = onProgress
        )
        return results
    }

    private suspend fun scanFilesystemDir(
        dir: File,
        destinationNormalized: String,
        includeSubfolders: Boolean,
        mediaTypeFilter: MediaTypeFilter,
        enabledExtensions: Set<String>,
        results: MutableList<ScannedMediaCandidate>,
        onProgress: suspend (Int) -> Unit
    ) {
        currentCoroutineContext().ensureActive()
        val currentNormalized = normalizeUriOrPath(dir.absolutePath)
        if (destinationNormalized.isNotEmpty() &&
            (currentNormalized.equals(destinationNormalized, ignoreCase = true) ||
             currentNormalized.startsWith("$destinationNormalized/", ignoreCase = true))
        ) {
            return // Never scan inside destination directory
        }

        val children = dir.listFiles() ?: return
        for (child in children) {
            currentCoroutineContext().ensureActive()
            if (child.name.startsWith(".")) continue
            if (child.isDirectory) {
                if (includeSubfolders) {
                    scanFilesystemDir(
                        dir = child,
                        destinationNormalized = destinationNormalized,
                        includeSubfolders = true,
                        mediaTypeFilter = mediaTypeFilter,
                        enabledExtensions = enabledExtensions,
                        results = results,
                        onProgress = onProgress
                    )
                }
            } else if (child.isFile && child.length() > 0L) {
                val ext = child.extension.lowercase()
                if (shouldIncludeExtension(ext, mediaTypeFilter, enabledExtensions)) {
                    val isVideo = DefaultCategories.SUPPORTED_VIDEO_EXTENSIONS.contains(ext)
                    val lastMod = child.lastModified()
                    val size = child.length()
                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                        ?: if (isVideo) "video/mp4" else "image/jpeg"
                    results.add(
                        ScannedMediaCandidate(
                            uri = Uri.fromFile(child),
                            filePath = child.absolutePath,
                            fileName = child.name,
                            extension = ext,
                            isVideo = isVideo,
                            mimeType = mime,
                            fileSizeBytes = size,
                            lastModifiedMs = lastMod,
                            quickFingerprint = "${child.name}:$size:$lastMod"
                        )
                    )
                    onProgress(results.size)
                }
            }
        }
    }

    private suspend fun scanDocumentTree(
        dir: DocumentFile,
        destinationNormalized: String,
        includeSubfolders: Boolean,
        mediaTypeFilter: MediaTypeFilter,
        enabledExtensions: Set<String>,
        results: MutableList<ScannedMediaCandidate>,
        onProgress: suspend (Int) -> Unit
    ) {
        currentCoroutineContext().ensureActive()
        val currentNormalized = normalizeUriOrPath(dir.uri.toString())
        if (destinationNormalized.isNotEmpty() &&
            (currentNormalized.equals(destinationNormalized, ignoreCase = true) ||
             currentNormalized.startsWith("$destinationNormalized/", ignoreCase = true))
        ) {
            return
        }

        for (child in dir.listFiles()) {
            currentCoroutineContext().ensureActive()
            val name = child.name ?: continue
            if (name.startsWith(".")) continue
            if (child.isDirectory) {
                if (includeSubfolders) {
                    scanDocumentTree(
                        dir = child,
                        destinationNormalized = destinationNormalized,
                        includeSubfolders = true,
                        mediaTypeFilter = mediaTypeFilter,
                        enabledExtensions = enabledExtensions,
                        results = results,
                        onProgress = onProgress
                    )
                }
            } else if (child.isFile) {
                val ext = name.substringAfterLast('.', "").lowercase()
                val size = child.length()
                if (size > 0L && shouldIncludeExtension(ext, mediaTypeFilter, enabledExtensions)) {
                    val isVideo = DefaultCategories.SUPPORTED_VIDEO_EXTENSIONS.contains(ext)
                    val lastMod = child.lastModified()
                    val mime = child.type ?: if (isVideo) "video/mp4" else "image/jpeg"
                    results.add(
                        ScannedMediaCandidate(
                            uri = child.uri,
                            filePath = null,
                            fileName = name,
                            extension = ext,
                            isVideo = isVideo,
                            mimeType = mime,
                            fileSizeBytes = size,
                            lastModifiedMs = lastMod,
                            quickFingerprint = "$name:$size:$lastMod"
                        )
                    )
                    onProgress(results.size)
                }
            }
        }
    }

    private fun shouldIncludeExtension(
        ext: String,
        filter: MediaTypeFilter,
        enabledExtensions: Set<String>
    ): Boolean {
        if (ext.isBlank() || !enabledExtensions.contains(ext)) return false
        val isPhoto = DefaultCategories.SUPPORTED_PHOTO_EXTENSIONS.contains(ext)
        val isVideo = DefaultCategories.SUPPORTED_VIDEO_EXTENSIONS.contains(ext)
        return when (filter) {
            MediaTypeFilter.ALL -> isPhoto || isVideo
            MediaTypeFilter.PHOTOS_ONLY -> isPhoto
            MediaTypeFilter.VIDEOS_ONLY -> isVideo
        }
    }
}
