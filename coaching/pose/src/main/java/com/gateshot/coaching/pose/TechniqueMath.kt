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
 * lean/tilt angles, percentile statistics, travel-direction sign, crop-rect
 * geometry, pan-compensated motion-saliency racer localization, and strict
 * pose validity, all directly unit-testable on the JVM.
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

/** Same as [angleAt] but null unless every keypoint is present. */
fun angleOrNull(a: Point?, b: Point?, c: Point?): Float? =
    if (a != null && b != null && c != null) angleAt(a, b, c) else null

/** Lean of the bottom->top segment vs. vertical, in degrees; 0 = perfectly vertical. */
fun leanAngleDeg(topX: Float, topY: Float, bottomX: Float, bottomY: Float): Float {
    val dx = topX - bottomX
    val dy = topY - bottomY
    return (atan2(dx.toDouble(), -dy.toDouble()) * 180.0 / PI).toFloat()
}

fun leanOrNull(top: Point?, bottom: Point?): Float? =
    if (top != null && bottom != null) leanAngleDeg(top.x, top.y, bottom.x, bottom.y) else null

/** Tilt of the left->right line vs. horizontal, in degrees; 0 = perfectly horizontal. */
fun tiltAngleDeg(leftX: Float, leftY: Float, rightX: Float, rightY: Float): Float {
    val dx = rightX - leftX
    val dy = rightY - leftY
    return (atan2(dy.toDouble(), dx.toDouble()) * 180.0 / PI).toFloat()
}

/** Wraps a degree value into [-90, 90] by adding/subtracting 180. */
fun wrapTo90(deg: Float): Float {
    var d = deg
    while (d > 90f) d -= 180f
    while (d < -90f) d += 180f
    return d
}

/**
 * Shoulder tilt, robust to a left/right keypoint swap: if the shoulders'
 * left/right x-order contradicts the hips' x-order, the shoulder points are
 * swapped before computing tilt, then the result is wrapped to [-90, 90] so
 * a swap reads as ~0 degrees instead of ~180.
 */
fun shoulderTiltDeg(
    lShoulderX: Float, lShoulderY: Float,
    rShoulderX: Float, rShoulderY: Float,
    lHipX: Float, rHipX: Float
): Float {
    val hipsLeftIsLeft = lHipX <= rHipX
    val shouldersLeftIsLeft = lShoulderX <= rShoulderX
    val raw = if (hipsLeftIsLeft == shouldersLeftIsLeft) {
        tiltAngleDeg(lShoulderX, lShoulderY, rShoulderX, rShoulderY)
    } else {
        tiltAngleDeg(rShoulderX, rShoulderY, lShoulderX, lShoulderY)
    }
    return wrapTo90(raw)
}

fun shoulderTiltOrNull(lShoulder: Point?, rShoulder: Point?, lHip: Point?, rHip: Point?): Float? {
    if (lShoulder == null || rShoulder == null || lHip == null || rHip == null) return null
    return shoulderTiltDeg(lShoulder.x, lShoulder.y, rShoulder.x, rShoulder.y, lHip.x, rHip.x)
}

fun distance(ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = ax - bx; val dy = ay - by
    return sqrt(dx * dx + dy * dy)
}

/** Ankle-spread / hip-width ratio, clamped to [0, 4]; null unless every input keypoint is present. */
fun stanceRatioOrNull(lAnkle: Point?, rAnkle: Point?, lHip: Point?, rHip: Point?): Float? {
    if (lAnkle == null || rAnkle == null || lHip == null || rHip == null) return null
    val hipDist = distance(lHip.x, lHip.y, rHip.x, rHip.y)
    if (hipDist <= 0f) return null
    return (distance(lAnkle.x, lAnkle.y, rAnkle.x, rAnkle.y) / hipDist).coerceIn(0f, 4f)
}

