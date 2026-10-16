package com.aimediasorter.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aimediasorter.app.data.local.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    @Test fun versionOneMigratesWithoutLosingLegacyMedia() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-" + UUID.randomUUID() + ".db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                LEGACY_SCHEMA.forEach { db.execSQL(it) }
                val values = ContentValues().apply {
                put("id", 7L)
                put("uriString", "content://legacy/photo")
                put("fileName", "legacy.jpg")
                put("extension", "")
                put("isVideo", 0L)
                put("mimeType", "")
                put("fileSizeBytes", 0L)
                put("width", 0L)
                put("height", 0L)
                put("durationMs", 0L)
                put("originalLastModifiedMs", 0L)
                put("quickFingerprint", "")
                put("detectedCategoryId", "")
                put("detectedCategoryName", "")
                put("effectiveCategoryId", "")
                put("effectiveCategoryName", "")
                put("calibratedConfidence", 0f)
                put("rawSimilarityScore", 0f)
                put("topMargin", 0f)
                put("isAmbiguousMultiScene", 0L)
                put("needsManualReview", 0L)
                put("isManuallyCorrected", 0L)
                put("isExcludedFromTransfer", 0L)
                put("primarySubjectNote", "")
                put("frameBreakdownJson", "")
                put("analyzedAtMs", 0L)
                put("transferStatus", "")
                put("proposedRelativePath", "")
                }
                assertEquals(7L, db.insertOrThrow("media_items", null, values))
                db.version = 1
            }
            val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_1_2,AppDatabase.MIGRATION_2_3,AppDatabase.MIGRATION_3_4).build()
            try {
                val preserved = requireNotNull(migrated.mediaItemDao().getByUri("content://legacy/photo"))
                assertEquals("legacy.jpg", preserved.fileName)
                assertEquals("", preserved.sourceRootUri)
                assertEquals("legacy", preserved.inferenceEngine)
                assertEquals("UNRECORDED",preserved.diagnosticCode)
                assertEquals(-1,preserved.apiRequestsSent)
                assertEquals(-1,preserved.requestReservations)
                assertTrue(migrated.mediaItemDao().getCurrentMediaItems("any-source").isEmpty())
                assertTrue(migrated.transferJournalDao().unfinished().isEmpty())
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }
    companion object {
        private val LEGACY_SCHEMA = listOf(
            "CREATE TABLE categories (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, description TEXT NOT NULL, colorHex INTEGER NOT NULL, iconKey TEXT NOT NULL, priority INTEGER NOT NULL, isEnabled INTEGER NOT NULL, isDefault INTEGER NOT NULL, destinationSubfolder TEXT NOT NULL, customDestinationUri TEXT)",
            "CREATE TABLE media_items (id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, uriString TEXT NOT NULL, filePath TEXT, fileName TEXT NOT NULL, extension TEXT NOT NULL, isVideo INTEGER NOT NULL, mimeType TEXT NOT NULL, fileSizeBytes INTEGER NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL, durationMs INTEGER NOT NULL, originalLastModifiedMs INTEGER NOT NULL, exifCaptureDateMs INTEGER, quickFingerprint TEXT NOT NULL, detectedCategoryId TEXT NOT NULL, detectedCategoryName TEXT NOT NULL, effectiveCategoryId TEXT NOT NULL, effectiveCategoryName TEXT NOT NULL, calibratedConfidence REAL NOT NULL, rawSimilarityScore REAL NOT NULL, topMargin REAL NOT NULL, secondaryCategoryName TEXT, isAmbiguousMultiScene INTEGER NOT NULL, needsManualReview INTEGER NOT NULL, isManuallyCorrected INTEGER NOT NULL, isExcludedFromTransfer INTEGER NOT NULL, primarySubjectNote TEXT NOT NULL, frameBreakdownJson TEXT NOT NULL, analyzedAtMs INTEGER NOT NULL, transferStatus TEXT NOT NULL, proposedRelativePath TEXT NOT NULL, destinationUriString TEXT, sha256Checksum TEXT, timestampVerified INTEGER, timestampStatusNote TEXT)",
            "CREATE TABLE transfer_logs (id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, sessionId TEXT NOT NULL, sourceUri TEXT NOT NULL, sourceFileName TEXT NOT NULL, destinationUri TEXT, categoryName TEXT NOT NULL, operationType TEXT NOT NULL, bytesTransferred INTEGER NOT NULL, sha256Checksum TEXT, originalLastModifiedMs INTEGER NOT NULL, destinationLastModifiedMs INTEGER, timestampPreserved INTEGER NOT NULL, collisionResolution TEXT, status TEXT NOT NULL, detailsMessage TEXT NOT NULL, executedAtMs INTEGER NOT NULL)",
            "CREATE TABLE analysis_sessions (sessionId TEXT NOT NULL PRIMARY KEY, startedAtMs INTEGER NOT NULL, completedAtMs INTEGER, sourceUri TEXT NOT NULL, destinationUri TEXT NOT NULL, totalScanned INTEGER NOT NULL, totalClassified INTEGER NOT NULL, uncertainCount INTEGER NOT NULL, movedCount INTEGER NOT NULL, failedCount INTEGER NOT NULL, status TEXT NOT NULL)",
            "CREATE UNIQUE INDEX index_media_items_uriString ON media_items(uriString)",
            "CREATE INDEX index_media_items_quickFingerprint ON media_items(quickFingerprint)",
            "CREATE INDEX index_media_items_effectiveCategoryId ON media_items(effectiveCategoryId)"
        )
    }
}
