package com.gateshot.processing.sr

import android.graphics.Bitmap
import android.graphics.Color
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Multi-camera fusion: main camera (1/1.28") + telephoto (1/1.56") for low-light.
 *
 * PROBLEM:
 * The 200MP telephoto has 0.5µm pixels — very noisy in shadows, dusk, storms,
 * or under forest canopy. Multi-frame denoising helps but requires the subject
 * to be still (bad for racing). Single-frame capture in low light = noisy mess.
 *
 * SOLUTION:
 * The main camera's 1/1.28" sensor is physically larger (30% more area than
 * the telephoto's 1/1.56"). At 50MP, its pixel pitch is ~1.2µm — over 2× the
 * area per pixel, capturing ~2.4× more photons. In low light, the main camera
 * produces a cleaner signal at the cost of a wider field of view (23mm vs 70mm).
 *
 * We capture both cameras simultaneously and use the main camera's clean
 * luminance channel to guide denoising of the telephoto crop:
 *
 * 1. CAPTURE: Take simultaneous frames from both cameras
 * 2. REGISTER: Crop the main camera's 23mm frame to match the telephoto's
 *    70mm field of view (a 3× crop = center ~33% of the main image)
 * 3. UPSCALE: Upscale the main crop to match telephoto resolution
 * 4. FUSE: Use the main camera's clean luminance as a denoising guide —
 *    preserve telephoto detail where it's sharp, blend in main camera
 *    signal where telephoto is too noisy
 *
 * This is similar to Google's Super Res Zoom (Pixel) and Samsung's Adaptive
 * Pixel — both use the wide camera to assist telephoto denoising.
 */
@Singleton
class LowLightFusion @Inject constructor() {

    data class FusionInput(
        val telephotoPixels: IntArray,
        val telephotoWidth: Int,
        val telephotoHeight: Int,
        val mainPixels: IntArray,
        val mainWidth: Int,
        val mainHeight: Int,
        val telephotoFocalMm: Float = 70f,
        val mainFocalMm: Float = 23f
    ) {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = telephotoPixels.contentHashCode()
    }

    data class FusionResult(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        val fusionStrength: Float,  // 0-1: how much the main camera contributed
        val snrImprovement: Float   // Estimated SNR improvement in dB
    ) {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = pixels.contentHashCode()
    }

    /**
     * Detect if conditions warrant multi-camera fusion.
     *
     * Fusion adds latency (~50ms) and complexity, so we only activate it when
     * the telephoto signal is genuinely noisy:
     * - Low average luminance (< 80/255 = deep shadow or dusk)
     * - High noise estimate (variance in flat regions)
     */
    fun shouldActivateFusion(telephotoPixels: IntArray, width: Int, height: Int): Boolean {
        if (telephotoPixels.isEmpty()) return false

        // Sample luminance at regular intervals
        val step = max(1, telephotoPixels.size / 1000)
        var lumSum = 0L
        var count = 0

        for (i in telephotoPixels.indices step step) {
            val pixel = telephotoPixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            lumSum += (0.299 * r + 0.587 * g + 0.114 * b).toLong()
            count++
        }

        val avgLuminance = if (count > 0) lumSum.toFloat() / count else 128f

        // Also estimate noise by looking at variance in a flat region (center)
        val noiseEstimate = estimateNoise(telephotoPixels, width, height)

        // Activate if scene is dark enough or noise is high enough
        return avgLuminance < 80f || noiseEstimate > 15f
    }

