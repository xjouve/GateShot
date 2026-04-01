package com.gateshot.processing.sr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.gateshot.core.api.ApiEndpoint
import com.gateshot.core.api.ApiResponse
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
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Super-Resolution Feature Module.
 *
 * Zoom-aware enhancement pipeline for the Oppo Find X9 Pro:
 *
 * ZOOM RANGE → PIPELINE
 * ─────────────────────────────────────────────────────
 * 1-5x       → Native 200MP telephoto (no processing needed)
 * 5-10x      → With Hasselblad: optical (deconvolution only)
 *               Without: 200MP crop + denoise
 * 10-13.2x   → 200MP crop (lossless) + multi-frame denoise
 * 13.2-20x   → Multi-frame SR + AI upscale + denoise + sharpen
 * 20x+       → AI upscale + aggressive denoise
 *
 * The key insight: the 200MP telephoto sensor (1/1.56", 0.5µm pixels)
 * is the LARGEST sensor in the system. It produces enormous resolution
 * but noisy pixels. Our job is to reduce that noise and sharpen beyond
 * the crop limit.
 */
@Singleton
class SuperResolutionModule @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cameraPlatform: CameraXPlatform,
    private val eventBus: EventBus,
    private val configStore: ConfigStore
) : FeatureModule {

    override val name = "super_resolution"
    override val version = "0.1.0"
    override val requiredMode: AppMode? = null
    override val dependencies = listOf("camera")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val aligner = FrameAligner()
    private val fuser = FrameFuser()
    private val telephotoOptimizer = TelephotoOptimizer()
    private val lensDeconvolution = LensDeconvolution()
    private val aiUpscaler = AiUpscaler(context)

    private var autoEnhance = true
    private var hasTelevonverter = false
    private var lastProcessingTimeMs = 0L

    override suspend fun initialize() {
        aiUpscaler.initialize()

        // Detect teleconverter
        eventBus.collect<AppEvent.LensDetected>(scope) {
            hasTelevonverter = true
        }
        eventBus.collect<AppEvent.LensRemoved>(scope) {
            hasTelevonverter = false
        }
    }

    override suspend fun shutdown() {
        aiUpscaler.release()
    }

    override fun endpoints(): List<ApiEndpoint<*, *>> = listOf(
        EnhancePhoto(),
        EnhanceBurst(),
        GetEnhanceConfig(),
        SetEnhanceConfig(),
        GetEnhanceStatus()
    )

    override fun healthCheck(): ModuleHealth {
        return ModuleHealth(name, ModuleHealth.Status.OK,
            "Auto=$autoEnhance, Tele=$hasTelevonverter, Last=${lastProcessingTimeMs}ms")
    }

    /**
     * Select the optimal processing pipeline based on current zoom level.
     */
    private fun selectPipeline(zoomLevel: Float): EnhancementPipeline {
        val zone = telephotoOptimizer.getQualityZone(zoomLevel, hasTelevonverter)

        return when (zone) {
            TelephotoOptimizer.QualityZone.OPTICAL_NATIVE -> {
                // Native 200MP — just denoise the tiny pixels
                EnhancementPipeline(
                    denoise = true, denoiseFrameCount = 3,
                    deconvolve = false,
                    upscale = false,
                    sharpen = true, sharpenStrength = 0.2f,
                    description = "Native 200MP telephoto — light denoise + sharpen"
                )
            }
            TelephotoOptimizer.QualityZone.OPTICAL_TELE -> {
                // With Hasselblad teleconverter — deconvolve + denoise
                EnhancementPipeline(
                    denoise = true, denoiseFrameCount = 5,
                    deconvolve = true,
                    upscale = false,
                    sharpen = true, sharpenStrength = 0.4f,
                    description = "Hasselblad teleconverter — deconvolution + denoise"
                )
            }
            TelephotoOptimizer.QualityZone.LOSSLESS_CROP -> {
                // 200MP crop — denoise is critical (tiny pixels cropped = noisy)
                EnhancementPipeline(
                    denoise = true, denoiseFrameCount = 8,
                    deconvolve = hasTelevonverter,
                    upscale = false,
                    sharpen = true, sharpenStrength = 0.5f,
                    description = "200MP lossless crop — multi-frame denoise"
                )
            }
            TelephotoOptimizer.QualityZone.ENHANCED_CROP -> {
                // Beyond lossless — multi-frame SR + AI upscale
                EnhancementPipeline(
                    denoise = true, denoiseFrameCount = 10,
                    deconvolve = hasTelevonverter,
                    upscale = true, upscaleFactor = 2,
                    sharpen = true, sharpenStrength = 0.6f,
                    description = "Enhanced crop — multi-frame SR + AI upscale 2x"
                )
            }
            TelephotoOptimizer.QualityZone.DIGITAL_ZOOM -> {
                // Extreme zoom — everything we've got
                EnhancementPipeline(
                    denoise = true, denoiseFrameCount = 12,
                    deconvolve = hasTelevonverter,
                    upscale = true, upscaleFactor = 4,
                    sharpen = true, sharpenStrength = 0.7f,
                    description = "Digital zoom — max SR + AI upscale 4x"
                )
            }
        }
    }

    /**
     * Run the full enhancement pipeline on a single photo.
     */
    private fun enhanceSingle(
        pixels: IntArray,
        width: Int,
        height: Int,
        zoomLevel: Float
    ): EnhanceResult {
        val startTime = System.currentTimeMillis()
        val pipeline = selectPipeline(zoomLevel)
        var current = pixels
        var currentW = width
        var currentH = height

        // Step 1: Lens deconvolution (if teleconverter is attached)
        if (pipeline.deconvolve) {
            current = lensDeconvolution.deconvolve(current, currentW, currentH)
        }

        // Step 2: Sharpening
        if (pipeline.sharpen) {
            current = telephotoOptimizer.sharpen(current, currentW, currentH, pipeline.sharpenStrength)
        }

        // Step 3: AI upscale (if needed)
        if (pipeline.upscale) {
            val result = aiUpscaler.upscale(current, currentW, currentH)
            current = result.pixels
            currentW = result.width
            currentH = result.height
        }

        lastProcessingTimeMs = System.currentTimeMillis() - startTime

        return EnhanceResult(
            pixels = current,
            width = currentW,
            height = currentH,
            pipeline = pipeline.description,
            processingTimeMs = lastProcessingTimeMs,
            qualityZone = telephotoOptimizer.getQualityZone(zoomLevel, hasTelevonverter).label
        )
    }

    /**
     * Run multi-frame enhancement on a burst of photos.
     * This is the full power pipeline — align, fuse, denoise, deconvolve, upscale.
     */
    private fun enhanceBurst(
        frames: List<IntArray>,
        width: Int,
        height: Int,
        zoomLevel: Float
    ): EnhanceResult {
        val startTime = System.currentTimeMillis()
        val pipeline = selectPipeline(zoomLevel)

        var current: IntArray
        var currentW = width
        var currentH = height

        if (frames.size >= 3 && pipeline.denoise) {
            // Step 1: Align frames
            val alignments = aligner.alignBatch(frames, width, height)
            val usableFrames = mutableListOf(frames[0])
            val usableAlignments = mutableListOf<FrameAligner.AlignmentResult>()
            for (i in alignments.indices) {
                if (alignments[i].isUsable) {
                    usableFrames.add(frames[i + 1])
                    usableAlignments.add(alignments[i])
                }
            }

            // Step 2: Multi-frame denoise (temporal averaging with motion rejection)
            current = telephotoOptimizer.multiFrameDenoise(usableFrames, width, height)

            // Step 3: If we need upscaling AND have enough frames with sub-pixel shifts,
            // use multi-frame fusion for true super-resolution
            if (pipeline.upscale && usableFrames.size >= 4) {
                current = fuser.fuseAndUpscale2x(
                    current, usableFrames.drop(1), usableAlignments,
                    width, height
                )
                currentW *= 2
                currentH *= 2
            }
        } else {
            current = frames.firstOrNull() ?: IntArray(0)
        }

        // Step 4: Lens deconvolution
        if (pipeline.deconvolve) {
            current = lensDeconvolution.deconvolve(current, currentW, currentH)
        }

        // Step 5: Sharpening
        if (pipeline.sharpen) {
            current = telephotoOptimizer.sharpen(current, currentW, currentH, pipeline.sharpenStrength)
        }

        // Step 6: Additional AI upscale if 2x from fusion wasn't enough
        if (pipeline.upscale && pipeline.upscaleFactor > 2 && currentW == width * 2) {
            val result = aiUpscaler.upscale(current, currentW, currentH)
            current = result.pixels
            currentW = result.width
            currentH = result.height
        }

        lastProcessingTimeMs = System.currentTimeMillis() - startTime

        return EnhanceResult(
            pixels = current,
            width = currentW,
            height = currentH,
            pipeline = pipeline.description,
            processingTimeMs = lastProcessingTimeMs,
            qualityZone = telephotoOptimizer.getQualityZone(zoomLevel, hasTelevonverter).label,
            framesUsed = frames.size
        )
    }

    // --- enhance/photo ---
    inner class EnhancePhoto : ApiEndpoint<EnhancePhotoRequest, EnhanceResult> {
        override val path = "enhance/photo"
        override val module = "super_resolution"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: EnhancePhotoRequest): ApiResponse<EnhanceResult> {
            return try {
                val result = enhanceSingle(
                    request.pixels, request.width, request.height, request.zoomLevel
                )
                ApiResponse.success(result)
            } catch (e: Exception) {
                ApiResponse.moduleError(module, e.message ?: "Enhancement failed")
            }
        }
    }

    // --- enhance/burst ---
    inner class EnhanceBurst : ApiEndpoint<EnhanceBurstRequest, EnhanceResult> {
        override val path = "enhance/burst"
        override val module = "super_resolution"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: EnhanceBurstRequest): ApiResponse<EnhanceResult> {
            return try {
                val result = enhanceBurst(
                    request.frames, request.width, request.height, request.zoomLevel
                )
                ApiResponse.success(result)
            } catch (e: Exception) {
                ApiResponse.moduleError(module, e.message ?: "Burst enhancement failed")
            }
        }
    }

    // --- enhance/config ---
    inner class GetEnhanceConfig : ApiEndpoint<Unit, EnhanceConfig> {
        override val path = "enhance/config"
        override val module = "super_resolution"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: Unit): ApiResponse<EnhanceConfig> {
            return ApiResponse.success(
                EnhanceConfig(
                    autoEnhance = autoEnhance,
                    hasTelevonverter = hasTelevonverter
                )
            )
        }
    }

    // --- enhance/config/set ---
    inner class SetEnhanceConfig : ApiEndpoint<EnhanceConfig, Boolean> {
        override val path = "enhance/config/set"
        override val module = "super_resolution"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: EnhanceConfig): ApiResponse<Boolean> {
            autoEnhance = request.autoEnhance
            return ApiResponse.success(true)
        }
    }

    // --- enhance/status ---
    inner class GetEnhanceStatus : ApiEndpoint<Float, EnhancementPipeline> {
        override val path = "enhance/status"
        override val module = "super_resolution"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: Float): ApiResponse<EnhancementPipeline> {
            return ApiResponse.success(selectPipeline(request))
        }
    }
}

@Serializable
data class EnhancementPipeline(
    val denoise: Boolean = false,
    val denoiseFrameCount: Int = 0,
    val deconvolve: Boolean = false,
    val upscale: Boolean = false,
    val upscaleFactor: Int = 1,
    val sharpen: Boolean = false,
    val sharpenStrength: Float = 0f,
    val description: String = ""
)

data class EnhancePhotoRequest(
    val pixels: IntArray,
    val width: Int,
    val height: Int,
    val zoomLevel: Float
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = pixels.contentHashCode()
}

data class EnhanceBurstRequest(
    val frames: List<IntArray>,
    val width: Int,
    val height: Int,
    val zoomLevel: Float
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = frames.hashCode()
}

data class EnhanceResult(
    val pixels: IntArray,
    val width: Int,
    val height: Int,
    val pipeline: String,
    val processingTimeMs: Long,
    val qualityZone: String,
    val framesUsed: Int = 1
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = pixels.contentHashCode()
}

data class EnhanceConfig(
    val autoEnhance: Boolean = true,
    val hasTelevonverter: Boolean = false
)
