package com.gateshot.processing.stabilize

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs

/**
 * The live L1 planner (ADMM) against an LP solution of the same sliding-window
 * problem (scipy HiGHS) on real 20x gyro paths: a pan and a still clip, both
 * axes (build/qa/stab_m6/pan/l1ref). Also a synthetic still -> pan -> still path.
 */
class L1PathPlannerTest {
    private fun objective(p: DoubleArray): Double {
        var s = 0.0
        for (i in 0 until p.size - 1) s += 10 * abs(p[i + 1] - p[i])
        for (i in 0 until p.size - 2) s += 1 * abs(p[i] - 2 * p[i + 1] + p[i + 2])
        for (i in 0 until p.size - 3) s += 100 * abs(-p[i] + 3 * p[i + 1] - 3 * p[i + 2] + p[i + 3])
        return s
    }

    private fun run(c: DoubleArray, margin: Double, look: Int = 30): DoubleArray {
        val pl = L1PathPlanner(look)
        val out = DoubleArray(c.size) { i -> pl.next(c.copyOfRange(i, minOf(c.size, i + look + 1)), margin) }
        println("   (max simplex iterations per window: ${pl.maxIterations}; exits optimal ${pl.exitOptimal} unbounded ${pl.exitUnbounded}; windows with infeasible basics ${pl.infeasibleRows})")
        return out
    }

    @Test
    fun `synthetic still-pan-still gives clean segments`() {
        val rnd = java.util.Random(0)
        val c = DoubleArray(300) { t -> (if (t < 100) 0.0 else if (t < 200) (t - 100) * 0.5 else 50.0) + rnd.nextGaussian() * 2 }
        val p = run(c, 10.0)
        for (i in p.indices) assertTrue(abs(p[i] - c[i]) <= 10.0 + 1e-3, "margin at $i")
        val vStill = abs(p[60] - p[40]) / 20; val vPan = (p[170] - p[130]) / 40
        println("synthetic: still velocity $vStill px/frame, pan velocity $vPan (true 0.5)")
        assertTrue(vStill < 0.05 && abs(vPan - 0.5) < 0.1)
    }

    @Test
    fun `matches the LP solution on real gyro paths`() {
        val dir = File("../../build/qa/stab_m6/pan/l1ref")
        assumeTrue(dir.isDirectory)
        for (f in dir.listFiles()!!.filter { it.name.endsWith(".csv") }.sorted()) {
            val lines = f.readLines()
            val margin = lines[0].toDouble()
            val rows = lines.drop(1).map { l -> l.split(',').map { it.toDouble() } }
            val c = DoubleArray(rows.size) { rows[it][0] }
            val ref = DoubleArray(rows.size) { rows[it][1] }
            val t0 = System.nanoTime()
            val p = run(c, margin)
            val ms = (System.nanoTime() - t0) / 1e6 / c.size
            var maxViol = 0.0; var maxDiff = 0.0
            for (i in p.indices) { maxViol = maxOf(maxViol, abs(p[i] - c[i]) - margin); maxDiff = maxOf(maxDiff, abs(p[i] - ref[i])) }
            val oK = objective(p); val oR = objective(ref)
            println("${f.name}: objective kotlin ${"%.0f".format(oK)} vs LP ${"%.0f".format(oR)} (${"%+.1f".format(100 * (oK / oR - 1))}%), max |diff| ${"%.1f".format(maxDiff)} px, margin violation ${"%.2f".format(maxViol)}, ${"%.2f".format(ms)} ms/frame")
            assertTrue(maxViol < 0.5, "margin violated")
            assertTrue(oK < oR * 1.10, "objective more than 10% worse than LP")
        }
    }
}
