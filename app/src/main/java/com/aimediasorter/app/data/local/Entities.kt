package com.aimediasorter.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.aimediasorter.app.data.model.TransferItemStatus
import kotlinx.serialization.Serializable

@Entity(tableName = "categories")
@Serializable
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val colorHex: Long,
    val iconKey: String,
    val priority: Int,
    val isEnabled: Boolean = true,
    val isDefault: Boolean = false,
    val destinationSubfolder: String,
    val customDestinationUri: String? = null
)

@Entity(
    tableName = "media_items",
    indices = [
        Index(value = ["uriString"], unique = true),
        Index(value = ["quickFingerprint"]),
        Index(value = ["effectiveCategoryId"])
    ]
)
@Serializable
data class MediaItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val uriString: String,
    val filePath: String?,
    val fileName: String,
    val extension: String,
    val isVideo: Boolean,
    val mimeType: String,
    val fileSizeBytes: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val originalLastModifiedMs: Long, // True filesystem modification timestamp
    val exifCaptureDateMs: Long?,     // Embedded EXIF/Metadata capture date (kept strictly distinct)
    val quickFingerprint: String,     // Used to skip unchanged files on repeat scans
    val detectedCategoryId: String,
    val detectedCategoryName: String,
    val effectiveCategoryId: String,
    val effectiveCategoryName: String,
    val calibratedConfidence: Float,
    val rawSimilarityScore: Float,
    val topMargin: Float,
    val secondaryCategoryName: String?,
    val isAmbiguousMultiScene: Boolean = false,
    val needsManualReview: Boolean = false,
    val isManuallyCorrected: Boolean = false,
    val isExcludedFromTransfer: Boolean = false,
    val primarySubjectNote: String = "",
    val frameBreakdownJson: String = "[]",
    val analyzedAtMs: Long,
    val transferStatus: String = TransferItemStatus.PENDING.name,
    val proposedRelativePath: String = "",
    val destinationUriString: String? = null,
    val sha256Checksum: String? = null,
    val timestampVerified: Boolean? = null,
    val timestampStatusNote: String? = null
)

@Entity(tableName = "transfer_logs")
@Serializable
data class TransferLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sessionId: String,
    val sourceUri: String,
    val sourceFileName: String,
    val destinationUri: String?,
    val categoryName: String,
    val operationType: String, // MOVE or COPY
    val bytesTransferred: Long,
    val sha256Checksum: String?,
    val originalLastModifiedMs: Long,
    val destinationLastModifiedMs: Long?,
    val timestampPreserved: Boolean,
    val collisionResolution: String?,
    val status: String,
    val detailsMessage: String,
    val executedAtMs: Long
)

@Entity(tableName = "analysis_sessions")
@Serializable
data class AnalysisSessionEntity(
    @PrimaryKey val sessionId: String,
    val startedAtMs: Long,
    val completedAtMs: Long?,
    val sourceUri: String,
    val destinationUri: String,
    val totalScanned: Int,
    val totalClassified: Int,
    val uncertainCount: Int,
    val movedCount: Int,
    val failedCount: Int,
    val status: String
)
