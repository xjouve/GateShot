package com.gateshot.processing.stabilize

import java.io.File

/**
 * Gyroscope track: timestamped angular velocity (rad/s) on the
 * elapsedRealtimeNanos / BOOTTIME clock, integrated to a cumulative orientation
 * angle per axis. Port of the integration + interpolation in `hybrid.py`.
 */
internal class GyroTrack(
    val t: LongArray,
    private val gx: FloatArray,
    private val gy: FloatArray,
    private val gz: FloatArray
) {
    val size get() = t.size

    // Cumulative integrated angle (rad) per axis: ang[i] = ang[i-1] + g[i-1]*dt.
    private val ang: Array<DoubleArray> = Array(3) { DoubleArray(t.size) }

    init {
        val g = arrayOf(gx, gy, gz)
        for (a in 0 until 3) {
            for (i in 1 until t.size) {
                val dt = (t[i] - t[i - 1]) / 1e9
                ang[a][i] = ang[a][i - 1] + g[a][i - 1] * dt
            }
        }
    }

    /** Linearly interpolate the integrated angle of [axis] at absolute time [tNs]. */
    fun angleAt(tNs: Double, axis: Int): Double {
        val arr = ang[axis]
        val n = t.size
        if (n == 0) return 0.0
        if (tNs <= t[0]) return arr[0]
        if (tNs >= t[n - 1]) return arr[n - 1]
        // Binary search for the bracketing samples.
        var lo = 0; var hi = n - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (t[mid].toDouble() <= tNs) lo = mid else hi = mid
        }
        val t0 = t[lo].toDouble(); val t1 = t[hi].toDouble()
        val f = if (t1 > t0) (tNs - t0) / (t1 - t0) else 0.0
        return arr[lo] + f * (arr[hi] - arr[lo])
    }

    companion object {
        /** Parse a `<clip>_gyro.csv` (header `t_ns,gx,gy,gz`). */
        fun fromCsv(file: File): GyroTrack {
            val ts = ArrayList<Long>(8192)
            val xs = ArrayList<Float>(8192)
            val ys = ArrayList<Float>(8192)
            val zs = ArrayList<Float>(8192)
            file.bufferedReader().useLines { lines ->
                var first = true
                for (line in lines) {
                    if (first) { first = false; continue }  // header
                    if (line.isBlank()) continue
                    val p = line.split(',')
                    if (p.size < 4) continue
                    ts.add(p[0].trim().toLong())
                    xs.add(p[1].trim().toFloat())
                    ys.add(p[2].trim().toFloat())
                    zs.add(p[3].trim().toFloat())
                }
            }
            return GyroTrack(ts.toLongArray(), xs.toFloatArray(), ys.toFloatArray(), zs.toFloatArray())
        }
    }
}