    /**
     * Fuse main camera and telephoto frames for improved low-light quality.
     */
    fun fuse(input: FusionInput): FusionResult {
        // Step 1: Crop the main camera to match telephoto field of view
        val cropRatio = input.mainFocalMm / input.telephotoFocalMm  // ~0.33 (23/70)
        val cropWidth = (input.mainWidth * cropRatio).toInt()
        val cropHeight = (input.mainHeight * cropRatio).toInt()
        val cropX = (input.mainWidth - cropWidth) / 2
        val cropY = (input.mainHeight - cropHeight) / 2

        val croppedMain = cropImage(
            input.mainPixels, input.mainWidth, input.mainHeight,
            cropX, cropY, cropWidth, cropHeight
        )

        // Step 2: Upscale cropped main to match telephoto resolution
        val scaledMain = bilinearScale(
            croppedMain, cropWidth, cropHeight,
            input.telephotoWidth, input.telephotoHeight
        )

        // Step 3: Estimate per-pixel noise in the telephoto frame
        val noiseMap = buildNoiseMap(
            input.telephotoPixels, input.telephotoWidth, input.telephotoHeight
        )

        // Step 4: Guided fusion — blend based on local noise level
        val fused = IntArray(input.telephotoPixels.size)
        var totalFusionWeight = 0f
        var pixelCount = 0

        for (i in input.telephotoPixels.indices) {
            val telePixel = input.telephotoPixels[i]
            val mainPixel = scaledMain[i]
            val noise = if (i < noiseMap.size) noiseMap[i] else 0f

            // Fusion weight: 0 = pure telephoto, 1 = pure main camera
            // High noise in telephoto → lean toward main camera's cleaner signal
            // Low noise → keep telephoto's superior resolution
            val fusionWeight = noiseToBlenWeight(noise)

            val tr = (telePixel shr 16) and 0xFF
            val tg = (telePixel shr 8) and 0xFF
            val tb = telePixel and 0xFF

            val mr = (mainPixel shr 16) and 0xFF
            val mg = (mainPixel shr 8) and 0xFF
            val mb = mainPixel and 0xFF

            // Luminance-guided blending: use main camera's luminance as
            // a guide, but preserve telephoto's chrominance (color detail).
            // This prevents the blurred main camera from washing out
            // fine color detail that the telephoto resolved.
            val teleLum = 0.299f * tr + 0.587f * tg + 0.114f * tb
            val mainLum = 0.299f * mr + 0.587f * mg + 0.114f * mb

            // Blend luminance
            val fusedLum = teleLum * (1f - fusionWeight) + mainLum * fusionWeight

            // Preserve telephoto chrominance, scale by luminance ratio
            val lumRatio = if (teleLum > 1f) fusedLum / teleLum else 1f
            val fr = (tr * lumRatio).toInt().coerceIn(0, 255)
            val fg = (tg * lumRatio).toInt().coerceIn(0, 255)
            val fb = (tb * lumRatio).toInt().coerceIn(0, 255)

            fused[i] = (0xFF shl 24) or (fr shl 16) or (fg shl 8) or fb

            totalFusionWeight += fusionWeight
            pixelCount++
        }

        val avgFusionStrength = if (pixelCount > 0) totalFusionWeight / pixelCount else 0f

        // Estimate SNR improvement: main camera has ~2.4× more photons per pixel,
        // so fully fused regions get sqrt(2.4) ≈ 1.55× SNR = ~3.8 dB improvement.
        val snrImprovementDb = avgFusionStrength * 3.8f

        return FusionResult(
            pixels = fused,
            width = input.telephotoWidth,
            height = input.telephotoHeight,
            fusionStrength = avgFusionStrength,
            snrImprovement = snrImprovementDb
        )
    }

    /**
     * Map noise level to fusion blend weight.
     *
     * Noise estimate from 0 (clean) to 50+ (very noisy).
     * We map this to a blend weight:
     *   noise < 5   → weight 0 (telephoto is clean enough)
     *   noise 5-15  → weight ramps from 0 to 0.5
     *   noise 15-30 → weight ramps from 0.5 to 0.8
     *   noise > 30  → weight 0.8 (never fully replace telephoto)
     *
     * We cap at 0.8 because the telephoto always has better spatial resolution
     * than the upscaled main camera crop. Even noisy telephoto pixels carry
     * real detail that the main camera can't provide.
     */
    private fun noiseToBlenWeight(noise: Float): Float {
        return when {
            noise < 5f -> 0f
            noise < 15f -> (noise - 5f) / 20f        // 0 → 0.5
            noise < 30f -> 0.5f + (noise - 15f) / 50f  // 0.5 → 0.8
            else -> 0.8f
        }
    }

    private fun estimateNoise(pixels: IntArray, width: Int, height: Int): Float {
        // Estimate noise using the MAD (Median Absolute Deviation) of
        // local gradients in a center patch
        val patchSize = 64
        val startX = (width - patchSize) / 2
        val startY = (height - patchSize) / 2
        val gradients = mutableListOf<Float>()

        for (y in startY until startY + patchSize - 1) {
            for (x in startX until startX + patchSize - 1) {
                val idx = y * width + x
                val idxRight = idx + 1
                val idxDown = idx + width

                if (idx >= pixels.size || idxRight >= pixels.size || idxDown >= pixels.size) continue

                val lum = luminance(pixels[idx])
                val lumR = luminance(pixels[idxRight])
                val lumD = luminance(pixels[idxDown])

                gradients.add(abs(lum - lumR).toFloat())
                gradients.add(abs(lum - lumD).toFloat())
            }
        }

        if (gradients.isEmpty()) return 0f

        gradients.sort()
        val median = gradients[gradients.size / 2]
        // MAD-based noise estimate (robust to edges)
        return median * 1.4826f  // Scale factor to estimate sigma from MAD
    }

