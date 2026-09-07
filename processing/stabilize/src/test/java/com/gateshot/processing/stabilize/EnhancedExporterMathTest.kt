package com.gateshot.processing.stabilize

import com.gateshot.processing.stabilize.EnhancedExporter.Companion.computeJitterChangePercent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * JVM tests for the pure math behind the export self-check badge: percent
 * jitter change between source and output, normalized for the crop's
 * magnification of identical physical motion.
 */
class EnhancedExporterMathTest {

    @Test
    fun `halved jitter reports negative fifty percent`() {
        // Output crop is 1x here so the raw measurement is the normalized one.
        val pct = computeJitterChangePercent(srcJitter = 10f, outJitterRaw = 5f, cropFactor = 1f)
        assertEquals(-50, pct)
    }

    @Test
    fun `identical jitter after normalizing crop reports zero change`() {
        // A 1.15x crop magnifies identical physical motion by 1.15x in pixels;
        // normalizing outJitterRaw by cropFactor should recover ~0% change.
        val cropFactor = 1.15f
        val pct = computeJitterChangePercent(
            srcJitter = 10f,
            outJitterRaw = 10f * cropFactor,
            cropFactor = cropFactor
        )
        assertEquals(0, pct)
    }

    @Test
    fun `doubled jitter reports positive change (worse, not silently clamped)`() {
        val pct = computeJitterChangePercent(srcJitter = 10f, outJitterRaw = 20f, cropFactor = 1f)
        assertEquals(100, pct)
    }

    @Test
    fun `near-zero source jitter returns null instead of a meaningless ratio`() {
        val pct = computeJitterChangePercent(srcJitter = 0.0005f, outJitterRaw = 3f, cropFactor = 1.15f)
        assertNull(pct)
    }

    @Test
    fun `zero source jitter returns null`() {
        val pct = computeJitterChangePercent(srcJitter = 0f, outJitterRaw = 0f, cropFactor = 1.15f)
        assertNull(pct)
    }
}
