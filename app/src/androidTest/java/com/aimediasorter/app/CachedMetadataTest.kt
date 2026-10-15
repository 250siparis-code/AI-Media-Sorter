package com.aimediasorter.app

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aimediasorter.app.data.local.AppDatabase
import com.aimediasorter.app.data.preferences.AppSettings
import com.aimediasorter.app.data.repository.MediaRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CachedMetadataTest {
    @Test fun identicalBytesReuseInferenceButRefreshFilesystemModificationTime() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.cacheDir, "qa-cache-" + UUID.randomUUID()).apply { check(mkdirs()) }
        val file = File(folder, "image.png")
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.GREEN)
            file.outputStream().use { check(compress(Bitmap.CompressFormat.PNG, 100, it)) }
            recycle()
        }
        val before = 1700000000000L
        val after = before + 10000
        check(file.setLastModified(before))
        val database = AppDatabase.getInstance(context)
        val repository = MediaRepository(context)
        val settings = AppSettings(sourceUri = folder.path, destinationUri = "", includeSubfolders = false)
        try {
            repository.executeMediaScanAndAnalysis(settings)
            val first = requireNotNull(database.mediaItemDao().getByUri(Uri.fromFile(file).toString()))
            assertEquals(before, first.originalLastModifiedMs)
            // Simulate a prior verified COPY result; cached timestamp claims must become stale after touch.
            database.mediaItemDao().updateMediaItem(first.copy(
                transferStatus = com.aimediasorter.app.data.model.TransferItemStatus.COMPLETED_VERIFIED.name,
                timestampVerified = true, destinationUriString = Uri.fromFile(file).toString()))
            check(file.setLastModified(after))
            repository.executeMediaScanAndAnalysis(settings)
            val second = requireNotNull(database.mediaItemDao().getByUri(Uri.fromFile(file).toString()))
            assertEquals(first.sha256Checksum, second.sha256Checksum)
            assertEquals(first.detectedCategoryId, second.detectedCategoryId)
            assertEquals(1, repository.analysisProgress.value.skippedUnchangedCount)
            assertEquals(after, second.originalLastModifiedMs)
            assertEquals(false, second.timestampVerified)
            assertEquals(com.aimediasorter.app.data.model.TransferItemStatus.COMPLETED_TIMESTAMP_WARNING.name,
                second.transferStatus)
        } finally {
            repository.aiClassifier.close()
            database.openHelper.writableDatabase.execSQL(
                "DELETE FROM media_items WHERE sourceRootUri = ?", arrayOf(folder.path))
            database.openHelper.writableDatabase.execSQL(
                "DELETE FROM analysis_sessions WHERE sourceUri = ?", arrayOf(folder.path))
            folder.deleteRecursively()
        }
    }
}
