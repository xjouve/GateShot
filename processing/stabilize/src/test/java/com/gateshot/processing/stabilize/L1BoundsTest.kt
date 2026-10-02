package com.gateshot.processing.stabilize

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs

/** Windows whose allowed band does NOT contain the camera path (self-centred buffer), vs the LP. */
class L1BoundsTest {
    @Test
    fun `asymmetric bands match the LP`() {
        val dir = File("../../build/qa/stab_m6/pan/l1asym"); assumeTrue(dir.isDirectory)
        var worst = 0.0
        for (f in dir.listFiles()!!.sorted()) {
            val lines = f.readLines(); val refObj = lines[0].toDouble()
            val rows = lines.drop(1).map { l -> l.split(',').map { it.toDouble() } }
            val c = DoubleArray(rows.size) { rows[it][0] }; val lo = DoubleArray(rows.size) { rows[it][1] }; val hi = DoubleArray(rows.size) { rows[it][2] }
            val pl = L1PathPlanner(30)
            pl.nextBounded(c, lo, hi)
            val p = pl.lastWindow()
            var obj = 0.0
            for (i in 0 until p.size - 1) obj += 10 * abs(p[i + 1] - p[i])
            for (i in 0 until p.size - 2) obj += abs(p[i] - 2 * p[i + 1] + p[i + 2])
            for (i in 0 until p.size - 3) obj += 100 * abs(-p[i] + 3 * p[i + 1] - 3 * p[i + 2] + p[i + 3])
            for (i in p.indices) obj += 0.01 * abs(p[i] - c[i])
            var viol = 0.0; for (i in p.indices) viol = maxOf(viol, lo[i] - p[i], p[i] - hi[i])
            worst = maxOf(worst, obj / refObj - 1)
            assertTrue(viol < 1e-6, "${f.name} bound violated by $viol")
            assertTrue(obj <= refObj * 1.001 + 1e-6, "${f.name}: objective $obj vs LP $refObj")
        }
        println("asymmetric windows: worst objective gap ${"%.5f".format(worst * 100)}%")
    }
}
