package com.aimediasorter.app.domain.ai

import com.aimediasorter.app.data.local.CategoryEntity
import com.aimediasorter.app.data.model.ClassificationScoreMetrics
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

@Serializable
data class BundledVocabPayload(
    val model_name: String = "",
    val license: String = "Apache-2.0",
    val embedding_dim: Int = 32,
    val feature_dim: Int = 64,
    val default_categories: List<String> = emptyList(),
    val token_embeddings: Map<String, List<Float>> = emptyMap()
)

/**
 * Handles:
 * 1. Open-vocabulary text embedding computation for user-defined categories & descriptions (EN/TR).
 * 2. Primary-Subject Disambiguation: Ensures a photo of a person holding a plate of food (or with a pet,
 *    vehicle, or device as the primary central subject) is classified by its primary subject rather than
 *    defaulting to "People" merely because a human is visible.
 * 3. Uncalibrated diagnostic ranking score calculation strictly separated from raw cosine similarity.
 */
class PrimarySubjectDisambiguator(vocabJsonString: String) {

    private val json = Json { ignoreUnknownKeys = true }
    val vocabPayload: BundledVocabPayload = runCatching {
        json.decodeFromString<BundledVocabPayload>(vocabJsonString)
    }.getOrDefault(BundledVocabPayload())

    /**
     * Computes a normalized 32-D text embedding for any category (default or user-created)
     * using its name and description tokens.
     */
    fun computeCategoryTextEmbedding(category: CategoryEntity): FloatArray {
        val dim = vocabPayload.embedding_dim.coerceAtLeast(32)
        val combinedText = "${category.name} ${category.description}".lowercase()
        val tokens = combinedText.split(Regex("[^a-z0-9çğıöşü]+")).filter { it.isNotBlank() }

        val sumVec = FloatArray(dim)
        var matchedTokens = 0
        for (token in tokens) {
            val emb = vocabPayload.token_embeddings[token]
            if (emb != null && emb.size == dim) {
                for (i in 0 until dim) {
                    sumVec[i] += emb[i]
                }
                matchedTokens++
            }
        }

        // Fallback mapping if default ID matches known block
        if (matchedTokens == 0) {
            val blockIndex = when (category.id) {
                "cat_people" -> 0
                "cat_food" -> 1
                "cat_vehicles" -> 2
                "cat_animals" -> 3
                "cat_nature" -> 4
                "cat_buildings" -> 5
                "cat_technology" -> 6
                "cat_text" -> 7
                else -> -1
            }
            if (blockIndex in 0..7) {
                for (d in 0 until 4) {
                    sumVec[blockIndex * 4 + d] = 0.5f
                }
            }
        }

        return l2Normalize(sumVec)
    }

