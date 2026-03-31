package com.gateshot.processing.sr

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Multi-frame fusion engine for super-resolution.
 *
 * Takes multiple aligned frames and fuses them into a single higher-quality image.
 * Uses weighted averaging with outlier rejection to handle moving subjects —
 * pixels that change between frames (racer moving) are taken from the reference
 * frame only, while static regions (snow, gates, trees) benefit from multi-frame fusion.
 */
class FrameFuser {

    data class FusionConfig(
        val outlierThreshold: Int = 30,     // Pixel difference threshold to flag as motion
        val motionWeightFalloff: Float = 0.1f, // Weight for motion pixels (0 = ignore, 1 = full weight)
        val sharpnessWeight: Boolean = true, // Weight frames by local sharpness
        val denoiseStrength: Float = 0.5f    // 0 = no denoise, 1 = maximum
    )

    /**
     * Fuse multiple aligned frames into a single super-resolved image.
     *
     * @param reference The reference frame (base image, highest priority)
     * @param alignedFrames Other frames, already shifted to align with reference
     * @param alignments Alignment data for each frame (confidence used as weight)
     * @param width Image width
     * @param height Image height
     * @param config Fusion parameters
     * @return Fused pixel array (ARGB)
     */
    fun fuse(
        reference: IntArray,
        alignedFrames: List<IntArray>,
        alignments: List<FrameAligner.AlignmentResult>,
        width: Int,
        height: Int,
        config: FusionConfig = FusionConfig()
    ): IntArray {
        val output = IntArray(width * height)
        val allFrames = listOf(reference) + alignedFrames
        val weights = listOf(1.0f) + alignments.map { it.confidence }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val refPixel = reference[idx]

                var totalR = 0f
                var totalG = 0f
                var totalB = 0f
                var totalWeight = 0f

                for (f in allFrames.indices) {
                    val frame = allFrames[f]
                    if (idx >= frame.size) continue

                    val pixel = frame[idx]
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF

                    // Detect motion: compare this pixel to reference
                    val refR = (refPixel shr 16) and 0xFF
                    val refG = (refPixel shr 8) and 0xFF
                    val refB = refPixel and 0xFF
                    val diff = (abs(r - refR) + abs(g - refG) + abs(b - refB)) / 3

                    // Weight: base weight from alignment confidence
                    var w = weights.getOrElse(f) { 0.5f }

                    // Outlier rejection: if pixel differs too much from reference, it's motion
                    if (diff > config.outlierThreshold && f > 0) {
                        w *= config.motionWeightFalloff  // Heavily reduce weight for motion pixels
                    }

                    // Local sharpness weighting: prefer sharper frames
                    if (config.sharpnessWeight && f > 0) {
                        val localSharpness = computeLocalSharpness(frame, x, y, width, height)
                        w *= (0.5f + localSharpness)  // Boost sharp frames
                    }

                    totalR += r * w
                    totalG += g * w
                    totalB += b * w
                    totalWeight += w
                }

                if (totalWeight > 0) {
                    val finalR = (totalR / totalWeight).roundToInt().coerceIn(0, 255)
                    val finalG = (totalG / totalWeight).roundToInt().coerceIn(0, 255)
                    val finalB = (totalB / totalWeight).roundToInt().coerceIn(0, 255)
                    output[idx] = Color.argb(255, finalR, finalG, finalB)
                } else {
                    output[idx] = refPixel
                }
            }
        }

        return output
    }

    /**
     * Fuse and upscale: create a 2x resolution output from multiple frames.
     * Uses sub-pixel shift information to place pixels on a finer grid.
     */
    fun fuseAndUpscale2x(
        reference: IntArray,
        alignedFrames: List<IntArray>,
        alignments: List<FrameAligner.AlignmentResult>,
        width: Int,
        height: Int
    ): IntArray {
        val outW = width * 2
        val outH = height * 2
        val output = IntArray(outW * outH)

        // First, place reference pixels on the 2x grid (every other pixel)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val srcIdx = y * width + x
                val dstIdx = (y * 2) * outW + (x * 2)
                if (srcIdx < reference.size && dstIdx < output.size) {
                    output[dstIdx] = reference[srcIdx]
                }
            }
        }

        // Then, use shifted frames to fill in the gaps
        for (f in alignedFrames.indices) {
            val frame = alignedFrames[f]
            val alignment = alignments[f]
            if (!alignment.isUsable) continue

            // The sub-pixel shift tells us where this frame's pixels land on the 2x grid
            val subX = ((alignment.shiftX % 1.0f) * 2).roundToInt().coerceIn(0, 1)
            val subY = ((alignment.shiftY % 1.0f) * 2).roundToInt().coerceIn(0, 1)
            val intShiftX = alignment.shiftX.toInt()
            val intShiftY = alignment.shiftY.toInt()

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val srcIdx = y * width + x
                    if (srcIdx >= frame.size) continue

                    val dstX = (x - intShiftX) * 2 + subX
                    val dstY = (y - intShiftY) * 2 + subY
                    if (dstX < 0 || dstX >= outW || dstY < 0 || dstY >= outH) continue

                    val dstIdx = dstY * outW + dstX

                    // Only fill if empty or blend
                    if (output[dstIdx] == 0) {
                        output[dstIdx] = frame[srcIdx]
                    } else {
                        // Blend with existing pixel
                        val existing = output[dstIdx]
                        val w = alignment.confidence
                        output[dstIdx] = blendPixels(existing, frame[srcIdx], 1f - w, w)
                    }
                }
            }
        }

        // Fill any remaining gaps with bilinear interpolation
        fillGaps(output, outW, outH)

        return output
    }

    private fun computeLocalSharpness(frame: IntArray, x: Int, y: Int, width: Int, height: Int): Float {
        if (x < 1 || x >= width - 1 || y < 1 || y >= height - 1) return 0.5f

        val center = luminance(frame[y * width + x])
        val top = luminance(frame[(y - 1) * width + x])
        val bottom = luminance(frame[(y + 1) * width + x])
        val left = luminance(frame[y * width + (x - 1)])
        val right = luminance(frame[y * width + (x + 1)])

        val laplacian = abs(top + bottom + left + right - 4 * center)
        return (laplacian / 100f).coerceIn(0f, 1f)
    }

    private fun blendPixels(a: Int, b: Int, wA: Float, wB: Float): Int {
        val totalW = wA + wB
        val r = (((a shr 16) and 0xFF) * wA + ((b shr 16) and 0xFF) * wB) / totalW
        val g = (((a shr 8) and 0xFF) * wA + ((b shr 8) and 0xFF) * wB) / totalW
        val blue = ((a and 0xFF) * wA + (b and 0xFF) * wB) / totalW
        return Color.argb(255, r.roundToInt().coerceIn(0, 255),
            g.roundToInt().coerceIn(0, 255), blue.roundToInt().coerceIn(0, 255))
    }

    private fun fillGaps(pixels: IntArray, width: Int, height: Int) {
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                if (pixels[idx] != 0) continue

                // Bilinear interpolation from neighbors
                var r = 0; var g = 0; var b = 0; var count = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue
                        val nIdx = ny * width + nx
                        val p = pixels[nIdx]
                        if (p != 0) {
                            r += (p shr 16) and 0xFF
                            g += (p shr 8) and 0xFF
                            b += p and 0xFF
                            count++
                        }
                    }
                }
                if (count > 0) {
                    pixels[idx] = Color.argb(255, r / count, g / count, b / count)
                }
            }
        }
    }

    private fun luminance(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }
}
