package com.aimediasorter.app.domain.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.aimediasorter.app.data.local.CategoryEntity
import com.aimediasorter.app.data.model.AiEngineMode
import com.aimediasorter.app.data.model.ClassificationScoreMetrics
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer

/**
 * Automatic categorization uses bundled, on-device ML Kit inference.
 * The handcrafted ONNX graph is diagnostic only and never auto-approved.
 */
class OnDeviceAiClassifier(
    private val context: Context,
    private val featureExtractor: VisualFeatureExtractor
) {
    companion object {
        const val BUNDLED_ONNX_ASSET_PATH = "models/media_visual_embedder.onnx"
        const val BUNDLED_VOCAB_ASSET_PATH = "models/text_token_embeddings.json"
    }

    private var labeler: ImageLabeler? = null
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var activeModelPathKey: String = ""
    private var activeHwAccel: Boolean = false
    private var disambiguator: PrimarySubjectDisambiguator? = null

    @Synchronized
    fun ensureInitialized(customModelPath: String = "", enableHardwareAccel: Boolean = true) {
        if (disambiguator == null) {
            val vocabJson = context.assets.open(BUNDLED_VOCAB_ASSET_PATH).bufferedReader().use { it.readText() }
            disambiguator = PrimarySubjectDisambiguator(vocabJson)
        }

        require(customModelPath.isBlank()) { "Custom MobileCLIP import requires matching image/text encoders and is not supported." }
        val targetKey = BUNDLED_ONNX_ASSET_PATH
        if (ortSession != null && activeModelPathKey == targetKey && activeHwAccel == enableHardwareAccel) {
            return
        }

        runCatching { ortSession?.close() }
        val env = ortEnv ?: OrtEnvironment.getEnvironment().also { ortEnv = it }

        val sessionOptions = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            if (enableHardwareAccel) {
                runCatching { addNnapi() }
            }
        }

        val modelBytes = context.assets.open(BUNDLED_ONNX_ASSET_PATH).use { it.readBytes() }
        ortSession = sessionOptions.use { env.createSession(modelBytes, it) }
        activeModelPathKey = targetKey
        activeHwAccel = enableHardwareAccel
    }

    suspend fun classifyImage(
        uri: Uri,
        filePath: String?,
        orientationDegrees: Int,
        enabledCategories: List<CategoryEntity>,
        aiEngineMode: AiEngineMode,
        customOnnxPath: String,
        enableHardwareAccel: Boolean,
        confidenceThreshold: Float,
        isMirrored: Boolean = false
    ): ClassificationScoreMetrics = withContext(Dispatchers.Default) {
        val bitmap = featureExtractor.decodeAndNormalizeBitmap(
            uri = uri,
            filePath = filePath,
            orientationDegrees = orientationDegrees,
            targetSize = if (aiEngineMode == AiEngineMode.HYBRID_ONNX_AND_MLKIT) 512 else 224,
            isMirrored = isMirrored
        ) ?: return@withContext ClassificationScoreMetrics(
            categoryId = "cat_other",
            categoryName = "Other",
            calibratedConfidence = 0f,
            rawSimilarityScore = 0f,
            topMargin = 0f,
            needsManualReview = true,
            primarySubjectNote = "Corrupted or undecodable image stream."
        )

        try {
            classifyBitmap(
                bitmap = bitmap,
                enabledCategories = enabledCategories,
                aiEngineMode = aiEngineMode,
                customOnnxPath = customOnnxPath,
                enableHardwareAccel = enableHardwareAccel,
                confidenceThreshold = confidenceThreshold
            )
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    suspend fun classifyBitmap(
        bitmap: Bitmap,
        enabledCategories: List<CategoryEntity>,
        aiEngineMode: AiEngineMode,
        customOnnxPath: String,
        enableHardwareAccel: Boolean,
        confidenceThreshold: Float
    ): ClassificationScoreMetrics = withContext(Dispatchers.Default) {
        if (aiEngineMode == AiEngineMode.HYBRID_ONNX_AND_MLKIT) {
            return@withContext LabelCategoryMapper.classify(
                runMlKitOnDeviceLabeler(bitmap), enabledCategories, confidenceThreshold
            )
        }
        require(aiEngineMode == AiEngineMode.ONNX_EMBEDDER_ONLY) {
            "Custom MobileCLIP import is unavailable. Select the on-device ML Kit engine."
        }
        ensureInitialized(
            customModelPath = if (aiEngineMode == AiEngineMode.CUSTOM_MOBILECLIP_ONNX) customOnnxPath else "",
            enableHardwareAccel = enableHardwareAccel
        )

        val visualFeatures64D = featureExtractor.extract64DVisualDescriptor(bitmap)
        val env = ortEnv!!
        val session = ortSession!!

        val inputName = session.inputNames.single()
        val info = session.inputInfo[inputName]?.info as? ai.onnxruntime.TensorInfo
            ?: error("ONNX input is not a tensor.")
        require(info.shape.contentEquals(longArrayOf(1, 64))) { "Unexpected diagnostic ONNX input shape." }
        val outputs = OnnxTensor.createTensor(env, FloatBuffer.wrap(visualFeatures64D), longArrayOf(1, 64)).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                require(result.size() == 2) { "Diagnostic ONNX output schema mismatch." }
                val probabilities = (result[0].value as? Array<*>)?.singleOrNull() as? FloatArray
                    ?: error("ONNX probabilities unavailable.")
                val embedding = (result[1].value as? Array<*>)?.singleOrNull() as? FloatArray
                    ?: error("ONNX embedding unavailable.")
                require(probabilities.size == 9 && embedding.size == 32 &&
                    probabilities.all { it.isFinite() && it in 0f..1f } && embedding.all { it.isFinite() }) {
                    "Invalid diagnostic ONNX outputs."
                }
                probabilities to embedding
            }
        }

        disambiguator!!.evaluateAndDisambiguate(
            enabledCategories = enabledCategories,
            onnxCategoryProbs = outputs.first,
            imageEmbedding32D = outputs.second,
            visualFeatures64D = visualFeatures64D,
            mlKitLabels = emptyMap(),
            confidenceThreshold = confidenceThreshold
        ).copy(needsManualReview = true,
            primarySubjectNote = "Diagnostic handcrafted ONNX projection; not trained semantic recognition. Manual review is mandatory.",
            inferenceEngine = "ONNX_DIAGNOSTIC")
    }

    @Synchronized
    private fun imageLabeler(): ImageLabeler {
        return labeler ?: ImageLabeling.getClient(
            ImageLabelerOptions.Builder().setConfidenceThreshold(0.0f).build()
        ).also { labeler = it }
    }

    private suspend fun runMlKitOnDeviceLabeler(bitmap: Bitmap): Map<String, Float> {
        val task = imageLabeler().process(InputImage.fromBitmap(bitmap, 0))
        val labels = try { task.await() } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // The SDK task may keep reading the bitmap after coroutine cancellation.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { runCatching { task.await() } }
            throw cancelled
        }
        return labels.associate { it.text to it.confidence }
    }

    @Synchronized
    fun close() {
        labeler?.close()
        labeler = null
        runCatching { ortSession?.close() }
        ortSession = null
        ortEnv = null // OrtEnvironment is process-shared; do not close it while other clients may use it.
    }
}