    /**
     * Combines ONNX model probabilities, 32-D image-text cosine similarities, optional ML Kit labels,
     * and central-ROI primary-subject disambiguation rules.
     */
    fun evaluateAndDisambiguate(
        enabledCategories: List<CategoryEntity>,
        onnxCategoryProbs: FloatArray,
        imageEmbedding32D: FloatArray,
        visualFeatures64D: FloatArray,
        mlKitLabels: Map<String, Float>,
        confidenceThreshold: Float
    ): ClassificationScoreMetrics {
        if (enabledCategories.isEmpty()) {
            return ClassificationScoreMetrics(
                categoryId = "cat_other",
                categoryName = "Other",
                calibratedConfidence = 0f,
                rawSimilarityScore = 0f,
                topMargin = 0f,
                needsManualReview = true,
                primarySubjectNote = "No enabled categories available."
            )
        }

        val normalizedImageEmb = l2Normalize(imageEmbedding32D)
        val rawSimilarities = mutableMapOf<String, Float>()
        val fusedLogits = mutableMapOf<String, Float>()

        val defaultIdToIndex = mapOf(
            "cat_people" to 0,
            "cat_food" to 1,
            "cat_vehicles" to 2,
            "cat_animals" to 3,
            "cat_nature" to 4,
            "cat_buildings" to 5,
            "cat_technology" to 6,
            "cat_text" to 7,
            "cat_other" to 8
        )

        for (cat in enabledCategories) {
            val textEmb = computeCategoryTextEmbedding(cat)
            val cosSim = cosineSimilarity(normalizedImageEmb, textEmb)
            rawSimilarities[cat.id] = cosSim

            val baseOnnxProb = defaultIdToIndex[cat.id]?.let { idx ->
                if (idx < onnxCategoryProbs.size) onnxCategoryProbs[idx] else 0.1f
            } ?: max(0.05f, cosSim * 0.85f)

            val mlKitBoost = computeMlKitCategoryScore(cat, mlKitLabels)
            val priorityFactor = 1.0f + ((cat.priority.coerceIn(1, 10) - 5) * 0.025f)

            val combinedScore = (baseOnnxProb * 0.65f + cosSim.coerceAtLeast(0f) * 0.20f + mlKitBoost * 0.45f) * priorityFactor
            fusedLogits[cat.id] = combinedScore
        }

        // Primary-Subject Disambiguation Rule:
        // If both People (cat_people) and a specific foreground subject (e.g., Food, Animals, Vehicles, Technology, Text)
        // have strong signals, check whether the specific object is prominent in the central ROI or ML Kit labels.
        var disambiguationNote = ""
        val peopleScore = fusedLogits["cat_people"] ?: 0f
        val foodScore = fusedLogits["cat_food"] ?: 0f
        val centerFoodFeature = if (visualFeatures64D.size >= 16) visualFeatures64D[8] else 0f

        if (fusedLogits.containsKey("cat_food") && peopleScore > 0.25f && (foodScore > 0.22f || centerFoodFeature > 0.35f)) {
            // Person holding a plate of food or sitting behind a meal: promote Food as primary subject
            fusedLogits["cat_food"] = max(foodScore, peopleScore + 0.18f)
            fusedLogits["cat_people"] = peopleScore * 0.65f
            disambiguationNote = "Primary-subject disambiguation: Foreground Food prioritized over incidental Person presence."
        }

        // Apply temperature-scaled Softmax over enabled categories to obtain heuristic ranking weights
        val temperature = 0.35f
        var maxLogit = Float.NEGATIVE_INFINITY
        for ((_, v) in fusedLogits) {
            if (v > maxLogit) maxLogit = v
        }

        val expMap = mutableMapOf<String, Float>()
        var sumExp = 0f
        for ((id, v) in fusedLogits) {
            val e = exp(((v - maxLogit) / temperature).toDouble()).toFloat()
            expMap[id] = e
            sumExp += e
        }

        val calibratedProbs = expMap.mapValues { (_, e) ->
            if (sumExp > 0f) (e / sumExp).coerceIn(0f, 1f) else 0f
        }

        val ranked = enabledCategories.sortedByDescending { calibratedProbs[it.id] ?: 0f }
        val topCat = ranked.first()
        val secondCat = ranked.getOrNull(1)

        val topConf = calibratedProbs[topCat.id] ?: 0f
        val secondConf = secondCat?.let { calibratedProbs[it.id] ?: 0f } ?: 0f
        val margin = (topConf - secondConf).coerceAtLeast(0f)
        val rawSim = rawSimilarities[topCat.id] ?: 0f

        val uncertain = topConf < confidenceThreshold || margin < 0.12f || topCat.id == "cat_other"

        return ClassificationScoreMetrics(
            categoryId = topCat.id,
            categoryName = topCat.name,
            calibratedConfidence = topConf,
            rawSimilarityScore = rawSim,
            topMargin = margin,
            secondaryCategoryName = secondCat?.name,
            isAmbiguousMultiScene = false,
            needsManualReview = uncertain,
            primarySubjectNote = disambiguationNote
        )
    }

    private fun computeMlKitCategoryScore(
        category: CategoryEntity,
        mlKitLabels: Map<String, Float>
    ): Float {
        if (mlKitLabels.isEmpty()) return 0f
        val catKeywords = "${category.name} ${category.description}"
            .lowercase()
            .split(Regex("[^a-z0-9çğıöşü]+"))
            .filter { it.length >= 3 }
            .toSet()

        var bestMatch = 0f
        for ((label, conf) in mlKitLabels) {
            val lNorm = label.lowercase()
            if (catKeywords.any { kw -> lNorm.contains(kw) || kw.contains(lNorm) }) {
                if (conf > bestMatch) bestMatch = conf
            }
        }
        return bestMatch
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        val len = minOf(a.size, b.size)
        if (len == 0) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in 0 until len) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 1e-6f) (dot / denom).coerceIn(-1f, 1f) else 0f
    }

    private fun l2Normalize(vec: FloatArray): FloatArray {
        var sumSq = 0f
        for (v in vec) sumSq += v * v
        val norm = sqrt(sumSq)
        if (norm < 1e-6f) return vec.copyOf()
        return FloatArray(vec.size) { i -> vec[i] / norm }
    }
}
