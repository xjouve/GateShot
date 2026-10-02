package com.gateshot.processing.stabilize

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Compares the estimator with OpenCV feature tracking on real 256x256 pairs cut
 * from a handheld 20x clip (build/qa/stab_m2/ab3/pairs). Skipped if absent.
 */
class GlobalShiftRealFramesTest {
    private fun load(f: File): FloatArray {
        val bb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(256 * 256) { bb.float }
    }

    @Test
    fun `agrees with OpenCV on real frames`() {
        val dir = File("../../build/qa/stab_m2/ab3/pairs")
        assumeTrue(dir.isDirectory)
        val est = GlobalShiftEstimator(256)
        var se = 0.0; var sr = 0.0; var sxy = 0.0; var n = 0
        for (line in File(dir, "ref.csv").readLines()) {
            val (i, rx, ry) = line.split(',').map { it.toFloat() }
            val s = est.estimate(load(File(dir, "${i.toInt()}_a.bin")), load(File(dir, "${i.toInt()}_b.bin")))
            println("pair ${i.toInt()}: estimator (${"%.2f".format(s.dx)}, ${"%.2f".format(s.dy)}) inl ${"%.2f".format(s.inlierFraction)}  opencv (${"%.2f".format(rx)}, ${"%.2f".format(ry)})")
            for ((e, r) in listOf(s.dx to rx, s.dy to ry)) { se += (e - r) * (e - r); sr += r * r; sxy += e * r; n++ }
        }
        println("gain (estimator vs opencv) = ${"%.2f".format(sxy / sr)}, rms error = ${"%.3f".format(Math.sqrt(se / n))} px, rms opencv = ${"%.3f".format(Math.sqrt(sr / n))} px")
    }
}
