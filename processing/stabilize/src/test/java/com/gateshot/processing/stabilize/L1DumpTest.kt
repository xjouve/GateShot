package com.gateshot.processing.stabilize

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Dumps the Kotlin live-L1 committed path for the up-axis reference file (debugging aid). */
class L1DumpTest {
    @Test
    fun dump() {
        val f = File("../../build/qa/stab_m6/pan/l1ref/ois_on_up.csv"); assumeTrue(f.exists())
        val lines = f.readLines(); val margin = lines[0].toDouble()
        val c = lines.drop(1).map { it.split(',')[0].toDouble() }.toDoubleArray()
        val pl = L1PathPlanner(30)
        val p = DoubleArray(c.size) { i -> pl.next(c.copyOfRange(i, minOf(c.size, i + 31)), margin) }
        File(f.parentFile, "kotlin_up.csv").writeText(p.joinToString("\n"))
    }
}
