package com.aimediasorter.app.domain.storage

import android.content.Context
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import androidx.documentfile.provider.DocumentFile
import com.aimediasorter.app.data.local.MediaItemEntity
import com.aimediasorter.app.data.local.TransferLogEntity
import com.aimediasorter.app.data.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
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


class SafeFileMover(
    private val context: Context,
    private val timestampManager: ExifAndTimestampManager,
    private val scanner: StorageScanner
) {
    private val engine = VerifiedTransferEngine()

    suspend fun transferMediaItem(
        sessionId: String, item: MediaItemEntity, sourceRootUri: String,
        destinationRootUri: String, categorySubfolder: String, operationMode: OperationMode,
        transferMethod: TransferMethod, collisionPolicy: CollisionPolicy, timestampPolicy: TimestampPolicy,
        checkpoint: suspend (TransferCheckpoint) -> Unit = {}
    ): SingleTransferOutcome = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
<<<<<<< /tmp/prmerge/o4

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
=======
        var latestCheckpoint: TransferCheckpoint? = null
>>>>>>> /tmp/prmerge/t4
        try {
            require(operationMode == OperationMode.MOVE_FILES) { "Transfer blocked: Analysis Mode is active." }
            require(!scanner.isDestinationInsideSource(sourceRootUri, destinationRootUri)) {
                "Destination must be outside the source."
            }
            val segments = SafeRelativePath.segments(categorySubfolder)
            val localRoot = scanner.resolveLocalFile(destinationRootUri)
            val source: TransferDocument
            val directory: TransferDirectory
            if (localRoot != null && !item.filePath.isNullOrBlank()) {
                val localSource = File(item.filePath)
                val category = segments.fold(localRoot) { parent, child -> File(parent, child) }
                require(!category.canonicalFile.toPath().startsWith(localSource.parentFile!!.canonicalFile.toPath())
                    || !scanner.isDestinationInsideSource(sourceRootUri, category.canonicalPath)) {
                    "Actual category destination is inside source."
                }
                source = LocalTransferDocument(localSource)
                directory = LocalTransferDirectory(category, localRoot)
            } else {
                val root = DocumentFile.fromTreeUri(context, Uri.parse(destinationRootUri))
                    ?: error("Invalid destination tree.")
                require(root.canWrite()) { "Destination write permission revoked." }
                var category = root
                for (segment in segments) {
                    category = category.findFile(segment)?.also {
                        require(it.isDirectory) { "Category path conflicts with a file." }
                    } ?: category.createDirectory(segment) ?: error("Cannot create category directory.")
                }
                val localSource = item.filePath?.let { File(it) }?.takeIf { it.exists() }
                source = if (localSource != null) SafDocument(DocumentFile.fromFile(localSource))
                    else SafDocument(DocumentFile.fromSingleUri(context, Uri.parse(item.uriString))
                        ?: error("Invalid source document."))
                directory = SafDirectory(category)
            }
            val receipt = engine.transfer(source, directory, item.fileName, item.mimeType, transferMethod,
                collisionPolicy, timestampPolicy, item.sha256Checksum, item.fileSizeBytes,
                item.originalLastModifiedMs) { state ->
                latestCheckpoint = state
                checkpoint(state)
            }
            if (receipt.skipped) {
                return@withContext buildSkippedCollisionOutcome(sessionId, item,
                    receipt.destination?.id.orEmpty(), transferMethod, receipt.details)
            }
            val destination = checkNotNull(receipt.destination)
            val timestamp = checkNotNull(receipt.timestamp)
            val status = when {
                transferMethod == TransferMethod.MOVE && receipt.sourceRetained -> TransferItemStatus.COMPLETED_SOURCE_RETAINED
                timestamp.preserved -> TransferItemStatus.COMPLETED_VERIFIED
                else -> TransferItemStatus.COMPLETED_TIMESTAMP_WARNING
            }
            buildSuccessOutcome(sessionId, item, destination.id, destination.name, receipt.actualMethod,
                checkNotNull(receipt.checksum), timestamp, receipt.collisionNote, status, receipt.details)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val state = latestCheckpoint
            val status = if (state?.destinationId != null) TransferItemStatus.RECOVERY_REQUIRED else TransferItemStatus.FAILED
            val failed = createFailureOutcome(sessionId, item, transferMethod,
                "Transfer interrupted; inspect recorded destination and source before retrying. " + error.message)
            failed.copy(status = status, destinationUriString = state?.destinationId,
                sha256Checksum = state?.checksum, timestampPreserved = state?.timestampPreserved ?: false,
                destinationLastModifiedMs = state?.destinationModifiedMs,
                logEntry = failed.logEntry.copy(status = status.name, destinationUri = state?.destinationId,
                    sha256Checksum = state?.checksum, timestampPreserved = state?.timestampPreserved ?: false,
                    destinationLastModifiedMs = state?.destinationModifiedMs))
        }
    }

    private inner class SafDocument(private val document: DocumentFile) : TransferDocument {
        override val id: String get() = document.uri.toString()
        override val name: String get() = document.name ?: error("Document has no filename.")
        override fun identityKey(): String? = runCatching {
            OriginalMediaAccess(context).asset(document.uri).use {
                val stat = Os.fstat(it.parcelFileDescriptor.fileDescriptor)
                if (OsConstants.S_ISREG(stat.st_mode)) stat.st_dev.toString() + ":" + stat.st_ino else null
            }
        }.getOrNull()
        override fun exists(): Boolean = document.exists()
        override fun size(): Long = document.length()
        override fun modifiedMs(): Long = timestampManager.resolveFilesystemLastModified(document.uri,
            document.uri.path?.takeIf { document.uri.scheme == "file" }, 0)
        override fun input(): InputStream = OriginalMediaAccess(context).input(document.uri)
        override fun output(): OutputStream = context.contentResolver.openOutputStream(document.uri, "wt")
            ?: error("Destination write permission revoked.")
        override fun sync(output: OutputStream) {
            // Some providers expose pipes, which cannot fsync. Readback is still mandatory.
            if (output is FileOutputStream) runCatching { output.fd.sync() }
        }
        override fun setTimestamp(originalMs: Long): TimestampVerificationResult =
            timestampManager.restoreAndVerifyTimestamp(null, document.uri, originalMs)
        override fun delete(): Boolean = document.delete()
    }

    private inner class SafDirectory(private val document: DocumentFile) : TransferDirectory {
        override fun confirmPublicationDurable(): Boolean = runCatching {
            context.contentResolver.openFileDescriptor(document.uri, "r")?.use {
                if (!OsConstants.S_ISDIR(Os.fstat(it.fileDescriptor).st_mode)) return@use false
                Os.fsync(it.fileDescriptor)
                true
            } ?: false
        }.getOrDefault(false)
        override fun find(name: String): TransferDocument? = document.findFile(name)?.let { SafDocument(it) }
        override fun createExclusive(name: String, mimeType: String): TransferDocument? {
            SafeRelativePath.fileName(name)
            if (document.findFile(name) != null) return null
            val created = document.createFile(mimeType, name)
                ?: error("Storage provider failed to create destination.")
            // DocumentsProvider createDocument creates a new document and may resolve a collision by renaming.
            return SafDocument(created)
        }
    }

    fun computeFileSha256(file: File): String = file.inputStream().use { computeStreamSha256(it) }
    fun computeStreamSha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
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
            sha256Checksum = null,
            originalLastModifiedMs = item.originalLastModifiedMs,
            destinationLastModifiedMs = null,
            timestampPreserved = false,
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
            sha256Checksum = null,
            timestampPreserved = false,
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
