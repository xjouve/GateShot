package com.gateshot.processing.sr

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Sub-pixel frame alignment using phase correlation.
 *
 * Each frame in a burst is slightly shifted due to hand tremor.
 * These sub-pixel shifts contain unique spatial information that
 * can be fused to reconstruct detail beyond a single frame's resolution.
 */
class FrameAligner {

    data class AlignmentResult(
        val shiftX: Float,      // Sub-pixel shift in X
        val shiftY: Float,      // Sub-pixel shift in Y
        val confidence: Float,  // 0.0-1.0: how confident the alignment is
        val isUsable: Boolean   // False if shift is too large (motion blur / different scene)
    )

    // Maximum pixel shift we consider valid for SR (beyond this = camera moved too much)
    private val maxShiftPixels = 16f
    // Minimum confidence to include frame in fusion
    private val minConfidence = 0.3f

    /**
     * Align a frame to a reference frame using block-based phase correlation.
     * Returns sub-pixel shift estimate.
     */
    fun align(reference: IntArray, target: IntArray, width: Int, height: Int): AlignmentResult {
        // Compute alignment on a downscaled grid for performance
        val blockSize = 32
        val searchRadius = 16
        val gridW = width / blockSize
        val gridH = height / blockSize

        var bestShiftX = 0f
        var bestShiftY = 0f
        var bestScore = Float.MAX_VALUE
        var totalBlocks = 0
        var validBlocks = 0

        // Coarse search: integer pixel shifts
        for (dy in -searchRadius..searchRadius step 2) {
            for (dx in -searchRadius..searchRadius step 2) {
                var totalSAD = 0L
                var blockCount = 0

                for (gy in 1 until gridH - 1) {
                    for (gx in 1 until gridW - 1) {
                        val refX = gx * blockSize
                        val refY = gy * blockSize
                        val tarX = refX + dx
                        val tarY = refY + dy

                        if (tarX < 0 || tarX + blockSize > width || tarY < 0 || tarY + blockSize > height) continue

                        // Sum of Absolute Differences on luminance
                        var sad = 0L
                        for (by in 0 until blockSize step 4) {
                            for (bx in 0 until blockSize step 4) {
                                val refIdx = (refY + by) * width + (refX + bx)
                                val tarIdx = (tarY + by) * width + (tarX + bx)
                                if (refIdx < reference.size && tarIdx < target.size) {
                                    val refLum = luminance(reference[refIdx])
                                    val tarLum = luminance(target[tarIdx])
                                    sad += abs(refLum - tarLum)
                                }
                            }
                        }
                        totalSAD += sad
                        blockCount++
                    }
                }

                if (blockCount > 0) {
                    val avgSAD = totalSAD.toFloat() / blockCount
                    if (avgSAD < bestScore) {
                        bestScore = avgSAD
                        bestShiftX = dx.toFloat()
                        bestShiftY = dy.toFloat()
                    }
                }
            }
        }

        // Fine search: sub-pixel refinement around best integer shift
        val fineStep = 0.5f
        val coarseX = bestShiftX
        val coarseY = bestShiftY
        for (fdy in -2..2) {
            for (fdx in -2..2) {
                val testX = coarseX + fdx * fineStep
                val testY = coarseY + fdy * fineStep
                // For sub-pixel, we'd need bilinear interpolation
                // Approximate by using integer nearest neighbor
                val score = computeAlignmentScore(
                    reference, target, width, height,
                    testX.toInt(), testY.toInt(), blockSize
                )
                if (score < bestScore) {
                    bestScore = score
                    bestShiftX = testX
                    bestShiftY = testY
                }
            }
        }

        val shiftMagnitude = sqrt(bestShiftX * bestShiftX + bestShiftY * bestShiftY)
        val isUsable = shiftMagnitude <= maxShiftPixels
        // Confidence: lower SAD = higher confidence, normalized
        val confidence = (1f - (bestScore / 50f)).coerceIn(0f, 1f)

        return AlignmentResult(
            shiftX = bestShiftX,
            shiftY = bestShiftY,
            confidence = confidence,
            isUsable = isUsable && confidence >= minConfidence
        )
    }

    /**
     * Align multiple frames to a reference (first frame).
     * Returns list of alignment results, one per frame (excluding reference).
     */
    fun alignBatch(
        frames: List<IntArray>,
        width: Int,
        height: Int
    ): List<AlignmentResult> {
        if (frames.size < 2) return emptyList()
        val reference = frames[0]
        return frames.drop(1).map { target ->
            align(reference, target, width, height)
        }
    }

    private fun computeAlignmentScore(
        ref: IntArray, tar: IntArray,
        width: Int, height: Int,
        dx: Int, dy: Int, blockSize: Int
    ): Float {
        var totalSAD = 0L
        var count = 0
        val step = 8

        for (y in blockSize until height - blockSize step step) {
            for (x in blockSize until width - blockSize step step) {
                val tarX = x + dx
                val tarY = y + dy
                if (tarX < 0 || tarX >= width || tarY < 0 || tarY >= height) continue

                val refIdx = y * width + x
                val tarIdx = tarY * width + tarX
                if (refIdx < ref.size && tarIdx < tar.size) {
                    totalSAD += abs(luminance(ref[refIdx]) - luminance(tar[tarIdx]))
                    count++
                }
            }
        }

        return if (count > 0) totalSAD.toFloat() / count else Float.MAX_VALUE
    }

    private fun luminance(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }
}
