package com.aimediasorter.app.domain.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Memory-efficient image decoder, EXIF orientation normalizer, and spatial visual descriptor extractor.
 * Supports both:
 * 1. 64-D spatial-chromatic-edge descriptor for the bundled AIMediaSorter ONNX model (1x64 input)
 * 2. Full [1, 3, 224, 224] NCHW normalized RGB tensor for external MobileCLIP-S0 / Vision ONNX models
 */
class VisualFeatureExtractor(private val context: Context) {

    companion object {
        const val MODEL_INPUT_SIZE = 224
        const val FEATURE_DIM = 64

        // Standard CLIP RGB normalization constants
        private val CLIP_MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        private val CLIP_STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
    }

    /**
     * Decodes a subsampled Bitmap from a File or SAF Uri, applies EXIF rotation,
     * and scales to 224x224 for inference.
     */
    fun decodeAndNormalizeBitmap(
        uri: Uri,
        filePath: String?,
        orientationDegrees: Int,
        targetSize: Int = MODEL_INPUT_SIZE
    ): Bitmap? {
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        if (!filePath.isNullOrBlank() && File(filePath).exists()) {
            BitmapFactory.decodeFile(filePath, boundsOptions)
        } else {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            }
        }

        if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
            return null
        }

        val sampleSize = calculateInSampleSize(
            boundsOptions.outWidth,
            boundsOptions.outHeight,
            targetSize * 2,
            targetSize * 2
        )

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val rawBitmap = if (!filePath.isNullOrBlank() && File(filePath).exists()) {
            BitmapFactory.decodeFile(filePath, decodeOptions)
        } else {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            }
        } ?: return null

        val orientedBitmap = if (orientationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(orientationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(
                rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true
            )
            if (rotated !== rawBitmap) rawBitmap.recycle()
            rotated
        } else {
            rawBitmap
        }

        val resized = Bitmap.createScaledBitmap(orientedBitmap, targetSize, targetSize, true)
        if (resized !== orientedBitmap) {
            orientedBitmap.recycle()
        }
        return resized
    }

    /**
     * Prepares a [1, 3, H, W] NCHW FloatBuffer normalized with CLIP mean/std
     * for full MobileCLIP-S0 / MobileNetV3 ONNX models.
     */
    fun bitmapToNormalizedNchwBuffer(bitmap: Bitmap, size: Int = MODEL_INPUT_SIZE): FloatBuffer {
        val scaled = if (bitmap.width == size && bitmap.height == size) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, size, size, true)
        }

        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        if (scaled !== bitmap) scaled.recycle()

        val buffer = FloatBuffer.allocate(3 * size * size)
        val area = size * size
        for (i in 0 until area) {
            val px = pixels[i]
            val r = ((px shr 16) and 0xFF) / 255.0f
            val g = ((px shr 8) and 0xFF) / 255.0f
            val b = (px and 0xFF) / 255.0f

            buffer.put(i, (r - CLIP_MEAN[0]) / CLIP_STD[0])
            buffer.put(area + i, (g - CLIP_MEAN[1]) / CLIP_STD[1])
            buffer.put(2 * area + i, (b - CLIP_MEAN[2]) / CLIP_STD[2])
        }
        buffer.rewind()
        return buffer
    }

    /**
     * Extracts the 64-dimensional visual feature vector grouped into 8 semantic blocks (8 dims each),
     * separating central 50% foreground ROI from peripheral border to enable primary-subject disambiguation.
     */
    fun extract64DVisualDescriptor(bitmap: Bitmap): FloatArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val features = FloatArray(FEATURE_DIM)
        val hsv = FloatArray(3)

        val roiX0 = w / 4
        val roiX1 = (w * 3) / 4
        val roiY0 = h / 4
        val roiY1 = (h * 3) / 4

        var skinCenterCount = 0f
        var skinPeripheryCount = 0f
        var warmFoodCenterCount = 0f
        var warmFoodPeripheryCount = 0f
        var metallicCenterCount = 0f
        var darkWheelBottomCount = 0f
        var skyTopCount = 0f
        var foliageBottomCount = 0f
        var whiteDocCount = 0f
        var darkBezelCount = 0f
        var brightScreenCenterCount = 0f

        var horizEdgeSum = 0f
        var vertEdgeSum = 0f
        var diagEdgeSum = 0f
        var highFreqStrokeCount = 0f
        var centerSatSum = 0f
        var peripherySatSum = 0f
        var centerValSum = 0f

        var centerCount = 0f
        var peripheryCount = 0f
        val totalPixels = (w * h).toFloat().coerceAtLeast(1f)

        for (y in 1 until h - 1) {
            val isTopThird = y < h / 3
            val isBottomThird = y > (h * 2) / 3
            for (x in 1 until w - 1) {
                val idx = y * w + x
                val px = pixels[idx]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF

                Color.RGBToHSV(r, g, b, hsv)
                val hue = hsv[0] // 0..360
                val sat = hsv[1] // 0..1
                val v = hsv[2]   // 0..1

                val inCenter = (x in roiX0..roiX1) && (y in roiY0..roiY1)
                if (inCenter) {
                    centerCount += 1f
                    centerSatSum += sat
                    centerValSum += v
                } else {
                    peripheryCount += 1f
                    peripherySatSum += sat
                }

                // 1. Skin-tone / portrait detection (RGB + HSV rule)
                val isSkin = (r > 95 && g > 40 && b > 20 && (r - g) > 15 && r > b && hue in 6f..42f && sat in 0.15f..0.68f)
                if (isSkin) {
                    if (inCenter) skinCenterCount += 1f else skinPeripheryCount += 1f
                }

                // 2. Warm rich food chroma (orange/red/yellow/brown savory & dessert tones in high saturation)
                val isWarmFood = (hue in 0f..55f || hue in 335f..360f) && sat > 0.42f && v in 0.25f..0.95f && !isSkin
                if (isWarmFood) {
                    if (inCenter) warmFoodCenterCount += 1f else warmFoodPeripheryCount += 1f
                }

                // 3. Metallic / automotive low-saturation sleek surfaces & lower dark tires
                if (sat < 0.22f && v in 0.25f..0.82f && inCenter) {
                    metallicCenterCount += 1f
                }
                if (isBottomThird && v < 0.18f && (x in (w / 5)..((w * 4) / 5))) {
                    darkWheelBottomCount += 1f
                }

                // 4. Nature: top sky blue + bottom foliage green
                if (isTopThird && hue in 185f..245f && sat > 0.22f && v > 0.45f) {
                    skyTopCount += 1f
                }
                if (!isTopThird && hue in 65f..165f && sat > 0.20f && v in 0.18f..0.88f) {
                    foliageBottomCount += 1f
                }

                // 5. Document / Screenshot white or light uniform background
                if (sat < 0.08f && v > 0.86f) {
                    whiteDocCount += 1f
                }

                // 6. Technology: dark bezel periphery + glowing screen center
                if (!inCenter && v < 0.15f && sat < 0.20f) {
                    darkBezelCount += 1f
                }
                if (inCenter && v > 0.65f && (hue in 180f..260f || sat < 0.15f)) {
                    brightScreenCenterCount += 1f
                }

                // Sobel gradients for edge & texture analysis
                val lumLeft = luminance(pixels[idx - 1])
                val lumRight = luminance(pixels[idx + 1])
                val lumUp = luminance(pixels[idx - w])
                val lumDown = luminance(pixels[idx + w])

                val gx = abs(lumRight - lumLeft)
                val gy = abs(lumDown - lumUp)

                horizEdgeSum += gy
                vertEdgeSum += gx
                if (gx > 0.15f && gy > 0.15f) {
                    diagEdgeSum += sqrt(gx * gx + gy * gy)
                }
                if ((gx + gy) > 0.55f) {
                    highFreqStrokeCount += 1f
                }
            }
        }

        val cNorm = max(centerCount, 1f)
        val pNorm = max(peripheryCount, 1f)

        val skinCenterRatio = (skinCenterCount / cNorm).coerceIn(0f, 1f)
        val skinPeripheryRatio = (skinPeripheryCount / pNorm).coerceIn(0f, 1f)
        val foodCenterRatio = (warmFoodCenterCount / cNorm).coerceIn(0f, 1f)
        val foodPeripheryRatio = (warmFoodPeripheryCount / pNorm).coerceIn(0f, 1f)
        val metallicRatio = (metallicCenterCount / cNorm).coerceIn(0f, 1f)
        val wheelRatio = (darkWheelBottomCount / (totalPixels / 3f)).coerceIn(0f, 1f)
        val skyRatio = (skyTopCount / (totalPixels / 3f)).coerceIn(0f, 1f)
        val foliageRatio = (foliageBottomCount / (totalPixels * 2f / 3f)).coerceIn(0f, 1f)
        val whiteDocRatio = (whiteDocCount / totalPixels).coerceIn(0f, 1f)
        val bezelRatio = (darkBezelCount / pNorm).coerceIn(0f, 1f)
        val screenCenterRatio = (brightScreenCenterCount / cNorm).coerceIn(0f, 1f)

        val avgHorizEdge = (horizEdgeSum / totalPixels * 3.0f).coerceIn(0f, 1f)
        val avgVertEdge = (vertEdgeSum / totalPixels * 3.0f).coerceIn(0f, 1f)
        val avgDiagEdge = (diagEdgeSum / totalPixels * 2.5f).coerceIn(0f, 1f)
        val strokeDensity = (highFreqStrokeCount / totalPixels * 4.0f).coerceIn(0f, 1f)
        val centerSat = (centerSatSum / cNorm).coerceIn(0f, 1f)

        // Populate Block 0 (0..7): People
        val peopleScore = (skinCenterRatio * 2.4f + skinPeripheryRatio * 0.8f).coerceIn(0f, 1f)
        for (i in 0..7) features[i] = peopleScore

        // Populate Block 1 (8..15): Food (boosted when center has warm food chroma + circular contrast)
        val foodScore = (foodCenterRatio * 2.8f + centerSat * 0.35f - foodPeripheryRatio * 0.2f).coerceIn(0f, 1f)
        for (i in 8..15) features[i] = foodScore

        // Populate Block 2 (16..23): Vehicles (metallic + horizontal edges + lower wheel contrast)
        val vehicleScore = (metallicRatio * 1.2f + avgHorizEdge * 0.9f + wheelRatio * 1.6f).coerceIn(0f, 1f)
        for (i in 16..23) features[i] = vehicleScore

        // Populate Block 3 (24..31): Animals (diagonal fur micro-texture + organic foreground contrast)
        val animalScore = (avgDiagEdge * 1.8f + (centerSat - (peripherySatSum / pNorm)).coerceAtLeast(0f) * 1.4f).coerceIn(0f, 1f)
        for (i in 24..31) features[i] = animalScore

        // Populate Block 4 (32..39): Nature (sky top + foliage bottom)
        val natureScore = (skyRatio * 1.5f + foliageRatio * 1.6f).coerceIn(0f, 1f)
        for (i in 32..39) features[i] = natureScore

        // Populate Block 5 (40..47): Buildings (strong orthogonal vertical + horizontal grid)
        val orthoGrid = min(avgHorizEdge, avgVertEdge) * 2.2f
        val buildingScore = (orthoGrid + avgVertEdge * 0.7f).coerceIn(0f, 1f)
        for (i in 40..47) features[i] = buildingScore

        // Populate Block 6 (48..55): Technology (dark outer bezel + bright inner display + rectangular edges)
        val techScore = (bezelRatio * 1.5f + screenCenterRatio * 1.2f).coerceIn(0f, 1f)
        for (i in 48..55) features[i] = techScore

        // Populate Block 7 (56..63): Text (high white background ratio + high-frequency stroke density)
        val textScore = if (whiteDocRatio > 0.42f) {
            (whiteDocRatio * 1.1f + strokeDensity * 1.8f).coerceIn(0f, 1f)
        } else {
            (strokeDensity * 0.5f).coerceIn(0f, 1f)
        }
        for (i in 56..63) features[i] = textScore

        return features
    }

    private fun luminance(argb: Int): Float {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (0.299f * r + 0.587f * g + 0.114f * b) / 255.0f
    }

    private fun calculateInSampleSize(
        rawWidth: Int,
        rawHeight: Int,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        var inSampleSize = 1
        if (rawHeight > reqHeight || rawWidth > reqWidth) {
            val halfHeight = rawHeight / 2
            val halfWidth = rawWidth / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
