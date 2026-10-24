package com.aimediasorter.app.domain.storage

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.aimediasorter.app.data.local.MediaItemEntity
import com.aimediasorter.app.data.local.TransferLogEntity
import com.aimediasorter.app.data.model.CollisionPolicy
import com.aimediasorter.app.data.model.OperationMode
import com.aimediasorter.app.data.model.TimestampPolicy
import com.aimediasorter.app.data.model.TransferItemStatus
import com.aimediasorter.app.data.model.TransferMethod
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class SingleTransferOutcome(
    val itemId: Long,
    val status: TransferItemStatus,
    val destinationUriString: String?,
    val resolvedFileName: String,
    val sha256Checksum: String?,
    val timestampPreserved: Boolean,
    val destinationLastModifiedMs: Long?,
    val collisionResolutionNote: String?,
    val detailsMessage: String,
    val logEntry: TransferLogEntity
)

/**
 * Transactional, byte-preserving file movement and copying engine.
 * - Never modifies, re-encodes, or strips EXIF/video metadata.
 * - Verifies SHA-256 checksum equality before deleting source on cross-mount/SAF move.
 * - Preserves and verifies original last-modified timestamps whenever supported.
 * - Honors CollisionPolicy (Rename with _1/_2 suffix, Skip, or SHA-256 Dedup).
 */
