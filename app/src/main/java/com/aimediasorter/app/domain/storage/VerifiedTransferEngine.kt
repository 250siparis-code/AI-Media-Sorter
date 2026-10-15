package com.aimediasorter.app.domain.storage

import com.aimediasorter.app.data.model.CollisionPolicy
import com.aimediasorter.app.data.model.TimestampPolicy
import com.aimediasorter.app.data.model.TransferMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.security.MessageDigest

interface TransferDocument {
    val id: String
    val name: String
    fun identityKey(): String? = null
    fun exists(): Boolean
    fun size(): Long
    fun modifiedMs(): Long
    fun input(): InputStream
    fun output(): OutputStream
    fun sync(output: OutputStream)
    fun setTimestamp(originalMs: Long): TimestampVerificationResult
    fun delete(): Boolean
}

interface TransferDirectory {
    fun confirmPublicationDurable(): Boolean = false
    fun find(name: String): TransferDocument?
    /** Returns null only for a collision; never truncates an existing document. */
    fun createExclusive(name: String, mimeType: String): TransferDocument?
}

data class TransferCheckpoint(
    val phase: String,
    val destinationId: String? = null,
    val stagingId: String? = null,
    val checksum: String? = null,
    val originalModifiedMs: Long = 0,
    val destinationModifiedMs: Long = 0,
    val timestampPreserved: Boolean = false
)

data class VerifiedTransferReceipt(
    val destination: TransferDocument?,
    val checksum: String?,
    val timestamp: TimestampVerificationResult?,
    val actualMethod: TransferMethod,
    val sourceRetained: Boolean,
    val skipped: Boolean = false,
    val collisionNote: String? = null,
    val details: String
)

