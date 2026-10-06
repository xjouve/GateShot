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

    /**
     * Fast-pan pairs (build/qa/stab_m9/g1/pairs.py): shifts of 15-35 px at 256, beyond the
     * +/-24 px a search around zero reaches, with motion blur. Seeded with a prediction that
     * is 15% too long, the estimator must still find them.
     */
    @Test
    fun `finds a fast pan shift when seeded with the gyro prediction`() {
        val dir = File("../../build/qa/stab_m9/g1/pairs")
        assumeTrue(dir.isDirectory)
        val est = GlobalShiftEstimator(256)
        var worstSeeded = 0.0; var se = 0.0; var n = 0; var unseededLost = 0
        for (line in File(dir, "ref.csv").readLines()) {
            val v = line.split(',').map { it.toFloat() }
            val a = load(File(dir, "${v[0].toInt()}_a.bin")); val b = load(File(dir, "${v[0].toInt()}_b.bin"))
            val s = est.estimate(a, b, v[3], v[4])
            val u = est.estimate(a, b)
            val e = Math.hypot((s.dx - v[1]).toDouble(), (s.dy - v[2]).toDouble())
            val eu = Math.hypot((u.dx - v[1]).toDouble(), (u.dy - v[2]).toDouble())
            println("pair ${v[0].toInt()}: truth (${"%.2f".format(v[1])}, ${"%.2f".format(v[2])}) seeded error ${"%.2f".format(e)} inl ${"%.2f".format(s.inlierFraction)} | unseeded error ${"%.2f".format(eu)} inl ${"%.2f".format(u.inlierFraction)}")
            worstSeeded = maxOf(worstSeeded, e); se += e * e; n++
            if (eu > 1.0) unseededLost++
        }
        println("seeded: rms error ${"%.3f".format(Math.sqrt(se / n))} px, worst ${"%.3f".format(worstSeeded)} px; unseeded lost $unseededLost of $n")
        org.junit.jupiter.api.Assertions.assertTrue(worstSeeded < 0.5, "worst seeded error $worstSeeded px")
    }

    /** The viewfinder's cheap estimate against the full one: accuracy on real handheld pairs
     *  and on the fast-pan pairs (seed 15% long), and its cost relative to the full estimate. */
    @Test
    fun `fast estimate stays close to the full one`() {
        val real = File("../../build/qa/stab_m2/ab3/pairs"); val pan = File("../../build/qa/stab_m9/g1/pairs")
        assumeTrue(real.isDirectory && pan.isDirectory)
        val est = GlobalShiftEstimator(256)
        val pairs = File(pan, "ref.csv").readLines().map { it.split(',').map { v -> v.toFloat() } }
        val a0 = load(File(pan, "0_a.bin")); val b0 = load(File(pan, "0_b.bin")); val v0 = pairs[0]
        repeat(30) { est.estimate(a0, b0, v0[3], v0[4]) }
        var t0 = System.nanoTime(); repeat(60) { est.estimate(a0, b0, v0[3], v0[4]) }; val full = (System.nanoTime() - t0) / 60e6
        var worst = 0.0; var worstPan = 0.0
        for ((hi, fi) in listOf(8 to 0, 8 to 1)) {
            var se = 0.0; var n = 0; var w = 0.0
            for (line in File(real, "ref.csv").readLines()) {
                val i = line.split(',')[0].toFloat().toInt()
                val a = load(File(real, "${i}_a.bin")); val b = load(File(real, "${i}_b.bin"))
                val f = est.estimate(a, b); val q = est.estimateFast(a, b, 0f, 0f, hi, fi)
                val e = Math.hypot((q.dx - f.dx).toDouble(), (q.dy - f.dy).toDouble())
                se += e * e; n++; w = maxOf(w, e)
            }
            var wp = 0.0; var sp = 0.0
            for (v in pairs) {
                val s = est.estimateFast(load(File(pan, "${v[0].toInt()}_a.bin")), load(File(pan, "${v[0].toInt()}_b.bin")), v[3], v[4], hi, fi)
                val e = Math.hypot((s.dx - v[1]).toDouble(), (s.dy - v[2]).toDouble()); wp = maxOf(wp, e); sp += e * e
            }
            repeat(30) { est.estimateFast(a0, b0, v0[3], v0[4], hi, fi) }
            t0 = System.nanoTime(); repeat(60) { est.estimateFast(a0, b0, v0[3], v0[4], hi, fi) }; val fast = (System.nanoTime() - t0) / 60e6
            println("half $hi full $fi: real pairs vs full estimate rms ${"%.3f".format(Math.sqrt(se / n))} worst ${"%.3f".format(w)} px | fast-pan pairs rms ${"%.3f".format(Math.sqrt(sp / pairs.size))} worst ${"%.3f".format(wp)} px | cost ${"%.2f".format(fast)} ms = ${"%.0f".format(100 * fast / full)}% of full (${"%.2f".format(full)} ms)")
            if (fi == 0) { worst = w; worstPan = wp }
        }
        org.junit.jupiter.api.Assertions.assertTrue(worst < 0.15 && worstPan < 0.5, "fast estimate off: real $worst px, pan $worstPan px")
    }
}
