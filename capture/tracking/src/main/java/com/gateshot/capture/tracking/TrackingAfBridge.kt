package com.gateshot.capture.tracking

import androidx.camera.core.ImageProxy
import com.gateshot.platform.camera.AfRegion
import com.gateshot.platform.camera.CameraXPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges SubjectTracker output to Camera2 AF/AE metering regions.
 *
 * The SubjectTracker detects and locks onto the fastest-moving subject
 * (the racer) and produces an AfTarget with normalized coordinates.
 * This bridge converts that target into Camera2 MeteringRectangles
 * and pushes them to the camera hardware every frame.
 *
 * Without this bridge, the tracker was computing AF targets that were
 * never actually sent to the camera — focus was effectively random.
 */
@Singleton
class TrackingAfBridge @Inject constructor(
    private val camera: CameraXPlatform,
    private val tracker: SubjectTracker
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Throttle AF region updates — Camera2 doesn't need 30 updates/sec
    private var lastAfUpdateTimeMs = 0L
    private val minAfUpdateIntervalMs = 50L  // ~20 AF updates/sec max

    // Weight for the tracked subject AF region (Camera2 range: 0-1000)
    // High weight ensures focus prioritizes the racer over background
    private val racerAfWeight = 1000
    private val racerAeWeight = 800

    private val frameListener: (ImageProxy) -> Unit = { imageProxy ->
        processTrackerFrame(imageProxy)
    }

    fun start() {
        camera.addFrameListener(frameListener)
    }

    fun stop() {
        camera.removeFrameListener(frameListener)
        camera.setAfRegions(emptyList())
    }

    private fun processTrackerFrame(imageProxy: ImageProxy) {
        if (!tracker.isEnabled()) return

        val now = System.currentTimeMillis()
        if (now - lastAfUpdateTimeMs < minAfUpdateIntervalMs) return
        lastAfUpdateTimeMs = now

        val target = tracker.processFrame(imageProxy) ?: run {
            // No target — clear AF regions so camera reverts to full-frame AF
            camera.setAfRegions(emptyList())
            return
        }

        // Convert the tracker's AfTarget to a Camera2 AfRegion.
        // The tracker provides normalized coordinates (0-1) and a region size.
        // We create a primary focus region on the racer and a secondary
        // wider region for context (helps AE metering).
        val primaryRegion = AfRegion(
            centerX = target.centerX,
            centerY = target.centerY,
            size = target.regionSize,
            weight = racerAfWeight
        )

        // Secondary wider region at half weight for AE context.
        // This prevents the exposure from swinging wildly when the racer
        // moves against bright snow vs. dark trees.
        val contextRegion = AfRegion(
            centerX = target.centerX,
            centerY = target.centerY,
            size = target.regionSize * 2.5f,
            weight = racerAeWeight / 2
        )

        camera.setAfRegions(listOf(primaryRegion, contextRegion))
    }
}
