package com.gateshot.core

import com.gateshot.core.event.EventBus
import com.gateshot.core.mode.AppMode
import com.gateshot.core.mode.ModeManager
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModeManagerTest {

    private val eventBus = EventBus()
    private val modeManager = ModeManager(eventBus)

    @Test
    fun `default mode is SHOOT`() {
        assertEquals(AppMode.SHOOT, modeManager.currentMode.value)
    }

    @Test
    fun `toggle switches between modes`() {
        modeManager.toggleMode()
        assertEquals(AppMode.COACH, modeManager.currentMode.value)

        modeManager.toggleMode()
        assertEquals(AppMode.SHOOT, modeManager.currentMode.value)
    }

    @Test
    fun `setMode changes mode`() = runTest {
        modeManager.setMode(AppMode.COACH)
        assertEquals(AppMode.COACH, modeManager.currentMode.value)
    }

    @Test
    fun `null requiredMode is always available`() {
        assertTrue(modeManager.isFeatureAvailable(null))
    }

    @Test
    fun `SHOOT features always available`() {
        assertTrue(modeManager.isFeatureAvailable(AppMode.SHOOT))
    }

    @Test
    fun `COACH features blocked in SHOOT mode`() {
        assertEquals(AppMode.SHOOT, modeManager.currentMode.value)
        assertFalse(modeManager.isFeatureAvailable(AppMode.COACH))
    }

    @Test
    fun `COACH features available in COACH mode`() {
        modeManager.toggleMode()
        assertTrue(modeManager.isFeatureAvailable(AppMode.COACH))
    }
}
