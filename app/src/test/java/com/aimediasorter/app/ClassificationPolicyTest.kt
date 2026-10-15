package com.aimediasorter.app

import com.aimediasorter.app.data.local.CategoryEntity
import com.aimediasorter.app.data.model.*
import com.aimediasorter.app.domain.ai.LabelCategoryMapper
import com.aimediasorter.app.domain.ai.VideoPredictionAggregator
import org.junit.Assert.*
import org.junit.Test

/** Tests mapping and review policy; these are not recognition-accuracy tests. */
class ClassificationPolicyTest {
    private val categories = DefaultCategories.ALL.map {
        CategoryEntity(it.id, it.defaultName, it.defaultDescription, it.colorHex, it.iconKey,
            it.priority, isDefault = true, destinationSubfolder = it.subfolderName)
    }
    @Test fun unknownLabelsDoNotForceEnabledCategory() {
        val result = LabelCategoryMapper.classify(mapOf("Unmapped label" to 0.99f), categories.take(1), 0.6f)
        assertEquals("cat_other", result.categoryId)
        assertTrue(result.needsManualReview)
    }
    @Test fun oneEnabledCategoryDoesNotTurnWeakEvidenceIntoCertainty() {
        val result = LabelCategoryMapper.classify(mapOf("Person" to 0.44f), categories.take(1), 0.6f)
        assertEquals(0.44f, result.calibratedConfidence, 0.0001f)
        assertTrue(result.needsManualReview)
    }
    @Test fun actualLabelScoresAndMultiSubjectAmbiguityArePreserved() {
        val result = LabelCategoryMapper.classify(mapOf("Person" to 0.90f, "Food" to 0.91f), categories, 0.6f)
        assertEquals("cat_food", result.categoryId)
        assertEquals(0.91f, result.calibratedConfidence, 0.0001f)
        assertTrue(result.needsManualReview)
    }
    @Test fun turkishCustomKeywordsMatchWholeLabelWords() {
        val custom = CategoryEntity("custom", "Kediler", "Evcil kedi", 0, "label", 5,
            destinationSubfolder = "Kediler")
        val result = LabelCategoryMapper.classify(mapOf("Cat" to 0.9f), listOf(custom), 0.6f)
        assertEquals("custom", result.categoryId)
        assertFalse(result.needsManualReview)
        val carpet = LabelCategoryMapper.classify(mapOf("Carpet" to 0.99f), categories, 0.6f)
        assertNotEquals("cat_vehicles", carpet.categoryId)
    }
    private fun sample(score: Float, review: Boolean) =
        ClassificationScoreMetrics("cat_food", "Food", score, 0f, 0.2f,
            needsManualReview = review, inferenceEngine = "ML_KIT_IMAGE_LABELING")
    @Test fun repeatedUncertainVideoVotesNeverBecomeApproved() {
        val result = VideoPredictionAggregator.aggregate(List(5) { sample(0.44f, true) },
            emptyList(), categories, 0.6f)
        assertEquals(0.44f, result.calibratedConfidence, 0.0001f)
        assertTrue(result.needsManualReview)
    }
    @Test fun sparseVideoEvidenceRequiresReviewEvenWhenConfident() {
        val result = VideoPredictionAggregator.aggregate(listOf(sample(0.95f, false)),
            emptyList(), categories, 0.6f)
        assertTrue(result.needsManualReview)
    }
}
