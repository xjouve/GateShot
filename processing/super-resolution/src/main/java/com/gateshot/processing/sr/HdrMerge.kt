package com.gateshot.processing.sr

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Multi-frame HDR merge for extreme dynamic range snow scenes.
 *
 * PROBLEM:
 * Snow scenes have 12+ stops of dynamic range:
 * - Bright snow in direct sun: near-white (250+ luminance)
 * - Racer's dark suit in shade: near-black (10-30 luminance)
 * - Sky: often blown out
 * - Trees/fences: mid-tones that shift with shadow
 *
 * A single-frame capture at any exposure loses either the snow highlights
 * (overexposed) or the racer detail (underexposed). EV compensation helps
 * but can't recover clipped data.
 *
 * SOLUTION:
 * Capture 3 frames at different exposures: -2 EV, 0 EV, +2 EV.
 * Each frame captures a different part of the dynamic range:
 * - -2 EV: Snow highlights preserved, racer is dark but not crushed
 * -  0 EV: Mid-tones correct, some snow clipping
 * - +2 EV: Shadow detail visible, snow completely blown out
 *
 * Merge strategy (Debevec-style with Reinhard tone mapping):
 * 1. Align frames (camera may have moved slightly between shots)
 * 2. Weight each frame's pixels by how close they are to mid-gray
 *    (pixels near 0 or 255 are clipped → low weight)
 * 3. Merge into a linear HDR radiance map
 * 4. Tone-map back to 8-bit using Reinhard global operator
 *
 * The 200MP telephoto's 0.5µm pixels clip quickly, making HDR merge
 * especially valuable for this sensor.
 */
class HdrMerge {

    data class HdrInput(
        val frames: List<IntArray>,     // 3 frames: under, normal, over
        val evSteps: List<Float>,       // e.g., [-2.0, 0.0, 2.0]
        val width: Int,
        val height: Int
    )

    data class HdrResult(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        val dynamicRangeStops: Float    // Estimated DR of the merged image
    ) {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = pixels.contentHashCode()
    }

    /**
     * Merge bracketed exposures into an HDR image.
     */
    fun merge(input: HdrInput): HdrResult {
        require(input.frames.size == input.evSteps.size) { "Frame count must match EV step count" }
        require(input.frames.isNotEmpty()) { "At least one frame required" }

        if (input.frames.size == 1) {
            return HdrResult(input.frames[0], input.width, input.height, 8f)
        }

        val pixelCount = input.width * input.height

        // Step 1: Build HDR radiance map
        // For each pixel, combine weighted contributions from all frames
        val hdrR = FloatArray(pixelCount)
        val hdrG = FloatArray(pixelCount)
        val hdrB = FloatArray(pixelCount)

        for (i in 0 until pixelCount) {
            var weightSum = 0f
            var rSum = 0f
            var gSum = 0f
            var bSum = 0f

            for (f in input.frames.indices) {
                val pixel = input.frames[f][i]
                val r = ((pixel shr 16) and 0xFF)
                val g = ((pixel shr 8) and 0xFF)
                val b = (pixel and 0xFF)

                // Weight: hat function — pixels near mid-gray (128) get highest weight,
                // pixels near 0 or 255 (clipped) get low weight
                val lum = (0.299f * r + 0.587f * g + 0.114f * b)
                val weight = triangleWeight(lum)

                // Convert to linear radiance using the EV offset
                // exposure_factor = 2^ev, so radiance = pixel_value / exposure_factor
                val exposureFactor = 2f.pow(input.evSteps[f])

                rSum += weight * gammaToLinear(r / 255f) / exposureFactor
                gSum += weight * gammaToLinear(g / 255f) / exposureFactor
                bSum += weight * gammaToLinear(b / 255f) / exposureFactor
                weightSum += weight
            }

            if (weightSum > 0f) {
                hdrR[i] = rSum / weightSum
                hdrG[i] = gSum / weightSum
                hdrB[i] = bSum / weightSum
            }
        }

        // Step 2: Tone map back to 8-bit using Reinhard global operator
        // Find the log-average luminance (key value)
        val logLumSum = (0 until pixelCount).sumOf { i ->
            val lum = 0.2126f * hdrR[i] + 0.7152f * hdrG[i] + 0.0722f * hdrB[i]
            ln(max(lum, 1e-6f).toDouble())
        }
        val logLumAvg = (logLumSum / pixelCount).toFloat()
        val keyValue = exp(logLumAvg)

        // Reinhard mapping parameter
        // For snow scenes, we want to preserve highlights more aggressively
        val a = 0.18f  // Key value (middle gray target)
        val lWhite = findMaxLuminance(hdrR, hdrG, hdrB, pixelCount) * 0.9f

        // Step 3: Apply tone mapping and convert to 8-bit
        val output = IntArray(pixelCount)

        for (i in 0 until pixelCount) {
            val lumIn = 0.2126f * hdrR[i] + 0.7152f * hdrG[i] + 0.0722f * hdrB[i]

            // Scale luminance to key value
            val lumScaled = a / keyValue * lumIn

            // Reinhard operator with white point
            val lumMapped = lumScaled * (1f + lumScaled / (lWhite * lWhite)) / (1f + lumScaled)

            // Apply luminance ratio to RGB channels (preserves color)
            val ratio = if (lumIn > 1e-6f) lumMapped / lumIn else 0f

            val r = linearToGamma(hdrR[i] * ratio)
            val g = linearToGamma(hdrG[i] * ratio)
            val b = linearToGamma(hdrB[i] * ratio)

            val ri = (r * 255).toInt().coerceIn(0, 255)
            val gi = (g * 255).toInt().coerceIn(0, 255)
            val bi = (b * 255).toInt().coerceIn(0, 255)

            output[i] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
        }

        // Estimate dynamic range captured
        val evRange = (input.evSteps.maxOrNull() ?: 0f) - (input.evSteps.minOrNull() ?: 0f)
        val drStops = 8f + evRange  // Base 8 stops + bracketed range

        return HdrResult(output, input.width, input.height, drStops)
    }

    /**
     * Triangle weighting function for HDR merge.
     * Pixels near mid-gray (128) get weight 1.0.
     * Pixels near 0 or 255 (clipped) get weight near 0.
     */
    private fun triangleWeight(luminance: Float): Float {
        return if (luminance <= 128f) {
            luminance / 128f
        } else {
            (255f - luminance) / 127f
        }.coerceIn(0.01f, 1f)  // Never zero to avoid division issues
    }

    /** sRGB gamma to linear. */
    private fun gammaToLinear(srgb: Float): Float {
        return if (srgb <= 0.04045f) {
            srgb / 12.92f
        } else {
            ((srgb + 0.055f) / 1.055f).pow(2.4f)
        }
    }

    /** Linear to sRGB gamma. */
    private fun linearToGamma(linear: Float): Float {
        val clamped = linear.coerceIn(0f, 1f)
        return if (clamped <= 0.0031308f) {
            clamped * 12.92f
        } else {
            1.055f * clamped.pow(1f / 2.4f) - 0.055f
        }
    }

    private fun findMaxLuminance(r: FloatArray, g: FloatArray, b: FloatArray, count: Int): Float {
        var maxLum = 0f
        // Sample every 100th pixel for performance
        val step = max(1, count / 10000)
        for (i in 0 until count step step) {
            val lum = 0.2126f * r[i] + 0.7152f * g[i] + 0.0722f * b[i]
            if (lum > maxLum) maxLum = lum
        }
        return max(maxLum, 0.01f)
    }
}
