package com.gateshot.core

import com.gateshot.core.api.ApiEndpoint
import com.gateshot.core.api.ApiResponse
import com.gateshot.core.api.EndpointRegistry
import com.gateshot.core.event.EventBus
import com.gateshot.core.mode.AppMode
import com.gateshot.core.mode.ModeManager
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers HANDOFF fact 1: EndpointRegistry does exact-path lookup + an
 * unchecked cast, and RETURNS failures instead of throwing. These tests
 * pin that contract down so a future change can't silently regress it
 * back into a crash (unknown path) or a ClassCastException leaking out
 * of `call` (wrong request type).
 */
class EndpointRegistryTest {

    private val modeManager = ModeManager(EventBus())
    private val registry = EndpointRegistry(modeManager)

    /** requiredMode = null -> always available, per ModeManager.isFeatureAvailable(null). */
    private class EchoEndpoint(override val path: String = "test/echo") : ApiEndpoint<String, String> {
        override val module = "test"
        override val requiredMode: AppMode? = null
        override suspend fun handle(request: String): ApiResponse<String> =
            ApiResponse.success(request.uppercase())
    }

    @Test
    fun `register then call by exact path succeeds`() = runTest {
        registry.register(EchoEndpoint())

        val response = registry.call<String, String>("test/echo", "hello")

        assertTrue(response.isSuccess)
        assertEquals("HELLO", response.dataOrNull())
    }

    @Test
    fun `call on unknown path returns a failure result instead of throwing`() = runTest {
        val response = registry.call<String, String>("does/not/exist", "hello")

        assertFalse(response.isSuccess)
        assertTrue(response is ApiResponse.Error)
        assertEquals(404, (response as ApiResponse.Error).code)
    }

    @Test
    fun `call with wrong request type returns a failure result instead of throwing ClassCastException`() = runTest {
        registry.register(EchoEndpoint())

        // Req is erased at the call site (the registry's unchecked cast lets any
        // type through). EchoEndpoint's synthetic bridge method casts the
        // parameter to String and throws ClassCastException when it isn't one —
        // the registry must catch that and return a failure, never let it escape.
        val response = registry.call<Int, String>("test/echo", 42)

        assertFalse(response.isSuccess)
        assertTrue(response is ApiResponse.Error)
        assertEquals(500, (response as ApiResponse.Error).code)
    }

    @Test
    fun `isAvailable is false for an unknown path`() {
        assertFalse(registry.isAvailable("does/not/exist"))
    }

    @Test
    fun `isAvailable is true for a registered path whose mode is available`() {
        registry.register(EchoEndpoint())
        assertTrue(registry.isAvailable("test/echo"))
    }

    @Test
    fun `unregister removes the endpoint so subsequent calls report not found`() = runTest {
        registry.register(EchoEndpoint())
        registry.unregister("test/echo")

        val response = registry.call<String, String>("test/echo", "hello")

        assertFalse(response.isSuccess)
        assertEquals(404, (response as ApiResponse.Error).code)
    }
}
