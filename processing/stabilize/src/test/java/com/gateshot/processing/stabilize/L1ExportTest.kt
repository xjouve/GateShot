package com.gateshot.processing.stabilize

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Writes Kotlin live-L1 paths for the bench clips (full length) so the pixel bench can score them. */
class L1ExportTest {
    @Test
    fun export() {
        val dir = File("../../build/qa/stab_m6/pan/l1in"); assumeTrue(dir.isDirectory)
        for (f in dir.listFiles()!!.filter { it.name.endsWith(".csv") }) {
            val lines = f.readLines(); val margin = lines[0].toDouble()
            val c = lines.drop(1).map { it.toDouble() }.toDoubleArray()
            for ((tag, rho, it) in listOf(Triple("ipm", 1.0, 1))) {
                val pl = L1PathPlanner(30, rho = rho, iterations = it)
                val p = DoubleArray(c.size) { i -> pl.next(c.copyOfRange(i, minOf(c.size, i + 31)), margin) }
                File(dir.parentFile, "l1out").mkdirs()
                File(File(dir.parentFile, "l1out"), f.name.replace(".csv", "_$tag.csv")).writeText(p.joinToString("\n"))
            }
        }
    }
}
