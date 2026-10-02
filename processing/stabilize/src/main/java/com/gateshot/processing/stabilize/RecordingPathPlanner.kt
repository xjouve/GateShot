package com.gateshot.processing.stabilize

import kotlin.math.abs
import kotlin.math.exp

/**
 * Look-ahead path planning for the recording's second stabilization stage
 * (pure maths, one instance per axis). Frames arrive with the leftover motion
 * of the buffered picture (px, frame k relative to k-1); a frame is planned
 * once [lookahead] later frames are known.
 *
 * 1. Gaussian look-ahead smoothing of the path (removes what the gyro misses
 *    and re-smooths the gyro stage's pans).
 * 2. A "hold" on that smoothed path: stays put while the camera is held still,
 *    moves with a pan (detected from the centred slope, so it sees a pan coming
 *    before it starts) and releases progressively before the margin is reached.
 *
 * Tuned by end-to-end replay of real handheld 10x/20x clips
 * (build/qa/stab_m3/ab4/combo5.py); [GlobalShiftRealFramesTest]-style replay
 * tests pin this port to that simulation.
 */
class RecordingPathPlanner(
    private val lookahead: Int = 12,
    private val sigma: Double = 8.0,
    fps: Double = 30.0,
    holdHz: Double = 0.05,
    panTauSec: Double = 0.15,
    private val panStart: Double = Math.toRadians(0.15),
    private val panFull: Double = Math.toRadians(0.35),
    private val stiff: Double = 0.3,
    /**
     * Long-term hold on the image-measured path. OFF in the app: measured on the
     * phone (2026-09-28, known-signal test) the per-frame measurement error is
     * 0.4-0.6 px, and the hold sums it over seconds into a random walk -- the
     * picture drifted "by itself". Holding is left to the gyro, which does not
     * drift like that; this stage then only does the +/-lookahead smoothing.
     */
    private val holdEnabled: Boolean = true,
) {
    private val fps = fps
    private val aHold = 1 - exp(-2 * Math.PI * holdHz / fps)
    private val aPan = 1 - exp(-1.0 / (panTauSec * fps))
    private val shifts = HashMap<Long, Double>()
    private var path = 0.0
    private var hold = 0.0
    private var prevQ = 0.0
    private var first = true

    fun reset() { shifts.clear(); path = 0.0; first = true }

    /** Leftover motion of frame [k] relative to k-1 (px); missing frames count as 0. */
    fun addShift(k: Long, px: Double) { shifts[k] = px }

    /**
     * Sampling offset (px, same axis) for frame [i], given frames up to [last]
     * are known. [pxPerRad] converts path px to camera angle (pan detection);
     * [marginPx] is the offset budget. Call for consecutive i.
     */
    fun plan(i: Long, last: Long, pxPerRad: Double, marginPx: Double): Double {
        var sw = 1.0; var s = 0.0
        var r = 0.0; var hiR = 0.0; var hiK = i
        for (k in i + 1..minOf(last, i + lookahead)) {
            r += shifts[k] ?: 0.0
            val w = weight(k - i); sw += w; s += w * r
            hiR = r; hiK = k
        }
        r = 0.0
        var loR = 0.0; var loK = i
        for (k in i - 1 downTo maxOf(0L, i - lookahead)) {
            r -= shifts[k + 1] ?: 0.0
            val w = weight(k - i); sw += w; s += w * r
            loR = r; loK = k
        }
        path += shifts[i] ?: 0.0
        shifts.remove(i - lookahead - 1)
        val q = path + s / sw
        val v = abs(hiR - loR) / maxOf(1L, hiK - loK) * fps / pxPerRad
        val x = ((v - panStart) / (panFull - panStart)).coerceIn(0.0, 1.0)
        val kPan = x * x * (3 - 2 * x)
        if (first) { hold = q; prevQ = q; first = false }
        hold += kPan * (q - prevQ)
        var a = aHold + (aPan - aHold) * kPan
        val e = abs(q - hold) / marginPx
        if (e > stiff) a = maxOf(a, minOf(1.0, (e - stiff) / (1 - stiff)))
        hold += a * (q - hold)
        prevQ = q
        val target = if (holdEnabled) hold else q
        return (path - target).coerceIn(-marginPx, marginPx)
    }

    private fun weight(d: Long): Double = exp(-0.5 * (d / sigma) * (d / sigma))
}
