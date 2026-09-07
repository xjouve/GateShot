package com.gateshot.coaching.pose

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure, Android-free math used by [TechniqueAnalyzer]: joint angles,
 * lean/tilt angles, percentile statistics, travel-direction sign, and
 * crop-rect geometry for the localize-then-track pose pipeline.
 *
 * Mirrors the angle conventions of [PoseEstimationModule]'s private
 * computeAngle/computeTorsoLean/computeShoulderRotation, generalized to
 * plain [Point]s so it is unit-testable on the JVM without Android/Robolectric.
 */

const val MIN_KEYPOINT_CONFIDENCE = 0.2f
const val MIN_TRACK_CONFIDENCE = 0.3f

data class Point(val x: Float, val y: Float)

/** Interior angle at [b] formed by rays b->a and b->c, in degrees [0, 180]. */
fun angleAt(a: Point, b: Point, c: Point): Float {
    val baX = a.x - b.x; val baY = a.y - b.y
    val bcX = c.x - b.x; val bcY = c.y - b.y
    val magBA = sqrt(baX * baX + baY * baY)
    val magBC = sqrt(bcX * bcX + bcY * bcY)
    if (magBA == 0f || magBC == 0f) return 0f
    val cos = ((baX * bcX + baY * bcY) / (magBA * magBC)).coerceIn(-1f, 1f)
    return (acos(cos.toDouble()) * 180.0 / PI).toFloat()
}

/** Lean of the bottom->top segment vs. vertical, in degrees; 0 = perfectly vertical. */
fun leanAngleDeg(topX: Float, topY: Float, bottomX: Float, bottomY: Float): Float {
    val dx = topX - bottomX
    val dy = topY - bottomY
    return (atan2(dx.toDouble(), -dy.toDouble()) * 180.0 / PI).toFloat()
}

/** Tilt of the left->right line vs. horizontal, in degrees; 0 = perfectly horizontal. */
fun tiltAngleDeg(leftX: Float, leftY: Float, rightX: Float, rightY: Float): Float {
    val dx = rightX - leftX
    val dy = rightY - leftY
    return (atan2(dy.toDouble(), dx.toDouble()) * 180.0 / PI).toFloat()
}

fun distance(ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = ax - bx; val dy = ay - by
    return sqrt(dx * dx + dy * dy)
}

data class MetricStatsRaw(val min: Float, val max: Float, val mean: Float, val p10: Float, val p90: Float)

/** Linear-interpolation percentile (p in [0, 100]) over an already-sorted list. */
fun percentile(sorted: List<Float>, p: Float): Float {
    if (sorted.isEmpty()) return 0f
    if (sorted.size == 1) return sorted[0]
    val idx = (p / 100f) * (sorted.size - 1)
    val lo = floor(idx).toInt().coerceIn(0, sorted.size - 1)
    val hi = ceil(idx).toInt().coerceIn(0, sorted.size - 1)
    if (lo == hi) return sorted[lo]
    val frac = idx - lo
    return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
}

fun computeStats(values: List<Float>): MetricStatsRaw? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    return MetricStatsRaw(
        min = sorted.first(),
        max = sorted.last(),
        mean = values.average().toFloat(),
        p10 = percentile(sorted, 10f),
        p90 = percentile(sorted, 90f)
    )
}

/**
 * Sign of net horizontal travel over the last [lookback] samples of x
 * positions (earliest-first list): +1 moving toward increasing x, -1 toward
 * decreasing x. Defaults to +1 when there isn't enough history to tell.
 */
fun travelDirectionSign(xs: List<Float>, lookback: Int = 5): Float {
    if (xs.size < 2) return 1f
    val start = max(0, xs.size - 1 - lookback)
    val dx = xs.last() - xs[start]
    return if (dx < 0f) -1f else 1f
}

data class CropRect(val x: Float, val y: Float, val width: Float, val height: Float)

/**
 * A square crop of side [side] centered at ([centerX], [centerY]), clamped to
 * stay fully inside a [frameWidth] x [frameHeight] frame. The side is first
 * coerced into [minSide, min(frameWidth, frameHeight)].
 */
fun clampSquareCrop(
    centerX: Float,
    centerY: Float,
    side: Float,
    frameWidth: Float,
    frameHeight: Float,
    minSide: Float = 1f
): CropRect {
    val maxSide = max(1f, min(frameWidth, frameHeight))
    val s = side.coerceIn(min(minSide, maxSide), maxSide)
    val x = (centerX - s / 2f).coerceIn(0f, frameWidth - s)
    val y = (centerY - s / 2f).coerceIn(0f, frameHeight - s)
    return CropRect(x, y, s, s)
}

/** Whether a candidate skeleton plausibly frames a whole standing person. */
fun isPlausiblePerson(hipDetected: Boolean, kneeOrAnkleDetected: Boolean, heightFraction: Float): Boolean =
    hipDetected && kneeOrAnkleDetected && heightFraction in 0.15f..0.9f
