package com.aimediasorter.app.domain.ai

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.aimediasorter.app.data.local.CategoryEntity
import com.aimediasorter.app.data.model.AiEngineMode
import com.aimediasorter.app.data.model.ClassificationScoreMetrics
import com.aimediasorter.app.data.model.FrameClassificationSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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
        val distinctFrames = hashSetOf<String>()

        try {
            if (!filePath.isNullOrBlank() && File(filePath).exists()) {
                retriever.setDataSource(filePath)
            } else {
                retriever.setDataSource(context, uri)
            }

            val resolvedDurationMs = if (durationMs > 0L) {
                durationMs
            } else {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            }
            require(resolvedDurationMs > 0) { "Video has no valid duration metadata." }

            val percents = selectAdaptiveSamplePercents(resolvedDurationMs)

            for (pct in percents) {
                currentCoroutineContext().ensureActive()
                val targetTimeMs = (resolvedDurationMs * pct) / 100L
                val targetTimeUs = targetTimeMs * 1000L

                val frameBitmap = runCatching {
                    retriever.getScaledFrameAtTime(
                        targetTimeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        DECODE_FRAME_DIMENSION,
                        DECODE_FRAME_DIMENSION
                    )
                }.getOrNull()

                if (frameBitmap != null) {
                    try {
                        val pixels = IntArray(frameBitmap.width * frameBitmap.height)
                        frameBitmap.getPixels(pixels, 0, frameBitmap.width, 0, 0, frameBitmap.width, frameBitmap.height)
                        val packed = java.nio.ByteBuffer.allocate(pixels.size * 4).apply { asIntBuffer().put(pixels) }
                        val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(packed.array())
                            .joinToString("") { "%02x".format(it) }
                        if (!distinctFrames.add(fingerprint)) continue
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            return@withContext ClassificationScoreMetrics(
                categoryId = "cat_other",
                categoryName = "Other",
                calibratedConfidence = 0f,
                rawSimilarityScore = 0f,
                topMargin = 0f,
                needsManualReview = true,
                primarySubjectNote = "Video decoding or inference failed: ${e.message}"
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
        return VideoPredictionAggregator.aggregate(frameMetrics, frameSamples, enabledCategories, confidenceThreshold)
    }
}
