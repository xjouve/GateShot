package com.gateshot.processing.stabilize

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs

/**
 * Pins [RecordingPathPlanner] to the Python end-to-end simulation it was tuned
 * with: same leftover-motion input from real handheld clips (10x/20x still,
 * 20x pan; both axes), same sampling offsets out. Data: build/qa/stab_m3/ab4/planner.
 */
class RecordingPathPlannerReplayTest {
    @Test
    fun `matches the tuned simulation on real clips`() {
        val dir = File("../../build/qa/stab_m3/ab4/planner")
        assumeTrue(dir.isDirectory)
        val files = dir.listFiles { f -> f.name.endsWith(".csv") }!!.sorted()
        assumeTrue(files.isNotEmpty())
        for (f in files) {
            val lines = f.readLines()
            val (fo, mpx) = lines[0].split(',').map { it.toDouble() }
            val rows = lines.drop(1).map { l -> l.split(',').map { if (it == "nan") Double.NaN else it.toDouble() } }
            val planner = RecordingPathPlanner()
            rows.forEachIndexed { k, r -> if (!r[0].isNaN()) planner.addShift(k.toLong(), r[0]) }
            var maxErr = 0.0
            val last = (rows.size - 1).toLong()
            for (i in 0..last) {
                val got = planner.plan(i, last, fo, mpx)
                maxErr = maxOf(maxErr, abs(got - rows[i.toInt()][1]))
            }
            println("${f.name}: frames ${rows.size}, max |kotlin - python| = ${"%.4f".format(maxErr)} px")
            assertTrue(maxErr < 0.01, "${f.name}: max error $maxErr px")
        }
    }
}
