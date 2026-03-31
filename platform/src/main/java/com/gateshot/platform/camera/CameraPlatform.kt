package com.gateshot.platform.camera

import android.util.Size
import kotlinx.coroutines.flow.StateFlow

data class CameraConfig(
    val lensFacing: LensFacing = LensFacing.BACK,
    val resolution: Size = Size(3840, 2160),
    val frameRate: Int = 30,
    val enableAudio: Boolean = false
)

enum class LensFacing { FRONT, BACK }

data class CameraCapabilities(
    val supportedResolutions: List<Size>,
    val supportedFrameRates: List<Int>,
    val hasOpticalStabilization: Boolean,
    val maxZoomRatio: Float,
    val hasFlash: Boolean
)

enum class CameraState {
    CLOSED,
    OPENING,
    OPEN,
    ERROR
}

interface CameraPlatform {
    val state: StateFlow<CameraState>
    val isRecording: StateFlow<Boolean>
    val capabilities: CameraCapabilities?

    suspend fun open(config: CameraConfig)
    suspend fun close()
    fun setZoom(ratio: Float)
    fun setExposureCompensation(ev: Float)
    suspend fun takePicture(): CaptureResult
    suspend fun startRecording()
    suspend fun stopRecording(): RecordingResult
    fun getSupportedConfigs(): List<CameraConfig>
}

data class CaptureResult(
    val uri: String,
    val width: Int,
    val height: Int,
    val timestamp: Long
)

data class RecordingResult(
    val uri: String,
    val durationMs: Long,
    val fileSize: Long
)
