package com.gateshot.processing.stabilize

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs the app's own estimator on the pixel test bench's gyro-warped frames
 * (build/qa/stab_m5/ois/kt) and compares with OpenCV on the same crops.
 */
class BenchReplayTest {
    private fun load(f: File): FloatArray {
        val bb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(256 * 256) { bb.float }
    }

    @Test
    fun `estimator on bench frames`() {
        val dir = File("../../build/qa/stab_m5/ois/kt")
        assumeTrue(dir.isDirectory)
        val ref = File(dir, "opencv_shifts.csv").readLines().map { l -> l.split(',').map { it.toDouble() } }
        val est = GlobalShiftEstimator(256)
        val out = StringBuilder("dx,dy,inl\n")
        var prev = load(File(dir, "0.bin"))
        var se = 0.0; var sr = 0.0; var sxy = 0.0; var n = 0
        for (i in 1..ref.size) {
            val cur = load(File(dir, "$i.bin"))
            val s = est.estimate(prev, cur)
            val dx = s.dx * 3.0; val dy = s.dy * 3.0
            out.append("$dx,$dy,${s.inlierFraction}\n")
            val r = ref[i - 1]
            for ((e, rr) in listOf(dx to r[0], dy to r[1])) { se += (e - rr) * (e - rr); sr += rr * rr; sxy += e * rr; n++ }
            prev = cur
        }
        File(dir, "kotlin_shifts.csv").writeText(out.toString())
        println("bench: gain ${"%.3f".format(sxy / sr)}, rms diff ${"%.3f".format(Math.sqrt(se / n))} px, rms motion ${"%.3f".format(Math.sqrt(sr / n))} px")
    }
}
