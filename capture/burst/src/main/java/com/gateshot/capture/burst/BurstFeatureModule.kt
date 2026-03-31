package com.gateshot.capture.burst

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import com.gateshot.core.api.ApiEndpoint
import com.gateshot.core.config.ConfigStore
import com.gateshot.core.event.AppEvent
import com.gateshot.core.event.EventBus
import com.gateshot.core.event.collect
import com.gateshot.core.mode.AppMode
import com.gateshot.core.module.FeatureModule
import com.gateshot.core.module.ModuleHealth
import com.gateshot.platform.camera.CameraXPlatform
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BurstFeatureModule @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cameraPlatform: CameraXPlatform,
    private val eventBus: EventBus,
    private val configStore: ConfigStore
) : FeatureModule {

    override val name = "burst"
    override val version = "0.1.0"
    override val requiredMode: AppMode? = null
    override val dependencies = listOf("camera")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Pre-capture ring buffer: ~45 frames at 30fps = 1.5 seconds
    private val preBuffer = FrameRingBuffer(maxFrames = 45)
    private var isBuffering = false
    private var isBursting = false

    private val frameListener: (ImageProxy) -> Unit = { imageProxy ->
        if (isBuffering && !isBursting) {
            captureToBuffer(imageProxy)
        }
    }

    override suspend fun initialize() {
        // Register as a frame listener on the camera pipeline
        cameraPlatform.addFrameListener(frameListener)

        // Start buffering when camera opens
        eventBus.collect<AppEvent.CameraOpened>(scope) {
            isBuffering = true
        }
        eventBus.collect<AppEvent.CameraClosed>(scope) {
            isBuffering = false
            preBuffer.clear()
        }

        // On shutter press: flush buffer + capture burst
        eventBus.collect<AppEvent.ShutterPressed>(scope) {
            onShutterPressed()
        }
    }

    override suspend fun shutdown() {
        isBuffering = false
        cameraPlatform.removeFrameListener(frameListener)
        preBuffer.clear()
    }

    override fun endpoints(): List<ApiEndpoint<*, *>> = listOf(
        StartBurst(),
        StopBurst(),
        GetBufferStatus(),
        FlushBuffer()
    )

    override fun healthCheck(): ModuleHealth {
        val memUsage = preBuffer.estimateMemoryUsageMb()
        return ModuleHealth(
            name,
            ModuleHealth.Status.OK,
            "Buffer: ${preBuffer.size}/${preBuffer.capacity} frames (${memUsage.toInt()} MB)"
        )
    }

    private fun captureToBuffer(imageProxy: ImageProxy) {
        try {
            val planes = imageProxy.planes
            if (planes.isEmpty()) return

            // Copy the Y plane data (luminance) for lightweight buffering
            // Full JPEG conversion happens only on flush
            val yBuffer = planes[0].buffer
            val data = ByteArray(yBuffer.remaining())
            yBuffer.get(data)

            preBuffer.push(
                BufferedFrame(
                    timestamp = System.currentTimeMillis(),
                    width = imageProxy.width,
                    height = imageProxy.height,
                    data = data,
                    format = imageProxy.format
                )
            )
        } catch (_: Exception) { }
    }

    private fun onShutterPressed() {
        if (isBursting) return
        isBursting = true

        scope.launch {
            try {
                val burstFrameCount = configStore.get("burst", "frame_count", 8)
                val preBufferFrames = preBuffer.flush()

                // Save pre-buffer frames to storage
                val savedFiles = mutableListOf<String>()
                val sessionDir = File(context.cacheDir, "burst_${System.currentTimeMillis()}")
                sessionDir.mkdirs()

                preBufferFrames.forEach { frame ->
                    val file = File(sessionDir, "prebuf_${frame.timestamp}.dat")
                    FileOutputStream(file).use { it.write(frame.data) }
                    savedFiles.add(file.absolutePath)
                }

                // Now take burst photos via the camera pipeline
                for (i in 0 until burstFrameCount) {
                    try {
                        val result = cameraPlatform.takePicture()
                        savedFiles.add(result.uri)
                    } catch (_: Exception) { break }
                }

                // Publish burst completed
                eventBus.publish(
                    AppEvent.BurstCompleted(
                        frameCount = savedFiles.size,
                        sessionId = sessionDir.name
                    )
                )
            } finally {
                isBursting = false
                preBuffer.clear()
            }
        }
    }

    // --- capture/photo/burst/start ---
    inner class StartBurst : ApiEndpoint<BurstConfig, Boolean> {
        override val path = "capture/photo/burst/start"
        override val module = "burst"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: BurstConfig): ApiResponse<Boolean> {
            onShutterPressed()
            return com.gateshot.core.api.ApiResponse.success(true)
        }
    }

    // --- capture/photo/burst/stop ---
    inner class StopBurst : ApiEndpoint<Unit, Boolean> {
        override val path = "capture/photo/burst/stop"
        override val module = "burst"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: Unit): ApiResponse<Boolean> {
            isBursting = false
            return com.gateshot.core.api.ApiResponse.success(true)
        }
    }

    // --- capture/prebuffer/status ---
    inner class GetBufferStatus : ApiEndpoint<Unit, BufferStatus> {
        override val path = "capture/prebuffer/status"
        override val module = "burst"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: Unit): ApiResponse<BufferStatus> {
            return com.gateshot.core.api.ApiResponse.success(
                BufferStatus(
                    frameCount = preBuffer.size,
                    capacity = preBuffer.capacity,
                    memoryUsageMb = preBuffer.estimateMemoryUsageMb(),
                    isBuffering = isBuffering
                )
            )
        }
    }

    // --- capture/prebuffer/save ---
    inner class FlushBuffer : ApiEndpoint<Unit, Int> {
        override val path = "capture/prebuffer/save"
        override val module = "burst"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: Unit): ApiResponse<Int> {
            val frames = preBuffer.flush()
            preBuffer.clear()
            return com.gateshot.core.api.ApiResponse.success(frames.size)
        }
    }
}

// Need to import ApiResponse for the inner classes
private typealias ApiResponse<T> = com.gateshot.core.api.ApiResponse<T>

data class BurstConfig(
    val frameCount: Int = 8,
    val includePreBuffer: Boolean = true
)

data class BufferStatus(
    val frameCount: Int,
    val capacity: Int,
    val memoryUsageMb: Float,
    val isBuffering: Boolean
)
