package com.gateshot.session

import com.gateshot.session.data.MediaEntity
import com.gateshot.session.data.MediaType
import com.gateshot.session.data.RunEntity
import com.gateshot.session.data.SessionEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Session data-model tests. SessionFeatureModule itself is Room/Android
 * coupled (SessionDao, EventBus) with no pure business logic beyond entity
 * construction and defaulting, so these tests exercise the genuinely pure
 * surface: the field defaults and shapes that CreateSession/RecordMedia
 * (and the eventBus.collect handler in SessionFeatureModule.initialize())
 * rely on holding.
 */
class SessionFeatureModuleTest {

    @Test
    fun `new SessionEntity defaults to active with no end time`() {
        val session = SessionEntity(eventName = "Regional Slalom", discipline = "SL", date = "2026-01-25")

        assertTrue(session.isActive)
        assertNull(session.endTime)
        assertEquals(0L, session.id)
    }

    @Test
    fun `new RunEntity defaults to active run 1 with no end time`() {
        val run = RunEntity(sessionId = 7L, runNumber = 1)

        assertTrue(run.isActive)
        assertNull(run.endTime)
        assertEquals(7L, run.sessionId)
    }

    @Test
    fun `MediaEntity from a video NativeCaptureCompleted event defaults are untagged and unflagged`() {
        // Mirrors the object SessionFeatureModule.initialize()'s eventBus.collect
        // handler builds from AppEvent.NativeCaptureCompleted before insertMedia.
        val media = MediaEntity(
            runId = 3L,
            type = MediaType.VIDEO,
            fileUri = "file:///storage/emulated/0/GateShot/videos/clip.mp4",
            captureTimestamp = 1_700_000_000_000L
        )

        assertEquals(MediaType.VIDEO, media.type)
        assertNull(media.bibNumber)
        assertEquals(0, media.starRating)
        assertFalse(media.isFlagged)
        assertEquals(0L, media.fileSizeBytes)
    }

    @Test
    fun `MediaEntity from a photo NativeCaptureCompleted event carries PHOTO type`() {
        val media = MediaEntity(
            runId = 3L,
            type = MediaType.PHOTO,
            fileUri = "file:///storage/emulated/0/GateShot/photos/frame.jpg",
            captureTimestamp = 1_700_000_000_000L
        )

        assertEquals(MediaType.PHOTO, media.type)
    }
}
