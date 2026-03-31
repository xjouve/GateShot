package com.gateshot.capture.preset

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DefaultPresetsTest {

    @Test
    fun `all presets have unique IDs`() {
        val ids = DefaultPresets.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `six presets defined`() {
        assertEquals(6, DefaultPresets.ALL.size)
    }

    @Test
    fun `BY_ID contains all presets`() {
        DefaultPresets.ALL.forEach { preset ->
            assertNotNull(DefaultPresets.BY_ID[preset.id])
        }
    }

    @Test
    fun `slalom preset has fast AF reacquisition`() {
        val preset = DefaultPresets.SLALOM_GS
        assertEquals(AfSpeed.FAST, preset.autofocus.reacquisitionSpeed)
        assertTrue(preset.autofocus.occlusionHold)
    }

    @Test
    fun `speed preset has maximum stabilization`() {
        val preset = DefaultPresets.SPEED
        assertEquals(OisMode.MAXIMUM, preset.stabilization.ois)
        assertEquals(EisMode.STANDARD, preset.stabilization.eis)
    }

    @Test
    fun `panning preset has panning EIS`() {
        val preset = DefaultPresets.PANNING
        assertEquals(EisMode.PANNING, preset.stabilization.eis)
    }

    @Test
    fun `finish preset has face priority`() {
        assertTrue(DefaultPresets.FINISH.autofocus.facePriority)
        assertFalse(DefaultPresets.SLALOM_GS.autofocus.facePriority)
    }

    @Test
    fun `training preset prefers JPEG over RAW`() {
        assertFalse(DefaultPresets.TRAINING.camera.preferRaw)
        assertTrue(DefaultPresets.SLALOM_GS.camera.preferRaw)
    }

    @Test
    fun `snow compensation enabled for all outdoor presets`() {
        listOf(DefaultPresets.SLALOM_GS, DefaultPresets.SPEED, DefaultPresets.TRAINING).forEach {
            assertTrue(it.exposure.snowCompensation, "Snow compensation should be on for ${it.id}")
        }
    }

    @Test
    fun `speed preset has highest EV bias`() {
        val maxEv = DefaultPresets.ALL.maxOf { it.exposure.evBias }
        assertEquals(DefaultPresets.SPEED.exposure.evBias, maxEv)
    }
}
