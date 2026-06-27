package com.gateshot.processing.stabilize

/** Auto-calibration result: which gyro axis drives each image axis, its signed
 *  px/rad scale, the gyro<->video sync offset, and the regression quality. */
internal data class CalibResult(
    val offsetNs: Double,
    val axX: Int, val sx: Double, val r2x: Double,
    val axY: Int, val sy: Double, val r2y: Double
)

/**
 * Calibrates the gyro->pixel mapping against measured optical motion and finds
 * the gyro<->video time offset — the part of `hybrid.py` that removes the need
 * for per-frame capture timestamps (the ±80 ms sync search absorbs the unknown
 * phase between the frame clock and the BOOTTIME gyro clock).
 */
internal class Calibrator(private val gyro: GyroTrack) {

    /** Per-frame integrated angle (rad) at frame times [ft] shifted by [offNs],
     *  relative to the first frame. Shape [nFrames][3]. */
    fun frameAngles(ft: DoubleArray, offNs: Double): Array<DoubleArray> {
        val fa = Array(ft.size) { f ->
            DoubleArray(3) { a -> gyro.angleAt(ft[f] + offNs, a) }
        }
        if (fa.isEmpty()) return fa
        val ref = fa[0].copyOf()
        for (f in fa.indices) for (a in 0 until 3) fa[f][a] -= ref[a]
        return fa
    }

    /** Best single-axis least-squares fit of [meas] onto a column of [dfa]
     *  (the per-frame angle differences). Returns (axis, scale, r2). */
    fun bestAxis(dfa: Array<DoubleArray>, meas: DoubleArray): Triple<Int, Double, Double> {
        var bestAxis = 0; var bestScale = 0.0; var bestR2 = -1.0
        val mean = if (meas.isNotEmpty()) meas.average() else 0.0
        var ssTot = 0.0
        for (m in meas) ssTot += (m - mean) * (m - mean)
        ssTot = maxOf(ssTot, 1e-9)
        for (a in 0 until 3) {
            var dot = 0.0; var xx = 0.0
            for (i in meas.indices) { dot += dfa[i][a] * meas[i]; xx += dfa[i][a] * dfa[i][a] }
            if (xx < 1e-12) continue
            val s = dot / xx
            var sse = 0.0
            for (i in meas.indices) { val r = meas[i] - s * dfa[i][a]; sse += r * r }
            val r2 = 1.0 - sse / ssTot
            if (r2 > bestR2) { bestR2 = r2; bestScale = s; bestAxis = a }
        }
        return Triple(bestAxis, bestScale, bestR2)
    }

    /** Full calibration: sync-offset search then per-axis fit. [mdx]/[mdy] are
     *  the measured inter-frame optical shifts (length nFrames-1). */
    fun calibrate(ft: DoubleArray, mdx: DoubleArray, mdy: DoubleArray): CalibResult {
        var bestOff = 0.0; var bestScore = -1e9
        var off = -80.0
        while (off <= 80.0) {
            val dfa = diff(frameAngles(ft, off * 1e6))
            val n = minOf(mdx.size, dfa.size)
            if (n > 1) {
                val rx = bestAxis(dfa.copyOfRange(0, n), mdx.copyOf(n)).third
                val ry = bestAxis(dfa.copyOfRange(0, n), mdy.copyOf(n)).third
                if (rx + ry > bestScore) { bestScore = rx + ry; bestOff = off }
            }
            off += 2.0
        }
        val offNs = bestOff * 1e6
        val dfa = diff(frameAngles(ft, offNs))
        val nf = minOf(mdx.size, dfa.size)
        val (axX, sx, r2x) = bestAxis(dfa.copyOfRange(0, nf), mdx.copyOf(nf))
        val (axY, sy, r2y) = bestAxis(dfa.copyOfRange(0, nf), mdy.copyOf(nf))
        return CalibResult(offNs, axX, sx, r2x, axY, sy, r2y)
    }

    private fun diff(fa: Array<DoubleArray>): Array<DoubleArray> =
        Array(maxOf(0, fa.size - 1)) { i ->
            DoubleArray(3) { a -> fa[i + 1][a] - fa[i][a] }
        }
}
