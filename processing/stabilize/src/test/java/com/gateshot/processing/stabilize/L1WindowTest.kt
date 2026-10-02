package com.gateshot.processing.stabilize

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

class L1WindowTest {
    @Test
    fun window() {
        val f = File("../../build/qa/stab_m6/pan/l1ref/window_dbg.csv"); assumeTrue(f.exists())
        val rows = f.readLines().map { l -> l.split(',').map { it.toDouble() } }
        val c = DoubleArray(rows.size) { rows[it][0] }; val m = rows[0][1]
        val pl = L1PathPlanner(30)
        val p0 = pl.next(c, m)
        println("commit $p0 iterations ${pl.lastIterations} exitsOptimal ${pl.exitOptimal} unbounded ${pl.exitUnbounded} infeasible ${pl.infeasibleRows}")
        println("C range ${c.min()}..${c.max()} margin $m ; window n=${c.size}")
    }
}
