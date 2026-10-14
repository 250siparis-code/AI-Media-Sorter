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
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer

/**
 * Real On-Device AI Image Classifier powered by:
 * 1. ONNX Runtime Mobile (ai.onnxruntime) executing bundled assets/models/media_visual_embedder.onnx
 *    or an imported external MobileCLIP-S0 .onnx model on CPU with optional NNAPI acceleration.
 * 2. Bundled On-Device ML Kit Image Labeling (offline MobileNet visual classifier) in Hybrid mode.
 * 3. PrimarySubjectDisambiguator for central-ROI foreground subject priority and custom text embeddings.
 */
class OnDeviceAiClassifier(
    private val context: Context,
    private val featureExtractor: VisualFeatureExtractor
) {
    companion object {
        const val BUNDLED_ONNX_ASSET_PATH = "models/media_visual_embedder.onnx"
        const val BUNDLED_VOCAB_ASSET_PATH = "models/text_token_embeddings.json"
    }

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

        val targetKey = customModelPath.ifBlank { BUNDLED_ONNX_ASSET_PATH }
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

        val modelBytes = if (customModelPath.isNotBlank() && File(customModelPath).exists()) {
            File(customModelPath).readBytes()
        } else {
            context.assets.open(BUNDLED_ONNX_ASSET_PATH).use { it.readBytes() }
        }

        ortSession = env.createSession(modelBytes, sessionOptions)
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
        confidenceThreshold: Float
    ): ClassificationScoreMetrics = withContext(Dispatchers.Default) {
        val bitmap = featureExtractor.decodeAndNormalizeBitmap(
            uri = uri,
            filePath = filePath,
            orientationDegrees = orientationDegrees
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
        ensureInitialized(
            customModelPath = if (aiEngineMode == AiEngineMode.CUSTOM_MOBILECLIP_ONNX) customOnnxPath else "",
            enableHardwareAccel = enableHardwareAccel
        )

        val visualFeatures64D = featureExtractor.extract64DVisualDescriptor(bitmap)
        val env = ortEnv!!
        val session = ortSession!!

        val inputName = session.inputNames.first()
        var onnxProbs = FloatArray(9) { 1f / 9f }
        var imageEmb32D = FloatArray(32)

        // Inspect whether the loaded ONNX session expects [1, 64] feature input or [1, 3, 224, 224] raw image tensor
        val inputTensor = if (aiEngineMode == AiEngineMode.CUSTOM_MOBILECLIP_ONNX && customOnnxPath.isNotBlank()) {
            val nchwBuffer = featureExtractor.bitmapToNormalizedNchwBuffer(bitmap, 224)
            OnnxTensor.createTensor(env, nchwBuffer, longArrayOf(1, 3, 224, 224))
        } else {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(visualFeatures64D), longArrayOf(1, 64))
        }

        inputTensor.use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                if (result.size() >= 1) {
                    val out0 = (result[0].value as? Array<*>)?.firstOrNull() as? FloatArray
                    if (out0 != null) {
                        if (out0.size == 9) onnxProbs = out0
                        else if (out0.size >= 32) imageEmb32D = out0.copyOfRange(0, 32)
                    }
                }
                if (result.size() >= 2) {
                    val out1 = (result[1].value as? Array<*>)?.firstOrNull() as? FloatArray
                    if (out1 != null && out1.size >= 32) {
                        imageEmb32D = out1.copyOfRange(0, 32)
                    }
                }
            }
        }

        val mlKitLabels = if (aiEngineMode == AiEngineMode.HYBRID_ONNX_AND_MLKIT) {
            runMlKitOnDeviceLabeler(bitmap)
        } else {
            emptyMap()
        }

        disambiguator!!.evaluateAndDisambiguate(
            enabledCategories = enabledCategories,
            onnxCategoryProbs = onnxProbs,
            imageEmbedding32D = imageEmb32D,
            visualFeatures64D = visualFeatures64D,
            mlKitLabels = mlKitLabels,
            confidenceThreshold = confidenceThreshold
        )
    }

    private suspend fun runMlKitOnDeviceLabeler(bitmap: Bitmap): Map<String, Float> {
        return runCatching {
            val options = ImageLabelerOptions.Builder()
                .setConfidenceThreshold(0.35f)
                .build()
            val labeler = ImageLabeling.getClient(options)
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val labels = labeler.process(inputImage).await()
            labels.associate { it.text to it.confidence }
        }.getOrDefault(emptyMap())
    }

    fun close() {
        runCatching { ortSession?.close() }
        runCatching { ortEnv?.close() }
    }
}
