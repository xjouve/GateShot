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
    fun `three presets defined`() {
        assertEquals(3, DefaultPresets.ALL.size)
    }

    @Test
    fun `BY_ID contains all presets`() {
        DefaultPresets.ALL.forEach { preset ->
            assertNotNull(DefaultPresets.BY_ID[preset.id])
        }
    }

    @Test
    fun `race preset has fast AF reacquisition and occlusion hold`() {
        val preset = DefaultPresets.RACE
        assertEquals(AfSpeed.FAST, preset.autofocus.reacquisitionSpeed)
        assertTrue(preset.autofocus.occlusionHold)
        assertEquals(AfMode.CONTINUOUS_PREDICTIVE, preset.autofocus.mode)
    }

    @Test
    fun `race preset has maximum OIS and no EIS`() {
        val preset = DefaultPresets.RACE
        assertEquals(OisMode.MAXIMUM, preset.stabilization.ois)
        assertEquals(EisMode.OFF, preset.stabilization.eis)
    }

    @Test
    fun `panning preset has panning EIS`() {
        val preset = DefaultPresets.PANNING
        assertEquals(EisMode.PANNING, preset.stabilization.eis)
    }

    @Test
    fun `video preset prefers JPEG over RAW`() {
        assertFalse(DefaultPresets.VIDEO.camera.preferRaw)
        assertTrue(DefaultPresets.RACE.camera.preferRaw)
    }

    @Test
    fun `video preset runs at 120fps for slow-motion coaching`() {
        assertEquals(120, DefaultPresets.VIDEO.camera.frameRate)
    }

    @Test
    fun `snow compensation enabled for race and video`() {
        listOf(DefaultPresets.RACE, DefaultPresets.VIDEO).forEach {
            assertTrue(it.exposure.snowCompensation, "Snow compensation should be on for ${it.id}")
        }
    }

    @Test
    fun `each preset serves a different shooting mode`() {
        // Race: burst stills, Video: coaching replay, Panning: creative motion blur
        assertEquals(BurstMode.CONTINUOUS, DefaultPresets.RACE.burst.mode)
        assertEquals(BurstMode.SINGLE, DefaultPresets.VIDEO.burst.mode)
        assertEquals(BurstMode.CONTINUOUS, DefaultPresets.PANNING.burst.mode)
    }
}
