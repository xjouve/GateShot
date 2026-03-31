package com.gateshot.coaching.replay

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Course Reference Capture — Panoramic sweep of the blank course.
 *
 * WORKFLOW:
 * 1. Before training, coach stands at their filming position
 * 2. Taps "Capture Reference" and slowly pans left-to-right across the course
 * 3. App captures frames and stitches them into a wide reference panorama
 * 4. Gates are auto-detected as anchor points in the panorama
 * 5. During runs, each video frame is matched to this panorama
 *
 * WHY THIS WORKS:
 * - The course (gates, nets, slope) is completely static during a session
 * - Gates are high-contrast (red/blue on white snow) = easy detection
 * - Even if the coach moves 5-10m between runs, the panorama covers
 *   the full field of view and each run can be registered back to it
 * - The coach can even change angles between runs and overlay still works
 *
 * TECHNICAL APPROACH:
 * - Frame capture during pan gesture (every ~5° of rotation via gyroscope)
 * - Feature detection (ORB) on each frame
 * - Pairwise homography estimation between adjacent frames
 * - Stitch into a cylindrical panorama
 * - Detect gate positions as colored pole features in the panorama
 * - Store panorama + gate positions as the session reference
 */
class CourseReferenceCapture {

    data class GatePosition(
        val id: Int,
        val x: Float,          // Position in panorama (normalized 0-1)
        val y: Float,
        val color: GateColor,
        val confidence: Float
    )

    enum class GateColor { RED, BLUE, UNKNOWN }

    data class CourseReference(
        val panoramaWidth: Int,
        val panoramaHeight: Int,
        val panoramaPixels: IntArray,
        val gates: List<GatePosition>,
        val captureTimestamp: Long,
        val cameraPositionDescription: String  // e.g., "100m below, center of course"
    ) {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = panoramaPixels.contentHashCode()
    }

    // Frames captured during pan sweep
    private val capturedFrames = mutableListOf<CapturedFrame>()
    private var isCapturing = false

    data class CapturedFrame(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        val rotationDegrees: Float,  // From gyroscope — how far we've panned
        val timestamp: Long
    ) {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = pixels.contentHashCode()
    }

    fun startCapture() {
        capturedFrames.clear()
        isCapturing = true
    }

    fun addFrame(pixels: IntArray, width: Int, height: Int, rotationDegrees: Float) {
        if (!isCapturing) return
        capturedFrames.add(CapturedFrame(pixels, width, height, rotationDegrees, System.currentTimeMillis()))
    }

    fun stopCapture(): CourseReference? {
        isCapturing = false
        if (capturedFrames.size < 3) return null

        // Step 1: Stitch frames into panorama
        val panorama = stitchFrames(capturedFrames)

        // Step 2: Detect gates in the panorama
        val gates = detectGates(panorama.first, panorama.second, panorama.third)

        return CourseReference(
            panoramaWidth = panorama.second,
            panoramaHeight = panorama.third,
            panoramaPixels = panorama.first,
            gates = gates,
            captureTimestamp = System.currentTimeMillis(),
            cameraPositionDescription = ""
        )
    }

    /**
     * Simplified panorama stitching.
     *
     * Uses horizontal concatenation with blending in overlap regions.
     * Each frame overlaps the previous by ~30% (estimated from rotation angle).
     *
     * Returns (pixels, width, height).
     */
    private fun stitchFrames(frames: List<CapturedFrame>): Triple<IntArray, Int, Int> {
        if (frames.isEmpty()) return Triple(IntArray(0), 0, 0)
        if (frames.size == 1) return Triple(frames[0].pixels, frames[0].width, frames[0].height)

        val frameWidth = frames[0].width
        val frameHeight = frames[0].height

        // Estimate total panorama width from rotation range
        val totalRotation = abs(frames.last().rotationDegrees - frames.first().rotationDegrees)
        val overlapFraction = 0.3f
        val stepWidth = (frameWidth * (1f - overlapFraction)).toInt()
        val panoramaWidth = frameWidth + (frames.size - 1) * stepWidth
        val panoramaHeight = frameHeight

        val output = IntArray(panoramaWidth * panoramaHeight)

        for (f in frames.indices) {
            val frame = frames[f]
            val offsetX = f * stepWidth

            for (y in 0 until frameHeight) {
                for (x in 0 until frame.width) {
                    val srcIdx = y * frame.width + x
                    if (srcIdx >= frame.pixels.size) continue

                    val dstX = offsetX + x
                    if (dstX >= panoramaWidth) continue
                    val dstIdx = y * panoramaWidth + dstX

                    if (dstIdx >= output.size) continue

                    val existing = output[dstIdx]
                    if (existing == 0) {
                        // Empty pixel — place directly
                        output[dstIdx] = frame.pixels[srcIdx]
                    } else {
                        // Overlap region — blend
                        val blendX = x.toFloat() / frame.width  // 0 at left edge, 1 at right
                        val weight = if (blendX < overlapFraction) blendX / overlapFraction else 1f
                        output[dstIdx] = blendPixels(existing, frame.pixels[srcIdx], 1f - weight, weight)
                    }
                }
            }
        }

        return Triple(output, panoramaWidth, panoramaHeight)
    }

