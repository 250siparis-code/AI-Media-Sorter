package com.aimediasorter.app.domain.ai

import com.aimediasorter.app.data.local.CategoryEntity
import com.aimediasorter.app.data.model.ClassificationScoreMetrics
import com.aimediasorter.app.data.model.FrameClassificationSample

object VideoPredictionAggregator {
    fun aggregate(frameMetrics: List<ClassificationScoreMetrics>, frameSamples: List<FrameClassificationSample>, enabledCategories: List<CategoryEntity>, confidenceThreshold: Float): ClassificationScoreMetrics {
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
        val isMultiSceneAmbiguous = secondaryId != null && secondaryShare >= 0.20f
        val calibratedAggregatedConf = (avgConfidence * dominantShare).coerceIn(0f, 1f)
        val needsReview = frameMetrics.size < 3 || frameMetrics.any { it.needsManualReview } ||
            isMultiSceneAmbiguous || margin < 0.12f || calibratedAggregatedConf < confidenceThreshold || dominantId == "cat_other"

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
                "Multi-scene video detected (${idToName[dominantId]} vs ${idToName[secondaryId]} across ${frameSamples.size} distinct sampled frames)."
            } else {
                "Aggregated across ${frameSamples.size} distinct video frames at requested timestamps. Actual presentation timestamps are not exposed by MediaMetadataRetriever."
            },
            frameSamples = frameSamples,
            inferenceEngine = if (frameMetrics.all { it.inferenceEngine.startsWith("ML_KIT") }) "ML_KIT_VIDEO_SAMPLES" else "ONNX_DIAGNOSTIC",
            modelLabels = frameMetrics.flatMap { it.modelLabels.entries }.groupBy { it.key }
                .mapValues { (_, values) -> values.maxOf { it.value } }
        )
    }
}
