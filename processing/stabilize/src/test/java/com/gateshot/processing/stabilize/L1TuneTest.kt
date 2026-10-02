package com.gateshot.processing.stabilize

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs

class L1TuneTest {
    private fun objective(p: DoubleArray): Double {
        var s = 0.0
        for (i in 0 until p.size - 1) s += 10 * abs(p[i + 1] - p[i])
        for (i in 0 until p.size - 2) s += 1 * abs(p[i] - 2 * p[i + 1] + p[i + 2])
        for (i in 0 until p.size - 3) s += 100 * abs(-p[i] + 3 * p[i + 1] - 3 * p[i + 2] + p[i + 3])
        return s
    }
    @Test
    fun tune() {
        val f = File("../../build/qa/stab_m6/pan/l1ref/ois_on_side.csv"); assumeTrue(f.exists())
        val lines = f.readLines(); val margin = lines[0].toDouble()
        val rows = lines.drop(1).map { l -> l.split(',').map { it.toDouble() } }
        val c = DoubleArray(rows.size) { rows[it][0] }; val ref = DoubleArray(rows.size) { rows[it][1] }
        println("LP objective ${"%.0f".format(objective(ref))}")
        for (rho in listOf(10.0, 30.0, 100.0, 300.0)) for (it in listOf(300, 1000)) {
            val pl = L1PathPlanner(30, rho = rho, iterations = it)
            val t0 = System.nanoTime()
            val p = DoubleArray(c.size) { i -> pl.next(c.copyOfRange(i, minOf(c.size, i + 31)), margin) }
            val ms = (System.nanoTime() - t0) / 1e6 / c.size
            var d = 0.0; for (i in p.indices) d = maxOf(d, abs(p[i] - ref[i]))
            println("rho $rho it $it: objective ${"%.0f".format(objective(p))} max|diff| ${"%.1f".format(d)} ${"%.2f".format(ms)} ms/frame")
        }
    }
}
