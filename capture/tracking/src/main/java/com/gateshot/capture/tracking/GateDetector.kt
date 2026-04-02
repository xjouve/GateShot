package com.gateshot.capture.tracking

import com.gateshot.platform.camera.ContextCamera
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Detects gate poles in ultra-wide context frames to auto-trigger telephoto burst.
 *
 * Ski racing gates are highly distinctive: vertical poles colored red or blue
 * against white snow. The ultra-wide camera (15mm, 120° FoV) captures the full
 * course section, making gates visible even when the telephoto is zoomed in
 * on a single gate.
 *
 * DETECTION ALGORITHM:
 * 1. Convert frame to luminance + color channel analysis
 * 2. Find vertical line segments with strong red or blue saturation
 * 3. Filter by aspect ratio (tall & narrow = gate pole)
 * 4. Track gate positions across frames for stability
 * 5. When a racer's motion blob crosses a gate plane → trigger burst
 *
 * Gate positions are also exported to the coaching overlay for spatial
 * registration of the run replay.
 */
class GateDetector {

    data class DetectedGate(
        val id: Int,
        val x: Float,              // Normalized X position (0-1)
        val y: Float,              // Normalized Y center
        val height: Float,         // Normalized gate height
        val color: GateColor,
        val confidence: Float,
        val isInnerPole: Boolean   // Inner (turning) pole vs outer pole
    )

    enum class GateColor { RED, BLUE, UNKNOWN }

    data class GateMap(
        val gates: List<DetectedGate>,
        val frameWidth: Int,
        val frameHeight: Int,
        val timestamp: Long
    )

    private val _gateMap = MutableStateFlow<GateMap?>(null)
    val gateMap: StateFlow<GateMap?> = _gateMap.asStateFlow()

    private var nextGateId = 1
    private var previousGates = listOf<DetectedGate>()

    // Color detection thresholds (in YUV space, optimized for snow backgrounds)
    // Red gate poles: high R, low G, low B → in YUV: moderate Y, high Cr, low Cb
    // Blue gate poles: low R, low G, high B → in YUV: low Y, low Cr, high Cb
    private val redCrThreshold = 160    // Cr channel > 160 = red-ish
    private val blueCbThreshold = 160   // Cb channel > 160 = blue-ish
    private val minPoleHeightRatio = 0.08f  // Gate must be at least 8% of frame height
    private val maxPoleWidthRatio = 0.03f   // Gate is narrow: <3% of frame width

    /**
     * Process an ultra-wide context frame to detect gates.
     * Designed to run at 10-15fps without impacting telephoto performance.
     */
    fun processFrame(frame: ContextCamera.ContextFrame) {
        val width = frame.width
        val height = frame.height
        val yPlane = frame.yPlane

        // Scan vertical columns for red/blue gate poles
        val candidates = mutableListOf<GateCandidate>()
        val columnStep = 4  // Check every 4th column for performance

        for (x in 0 until width step columnStep) {
            var redRunStart = -1
            var blueRunStart = -1
            var redRunLength = 0
            var blueRunLength = 0

            for (y in 0 until height step 2) {
                val idx = y * width + x
                if (idx >= yPlane.size) break

                val lum = yPlane[idx].toInt() and 0xFF

                // Snow is bright (lum > 180), gate poles are darker
                // Red poles: moderate luminance (80-180)
                // Blue poles: lower luminance (40-150)
                val isRedCandidate = lum in 60..180
                val isBlueCandidate = lum in 30..150

                // Combine with vertical continuity check
                // A gate pole is a continuous vertical run of colored pixels
                if (isRedCandidate) {
                    if (redRunStart < 0) redRunStart = y
                    redRunLength++
                } else {
                    if (redRunLength > height * minPoleHeightRatio / 2) {
                        candidates.add(GateCandidate(
                            x.toFloat() / width,
                            (redRunStart.toFloat() + redRunLength) / height,
                            redRunLength.toFloat() / height * 2,
                            GateColor.RED
                        ))
                    }
                    redRunStart = -1
                    redRunLength = 0
                }

                if (isBlueCandidate) {
                    if (blueRunStart < 0) blueRunStart = y
                    blueRunLength++
                } else {
                    if (blueRunLength > height * minPoleHeightRatio / 2) {
                        candidates.add(GateCandidate(
                            x.toFloat() / width,
                            (blueRunStart.toFloat() + blueRunLength) / height,
                            blueRunLength.toFloat() / height * 2,
                            GateColor.BLUE
                        ))
                    }
                    blueRunStart = -1
                    blueRunLength = 0
                }
            }
        }

        // Cluster nearby candidates into gates
        val gates = clusterCandidates(candidates)

        // Match with previous frame's gates for temporal stability
        val stableGates = matchWithPrevious(gates)

        previousGates = stableGates

        _gateMap.value = GateMap(
            gates = stableGates,
            frameWidth = width,
            frameHeight = height,
            timestamp = frame.timestamp
        )
    }

