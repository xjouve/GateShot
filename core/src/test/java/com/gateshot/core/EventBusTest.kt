package com.gateshot.core

import com.gateshot.core.event.AppEvent
import com.gateshot.core.event.EventBus
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EventBusTest {

    @Test
    fun `publish and receive event`() = runTest {
        val bus = EventBus()
        var received: AppEvent? = null

        val job = launch {
            bus.events.first().let { received = it }
        }

        bus.publish(AppEvent.ShutterPressed(12345L))
        job.join()

        assertTrue(received is AppEvent.ShutterPressed)
        assertEquals(12345L, (received as AppEvent.ShutterPressed).timestamp)
    }

    @Test
    fun `tryPublish returns true when buffer available`() {
        val bus = EventBus()
        val result = bus.tryPublish(AppEvent.CameraOpened)
        assertTrue(result)
    }

    @Test
    fun `filter events by type`() = runTest {
        val bus = EventBus()
        var shutterCount = 0

        val job = launch {
            bus.events.filterIsInstance<AppEvent.ShutterPressed>().collect {
                shutterCount++
            }
        }

        bus.publish(AppEvent.CameraOpened)
        bus.publish(AppEvent.ShutterPressed())
        bus.publish(AppEvent.CameraClosed)
        bus.publish(AppEvent.ShutterPressed())

        // Give collectors time to process
        kotlinx.coroutines.delay(50)
        job.cancel()

        assertEquals(2, shutterCount)
    }
}
