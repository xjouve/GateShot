package com.gateshot.coaching.replay

import android.graphics.Color
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Perspective Registration — Warp run videos to match the reference panorama.
 *
 * PROBLEM:
 * The coach moves between runs. Run 1 filmed from position A, run 3 from
 * position B (maybe 5m to the right). A direct overlay would show the racer
 * in different positions even if they took the exact same line.
 *
 * SOLUTION:
 * 1. Before training: capture a reference panorama of the blank course
 * 2. Detect gates as anchor points in both the panorama and each run's video
 * 3. Compute a homography (perspective transform) that maps each run's
 *    video space into the panorama's coordinate space
 * 4. All runs now share the same coordinate system → overlay works perfectly
 *
 * GATE-BASED REGISTRATION:
 * Rather than generic feature matching (ORB/SIFT), we use the gates themselves
 * as registration points. This is more robust because:
 * - Gates are the most distinctive features on a ski course
 * - We know they're vertical poles (geometric constraint)
 * - Their color (red/blue) helps with correspondence matching
 * - There are typically 20-60 of them = plenty of anchor points
 * - They DON'T MOVE between runs (unlike spectators, coaches, etc.)
 */
class PerspectiveRegistration {

    /**
     * A 3x3 homography matrix that maps points from one coordinate space to another.
     * [x', y', w'] = H * [x, y, 1]
     * final_x = x'/w', final_y = y'/w'
     */
    data class Homography(
        val h: FloatArray = FloatArray(9) { if (it == 0 || it == 4 || it == 8) 1f else 0f }
    ) {
        fun transform(x: Float, y: Float): Pair<Float, Float> {
            val w = h[6] * x + h[7] * y + h[8]
            if (abs(w) < 1e-6f) return Pair(x, y)
            val tx = (h[0] * x + h[1] * y + h[2]) / w
            val ty = (h[3] * x + h[4] * y + h[5]) / w
            return Pair(tx, ty)
        }

        override fun equals(other: Any?) = this === other
        override fun hashCode() = h.contentHashCode()
    }

    data class CorrespondencePoint(
        val srcX: Float, val srcY: Float,  // Position in run video (normalized 0-1)
        val dstX: Float, val dstY: Float,  // Position in reference panorama (normalized 0-1)
        val confidence: Float
    )

    /**
     * Find gate correspondences between a run video frame and the reference panorama.
     *
     * @param frameGates Gates detected in the current video frame
     * @param referenceGates Gates detected in the reference panorama
     * @return List of matched gate pairs
     */
    fun findCorrespondences(
        frameGates: List<CourseReferenceCapture.GatePosition>,
        referenceGates: List<CourseReferenceCapture.GatePosition>
    ): List<CorrespondencePoint> {
        val correspondences = mutableListOf<CorrespondencePoint>()

        // Match gates by color and spatial ordering
        // Gates appear left-to-right in both frame and panorama
        val frameRed = frameGates.filter { it.color == CourseReferenceCapture.GateColor.RED }.sortedBy { it.x }
        val frameBlue = frameGates.filter { it.color == CourseReferenceCapture.GateColor.BLUE }.sortedBy { it.x }
        val refRed = referenceGates.filter { it.color == CourseReferenceCapture.GateColor.RED }.sortedBy { it.x }
        val refBlue = referenceGates.filter { it.color == CourseReferenceCapture.GateColor.BLUE }.sortedBy { it.x }

        // Match red gates by order
        matchGatesByOrder(frameRed, refRed, correspondences)
        // Match blue gates by order
        matchGatesByOrder(frameBlue, refBlue, correspondences)

        return correspondences
    }

    private fun matchGatesByOrder(
        frameGates: List<CourseReferenceCapture.GatePosition>,
        refGates: List<CourseReferenceCapture.GatePosition>,
        out: MutableList<CorrespondencePoint>
    ) {
        // The frame typically shows a subset of the panorama's gates.
        // Find the best offset alignment between the two sequences.
        if (frameGates.isEmpty() || refGates.isEmpty()) return

        var bestOffset = 0
        var bestScore = Float.MAX_VALUE

        for (offset in 0..refGates.size - frameGates.size.coerceAtMost(refGates.size)) {
            var score = 0f
            val count = frameGates.size.coerceAtMost(refGates.size - offset)
            for (i in 0 until count) {
                // Score by vertical position similarity (Y should be similar for same gate)
                val dy = abs(frameGates[i].y - refGates[offset + i].y)
                score += dy
            }
            if (count > 0 && score / count < bestScore) {
                bestScore = score / count
                bestOffset = offset
            }
        }

        // Create correspondences at best offset
        val count = frameGates.size.coerceAtMost(refGates.size - bestOffset)
        for (i in 0 until count) {
            val fg = frameGates[i]
            val rg = refGates[bestOffset + i]
            out.add(CorrespondencePoint(
                srcX = fg.x, srcY = fg.y,
                dstX = rg.x, dstY = rg.y,
                confidence = (fg.confidence + rg.confidence) / 2f
            ))
        }
    }