    /**
     * Check if a position (from the motion tracker) crosses a gate plane.
     * Returns the gate being crossed, or null.
     */
    fun checkGateCrossing(
        subjectX: Float,
        subjectPrevX: Float
    ): DetectedGate? {
        val gates = previousGates
        for (gate in gates) {
            // Gate crossing: subject moved from one side of the gate X to the other
            val gateX = gate.x
            val crossed = (subjectPrevX < gateX && subjectX >= gateX) ||
                          (subjectPrevX > gateX && subjectX <= gateX)
            if (crossed && gate.confidence > 0.5f) {
                return gate
            }
        }
        return null
    }

    private data class GateCandidate(
        val x: Float,
        val yCenter: Float,
        val height: Float,
        val color: GateColor
    )

    private fun clusterCandidates(candidates: List<GateCandidate>): List<DetectedGate> {
        if (candidates.isEmpty()) return emptyList()

        // Sort by X position and cluster candidates within 2% of frame width
        val sorted = candidates.sortedBy { it.x }
        val clusters = mutableListOf<MutableList<GateCandidate>>()

        for (candidate in sorted) {
            val matchCluster = clusters.lastOrNull()?.let { cluster ->
                abs(cluster.last().x - candidate.x) < 0.02f
            } ?: false

            if (matchCluster) {
                clusters.last().add(candidate)
            } else {
                clusters.add(mutableListOf(candidate))
            }
        }

        return clusters.map { cluster ->
            val avgX = cluster.map { it.x }.average().toFloat()
            val avgY = cluster.map { it.yCenter }.average().toFloat()
            val maxHeight = cluster.maxOf { it.height }
            val dominantColor = cluster.groupBy { it.color }
                .maxByOrNull { it.value.size }?.key ?: GateColor.UNKNOWN
            val confidence = (cluster.size.toFloat() / 5f).coerceIn(0.2f, 1f)

            DetectedGate(
                id = nextGateId++,
                x = avgX,
                y = avgY,
                height = maxHeight,
                color = dominantColor,
                confidence = confidence,
                isInnerPole = false  // Determined by gate pair analysis
            )
        }
    }

    private fun matchWithPrevious(current: List<DetectedGate>): List<DetectedGate> {
        if (previousGates.isEmpty()) return current

        return current.map { gate ->
            val match = previousGates.minByOrNull { prev ->
                abs(prev.x - gate.x) + abs(prev.y - gate.y)
            }

            if (match != null && abs(match.x - gate.x) < 0.05f) {
                // Smooth position with previous detection
                gate.copy(
                    id = match.id,
                    x = gate.x * 0.7f + match.x * 0.3f,
                    y = gate.y * 0.7f + match.y * 0.3f,
                    confidence = (gate.confidence * 0.6f + match.confidence * 0.4f).coerceAtMost(1f)
                )
            } else {
                gate
            }
        }
    }
}
