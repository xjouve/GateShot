package com.gateshot.platform.camera

import android.util.Size
import kotlinx.coroutines.flow.StateFlow

data class CameraConfig(
    val lensFacing: LensFacing = LensFacing.BACK,
    val lens: CameraLens = CameraLens.MAIN,
    val resolution: Size = Size(3840, 2160),
    val frameRate: Int = 30,
    val enableAudio: Boolean = false,
    val enableRaw: Boolean = false,
    val hdrProfile: HdrProfile = HdrProfile.OFF,
    val outputFormat: ImageOutputFormat = ImageOutputFormat.JPEG,
    val jpegQuality: Int = 95
)

enum class ImageOutputFormat { JPEG, HEIF }

enum class LensFacing { FRONT, BACK }

enum class CameraLens {
    TELEPHOTO,   // 200MP, 1/1.56", 70mm, f/2.1
    MAIN,        // 50MP,  1/1.28", 23mm, f/1.5
    ULTRA_WIDE   // 50MP,  15mm, f/2.0, 120° FoV
}

enum class HdrProfile {
    OFF,
    HDR10,
    DOLBY_VISION
}

data class CameraCapabilities(
    val supportedResolutions: List<Size>,
    val supportedFrameRates: List<Int>,
    val hasOpticalStabilization: Boolean,
    val maxZoomRatio: Float,
    val hasFlash: Boolean,
    val supportsRaw: Boolean = false,
    val supportsDolbyVision: Boolean = false,
    val minFocusDistance: Float = 0f,
    val supportsHeif: Boolean = false,
    val supportsFaceDetection: Boolean = false
)

data class IspPipelineConfig(
    val noiseReduction: IspNoiseReduction = IspNoiseReduction.FAST,
    val edgeEnhancement: IspEdgeMode = IspEdgeMode.FAST,
    val hotPixelCorrection: IspHotPixel = IspHotPixel.FAST,
    val faceDetection: Boolean = false,
    val flashMode: FlashMode = FlashMode.OFF
)

enum class IspNoiseReduction { OFF, FAST, HIGH_QUALITY }
enum class IspEdgeMode { OFF, FAST, HIGH_QUALITY }
enum class IspHotPixel { OFF, FAST, HIGH_QUALITY }
enum class FlashMode { OFF, AUTO, ON, TORCH }

data class WhiteBalanceGains(
    val redGain: Float = 1f,
    val greenEvenGain: Float = 1f,
    val greenOddGain: Float = 1f,
    val blueGain: Float = 1f
)

data class CaptureMetadata(
    val exposureTimeNs: Long = 0,
    val sensitivity: Int = 0,        // ISO
    val focusDistanceDioptres: Float = 0f,
    val focalLengthMm: Float = 0f,
    val aperture: Float = 0f,
    val flashFired: Boolean = false,
    val timestamp: Long = 0
)

enum class CameraState {
    CLOSED,
    OPENING,
    OPEN,
    ERROR
}

data class ManualExposure(
    val shutterSpeedNs: Long? = null,   // Sensor exposure time in nanoseconds
    val iso: Int? = null,               // Sensor sensitivity (ISO)
    val enabled: Boolean = false
)

data class StabilizationConfig(
    val opticalStabilization: Boolean = true,
    val videoStabilization: Boolean = false
)

data class AfRegion(
    val centerX: Float,    // Normalized 0-1
    val centerY: Float,    // Normalized 0-1
    val size: Float,       // Fraction of frame
    val weight: Int = 1000 // Metering weight (0-1000)
)

interface CameraPlatform {
    val state: StateFlow<CameraState>
    val isRecording: StateFlow<Boolean>
    val capabilities: CameraCapabilities?
    val lastCaptureMetadata: CaptureMetadata?

    suspend fun open(config: CameraConfig)
    suspend fun close()
    fun setZoom(ratio: Float)
    fun setExposureCompensation(ev: Float)
    fun setManualExposure(exposure: ManualExposure)
    fun setStabilization(config: StabilizationConfig)
    fun setAfRegions(regions: List<AfRegion>)
    fun setFocusDistance(dioptres: Float)
    fun setIspPipeline(config: IspPipelineConfig)
    fun setWhiteBalanceGains(gains: WhiteBalanceGains)
    suspend fun takePicture(): CaptureResult
    suspend fun takeRawPicture(): CaptureResult
    suspend fun takeBracketedBurst(evSteps: List<Float>): List<CaptureResult>
    suspend fun startRecording()
    suspend fun stopRecording(): RecordingResult
    fun getSupportedConfigs(): List<CameraConfig>
}

data class CaptureResult(
    val uri: String,
    val width: Int,
    val height: Int,
    val timestamp: Long,
    val isRaw: Boolean = false,
    val metadata: CaptureMetadata? = null
)

data class RecordingResult(
    val uri: String,
    val durationMs: Long,
    val fileSize: Long
)
