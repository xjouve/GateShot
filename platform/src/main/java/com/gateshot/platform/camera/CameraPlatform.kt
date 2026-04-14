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
    val jpegQuality: Int = 95,
    // Hasselblad Haute Résolution: requests full-sensor remosaic capture
    // (50 MP main / 200 MP periscope instead of the binned 12 MP default).
    // Triggered via com.mediatek.control.capture.remosaicenable + output
    // size pinned to the largest advertised JPEG size on the active lens.
    val highResolution: Boolean = false
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
    val supportsFaceDetection: Boolean = false,
    // True when the active lens advertises a JPEG output larger than the
    // binned default (eg 50 MP main / 200 MP periscope). Only meaningful
    // after the camera has been opened and readCamera2Characteristics has
    // populated it from the SCALER_STREAM_CONFIGURATION_MAP.
    val supportsHighResolution: Boolean = false,
    val maxJpegSize: Size? = null
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
    val ois: OisMode = OisMode.STANDARD,
    val eis: EisMode = EisMode.OFF
)

enum class OisMode { OFF, STANDARD, MAXIMUM }
enum class EisMode { OFF, STANDARD, PANNING }

enum class CameraAfMode {
    SINGLE,       // CONTROL_AF_MODE_AUTO — focuses once, locks
    CONTINUOUS,   // CONTROL_AF_MODE_CONTINUOUS_VIDEO
    PREDICTIVE,   // CONTROL_AF_MODE_CONTINUOUS_PICTURE — predictive AF for moving subjects
    MANUAL        // CONTROL_AF_MODE_OFF — user sets focus distance
}

data class AfRegion(
    val centerX: Float,    // Normalized 0-1
    val centerY: Float,    // Normalized 0-1
    val size: Float,       // Fraction of frame
    val weight: Int = 1000 // Metering weight (0-1000)
)

data class TonemapConfig(
    val enabled: Boolean = false,
    val curveRed: FloatArray? = null,
    val curveGreen: FloatArray? = null,
    val curveBlue: FloatArray? = null
)

interface CameraPlatform {
    val state: StateFlow<CameraState>
    val isRecording: StateFlow<Boolean>
    val capabilities: CameraCapabilities?
    val lastCaptureMetadata: CaptureMetadata?
    val vendorKeyReport: StateFlow<VendorKeyReport>

    suspend fun open(config: CameraConfig)
    suspend fun close()
    fun setZoom(ratio: Float)
    fun setExposureCompensation(ev: Float)
    fun setManualExposure(exposure: ManualExposure)
    fun setStabilization(config: StabilizationConfig)
    fun setAfMode(mode: CameraAfMode)
    fun setHardwareTracking(enabled: Boolean, region: AfRegion? = null)
    fun setLogColorProfile(enabled: Boolean)
    fun setExtendedIsoEnabled(enabled: Boolean)
    fun setAfRegions(regions: List<AfRegion>)

    // ── Tier 1 / 2 / 3 vendor features ──────────────────────────────────────
    fun setAiShutter(enabled: Boolean)
    fun setBracketMode(mode: Int)               // 0 = off, 1..N = AEB
    fun setMdptzMode(mode: Int, pickup: AfRegion? = null)
    fun setAiScene(enabled: Boolean)
    fun setAeMetering(mode: Int)                // 0 = average, 1 = spot, 2 = matrix
    fun setExposureRoi(ae: AfRegion? = null, af: AfRegion? = null, awb: AfRegion? = null)
    fun setSmvrMode(mode: Int)                  // 0 = off, 1 = 120, 2 = 240, 3 = 480, 4 = 960
    fun setFastMotion(enabled: Boolean)
    fun setProTorch(level: Int)                 // 0 = off, 1..N = intensity
    fun setFilterPreset(presetId: Int)          // 0 = original
    fun setManualWb(kelvin: Int?, tint: Int? = null)
    fun setUltraHighResolution(enabled: Boolean)
    fun setFocusDistance(dioptres: Float)
    fun setIspPipeline(config: IspPipelineConfig)
    fun setWhiteBalanceGains(gains: WhiteBalanceGains)
    fun setTonemapCurve(config: TonemapConfig)
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
