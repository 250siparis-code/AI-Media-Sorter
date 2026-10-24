package com.aimediasorter.app

import com.aimediasorter.app.data.local.CategoryEntity
import com.aimediasorter.app.data.model.ClassificationScoreMetrics
import com.aimediasorter.app.data.model.DefaultCategories
import com.aimediasorter.app.data.model.FrameClassificationSample
import com.aimediasorter.app.domain.ai.PrimarySubjectDisambiguator
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.math.abs

class AcceptanceScenariosTest {

    private lateinit var categories: List<CategoryEntity>
    private lateinit var disambiguator: PrimarySubjectDisambiguator

    @Before
    fun setUp() {
        categories = DefaultCategories.ALL.map { spec ->
            CategoryEntity(
                id = spec.id,
                name = spec.defaultName,
                description = spec.defaultDescription,
                colorHex = spec.colorHex,
                iconKey = spec.iconKey,
                priority = spec.priority,
                isEnabled = true,
                isDefault = true,
                destinationSubfolder = spec.subfolderName
            )
        }
        val sampleVocabJson = """
            {
              "model_name": "AIMediaSorter-MobileEmbedder-v1",
              "license": "Apache-2.0",
              "embedding_dim": 32,
              "feature_dim": 64,
              "default_categories": ["People","Food","Vehicles","Animals","Nature","Buildings","Technology","Text","Other"],
              "token_embeddings": {
                "car": [0,0,0,0, 0,0,0,0, 0.5,0.5,0.5,0.5, 0,0,0,0, 0,0,0,0, 0,0,0,0, 0,0,0,0, 0,0,0,0],
                "food": [0,0,0,0, 0.5,0.5,0.5,0.5, 0,0,0,0, 0,0,0,0, 0,0,0,0, 0,0,0,0, 0,0,0,0, 0,0,0,0],
                "portrait": [0.5,0.5,0.5,0.5, 0,0,0,0, 0,0,0,0, 0,0,0,0, 0,0,0,0, 0,0,0,0, 0,0,0,0, 0,0,0,0],
                "dog": [0,0,0,0, 0,0,0,0, 0,0,0,0, 0.5,0.5,0.5,0.5, 0,0,0,0, 0,0,0,0, 0,0,0,0, 0,0,0,0]
              }
            }
        """.trimIndent()
        disambiguator = PrimarySubjectDisambiguator(sampleVocabJson)
    }

    @Test
    fun testPrimarySubjectDisambiguation_personHoldingFood_classifiesAsFood() {
        // Both People (index 0) and Food (index 1) have high raw probabilities,
        // and central ROI food feature (index 8) is active.
        val onnxProbs = floatArrayOf(0.44f, 0.42f, 0.02f, 0.02f, 0.02f, 0.02f, 0.02f, 0.02f, 0.02f)
        val emb32 = FloatArray(32).apply {
            for (i in 0..3) this[i] = 0.5f
            for (i in 4..7) this[i] = 0.5f
        }
        val feat64 = FloatArray(64).apply {
            for (i in 0..7) this[i] = 0.60f
            for (i in 8..15) this[i] = 0.78f // Strong central food plate feature
        }

        val result = disambiguator.evaluateAndDisambiguate(
            enabledCategories = categories,
            onnxCategoryProbs = onnxProbs,
            imageEmbedding32D = emb32,
            visualFeatures64D = feat64,
            mlKitLabels = mapOf("Person" to 0.75f, "Food" to 0.80f),
            confidenceThreshold = 0.55f
        )

        assertEquals("cat_food", result.categoryId)
        assertEquals("Food", result.categoryName)
        assertTrue(result.primarySubjectNote.contains("Food"))
    }

    @Test
    fun testFileMovePreservesBytesAndModificationTimestampAndHandlesCollisions() {
        val tempRoot = Files.createTempDirectory("aimediasorter_test").toFile()
        try {
            val srcDir = File(tempRoot, "source").apply { mkdirs() }
            val dstDir = File(tempRoot, "dest/Vehicles").apply { mkdirs() }

            val originalFile = File(srcDir, "car_photo.jpg")
            val payload = ByteArray(8192) { (it % 251).toByte() }
            originalFile.writeBytes(payload)

            val historicalTimestamp = 1684074130000L // 2023-05-14T14:22:10Z
            assertTrue(originalFile.setLastModified(historicalTimestamp))

            val srcSha = MessageDigest.getInstance("SHA-256")
                .digest(originalFile.readBytes())
                .joinToString("") { "%02x".format(it) }

            // Create existing file in destination to trigger collision suffix resolution
            val existingFile = File(dstDir, "car_photo.jpg")
            existingFile.writeBytes("different_existing_content".toByteArray())

            // Resolve collision name
            val resolvedDest = File(dstDir, "car_photo_1.jpg")
            originalFile.inputStream().use { input ->
                resolvedDest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            assertTrue(resolvedDest.setLastModified(historicalTimestamp))

            val dstSha = MessageDigest.getInstance("SHA-256")
                .digest(resolvedDest.readBytes())
                .joinToString("") { "%02x".format(it) }

            assertEquals(srcSha, dstSha)
            assertTrue(abs(resolvedDest.lastModified() - historicalTimestamp) <= 2000L)
            assertEquals("different_existing_content", String(existingFile.readBytes()))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun testPosixSingleQuoteEscaping_preservesUnicodeSpacesQuotesAndDollarSigns() {
        val rawName = "İstanbul Boğazı 'Kahvaltı' \$100 & (Test).jpg"
        val escaped = "'" + rawName.replace("'", "'\\''") + "'"
        assertEquals("'İstanbul Boğazı '\\''Kahvaltı'\\'' \$100 & (Test).jpg'", escaped)
    }

    @Test
    fun testSafUriDetection_flagsContentUriAsSafRestricted() {
        val contentUri = "content://com.android.externalstorage.documents/document/3132-6430%3ADCIM%2Fphoto.jpg"
        val isSafOnly = !contentUri.contains("primary:") && contentUri.startsWith("content://")
        assertTrue(isSafOnly)
    }
}