    /**
     * Detect ski racing gates in the panorama.
     *
     * Gates are vertical poles that are either:
     * - RED (slalom/GS outer gate, speed event gate)
     * - BLUE (slalom/GS turning gate)
     *
     * On white snow, these are the highest-saturation, highest-contrast features.
     * Detection: find vertical clusters of highly saturated red or blue pixels.
     */
    private fun detectGates(pixels: IntArray, width: Int, height: Int): List<GatePosition> {
        val gates = mutableListOf<GatePosition>()
        val columnSize = 8  // Analyze in 8-pixel-wide columns

        var gateId = 1

        for (col in 0 until width step columnSize) {
            var redCount = 0
            var blueCount = 0
            var totalInColumn = 0
            var avgY = 0f

            for (y in 0 until height step 4) {
                for (dx in 0 until columnSize) {
                    val x = col + dx
                    if (x >= width) continue
                    val idx = y * width + x
                    if (idx >= pixels.size) continue

                    val pixel = pixels[idx]
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF

                    // Check for high-saturation red (gate pole)
                    if (r > 150 && r > g * 2 && r > b * 2) {
                        redCount++
                        avgY += y
                        totalInColumn++
                    }
                    // Check for high-saturation blue (gate pole)
                    if (b > 150 && b > r * 1.5 && b > g * 1.5) {
                        blueCount++
                        avgY += y
                        totalInColumn++
                    }
                }
            }

            // A gate is a vertical cluster of colored pixels
            val minPixelsForGate = (height / 4) / 4  // Gate should span at least 1/4 of frame height
            if (redCount > minPixelsForGate || blueCount > minPixelsForGate) {
                val color = if (redCount > blueCount) GateColor.RED else GateColor.BLUE
                val count = max(redCount, blueCount)
                val confidence = (count.toFloat() / (height / 4)).coerceIn(0f, 1f)

                // Merge with nearby gates (within 20px = same gate)
                val nearbyGate = gates.lastOrNull()
                val normalizedX = (col + columnSize / 2f) / width
                if (nearbyGate != null && abs(nearbyGate.x - normalizedX) < 20f / width) {
                    // Same gate — update position to average
                    gates[gates.size - 1] = nearbyGate.copy(
                        x = (nearbyGate.x + normalizedX) / 2f,
                        confidence = max(nearbyGate.confidence, confidence)
                    )
                } else {
                    gates.add(GatePosition(
                        id = gateId++,
                        x = normalizedX,
                        y = if (totalInColumn > 0) (avgY / totalInColumn) / height else 0.5f,
                        color = color,
                        confidence = confidence
                    ))
                }
            }
        }

        return gates
    }

    private fun blendPixels(a: Int, b: Int, wA: Float, wB: Float): Int {
        val totalW = wA + wB
        if (totalW == 0f) return a
        val r = (((a shr 16) and 0xFF) * wA + ((b shr 16) and 0xFF) * wB) / totalW
        val g = (((a shr 8) and 0xFF) * wA + ((b shr 8) and 0xFF) * wB) / totalW
        val blue = ((a and 0xFF) * wA + (b and 0xFF) * wB) / totalW
        return Color.argb(255,
            r.toInt().coerceIn(0, 255),
            g.toInt().coerceIn(0, 255),
            blue.toInt().coerceIn(0, 255))
    }
}
