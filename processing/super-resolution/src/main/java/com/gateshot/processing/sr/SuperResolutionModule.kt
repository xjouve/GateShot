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
 * has the HIGHEST resolution in the system. It produces enormous resolution
 * but noisy pixels (the main camera's 1/1.28" sensor is physically larger). Our job is to reduce that noise and sharpen beyond
 * the crop limit.
 */
@Singleton
class SuperResolutionModule @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cameraPlatform: CameraXPlatform,
    private val eventBus: EventBus,
    private val configStore: ConfigStore,
    private val gyroAssist: GyroAssist,
    private val lowLightFusion: LowLightFusion
) : FeatureModule {

    override val name = "super_resolution"
    override val version = "0.2.0"
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

        // Wire gyroscope to frame aligner for faster, more robust alignment
        aligner.setGyroAssist(gyroAssist)
        gyroAssist.start()

        // Detect teleconverter
        eventBus.collect<AppEvent.LensDetected>(scope) {
            hasTelevonverter = true
            gyroAssist.setTelevonverter(true)
        }
        eventBus.collect<AppEvent.LensRemoved>(scope) {
            hasTelevonverter = false
            gyroAssist.setTelevonverter(false)
        }
    }

    override suspend fun shutdown() {
        aiUpscaler.release()
        gyroAssist.stop()
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
                // Native 200MP — clean image, sharpen only (no denoise needed)
                EnhancementPipeline(
                    denoise = false, denoiseFrameCount = 0,
                    deconvolve = false,
                    upscale = false,
                    sharpen = true, sharpenStrength = 0.6f,
                    description = "Native 200MP telephoto — sharpen"
                )
            }
            TelephotoOptimizer.QualityZone.OPTICAL_TELE -> {
                // With Hasselblad teleconverter — deconvolve + sharpen
                EnhancementPipeline(
                    denoise = false, denoiseFrameCount = 0,
                    deconvolve = true,
                    upscale = false,
                    sharpen = true, sharpenStrength = 0.7f,
                    description = "Hasselblad teleconverter — deconvolution + sharpen"
                )
            }
            TelephotoOptimizer.QualityZone.LOSSLESS_CROP -> {
                // 200MP crop — light denoise + strong sharpen
                EnhancementPipeline(
                    denoise = true, denoiseFrameCount = 8,
                    deconvolve = hasTelevonverter,
                    upscale = false,
                    sharpen = true, sharpenStrength = 0.8f,
                    description = "200MP lossless crop — denoise + sharpen"
                )
            }
            TelephotoOptimizer.QualityZone.ENHANCED_CROP -> {
                // Beyond lossless — denoise + sharpen + AI upscale
                EnhancementPipeline(
                    denoise = true, denoiseFrameCount = 10,
                    deconvolve = hasTelevonverter,
                    upscale = true, upscaleFactor = 2,
                    sharpen = true, sharpenStrength = 0.8f,
                    description = "Enhanced crop — SR + AI upscale 2x"
                )
            }
            TelephotoOptimizer.QualityZone.DIGITAL_ZOOM -> {
                // Extreme zoom — everything we've got
                EnhancementPipeline(
                    denoise = true, denoiseFrameCount = 12,
                    deconvolve = hasTelevonverter,
                    upscale = true, upscaleFactor = 4,
                    sharpen = true, sharpenStrength = 0.9f,
                    description = "Digital zoom — max SR + AI upscale 4x"
                )
            }
        }
    }

    /**
     * Run the full enhancement pipeline on a single photo.
     *
     * @param mainCameraPixels Optional: simultaneous capture from the main camera
     *        (1/1.28" sensor, 23mm). If provided and the scene is dark, the main
     *        camera's cleaner signal guides telephoto denoising.
     */
    private fun enhanceSingle(
        pixels: IntArray,
        width: Int,
        height: Int,
        zoomLevel: Float,
        mainCameraPixels: IntArray? = null,
        mainCameraWidth: Int = 0,
        mainCameraHeight: Int = 0
    ): EnhanceResult {
        val startTime = System.currentTimeMillis()
        val pipeline = selectPipeline(zoomLevel)
        var current = pixels
        var currentW = width
        var currentH = height

        // Step 0: Low-light fusion with main camera (if available and scene is dark)
        if (mainCameraPixels != null && mainCameraWidth > 0 &&
            lowLightFusion.shouldActivateFusion(pixels, width, height)
        ) {
            val fusionResult = lowLightFusion.fuse(
                LowLightFusion.FusionInput(
                    telephotoPixels = current,
                    telephotoWidth = currentW,
                    telephotoHeight = currentH,
                    mainPixels = mainCameraPixels,
                    mainWidth = mainCameraWidth,
                    mainHeight = mainCameraHeight
                )
            )
            current = fusionResult.pixels
        }

        // Step 1: Lens deconvolution (if teleconverter is attached)
        if (pipeline.deconvolve) {
            current = lensDeconvolution.deconvolve(current, currentW, currentH)
        }

        // Step 2: Spatial denoise — only for high zoom where noise is visible
        if (pipeline.denoise) {
            current = spatialDenoise(current, currentW, currentH)
        }

        // Step 3: Sharpening — runs AFTER denoise to restore edges
        if (pipeline.sharpen) {
            current = telephotoOptimizer.sharpen(current, currentW, currentH, pipeline.sharpenStrength)
        }

        // Step 4: AI upscale (if needed)
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

    /**
     * Edge-aware spatial denoise for single frames.
     * Bilateral-like filter: smooths flat regions while preserving edges.
     */
    private fun spatialDenoise(pixels: IntArray, width: Int, height: Int): IntArray {
        val output = pixels.copyOf()
        val radius = 1  // 3x3 kernel — lighter touch to preserve detail
        val colorThreshold = 15 // Only average very similar pixels (noise-level differences)

        for (y in radius until height - radius) {
            for (x in radius until width - radius) {
                val idx = y * width + x
                val centerR = (pixels[idx] shr 16) and 0xFF
                val centerG = (pixels[idx] shr 8) and 0xFF
                val centerB = pixels[idx] and 0xFF

                var sumR = 0L; var sumG = 0L; var sumB = 0L; var count = 0

                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        val nIdx = (y + dy) * width + (x + dx)
                        val nr = (pixels[nIdx] shr 16) and 0xFF
                        val ng = (pixels[nIdx] shr 8) and 0xFF
                        val nb = pixels[nIdx] and 0xFF

                        val colorDist = kotlin.math.abs(nr - centerR) +
                            kotlin.math.abs(ng - centerG) +
                            kotlin.math.abs(nb - centerB)

                        if (colorDist <= colorThreshold) {
                            sumR += nr; sumG += ng; sumB += nb
                            count++
                        }
                    }
                }

                if (count > 0) {
                    output[idx] = android.graphics.Color.argb(255,
                        (sumR / count).toInt(),
                        (sumG / count).toInt(),
                        (sumB / count).toInt())
                }
            }
        }
        return output
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