    /**
     * Compute a homography from 4+ correspondence points.
     *
     * Uses Direct Linear Transform (DLT) with SVD.
     * Simplified implementation — for production, use OpenCV's findHomography with RANSAC.
     */
    fun computeHomography(correspondences: List<CorrespondencePoint>): Homography? {
        if (correspondences.size < 4) return null

        // Use the 4 best correspondences (highest confidence)
        val sorted = correspondences.sortedByDescending { it.confidence }.take(4)

        // Simplified affine transform (6 DOF) instead of full homography (8 DOF)
        // This is sufficient when the camera doesn't change its distance to the slope much
        // (coach moves sideways, not forward/backward)
        return computeAffine(sorted)
    }

    /**
     * Compute affine transform from correspondences.
     * Affine = translation + rotation + scale + shear (6 parameters).
     * Handles the case where the coach moves sideways along the slope.
     */
    private fun computeAffine(points: List<CorrespondencePoint>): Homography {
        if (points.size < 3) return Homography()

        // Least squares solution for affine: [a b c; d e f; 0 0 1]
        // Using first 3 points for a direct solution
        val p = points.take(3)

        val srcX = floatArrayOf(p[0].srcX, p[1].srcX, p[2].srcX)
        val srcY = floatArrayOf(p[0].srcY, p[1].srcY, p[2].srcY)
        val dstX = floatArrayOf(p[0].dstX, p[1].dstX, p[2].dstX)
        val dstY = floatArrayOf(p[0].dstY, p[1].dstY, p[2].dstY)

        // Solve: [dstX] = [a b c] * [srcX]
        //        [dstY]   [d e f]   [srcY]
        //                           [1   ]
        val det = srcX[0] * (srcY[1] - srcY[2]) - srcX[1] * (srcY[0] - srcY[2]) + srcX[2] * (srcY[0] - srcY[1])
        if (abs(det) < 1e-6f) return Homography()

        val invDet = 1f / det

        // Cramer's rule for the 3x3 system
        val a = ((dstX[0] * (srcY[1] - srcY[2]) - dstX[1] * (srcY[0] - srcY[2]) + dstX[2] * (srcY[0] - srcY[1])) * invDet)
        val b = ((srcX[0] * (dstX[1] - dstX[2]) - srcX[1] * (dstX[0] - dstX[2]) + srcX[2] * (dstX[0] - dstX[1])) * invDet)
        val c = dstX[0] - a * srcX[0] - b * srcY[0]

        val d = ((dstY[0] * (srcY[1] - srcY[2]) - dstY[1] * (srcY[0] - srcY[2]) + dstY[2] * (srcY[0] - srcY[1])) * invDet)
        val e = ((srcX[0] * (dstY[1] - dstY[2]) - srcX[1] * (dstY[0] - dstY[2]) + srcX[2] * (dstY[0] - dstY[1])) * invDet)
        val f = dstY[0] - d * srcX[0] - e * srcY[0]

        return Homography(floatArrayOf(a, b, c, d, e, f, 0f, 0f, 1f))
    }

    /**
     * Warp a video frame using the computed homography.
     * Maps every pixel from the frame into the reference panorama's coordinate space.
     */
    fun warpFrame(
        srcPixels: IntArray,
        srcWidth: Int,
        srcHeight: Int,
        homography: Homography,
        dstWidth: Int,
        dstHeight: Int
    ): IntArray {
        val output = IntArray(dstWidth * dstHeight)

        // Inverse warp: for each output pixel, find the corresponding source pixel
        // We need the inverse homography
        val inv = invertHomography(homography) ?: return output

        for (dy in 0 until dstHeight) {
            for (dx in 0 until dstWidth) {
                val normDx = dx.toFloat() / dstWidth
                val normDy = dy.toFloat() / dstHeight

                val (normSx, normSy) = inv.transform(normDx, normDy)
                val sx = (normSx * srcWidth).toInt()
                val sy = (normSy * srcHeight).toInt()

                if (sx in 0 until srcWidth && sy in 0 until srcHeight) {
                    output[dy * dstWidth + dx] = srcPixels[sy * srcWidth + sx]
                }
                // Pixels outside source frame remain transparent (0)
            }
        }

        return output
    }

    private fun invertHomography(h: Homography): Homography? {
        val m = h.h
        val det = m[0] * (m[4] * m[8] - m[5] * m[7]) -
                  m[1] * (m[3] * m[8] - m[5] * m[6]) +
                  m[2] * (m[3] * m[7] - m[4] * m[6])

        if (abs(det) < 1e-6f) return null

        val invDet = 1f / det
        val inv = FloatArray(9)
        inv[0] = (m[4] * m[8] - m[5] * m[7]) * invDet
        inv[1] = (m[2] * m[7] - m[1] * m[8]) * invDet
        inv[2] = (m[1] * m[5] - m[2] * m[4]) * invDet
        inv[3] = (m[5] * m[6] - m[3] * m[8]) * invDet
        inv[4] = (m[0] * m[8] - m[2] * m[6]) * invDet
        inv[5] = (m[2] * m[3] - m[0] * m[5]) * invDet
        inv[6] = (m[3] * m[7] - m[4] * m[6]) * invDet
        inv[7] = (m[1] * m[6] - m[0] * m[7]) * invDet
        inv[8] = (m[0] * m[4] - m[1] * m[3]) * invDet

        return Homography(inv)
    }
}