    private fun buildNoiseMap(pixels: IntArray, width: Int, height: Int): FloatArray {
        // Build a per-pixel local noise estimate using a 5×5 window
        val noiseMap = FloatArray(pixels.size)
        val halfWindow = 2

        for (y in halfWindow until height - halfWindow) {
            for (x in halfWindow until width - halfWindow) {
                val idx = y * width + x
                val centerLum = luminance(pixels[idx])

                var diffSum = 0f
                var count = 0
                for (dy in -halfWindow..halfWindow) {
                    for (dx in -halfWindow..halfWindow) {
                        if (dx == 0 && dy == 0) continue
                        val ni = (y + dy) * width + (x + dx)
                        if (ni >= 0 && ni < pixels.size) {
                            diffSum += abs(luminance(pixels[ni]) - centerLum).toFloat()
                            count++
                        }
                    }
                }
                noiseMap[idx] = if (count > 0) diffSum / count else 0f
            }
        }
        return noiseMap
    }

    private fun cropImage(
        pixels: IntArray, width: Int, height: Int,
        x: Int, y: Int, cropW: Int, cropH: Int
    ): IntArray {
        val result = IntArray(cropW * cropH)
        for (row in 0 until cropH) {
            val srcOffset = (y + row) * width + x
            val dstOffset = row * cropW
            if (srcOffset + cropW <= pixels.size) {
                System.arraycopy(pixels, srcOffset, result, dstOffset, cropW)
            }
        }
        return result
    }

    private fun bilinearScale(
        src: IntArray, srcW: Int, srcH: Int,
        dstW: Int, dstH: Int
    ): IntArray {
        val dst = IntArray(dstW * dstH)
        val xRatio = srcW.toFloat() / dstW
        val yRatio = srcH.toFloat() / dstH

        for (dy in 0 until dstH) {
            for (dx in 0 until dstW) {
                val srcX = dx * xRatio
                val srcY = dy * yRatio
                val x0 = srcX.toInt().coerceIn(0, srcW - 2)
                val y0 = srcY.toInt().coerceIn(0, srcH - 2)
                val fx = srcX - x0
                val fy = srcY - y0

                val i00 = y0 * srcW + x0
                val i10 = i00 + 1
                val i01 = i00 + srcW
                val i11 = i01 + 1

                if (i11 >= src.size) {
                    dst[dy * dstW + dx] = src[min(i00, src.size - 1)]
                    continue
                }

                val p00 = src[i00]; val p10 = src[i10]
                val p01 = src[i01]; val p11 = src[i11]

                val r = bilerp(
                    ((p00 shr 16) and 0xFF).toFloat(), ((p10 shr 16) and 0xFF).toFloat(),
                    ((p01 shr 16) and 0xFF).toFloat(), ((p11 shr 16) and 0xFF).toFloat(),
                    fx, fy
                ).toInt().coerceIn(0, 255)

                val g = bilerp(
                    ((p00 shr 8) and 0xFF).toFloat(), ((p10 shr 8) and 0xFF).toFloat(),
                    ((p01 shr 8) and 0xFF).toFloat(), ((p11 shr 8) and 0xFF).toFloat(),
                    fx, fy
                ).toInt().coerceIn(0, 255)

                val b = bilerp(
                    (p00 and 0xFF).toFloat(), (p10 and 0xFF).toFloat(),
                    (p01 and 0xFF).toFloat(), (p11 and 0xFF).toFloat(),
                    fx, fy
                ).toInt().coerceIn(0, 255)

                dst[dy * dstW + dx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return dst
    }

    private fun bilerp(v00: Float, v10: Float, v01: Float, v11: Float, fx: Float, fy: Float): Float {
        val top = v00 + (v10 - v00) * fx
        val bot = v01 + (v11 - v01) * fx
        return top + (bot - top) * fy
    }

    private fun luminance(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }
}