/** Signed forward/back wrist offset from hip center, normalized by person height and travel direction. */
fun handsForwardOrNull(
    lWrist: Point?, rWrist: Point?, lHip: Point?, rHip: Point?,
    personHeightPx: Float?, dirSign: Float
): Float? {
    if (lWrist == null && rWrist == null) return null
    if (lHip == null || rHip == null) return null
    if (personHeightPx == null || personHeightPx <= 0f) return null
    val meanWristX = listOfNotNull(lWrist?.x, rWrist?.x).average().toFloat()
    val hipCenterX = (lHip.x + rHip.x) / 2f
    return ((meanWristX - hipCenterX) / personHeightPx) * dirSign
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

/** Whether a candidate skeleton plausibly frames a whole standing person (legacy SEARCH-grid heuristic). */
fun isPlausiblePerson(hipDetected: Boolean, kneeOrAnkleDetected: Boolean, heightFraction: Float): Boolean =
    hipDetected && kneeOrAnkleDetected && heightFraction in 0.15f..0.9f

// ─────────────────────────────────────────────────────────────────────────
// Strict pose validity
// ─────────────────────────────────────────────────────────────────────────

data class ValidityInput(
    val overallConfidence: Float,
    val leftHipConf: Float,
    val rightHipConf: Float,
    val leftKneeConf: Float,
    val rightKneeConf: Float,
    val leftAnkleConf: Float,
    val rightAnkleConf: Float,
    val heightPx: Float,
    val widthPx: Float
)

/**
 * A sample is tracked only if: overall confidence >= 0.45, both hips >= 0.3,
 * both knees >= 0.3, at least one ankle >= 0.3, person height >= 24px, and
 * height >= 0.8 * width (rules out wide, non-standing-person blobs).
 */
fun isValidTrack(v: ValidityInput): Boolean {
    if (v.overallConfidence < 0.45f) return false
    if (v.leftHipConf < 0.3f || v.rightHipConf < 0.3f) return false
    if (v.leftKneeConf < 0.3f || v.rightKneeConf < 0.3f) return false
    if (v.leftAnkleConf < 0.3f && v.rightAnkleConf < 0.3f) return false
    if (v.heightPx < 24f) return false
    if (v.heightPx < 0.8f * v.widthPx) return false
    return true
}

// ─────────────────────────────────────────────────────────────────────────
// Pan-compensated motion saliency (racer localization)
// ─────────────────────────────────────────────────────────────────────────

/** Sum of each row across all columns; a profile indexed by y, used to estimate vertical pan. */
fun rowProjection(luma: FloatArray, width: Int, height: Int): FloatArray {
    val out = FloatArray(height)
    for (y in 0 until height) {
        var sum = 0f
        val base = y * width
        for (x in 0 until width) sum += luma[base + x]
        out[y] = sum
    }
    return out
}

/** Sum of each column across all rows; a profile indexed by x, used to estimate horizontal pan. */
fun colProjection(luma: FloatArray, width: Int, height: Int): FloatArray {
    val out = FloatArray(width)
    for (y in 0 until height) {
        val base = y * width
        for (x in 0 until width) out[x] += luma[base + x]
    }
    return out
}

fun zeroMean(profile: FloatArray): FloatArray {
    if (profile.isEmpty()) return profile
    val mean = profile.average().toFloat()
    return FloatArray(profile.size) { profile[it] - mean }
}

/**
 * Shift s in [-maxShift, maxShift] maximizing the cross-correlation of [a]
 * against [b] shifted by s (i.e. b[i + s] aligned to a[i]): the s such that
 * content at b[x] corresponds to a[x - s]. Both profiles should be zero-mean.
 */
fun bestShift1D(a: FloatArray, b: FloatArray, maxShift: Int): Int {
    if (a.isEmpty() || b.isEmpty()) return 0
    var bestS = 0
    var bestScore = Float.NEGATIVE_INFINITY
    for (s in -maxShift..maxShift) {
        var score = 0f
        var count = 0
        for (i in a.indices) {
            val j = i + s
            if (j in b.indices) {
                score += a[i] * b[j]
                count++
            }
        }
        if (count == 0) continue
        val normalized = score / count
        if (normalized > bestScore) {
            bestScore = normalized
            bestS = s
        }
    }
    return bestS
}

/**
 * Estimates the global camera pan (dx, dy) between two same-size luma frames
 * via zero-mean row/column projection correlation, searching +/- [maxShift]
 * pixels. Positive dx/dy means content in [curr] appears shifted toward
 * increasing x/y relative to [prev] (i.e. curr[x,y] ~= prev[x - dx, y - dy]).
 */
fun estimatePan(prev: FloatArray, curr: FloatArray, width: Int, height: Int, maxShift: Int = 40): Pair<Int, Int> {
    val dx = bestShift1D(zeroMean(colProjection(prev, width, height)), zeroMean(colProjection(curr, width, height)), maxShift)
    val dy = bestShift1D(zeroMean(rowProjection(prev, width, height)), zeroMean(rowProjection(curr, width, height)), maxShift)
    return dx to dy
}

/** Absolute difference between [curr] and [prev] sampled at (x - dx, y - dy); out-of-bounds samples read as zero diff. */
fun alignedAbsDiff(prev: FloatArray, curr: FloatArray, width: Int, height: Int, dx: Int, dy: Int): FloatArray {
    val out = FloatArray(width * height)
    for (y in 0 until height) {
        val sy = y - dy
        for (x in 0 until width) {
            val sx = x - dx
            val idx = y * width + x
            if (sx in 0 until width && sy in 0 until height) {
                out[idx] = kotlin.math.abs(curr[idx] - prev[sy * width + sx])
            } else {
                out[idx] = 0f
            }
        }
    }
    return out
}

/** Simple (2*radius+1)-square box blur (radius=2 => 5px). */
fun boxBlur(data: FloatArray, width: Int, height: Int, radius: Int = 2): FloatArray {
    val out = FloatArray(data.size)
    for (y in 0 until height) {
        for (x in 0 until width) {
            var sum = 0f
            var count = 0
            for (dy in -radius..radius) {
                val yy = y + dy
                if (yy < 0 || yy >= height) continue
                val base = yy * width
                for (dx in -radius..radius) {
                    val xx = x + dx
                    if (xx < 0 || xx >= width) continue
                    sum += data[base + xx]
                    count++
                }
            }
            out[y * width + x] = if (count > 0) sum / count else 0f
        }
    }
    return out
}

/** Values above mean + [sigmaMultiplier] * stddev keep their excess energy; everything else is zeroed. */
fun thresholdEnergyMap(data: FloatArray, sigmaMultiplier: Float = 2f): FloatArray {
    if (data.isEmpty()) return data
    val mean = data.average().toFloat()
    val variance = data.map { (it - mean) * (it - mean) }.average().toFloat()
    val std = sqrt(variance)
    val threshold = mean + sigmaMultiplier * std
    return FloatArray(data.size) { i -> if (data[i] > threshold) data[i] - threshold else 0f }
}

/** Summed-area table with a 1px zero border, for O(1) rectangle sums via [rectSum]. */
fun integralImage(data: FloatArray, width: Int, height: Int): FloatArray {
    val stride = width + 1
    val integral = FloatArray(stride * (height + 1))
    for (y in 0 until height) {
        var rowSum = 0f
        for (x in 0 until width) {
            rowSum += data[y * width + x]
            integral[(y + 1) * stride + (x + 1)] = integral[y * stride + (x + 1)] + rowSum
        }
    }
    return integral
}

/** Sum of the half-open rectangle [x0,x1) x [y0,y1) using an [integralImage] built for the given width. */
fun rectSum(integral: FloatArray, width: Int, x0: Int, y0: Int, x1: Int, y1: Int): Float {
    val stride = width + 1
    return integral[y1 * stride + x1] - integral[y0 * stride + x1] - integral[y1 * stride + x0] + integral[y0 * stride + x0]
}

data class SalientCandidate(val centerX: Float, val centerY: Float, val height: Float, val energy: Float, val score: Float)

/**
 * Slides windows of size w in [minW,maxW] / h in [minH,maxH] over [energyMap]
 * (via an integral image) maximizing summed thresholded energy. When a
 * previous position is known, candidates are penalised by distance from it
 * (score = energy / (1 + d / [penaltyDistancePx])) so tracking prefers
 * continuity over a brighter but distant blob. Returns the top [topK]
 * candidates by score, best first.
 */
fun findSalientCandidates(
    energyMap: FloatArray,
    width: Int,
    height: Int,
    prevX: Float? = null,
    prevY: Float? = null,
    minW: Int = 12,
    maxW: Int = 20,
    minH: Int = 16,
    maxH: Int = 40,
    widthStep: Int = 4,
    heightStep: Int = 6,
    stride: Int = 2,
    penaltyDistancePx: Float = 60f,
    topK: Int = 3
): List<SalientCandidate> {
    if (width <= 0 || height <= 0 || energyMap.isEmpty()) return emptyList()
    val integral = integralImage(energyMap, width, height)
    val candidates = mutableListOf<SalientCandidate>()
    var w = minW
    while (w <= maxW && w <= width) {
        var h = minH
        while (h <= maxH && h <= height) {
            var y = 0
            while (y + h <= height) {
                var x = 0
                while (x + w <= width) {
                    val energy = rectSum(integral, width, x, y, x + w, y + h)
                    if (energy > 0f) {
                        val cx = x + w / 2f
                        val cy = y + h / 2f
                        val score = if (prevX != null && prevY != null) {
                            val d = distance(cx, cy, prevX, prevY)
                            energy / (1f + d / penaltyDistancePx)
                        } else energy
                        candidates += SalientCandidate(cx, cy, h.toFloat(), energy, score)
                    }
                    x += stride
                }
                y += stride
            }
            h += heightStep
        }
        w += widthStep
    }
    return candidates.sortedByDescending { it.score }.take(topK)
}
