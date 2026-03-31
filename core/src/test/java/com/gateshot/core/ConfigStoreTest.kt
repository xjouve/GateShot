package com.gateshot.core

import com.gateshot.core.config.ConfigStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigStoreTest {

    private val store = ConfigStore()

    @Test
    fun `get returns default when key not set`() {
        assertEquals(42, store.get("module", "key", 42))
    }

    @Test
    fun `set and get returns stored value`() {
        store.set("exposure", "ev_bias", 1.5f)
        assertEquals(1.5f, store.get("exposure", "ev_bias", 0f))
    }

    @Test
    fun `getModuleConfig returns all keys for module`() {
        store.set("camera", "fps", 60)
        store.set("camera", "resolution", "4K")
        store.set("burst", "count", 8)

        val config = store.getModuleConfig("camera")
        assertEquals(2, config.size)
        assertEquals(60, config["fps"])
        assertEquals("4K", config["resolution"])
    }

    @Test
    fun `clear removes module config`() {
        store.set("test", "key", "value")
        store.clear("test")
        assertEquals("default", store.get("test", "key", "default"))
    }

    @Test
    fun `export returns all modules`() {
        store.set("a", "x", 1)
        store.set("b", "y", 2)
        val exported = store.export()
        assertEquals(2, exported.size)
        assertTrue(exported.containsKey("a"))
        assertTrue(exported.containsKey("b"))
    }
}
