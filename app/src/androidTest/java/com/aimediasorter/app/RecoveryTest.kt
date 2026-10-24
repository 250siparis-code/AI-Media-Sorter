package com.aimediasorter.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aimediasorter.app.data.local.*
import com.aimediasorter.app.data.repository.MediaRepository
import com.aimediasorter.app.data.model.TransferItemStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecoveryTest {
    @Test fun laterInterruptionsAreReconciledInSameProcessAndTimestampIsReadAgain() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "qa-recovery-" + UUID.randomUUID()).apply { check(mkdirs()) }
        val source = File(directory, "source.jpg").apply { writeBytes(byteArrayOf(2, 4, 8, 16)) }
        val destination = File(directory, "destination.jpg").apply { writeBytes(source.readBytes()) }
        val originalTime = 1700000000000L
        check(source.setLastModified(originalTime))
        check(destination.setLastModified(originalTime + 10000))
        val checksum = MessageDigest.getInstance("SHA-256").digest(source.readBytes())
            .joinToString("") { "%02x".format(it) }
        val database = AppDatabase.getInstance(context)
        val repository = MediaRepository(context)
        val operation = UUID.randomUUID().toString()
        var id = 0L
        try {
            // A later interruption must still be examined after startup's first recovery pass.
            repository.reconcileInterruptedTransfers()
            id = database.mediaItemDao().upsertMediaItem(MediaItemEntity(
                uriString = Uri.fromFile(source).toString(), filePath = source.path, fileName = source.name,
                extension = "jpg", isVideo = false, mimeType = "image/jpeg", fileSizeBytes = source.length(),
                width = 0, height = 0, durationMs = 0, originalLastModifiedMs = originalTime,
                exifCaptureDateMs = null, quickFingerprint = "fixture", detectedCategoryId = "cat_other",
                detectedCategoryName = "Other", effectiveCategoryId = "cat_other", effectiveCategoryName = "Other",
                calibratedConfidence = 0f, rawSimilarityScore = 0f, topMargin = 0f, secondaryCategoryName = null,
                analyzedAtMs = 0, sha256Checksum = checksum, sourceRootUri = directory.path,
                transferStatus = TransferItemStatus.IN_PROGRESS.name))
            database.transferJournalDao().upsert(TransferJournalEntity(
                operation, id, directory.path, Uri.fromFile(source).toString(), "MOVE",
                "DESTINATION_VERIFIED", Uri.fromFile(destination).toString(), null, checksum,
                originalTime, originalTime, true, System.currentTimeMillis(), "Simulated interruption"))
            repository.reconcileInterruptedTransfers()
            val recovered = requireNotNull(database.mediaItemDao().getMediaItemById(id))
            assertEquals(TransferItemStatus.RECOVERY_REQUIRED.name, recovered.transferStatus)
            assertTrue(recovered.needsManualReview)
            assertEquals(false, recovered.timestampVerified)
            assertTrue(source.exists())
            assertArrayEquals(source.readBytes(), destination.readBytes())
            assertTrue(database.transferJournalDao().unfinished().none { it.operationId == operation })
        } finally {
            database.openHelper.writableDatabase.execSQL(
                "DELETE FROM transfer_journal WHERE operationId = ?", arrayOf(operation))
            if (id != 0L) database.openHelper.writableDatabase.execSQL(
                "DELETE FROM media_items WHERE id = ?", arrayOf(id))
            directory.deleteRecursively()
        }
    }
}