/** Production transfer protocol shared by local files and SAF, independently testable on the JVM. */
class VerifiedTransferEngine {
    suspend fun transfer(
        source: TransferDocument,
        directory: TransferDirectory,
        fileName: String,
        mimeType: String,
        method: TransferMethod,
        collisionPolicy: CollisionPolicy,
        timestampPolicy: TimestampPolicy,
        expectedChecksum: String?,
        expectedSize: Long,
        expectedModifiedMs: Long,
        checkpoint: suspend (TransferCheckpoint) -> Unit = {}
    ): VerifiedTransferReceipt = withContext(Dispatchers.IO) {
        SafeRelativePath.fileName(fileName)
        require(source.exists()) { "Source missing or permission revoked." }
        val sourceIdentity = source.identityKey()
        val beforeSize = source.size()
        val beforeModified = source.modifiedMs()
        require(expectedSize <= 0 || beforeSize <= 0 || beforeSize == expectedSize) {
            "Source size changed since analysis; re-analyze."
        }
        require(expectedModifiedMs <= 0 || beforeModified <= 0 ||
            kotlin.math.abs(beforeModified - expectedModifiedMs) <= 2000) {
            "Source modification time changed since analysis; re-analyze."
        }
        val originalModified = expectedModifiedMs.takeIf { it > 0 } ?: beforeModified
        val initial = hash(source)
        require(expectedChecksum == null || initial.first == expectedChecksum) {
            "Source contents changed since analysis; re-analyze."
        }
        val existing = directory.find(fileName)
        if (existing != null && (collisionPolicy == CollisionPolicy.SKIP_EXISTING ||
            collisionPolicy == CollisionPolicy.VERIFY_HASH_AND_SKIP && hash(existing).first == initial.first)) {
            return@withContext VerifiedTransferReceipt(
                existing, initial.first, null, TransferMethod.COPY, true, true,
                "Existing destination skipped; source retained.",
                "No transfer performed. Destination modification time was not verified."
            )
        }
        var staging: TransferDocument? = null
        var destination: TransferDocument? = null
        var destinationVerified = false
        var stagingOwned = false
        var destinationOwned = false
        try {
            currentCoroutineContext().ensureActive()
            staging = directory.createExclusive(".aimediasorter-" + UUID.randomUUID() + ".part", "application/octet-stream")
                ?: error("Could not exclusively create staging document.")
            require(staging.id != source.id &&
                (sourceIdentity == null || staging.identityKey() != sourceIdentity)) {
                "Provider returned the source as staging document."
            }
            stagingOwned = true
            checkpoint(TransferCheckpoint("STAGING", stagingId = staging.id, checksum = initial.first,
                originalModifiedMs = originalModified))
            copy(source, staging)
            require(hash(staging) == initial) { "Staging readback mismatch; original preserved." }
            require(source.exists() && hash(source) == initial &&
                (beforeModified <= 0 || source.modifiedMs() == beforeModified)) {
                "Source changed during copying; original preserved."
            }
            var suffix = 0
            while (destination == null) {
                currentCoroutineContext().ensureActive()
                val requested = if (suffix == 0) fileName else suffixed(fileName, suffix)
                destination = directory.createExclusive(requested, mimeType)
                if (destination == null) {
                    if (collisionPolicy == CollisionPolicy.SKIP_EXISTING) {
                        return@withContext VerifiedTransferReceipt(
                            directory.find(requested), initial.first, null, TransferMethod.COPY, true, true,
                            "Destination appeared during publication; skipped.",
                            "No source removed. Destination timestamp unverified."
                        )
                    }
                    suffix++
                }
            }
            val finalDestination = checkNotNull(destination)
            require(finalDestination.id != source.id && finalDestination.id != staging.id &&
                (sourceIdentity == null || finalDestination.identityKey() != sourceIdentity)) {
                "Storage provider returned an existing source/staging document."
            }
            destinationOwned = true
            checkpoint(TransferCheckpoint("PUBLISHING", finalDestination.id, staging.id, initial.first, originalModified))
            copy(checkNotNull(staging), finalDestination)
            require(hash(finalDestination) == initial) { "Final destination readback mismatch; original preserved." }
            val timestamp = finalDestination.setTimestamp(originalModified)
            val publicationDurable = directory.confirmPublicationDurable()
            destinationVerified = true
            checkpoint(TransferCheckpoint("DESTINATION_VERIFIED", finalDestination.id, staging.id,
                initial.first, originalModified, timestamp.actualDestinationLastModifiedMs, timestamp.preserved))
            var actualMethod = TransferMethod.COPY
            var retained = true
            var detail = "Destination bytes verified by SHA-256 readback. " + timestamp.note
            if (method == TransferMethod.MOVE && publicationDurable && sourceIdentity != null && finalDestination.identityKey() != null &&
                (timestamp.preserved || timestampPolicy == TimestampPolicy.BEST_EFFORT_WITH_REPORT)) {
                currentCoroutineContext().ensureActive()
                require(source.exists() && source.identityKey() == sourceIdentity && hash(source) == initial &&
                    (beforeModified <= 0 || source.modifiedMs() == beforeModified)) {
                    "Source changed before deletion; original and verified destination retained."
                }
                require(hash(finalDestination) == initial) { "Destination changed before deletion; original preserved." }
                checkpoint(TransferCheckpoint("SOURCE_DELETE_PENDING", finalDestination.id, staging.id,
                    initial.first, originalModified, timestamp.actualDestinationLastModifiedMs, timestamp.preserved))
                currentCoroutineContext().ensureActive()
                require(source.identityKey() == sourceIdentity && hash(source) == initial) { "Source changed before deletion; original preserved." }
                if (source.delete() && !source.exists()) {
                    retained = false
                    actualMethod = TransferMethod.MOVE
                } else {
                    detail += " Source deletion failed; verified copy retained and MOVE was not completed."
                }
            } else if (method == TransferMethod.MOVE) {
                detail += " Original retained because modification time, durable publication or stable storage identity could not be verified."
            }
            withContext(NonCancellable) {
                checkpoint(TransferCheckpoint("COMPLETED", finalDestination.id, staging.id,
                    initial.first, originalModified, timestamp.actualDestinationLastModifiedMs, timestamp.preserved))
            }
            VerifiedTransferReceipt(
                finalDestination, initial.first, timestamp, actualMethod, retained,
                collisionNote = if (finalDestination.name != fileName) "Resolved filename: " + finalDestination.name else null,
                details = detail
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                if (stagingOwned) runCatching { staging?.delete() }
                // A verified final copy is retained for recovery; never delete a source here.
                if (destinationOwned && !destinationVerified) runCatching { destination?.delete() }
            }
        }
    }

    private suspend fun copy(source: TransferDocument, destination: TransferDocument) {
        source.input().use { input ->
            destination.output().use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.flush()
                destination.sync(output)
            }
        }
    }

    suspend fun hash(document: TransferDocument): Pair<String, Long> = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        document.input().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                size += count
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) } to size
    }

    private fun suffixed(name: String, number: Int): String {
        val split = name.lastIndexOf('.')
        return if (split > 0) name.substring(0, split) + "_" + number + name.substring(split)
        else name + "_" + number
    }
}
