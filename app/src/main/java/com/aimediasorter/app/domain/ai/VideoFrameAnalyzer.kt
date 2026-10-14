package com.aimediasorter.app.domain.ai

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.aimediasorter.app.data.local.CategoryEntity
import com.aimediasorter.app.data.model.AiEngineMode
import com.aimediasorter.app.data.model.ClassificationScoreMetrics
import com.aimediasorter.app.data.model.FrameClassificationSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Multi-Frame Video Content Recognition Engine:
 * - Samples frames at 10%, 30%, 50%, 70%, and 90% of video duration (with adaptive sampling for short/long clips).
 * - Never relies solely on a single thumbnail.
 * - Uses native MediaMetadataRetriever.getScaledFrameAtTime for memory-safe decoding.
 * - Immediately recycles frame Bitmaps after each inference pass.
 * - Aggregates frame predictions, detects multi-scene ambiguity, and never touches or re-encodes original video bytes.
 */
class VideoFrameAnalyzer(
    private val context: Context,
    private val classifier: OnDeviceAiClassifier
) {
    companion object {
        val STANDARD_SAMPLE_PERCENTS = listOf(10, 30, 50, 70, 90)
        val SHORT_VIDEO_SAMPLE_PERCENTS = listOf(20, 50, 80)
        val LONG_VIDEO_SAMPLE_PERCENTS = listOf(10, 23, 36, 50, 63, 77, 90)
        private const val DECODE_FRAME_DIMENSION = 320
    }

    fun selectAdaptiveSamplePercents(durationMs: Long): List<Int> {
        return when {
            durationMs in 1..2499L -> SHORT_VIDEO_SAMPLE_PERCENTS
            durationMs > 120_000L -> LONG_VIDEO_SAMPLE_PERCENTS
            else -> STANDARD_SAMPLE_PERCENTS
        }
    }

    suspend fun analyzeVideo(
        uri: Uri,
        filePath: String?,
        durationMs: Long,
        enabledCategories: List<CategoryEntity>,
        aiEngineMode: AiEngineMode,
        customOnnxPath: String,
        enableHardwareAccel: Boolean,
        confidenceThreshold: Float
    ): ClassificationScoreMetrics = withContext(Dispatchers.Default) {
        val retriever = MediaMetadataRetriever()
        val frameSamples = mutableListOf<FrameClassificationSample>()
        val categoryResults = mutableListOf<ClassificationScoreMetrics>()

        try {
            if (!filePath.isNullOrBlank() && File(filePath).exists()) {
                retriever.setDataSource(filePath)
            } else {
                retriever.setDataSource(context, uri)
            }

            val resolvedDurationMs = if (durationMs > 0L) {
                durationMs
            } else {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 5000L
            }.coerceAtLeast(1000L)

            val percents = selectAdaptiveSamplePercents(resolvedDurationMs)

            for (pct in percents) {
                currentCoroutineContext().ensureActive()
                val targetTimeMs = (resolvedDurationMs * pct) / 100L
                val targetTimeUs = targetTimeMs * 1000L

                val frameBitmap = runCatching {
                    retriever.getScaledFrameAtTime(
                        targetTimeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        DECODE_FRAME_DIMENSION,
                        DECODE_FRAME_DIMENSION
                    ) ?: retriever.getFrameAtTime(targetTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }.getOrNull()

                if (frameBitmap != null) {
                    try {
                        val frameMetric = classifier.classifyBitmap(
                            bitmap = frameBitmap,
                            enabledCategories = enabledCategories,
                            aiEngineMode = aiEngineMode,
                            customOnnxPath = customOnnxPath,
                            enableHardwareAccel = enableHardwareAccel,
                            confidenceThreshold = confidenceThreshold
                        )
                        categoryResults.add(frameMetric)
                        frameSamples.add(
                            FrameClassificationSample(
                                timestampPercent = pct,
                                timestampMs = targetTimeMs,
                                categoryName = frameMetric.categoryName,
                                calibratedConfidence = frameMetric.calibratedConfidence,
                                rawSimilarity = frameMetric.rawSimilarityScore
                            )
                        )
                    } finally {
                        if (!frameBitmap.isRecycled) {
                            frameBitmap.recycle()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            return@withContext ClassificationScoreMetrics(
                categoryId = "cat_other",
                categoryName = "Other",
                calibratedConfidence = 0f,
                rawSimilarityScore = 0f,
                topMargin = 0f,
                needsManualReview = true,
                primarySubjectNote = "Corrupted or unsupported video stream: ${e.message}"
            )
        } finally {
            runCatching { retriever.release() }
        }

        aggregateFramePredictions(
            frameMetrics = categoryResults,
            frameSamples = frameSamples,
            enabledCategories = enabledCategories,
            confidenceThreshold = confidenceThreshold
        )
    }

    fun aggregateFramePredictions(
        frameMetrics: List<ClassificationScoreMetrics>,
        frameSamples: List<FrameClassificationSample>,
        enabledCategories: List<CategoryEntity>,
        confidenceThreshold: Float
    ): ClassificationScoreMetrics {
        if (frameMetrics.isEmpty()) {
            return ClassificationScoreMetrics(
                categoryId = "cat_other",
                categoryName = "Other",
                calibratedConfidence = 0f,
                rawSimilarityScore = 0f,
                topMargin = 0f,
                needsManualReview = true,
                primarySubjectNote = "No decodable video frames could be extracted."
            )
        }

        val weightedVotes = mutableMapOf<String, Float>()
        val similaritySum = mutableMapOf<String, Float>()
        val frameCounts = mutableMapOf<String, Int>()
        val idToName = mutableMapOf<String, String>()

        var totalWeight = 0f
        for (m in frameMetrics) {
            idToName[m.categoryId] = m.categoryName
            val weight = m.calibratedConfidence.coerceAtLeast(0.1f)
            weightedVotes[m.categoryId] = (weightedVotes[m.categoryId] ?: 0f) + weight
            similaritySum[m.categoryId] = (similaritySum[m.categoryId] ?: 0f) + m.rawSimilarityScore
            frameCounts[m.categoryId] = (frameCounts[m.categoryId] ?: 0) + 1
            totalWeight += weight
        }

        val rankedIds = weightedVotes.keys.sortedByDescending { weightedVotes[it] ?: 0f }
        val dominantId = rankedIds.first()
        val secondaryId = rankedIds.getOrNull(1)

        val dominantShare = if (totalWeight > 0f) (weightedVotes[dominantId] ?: 0f) / totalWeight else 0f
        val secondaryShare = if (secondaryId != null && totalWeight > 0f) (weightedVotes[secondaryId] ?: 0f) / totalWeight else 0f
        val dominantCount = frameCounts[dominantId] ?: 1

        val avgConfidence = frameMetrics
            .filter { it.categoryId == dominantId }
            .map { it.calibratedConfidence }
            .average()
            .toFloat()

        val avgSimilarity = (similaritySum[dominantId] ?: 0f) / dominantCount.toFloat()
        val margin = (dominantShare - secondaryShare).coerceAtLeast(0f)

        // Multi-scene ambiguity detection: if >= 2 unrelated categories appear across frames with close vote shares
        val isMultiSceneAmbiguous = secondaryId != null && secondaryShare >= 0.34f && margin < 0.25f
        val calibratedAggregatedConf = (avgConfidence * 0.7f + dominantShare * 0.3f).coerceIn(0f, 1f)
        val needsReview = isMultiSceneAmbiguous || calibratedAggregatedConf < confidenceThreshold || dominantId == "cat_other"

        return ClassificationScoreMetrics(
            categoryId = dominantId,
            categoryName = idToName[dominantId] ?: enabledCategories.firstOrNull { it.id == dominantId }?.name ?: "Other",
            calibratedConfidence = calibratedAggregatedConf,
            rawSimilarityScore = avgSimilarity,
            topMargin = margin,
            secondaryCategoryName = secondaryId?.let { idToName[it] },
            isAmbiguousMultiScene = isMultiSceneAmbiguous,
            needsManualReview = needsReview,
            primarySubjectNote = if (isMultiSceneAmbiguous) {
                "Multi-scene video detected (${idToName[dominantId]} vs ${idToName[secondaryId]} across ${frameSamples.size} sampled frames)."
            } else {
                "Aggregated across ${frameSamples.size} video frames (${frameSamples.joinToString { "${it.timestampPercent}%" }})."
            },
            frameSamples = frameSamples
        )
    }
}
