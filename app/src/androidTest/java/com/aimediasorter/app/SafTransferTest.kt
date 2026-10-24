package com.aimediasorter.app

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aimediasorter.app.data.local.MediaItemEntity
import com.aimediasorter.app.data.model.*
import com.aimediasorter.app.domain.storage.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafTransferTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext
    private val control get() = instrumentation.context
    private val authority = "com.aimediasorter.app.qa.documents"
    private val controlUri get() = Uri.parse("content://" + authority + "/control")
    private val sourceTree get() = DocumentsContract.buildTreeDocumentUri(authority, "source")
    private val destinationTree get() = DocumentsContract.buildTreeDocumentUri(authority, "destination")
    private val sourceUri get() = DocumentsContract.buildDocumentUriUsingTree(sourceTree, "source/photo.jpg")
    private fun setup() {
        val token = java.util.UUID.randomUUID().toString()
        control.startActivity(Intent().setClassName(control.packageName,
            FixtureGrantActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("qa.token", token))
        val deadline = android.os.SystemClock.elapsedRealtime() + 10000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val ready = runCatching {
                target.contentResolver.call(controlUri, "qa.state", null, null)?.getString("token") == token
            }.getOrDefault(false)
            if (ready) return
            Thread.sleep(50)
        }
        error("Test-only document provider did not grant its isolated fixture URIs.")
    }
    private fun cleanup() {
        target.contentResolver.call(controlUri, "qa.cleanup", null, null)
    }
    private fun hash() = control.contentResolver.call(controlUri, "qa.state", null, null)!!.getString("sourceHash")!!
    private fun sourceExists() = control.contentResolver.call(controlUri, "qa.state", null, null)!!.getBoolean("sourceExists")
    private fun item() = MediaItemEntity(
        uriString = sourceUri.toString(), filePath = null, fileName = "photo.jpg",
        extension = "jpg", isVideo = false, mimeType = "image/jpeg", fileSizeBytes = 4096,
        width = 0, height = 0, durationMs = 0, originalLastModifiedMs = 0,
        exifCaptureDateMs = null, quickFingerprint = "fixture", detectedCategoryId = "cat_people",
        detectedCategoryName = "People", effectiveCategoryId = "cat_people", effectiveCategoryName = "People",
        calibratedConfidence = .9f, rawSimilarityScore = 0f, topMargin = .5f,
        secondaryCategoryName = null, analyzedAtMs = 0, sha256Checksum = hash(),
        sourceRootUri = sourceTree.toString())
    private suspend fun transfer(method: TransferMethod) = SafeFileMover(target,
        ExifAndTimestampManager(target), StorageScanner(target)).transferMediaItem(
        "test", item(), sourceTree.toString(), destinationTree.toString(), "People",
        OperationMode.MOVE_FILES, method, CollisionPolicy.RENAME_WITH_SUFFIX,
        TimestampPolicy.FALLBACK_COPY_KEEP_ORIGINAL)

    @Test fun safCopyReadbackIsVerifiedAndCollisionDoesNotOverwrite() = runBlocking {
        setup()
        try {
            val original = hash()
            val first = transfer(TransferMethod.COPY)
            assertEquals(TransferItemStatus.COMPLETED_TIMESTAMP_WARNING, first.status)
            assertEquals(original, first.sha256Checksum)
            assertTrue(sourceExists())
            val second = transfer(TransferMethod.COPY)
            assertNotEquals(first.destinationUriString, second.destinationUriString)
            assertEquals(original, second.sha256Checksum)
            assertEquals(original, hash())
        } finally { cleanup() }
    }

    @Test fun safMoveKeepsOriginalWhenFilesystemTimestampCannotBeVerified() = runBlocking {
        setup()
        try {
            val original = hash()
            control.contentResolver.call(controlUri, "qa.fault", "timestamp", null)
            val result = transfer(TransferMethod.MOVE)
            assertEquals(TransferItemStatus.COMPLETED_SOURCE_RETAINED, result.status)
            assertFalse(result.timestampPreserved)
            assertEquals("COPY", result.logEntry.operationType)
            assertEquals(original, hash())
            assertTrue(sourceExists())
        } finally { cleanup() }
    }

    @Test fun corruptedDestinationReadbackNeverDeletesSource() = runBlocking {
        setup()
        try {
            val original = hash()
            control.contentResolver.call(controlUri, "qa.fault", "corrupt", null)
            val result = transfer(TransferMethod.MOVE)
            assertTrue(result.status in listOf(TransferItemStatus.FAILED, TransferItemStatus.RECOVERY_REQUIRED))
            assertTrue(sourceExists())
            assertEquals(original, hash())
        } finally { cleanup() }
    }

    @Test fun revokedProviderAccessNeverDeletesSource() = runBlocking {
        setup()
        try {
            val original = hash()
            val fixture = item()
            control.contentResolver.call(controlUri, "qa.fault", "revoke", null)
            val result = SafeFileMover(target, ExifAndTimestampManager(target), StorageScanner(target))
                .transferMediaItem("test", fixture, sourceTree.toString(), destinationTree.toString(),
                    "People", OperationMode.MOVE_FILES, TransferMethod.MOVE,
                    CollisionPolicy.RENAME_WITH_SUFFIX, TimestampPolicy.FALLBACK_COPY_KEEP_ORIGINAL)
            assertEquals(TransferItemStatus.FAILED, result.status)
            assertTrue(sourceExists())
            assertEquals(original, hash())
        } finally { cleanup() }
    }
}
