package com.aimediasorter.app.domain.ai

import com.aimediasorter.app.data.local.CategoryEntity
import com.aimediasorter.app.data.model.ClassificationScoreMetrics
import java.text.Normalizer
import java.util.Locale

/** Maps actual ML Kit labels to categories; this is not an image/text embedding model. */
object LabelCategoryMapper {
    private val aliases = mapOf(
        "cat_people" to setOf("person", "people", "human", "face", "portrait", "selfie", "social group"),
        "cat_food" to setOf("food", "dish", "meal", "cuisine", "dessert", "drink", "beverage", "coffee", "fruit", "vegetable", "bread", "cake"),
        "cat_vehicles" to setOf("vehicle", "car", "motorcycle", "truck", "bus", "train", "bicycle", "boat", "aircraft", "automotive", "transport"),
        "cat_animals" to setOf("animal", "cat", "dog", "bird", "pet", "wildlife", "insect", "fish", "mammal", "reptile", "horse", "cattle"),
        "cat_nature" to setOf("nature", "landscape", "forest", "mountain", "beach", "sea", "ocean", "tree", "flower", "plant", "sky", "waterfall"),
        "cat_buildings" to setOf("building", "architecture", "house", "skyscraper", "city", "street", "interior", "facade"),
        "cat_technology" to setOf("technology", "phone", "computer", "electronics", "laptop", "screen", "keyboard", "electronic device", "monitor", "camera"),
        "cat_text" to setOf("text", "document", "screenshot", "handwriting", "font", "book", "paper", "writing")
    )
    private val translations = mapOf(
        "insan" to "person", "insanlar" to "person", "portre" to "portrait", "yuz" to "face",
        "yemek" to "food", "tatli" to "dessert", "icecek" to "drink", "kahve" to "coffee",
        "arac" to "vehicle", "araba" to "car", "arabalar" to "car", "otomobil" to "car", "motosiklet" to "motorcycle",
        "hayvan" to "animal", "hayvanlar" to "animal", "kedi" to "cat", "kediler" to "cat", "kopek" to "dog", "kus" to "bird",
        "doga" to "nature", "manzara" to "landscape", "orman" to "forest", "deniz" to "sea", "agac" to "tree",
        "bina" to "building", "mimari" to "architecture", "sehir" to "city", "ev" to "house",
        "teknoloji" to "technology", "bilgisayar" to "computer", "telefon" to "phone", "elektronik" to "electronics",
        "metin" to "text", "belge" to "document", "kitap" to "book", "ekran" to "screen"
    )
    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT).replace('ı', 'i'), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").trim()
    private fun words(value: String): Set<String> = normalize(value).split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 3 }.flatMap {
            listOf(it, translations[it] ?: it, if (it.endsWith("s")) it.dropLast(1) else it)
        }.toSet()

    fun classify(labels: Map<String, Float>, categories: List<CategoryEntity>, threshold: Float): ClassificationScoreMetrics {
        val valid = labels.filterValues { it.isFinite() && it in 0f..1f }
        val scored = categories.filter { it.isEnabled && it.id != "cat_other" }.map { category ->
            val keywords = if (category.isDefault) aliases[category.id].orEmpty().flatMap { words(it) }.toSet()
                else words(category.name + " " + category.description)
            val score = valid.filterKeys { label -> words(label).any { it in keywords } }.values.maxOrNull() ?: 0f
            category to score
        }.sortedWith(compareByDescending<Pair<CategoryEntity, Float>> { it.second }.thenByDescending { it.first.priority })
        val first = scored.firstOrNull()
        if (first == null || first.second <= 0f) {
            return ClassificationScoreMetrics(
                "cat_other", categories.firstOrNull { it.id == "cat_other" }?.name ?: "Other",
                0f, 0f, 0f, needsManualReview = true,
                primarySubjectNote = "ML Kit returned no label matching an enabled category. Unknown content requires review.",
                modelLabels = valid, inferenceEngine = "ML_KIT_IMAGE_LABELING"
            )
        }
        val second = scored.getOrNull(1)
        val margin = first.second - (second?.second ?: 0f)
        val ambiguous = (second?.second ?: 0f) >= threshold && margin < 0.12f
        val evidence = valid.entries.sortedByDescending { it.value }.take(5).joinToString {
            it.key + "=" + "%.3f".format(Locale.US, it.value)
        }
        return ClassificationScoreMetrics(
            categoryId = first.first.id, categoryName = first.first.name,
            calibratedConfidence = first.second, rawSimilarityScore = 0f, topMargin = margin,
            secondaryCategoryName = second?.takeIf { it.second > 0 }?.first?.name,
            needsManualReview = first.second < threshold || margin < 0.12f || ambiguous,
            primarySubjectNote = "On-device ML Kit label evidence: " + evidence +
                if (ambiguous) ". Multiple subjects/categories require review." else ". Scores are not measured accuracy; custom descriptions match label keywords.",
            modelLabels = valid, inferenceEngine = "ML_KIT_IMAGE_LABELING"
        )
    }
}
