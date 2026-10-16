package com.aimediasorter.app.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class MediaTypeFilter {
    ALL,
    PHOTOS_ONLY,
    VIDEOS_ONLY
}

@Serializable
enum class OperationMode {
    ANALYSIS_ONLY, // Default safe mode: scans, classifies, previews, never moves or deletes
    MOVE_FILES     // Enabled explicitly when user confirms moving/copying files
}

@Serializable
enum class TransferMethod {
    MOVE,
    COPY
}

@Serializable
enum class CollisionPolicy {
    RENAME_WITH_SUFFIX,   // e.g., IMG_001_1.jpg
    SKIP_EXISTING,        // Leave existing file untouched and skip transfer
    VERIFY_HASH_AND_SKIP  // If SHA-256 matches, skip; otherwise rename with suffix
}

@Serializable
enum class TimestampPolicy {
    BEST_EFFORT_WITH_REPORT,     // Attempt restore, verify, and clearly report if SAF blocked it
    FALLBACK_COPY_KEEP_ORIGINAL  // Non-destructive: if timestamp cannot be preserved on move, keep source file intact
}

@Serializable
enum class AiEngineMode {
    HYBRID_ONNX_AND_MLKIT,
    ONNX_EMBEDDER_ONLY,
    CUSTOM_MOBILECLIP_ONNX
}

@Serializable
enum class TransferItemStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED_VERIFIED,
    COMPLETED_TIMESTAMP_WARNING,
    COMPLETED_SOURCE_RETAINED,
    RECOVERY_REQUIRED,
    SKIPPED_COLLISION,
    FAILED,
    CANCELLED
}

@Serializable
data class FrameClassificationSample(
    val timestampPercent: Int,
    val timestampMs: Long,
    val categoryName: String,
    val calibratedConfidence: Float,
    val rawSimilarity: Float
)

@Serializable
data class ClassificationScoreMetrics(
    val categoryId: String,
    val categoryName: String,
    val calibratedConfidence: Float, // Legacy field name: raw model/diagnostic score, not measured accuracy.
    val rawSimilarityScore: Float,   // Diagnostic ONNX cosine only; unavailable for ML Kit.
    val topMargin: Float,            // Difference between top-1 and top-2 calibrated score
    val secondaryCategoryName: String? = null,
    val isAmbiguousMultiScene: Boolean = false,
    val needsManualReview: Boolean = false,
    val primarySubjectNote: String = "",
    val frameSamples: List<FrameClassificationSample> = emptyList(),
    val modelLabels: Map<String, Float> = emptyMap(),
    val inferenceEngine: String = "unknown",
    val proposedCategoryIds: List<String> = emptyList(),
    val analysisJson: String = "{}",
    val analysisStatus: String = "LEGACY",
    val analysisModel: String = ""
)

data class DefaultCategorySpec(
    val id: String,
    val defaultName: String,
    val defaultDescription: String,
    val colorHex: Long,
    val iconKey: String,
    val priority: Int,
    val subfolderName: String
)

object DefaultCategories {
    val ALL: List<DefaultCategorySpec> = listOf(
        DefaultCategorySpec(
            id = "cat_people",
            defaultName = "People",
            defaultDescription = "Humans, portraits, selfies, fashion",
            colorHex = 0xFFE040FB,
            iconKey = "person",
            priority = 6,
            subfolderName = "People"
        ),
        DefaultCategorySpec(
            id = "cat_food",
            defaultName = "Food",
            defaultDescription = "Meals, desserts, beverages",
            colorHex = 0xFFFF6D00,
            iconKey = "restaurant",
            priority = 9, // Higher subject priority than generic background people when holding food
            subfolderName = "Food"
        ),
        DefaultCategorySpec(
            id = "cat_vehicles",
            defaultName = "Vehicles",
            defaultDescription = "Cars, motorcycles, trucks, transportation",
            colorHex = 0xFF00B0FF,
            iconKey = "directions_car",
            priority = 8,
            subfolderName = "Vehicles"
        ),
        DefaultCategorySpec(
            id = "cat_animals",
            defaultName = "Animals",
            defaultDescription = "Cats, dogs, birds, wildlife",
            colorHex = 0xFF00E676,
            iconKey = "pets",
            priority = 9,
            subfolderName = "Animals"
        ),
        DefaultCategorySpec(
            id = "cat_nature",
            defaultName = "Nature",
            defaultDescription = "Landscapes, forests, mountains, beaches",
            colorHex = 0xFF1DE9B6,
            iconKey = "landscape",
            priority = 7,
            subfolderName = "Nature"
        ),
        DefaultCategorySpec(
            id = "cat_buildings",
            defaultName = "Buildings",
            defaultDescription = "Architecture, houses, urban environments",
            colorHex = 0xFFFFC400,
            iconKey = "apartment",
            priority = 7,
            subfolderName = "Buildings"
        ),
        DefaultCategorySpec(
            id = "cat_technology",
            defaultName = "Technology",
            defaultDescription = "Phones, computers, electronics",
            colorHex = 0xFF651FFF,
            iconKey = "devices",
            priority = 8,
            subfolderName = "Technology"
        ),
        DefaultCategorySpec(
            id = "cat_text",
            defaultName = "Text",
            defaultDescription = "Documents, screenshots, text-heavy images",
            colorHex = 0xFF40C4FF,
            iconKey = "description",
            priority = 9,
            subfolderName = "Text"
        ),
        DefaultCategorySpec(
            id = "cat_other",
            defaultName = "Other",
            defaultDescription = "Unknown, ambiguous, or unsupported content",
            colorHex = 0xFF9E9E9E,
            iconKey = "folder_special",
            priority = 1,
            subfolderName = "Other"
        )
    )

    val SUPPORTED_PHOTO_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")
    val SUPPORTED_VIDEO_EXTENSIONS = setOf("mp4", "mov")
    val ALL_SUPPORTED_EXTENSIONS = SUPPORTED_PHOTO_EXTENSIONS + SUPPORTED_VIDEO_EXTENSIONS
}