class SafeFileMover(
    private val context: Context,
    private val timestampManager: ExifAndTimestampManager,
    private val scanner: StorageScanner
) {
    companion object {
        private const val STREAM_BUFFER_SIZE = 64 * 1024 // 64 KB
    }

    suspend fun transferMediaItem(
        sessionId: String,
        item: MediaItemEntity,
        sourceRootUri: String,
        destinationRootUri: String,
        categorySubfolder: String,
        operationMode: OperationMode,
        transferMethod: TransferMethod,
        collisionPolicy: CollisionPolicy,
        timestampPolicy: TimestampPolicy
    ): SingleTransferOutcome {
        currentCoroutineContext().ensureActive()

        // Guard 1: Never move or copy in Analysis Mode
        if (operationMode == OperationMode.ANALYSIS_ONLY) {
            return createFailureOutcome(
                sessionId = sessionId,
                item = item,
                method = transferMethod,
                message = "Operation blocked: Application is in Analysis Mode."
            )
        }

        // Guard 2: Prevent recursive loop (destination inside source)
        if (scanner.isDestinationInsideSource(sourceRootUri, destinationRootUri)) {
            return createFailureOutcome(
                sessionId = sessionId,
                item = item,
                method = transferMethod,
                message = "Safety violation: Destination directory cannot be equal to or inside Source directory."
            )
        }

        val cleanSubfolder = categorySubfolder.trim('/', '\\').ifBlank { item.effectiveCategoryName }
        val directDestRoot = File(destinationRootUri)
        if (! item.filePath.isNullOrBlank() && (directDestRoot.exists() || destinationRootUri.startsWith("/"))) {
            return transferViaLocalFilesystem(
                sessionId = sessionId,
                item = item,
                destRootDir = directDestRoot,
                subfolderName = cleanSubfolder,
                transferMethod = transferMethod,
                collisionPolicy = collisionPolicy,
                timestampPolicy = timestampPolicy
            )
        }

        return transferViaSafDocumentTree(
            sessionId = sessionId,
            item = item,
            destinationTreeUriStr = destinationRootUri,
            subfolderName = cleanSubfolder,
            transferMethod = transferMethod,
            collisionPolicy = collisionPolicy,
            timestampPolicy = timestampPolicy
        )
    }

    private suspend fun transferViaLocalFilesystem(
        sessionId: String,
        item: MediaItemEntity,
        destRootDir: File,
        subfolderName: String,
        transferMethod: TransferMethod,
        collisionPolicy: CollisionPolicy,
        timestampPolicy: TimestampPolicy
    ): SingleTransferOutcome {
        val sourceFile = File(item.filePath!!)
        if (!sourceFile.exists() || !sourceFile.canRead()) {
            return createFailureOutcome(sessionId, item, transferMethod, "Source file missing or unreadable: ${sourceFile.path}")
        }

        val targetCategoryDir = File(destRootDir, subfolderName)
        if (!targetCategoryDir.exists() && !targetCategoryDir.mkdirs()) {
            return createFailureOutcome(sessionId, item, transferMethod, "Failed to create category directory: ${targetCategoryDir.path}")
        }

        // Check available space
        val statFs = runCatching { StatFs(targetCategoryDir.absolutePath) }.getOrNull()
        if (statFs != null && statFs.availableBytes < sourceFile.length() + (2L * 1024 * 1024)) {
            return createFailureOutcome(sessionId, item, transferMethod, "Insufficient free storage space in destination.")
        }

        val originalModifiedMs = if (sourceFile.lastModified() > 0L) sourceFile.lastModified() else item.originalLastModifiedMs
        val sourceSha256 = computeFileSha256(sourceFile)

        // Collision check
        var candidateDestFile = File(targetCategoryDir, item.fileName)
        var collisionNote: String? = null
        if (candidateDestFile.exists()) {
            when (collisionPolicy) {
                CollisionPolicy.SKIP_EXISTING -> {
                    return buildSkippedCollisionOutcome(sessionId, item, candidateDestFile.absolutePath, transferMethod, "Skipped existing file per CollisionPolicy.SKIP_EXISTING")
                }
                CollisionPolicy.VERIFY_HASH_AND_SKIP -> {
                    val existingSha = computeFileSha256(candidateDestFile)
                    if (existingSha == sourceSha256) {
                        return buildSkippedCollisionOutcome(sessionId, item, candidateDestFile.absolutePath, transferMethod, "Identical SHA-256 already exists at destination ($existingSha); skipped.")
                    }
                    candidateDestFile = resolveUniqueFilesystemName(targetCategoryDir, item.fileName)
                    collisionNote = "Existing file had different SHA-256; renamed to ${candidateDestFile.name}"
                }
                CollisionPolicy.RENAME_WITH_SUFFIX -> {
                    candidateDestFile = resolveUniqueFilesystemName(targetCategoryDir, item.fileName)
                    collisionNote = "Collision resolved by renaming to ${candidateDestFile.name}"
                }
            }
        }

        // Step 1: Prefer true atomic filesystem move when MOVE is selected
        if (transferMethod == TransferMethod.MOVE) {
            val atomicMoveSucceeded = runCatching {
                Files.move(
                    sourceFile.toPath(),
                    candidateDestFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE
                )
                true
            }.getOrDefault(false)

            if (atomicMoveSucceeded && candidateDestFile.exists()) {
                val tsResult = timestampManager.restoreAndVerifyTimestamp(
                    destinationFile = candidateDestFile,
                    destinationUri = null,
                    originalLastModifiedMs = originalModifiedMs
                )
                val destSha = computeFileSha256(candidateDestFile)
                val status = if (tsResult.preserved) TransferItemStatus.COMPLETED_VERIFIED else TransferItemStatus.COMPLETED_TIMESTAMP_WARNING
                return buildSuccessOutcome(
                    sessionId = sessionId,
                    item = item,
                    destUriStr = Uri.fromFile(candidateDestFile).toString(),
                    resolvedName = candidateDestFile.name,
                    method = transferMethod,
                    sha256 = destSha,
                    tsResult = tsResult,
                    collisionNote = collisionNote,
                    status = status,
                    details = "Atomic filesystem move completed & verified. ${tsResult.note}"
                )
            }
        }

        // Step 2: Streaming copy to temporary .part file first, verify SHA-256, rename into place, restore timestamp, then delete source if MOVE
        val tempFile = File(targetCategoryDir, ".${candidateDestFile.name}.aimediasorter.tmp")
        try {
            val copiedSha256 = FileInputStream(sourceFile).use { input ->
                FileOutputStream(tempFile).use { output ->
                    streamWithSha256(input, output)
                }
            }

            if (copiedSha256 != sourceSha256 || tempFile.length() != sourceFile.length()) {
                tempFile.delete()
                return createFailureOutcome(
                    sessionId,
                    item,
                    transferMethod,
                    "SHA-256 integrity mismatch after copy (src=$sourceSha256, dst=$copiedSha256). Source file left untouched."
                )
            }

            if (!tempFile.renameTo(candidateDestFile)) {
                Files.move(tempFile.toPath(), candidateDestFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }

            val tsResult = timestampManager.restoreAndVerifyTimestamp(
                destinationFile = candidateDestFile,
                destinationUri = null,
                originalLastModifiedMs = originalModifiedMs
            )

            // Non-destructive fallback check if MOVE was requested but timestamp could not be preserved
            if (transferMethod == TransferMethod.MOVE) {
                if (!tsResult.preserved && timestampPolicy == TimestampPolicy.FALLBACK_COPY_KEEP_ORIGINAL) {
                    return buildSuccessOutcome(
                        sessionId = sessionId,
                        item = item,
                        destUriStr = Uri.fromFile(candidateDestFile).toString(),
                        resolvedName = candidateDestFile.name,
                        method = TransferMethod.COPY,
                        sha256 = copiedSha256,
                        tsResult = tsResult,
                        collisionNote = collisionNote,
                        status = TransferItemStatus.COMPLETED_TIMESTAMP_WARNING,
                        details = "Timestamp could not be restored; kept original source file per non-destructive policy. ${tsResult.note}"
                    )
                }
                // Safe to delete source only after SHA-256 and timestamp checks complete
                sourceFile.delete()
            }

            val finalStatus = if (tsResult.preserved) TransferItemStatus.COMPLETED_VERIFIED else TransferItemStatus.COMPLETED_TIMESTAMP_WARNING
            return buildSuccessOutcome(
                sessionId = sessionId,
                item = item,
                destUriStr = Uri.fromFile(candidateDestFile).toString(),
                resolvedName = candidateDestFile.name,
                method = transferMethod,
                sha256 = copiedSha256,
                tsResult = tsResult,
                collisionNote = collisionNote,
                status = finalStatus,
                details = "Streaming transfer verified via SHA-256 ($copiedSha256). ${tsResult.note}"
            )
        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            return createFailureOutcome(
                sessionId,
                item,
                transferMethod,
                "Transfer interrupted (${e.message}). Temporary file cleaned up; source file remains intact."
            )
        }
    }

    private suspend fun transferViaSafDocumentTree(
        sessionId: String,
        item: MediaItemEntity,
        destinationTreeUriStr: String,
        subfolderName: String,
        transferMethod: TransferMethod,
        collisionPolicy: CollisionPolicy,
        timestampPolicy: TimestampPolicy
    ): SingleTransferOutcome {
        val treeUri = Uri.parse(destinationTreeUriStr)
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
            ?: return createFailureOutcome(sessionId, item, transferMethod, "Destination SAF tree URI access revoked or unavailable.")

        val categoryDir = rootDoc.findFile(subfolderName)?.takeIf { it.isDirectory }
            ?: rootDoc.createDirectory(subfolderName)
            ?: return createFailureOutcome(sessionId, item, transferMethod, "Could not create or access category folder '$subfolderName' in destination.")

        val sourceUri = Uri.parse(item.uriString)
        val sourceSha256 = context.contentResolver.openInputStream(sourceUri)?.use { computeStreamSha256(it) }
            ?: return createFailureOutcome(sessionId, item, transferMethod, "Unable to open source URI stream: ${item.uriString}")

        var targetName = item.fileName
        var collisionNote: String? = null
        val existingDoc = categoryDir.findFile(targetName)
        if (existingDoc != null) {
            when (collisionPolicy) {
                CollisionPolicy.SKIP_EXISTING -> {
                    return buildSkippedCollisionOutcome(sessionId, item, existingDoc.uri.toString(), transferMethod, "Skipped existing file in SAF folder.")
                }
                CollisionPolicy.VERIFY_HASH_AND_SKIP -> {
                    val existingSha = context.contentResolver.openInputStream(existingDoc.uri)?.use { computeStreamSha256(it) }
                    if (existingSha == sourceSha256) {
                        return buildSkippedCollisionOutcome(sessionId, item, existingDoc.uri.toString(), transferMethod, "Identical SHA-256 already exists in destination; skipped.")
                    }
                    targetName = resolveUniqueSafName(categoryDir, item.fileName)
                    collisionNote = "Existing file had different SHA-256; renamed to $targetName"
                }
                CollisionPolicy.RENAME_WITH_SUFFIX -> {
                    targetName = resolveUniqueSafName(categoryDir, item.fileName)
                    collisionNote = "Collision resolved by renaming to $targetName"
                }
            }
        }

        val createdDoc = categoryDir.createFile(item.mimeType, targetName)
            ?: return createFailureOutcome(sessionId, item, transferMethod, "Failed to create destination SAF document '$targetName'.")

        try {
            val writtenSha256 = context.contentResolver.openInputStream(sourceUri)?.use { input ->
                context.contentResolver.openOutputStream(createdDoc.uri)?.use { output ->
                    streamWithSha256(input, output)
                }
            } ?: throw IllegalStateException("Could not open SAF input/output streams.")

            val verifyReadbackSha256 = context.contentResolver.openInputStream(createdDoc.uri)?.use {
                computeStreamSha256(it)
            }

            if (writtenSha256 != sourceSha256 || verifyReadbackSha256 != sourceSha256) {
                createdDoc.delete()
                return createFailureOutcome(
                    sessionId,
                    item,
                    transferMethod,
                    "SHA-256 verification failed on SAF destination. Incomplete destination deleted; source preserved."
                )
            }

            val tsResult = timestampManager.restoreAndVerifyTimestamp(
                destinationFile = null,
                destinationUri = createdDoc.uri,
                originalLastModifiedMs = item.originalLastModifiedMs
            )

            if (transferMethod == TransferMethod.MOVE) {
                if (!tsResult.preserved && timestampPolicy == TimestampPolicy.FALLBACK_COPY_KEEP_ORIGINAL) {
                    return buildSuccessOutcome(
                        sessionId = sessionId,
                        item = item,
                        destUriStr = createdDoc.uri.toString(),
                        resolvedName = createdDoc.name ?: targetName,
                        method = TransferMethod.COPY,
                        sha256 = verifyReadbackSha256,
                        tsResult = tsResult,
                        collisionNote = collisionNote,
                        status = TransferItemStatus.COMPLETED_TIMESTAMP_WARNING,
                        details = "SAF provider blocked timestamp restore; kept source file intact per non-destructive policy. ${tsResult.note}"
                    )
                }
                // Delete original only after readback SHA-256 verification succeeded
                runCatching {
                    DocumentsContract.deleteDocument(context.contentResolver, sourceUri)
                }
            }

            val finalStatus = if (tsResult.preserved) TransferItemStatus.COMPLETED_VERIFIED else TransferItemStatus.COMPLETED_TIMESTAMP_WARNING
            return buildSuccessOutcome(
                sessionId = sessionId,
                item = item,
                destUriStr = createdDoc.uri.toString(),
                resolvedName = createdDoc.name ?: targetName,
                method = transferMethod,
                sha256 = verifyReadbackSha256,
                tsResult = tsResult,
                collisionNote = collisionNote,
                status = finalStatus,
                details = "SAF transfer SHA-256 verified ($verifyReadbackSha256). ${tsResult.note}"
            )
        } catch (e: Exception) {
            runCatching { createdDoc.delete() }
            return createFailureOutcome(
                sessionId,
                item,
                transferMethod,
                "SAF transfer failed (${e.message}). Source file left untouched."
            )
        }
    }

    fun resolveUniqueFilesystemName(dir: File, originalFileName: String): File {
        val dotIndex = originalFileName.lastIndexOf('.')
        val baseName = if (dotIndex > 0) originalFileName.substring(0, dotIndex) else originalFileName
        val extSuffix = if (dotIndex > 0) originalFileName.substring(dotIndex) else ""
        var counter = 1
        while (true) {
            val candidate = File(dir, "${baseName}_${counter}${extSuffix}")
            if (!candidate.exists()) return candidate
            counter++
        }
    }

    private fun resolveUniqueSafName(dir: DocumentFile, originalFileName: String): String {
        val dotIndex = originalFileName.lastIndexOf('.')
        val baseName = if (dotIndex > 0) originalFileName.substring(0, dotIndex) else originalFileName
        val extSuffix = if (dotIndex > 0) originalFileName.substring(dotIndex) else ""
        var counter = 1
        while (true) {
            val candidateName = "${baseName}_${counter}${extSuffix}"
            if (dir.findFile(candidateName) == null) return candidateName
            counter++
        }
    }

    private suspend fun streamWithSha256(input: InputStream, output: OutputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(STREAM_BUFFER_SIZE)
        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } != -1) {
            currentCoroutineContext().ensureActive()
            output.write(buffer, 0, bytesRead)
            digest.update(buffer, 0, bytesRead)
        }
        output.flush()
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun computeFileSha256(file: File): String {
        FileInputStream(file).use { return computeStreamSha256(it) }
    }

    fun computeStreamSha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(STREAM_BUFFER_SIZE)
        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun buildSuccessOutcome(
        sessionId: String,
        item: MediaItemEntity,
        destUriStr: String,
        resolvedName: String,
        method: TransferMethod,
        sha256: String,
        tsResult: TimestampVerificationResult,
        collisionNote: String?,
        status: TransferItemStatus,
        details: String
    ): SingleTransferOutcome {
        val log = TransferLogEntity(
            sessionId = sessionId,
            sourceUri = item.uriString,
            sourceFileName = item.fileName,
            destinationUri = destUriStr,
            categoryName = item.effectiveCategoryName,
            operationType = method.name,
            bytesTransferred = item.fileSizeBytes,
            sha256Checksum = sha256,
            originalLastModifiedMs = item.originalLastModifiedMs,
            destinationLastModifiedMs = tsResult.actualDestinationLastModifiedMs,
            timestampPreserved = tsResult.preserved,
            collisionResolution = collisionNote,
            status = status.name,
            detailsMessage = details,
            executedAtMs = System.currentTimeMillis()
        )
        return SingleTransferOutcome(
            itemId = item.id,
            status = status,
            destinationUriString = destUriStr,
            resolvedFileName = resolvedName,
            sha256Checksum = sha256,
            timestampPreserved = tsResult.preserved,
            destinationLastModifiedMs = tsResult.actualDestinationLastModifiedMs,
            collisionResolutionNote = collisionNote,
            detailsMessage = details,
            logEntry = log
        )
    }

    private fun buildSkippedCollisionOutcome(
        sessionId: String,
        item: MediaItemEntity,
        existingDestUri: String,
        method: TransferMethod,
        reason: String
    ): SingleTransferOutcome {
        val log = TransferLogEntity(
            sessionId = sessionId,
            sourceUri = item.uriString,
            sourceFileName = item.fileName,
            destinationUri = existingDestUri,
            categoryName = item.effectiveCategoryName,
            operationType = method.name,
            bytesTransferred = 0L,
            sha256Checksum = item.sha256Checksum,
            originalLastModifiedMs = item.originalLastModifiedMs,
            destinationLastModifiedMs = null,
            timestampPreserved = true,
            collisionResolution = "SKIPPED",
            status = TransferItemStatus.SKIPPED_COLLISION.name,
            detailsMessage = reason,
            executedAtMs = System.currentTimeMillis()
        )
        return SingleTransferOutcome(
            itemId = item.id,
            status = TransferItemStatus.SKIPPED_COLLISION,
            destinationUriString = existingDestUri,
            resolvedFileName = item.fileName,
            sha256Checksum = item.sha256Checksum,
            timestampPreserved = true,
            destinationLastModifiedMs = null,
            collisionResolutionNote = reason,
            detailsMessage = reason,
            logEntry = log
        )
    }

    private fun createFailureOutcome(
        sessionId: String,
        item: MediaItemEntity,
        method: TransferMethod,
        message: String
    ): SingleTransferOutcome {
        val log = TransferLogEntity(
            sessionId = sessionId,
            sourceUri = item.uriString,
            sourceFileName = item.fileName,
            destinationUri = null,
            categoryName = item.effectiveCategoryName,
            operationType = method.name,
            bytesTransferred = 0L,
            sha256Checksum = null,
            originalLastModifiedMs = item.originalLastModifiedMs,
            destinationLastModifiedMs = null,
            timestampPreserved = false,
            collisionResolution = null,
            status = TransferItemStatus.FAILED.name,
            detailsMessage = message,
            executedAtMs = System.currentTimeMillis()
        )
        return SingleTransferOutcome(
            itemId = item.id,
            status = TransferItemStatus.FAILED,
            destinationUriString = null,
            resolvedFileName = item.fileName,
            sha256Checksum = null,
            timestampPreserved = false,
            destinationLastModifiedMs = null,
            collisionResolutionNote = null,
            detailsMessage = message,
            logEntry = log
        )
    }
}
