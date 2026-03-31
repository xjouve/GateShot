package com.gateshot.processing.culling

import android.content.Context
import android.graphics.BitmapFactory
import com.gateshot.core.api.ApiEndpoint
import com.gateshot.core.api.ApiResponse
import com.gateshot.core.event.AppEvent
import com.gateshot.core.event.EventBus
import com.gateshot.core.event.collect
import com.gateshot.core.mode.AppMode
import com.gateshot.core.module.FeatureModule
import com.gateshot.core.module.ModuleHealth
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BurstCullingModule @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventBus: EventBus
) : FeatureModule {

    override val name = "burst_culling"
    override val version = "0.1.0"
    override val requiredMode: AppMode? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override suspend fun initialize() {
        // Auto-cull after burst completes
        eventBus.collect<AppEvent.BurstCompleted>(scope) { event ->
            // Auto-culling would run here in background
        }
    }

    override suspend fun shutdown() {}

    override fun endpoints(): List<ApiEndpoint<*, *>> = listOf(
        CullBurst(),
        GetCullStatus()
    )

    override fun healthCheck() = ModuleHealth(name, ModuleHealth.Status.OK)

    private fun scoreFrame(filePath: String): FrameScore {
        val file = File(filePath)
        if (!file.exists()) return FrameScore(filePath, 0f, 0f, 0f, 0f)

        val bitmap = BitmapFactory.decodeFile(filePath)
            ?: return FrameScore(filePath, 0f, 0f, 0f, 0f)

        val sharpness = computeSharpness(bitmap)
        val composition = computeComposition(bitmap)
        val exposure = computeExposureScore(bitmap)
        val overall = (sharpness * 0.5f + composition * 0.3f + exposure * 0.2f)

        bitmap.recycle()
        return FrameScore(filePath, sharpness, composition, exposure, overall)
    }

    // Sharpness via Laplacian variance approximation
    private fun computeSharpness(bitmap: android.graphics.Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 3 || height < 3) return 0f

        var sumVariance = 0.0
        var count = 0
        val step = 4  // Sample every 4th pixel

        for (y in 1 until height - 1 step step) {
            for (x in 1 until width - 1 step step) {
                val center = luminance(bitmap.getPixel(x, y))
                val top = luminance(bitmap.getPixel(x, y - 1))
                val bottom = luminance(bitmap.getPixel(x, y + 1))
                val left = luminance(bitmap.getPixel(x - 1, y))
                val right = luminance(bitmap.getPixel(x + 1, y))

                val laplacian = (top + bottom + left + right - 4 * center).toDouble()
                sumVariance += laplacian * laplacian
                count++
            }
        }

        val variance = if (count > 0) sumVariance / count else 0.0
        // Normalize: typical sharp image variance > 500, blurry < 100
        return (variance / 1000.0).toFloat().coerceIn(0f, 1f)
    }

    // Composition: reward subject near rule-of-thirds intersections
    private fun computeComposition(bitmap: android.graphics.Bitmap): Float {
        // Find the brightest/most-contrasty region as a proxy for subject
        val width = bitmap.width
        val height = bitmap.height
        val gridX = 3
        val gridY = 3
        val regionW = width / gridX
        val regionH = height / gridY

        var maxContrast = 0f
        var maxRegionX = 1
        var maxRegionY = 1

        for (gy in 0 until gridY) {
            for (gx in 0 until gridX) {
                var sum = 0L
                var sumSq = 0L
                var count = 0
                for (y in gy * regionH until (gy + 1) * regionH step 8) {
                    for (x in gx * regionW until (gx + 1) * regionW step 8) {
                        if (x < width && y < height) {
                            val l = luminance(bitmap.getPixel(x, y))
                            sum += l
                            sumSq += l.toLong() * l
                            count++
                        }
                    }
                }
                if (count > 0) {
                    val mean = sum.toFloat() / count
                    val variance = (sumSq.toFloat() / count) - mean * mean
                    if (variance > maxContrast) {
                        maxContrast = variance
                        maxRegionX = gx
                        maxRegionY = gy
                    }
                }
            }
        }

        // Score higher if subject is near thirds (not center)
        val isOnThird = (maxRegionX != 1 || maxRegionY != 1)
        return if (isOnThird) 0.8f else 0.5f
    }

    // Exposure: penalize over/underexposure
    private fun computeExposureScore(bitmap: android.graphics.Bitmap): Float {
        var sum = 0L
        var count = 0
        val width = bitmap.width
        val height = bitmap.height

        for (y in 0 until height step 16) {
            for (x in 0 until width step 16) {
                sum += luminance(bitmap.getPixel(x, y))
                count++
            }
        }

        val avgLum = if (count > 0) sum.toFloat() / count else 128f
        // Ideal average luminance ~120-140 for well-exposed snow scene
        val deviation = Math.abs(avgLum - 130f) / 130f
        return (1f - deviation).coerceIn(0f, 1f)
    }

    private fun luminance(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }

    // --- process/burst/cull ---
    inner class CullBurst : ApiEndpoint<CullRequest, CullResult> {
        override val path = "process/burst/cull"
        override val module = "burst_culling"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: CullRequest): ApiResponse<CullResult> {
            val scores = request.framePaths.map { scoreFrame(it) }
            val ranked = scores.sortedByDescending { it.overallScore }
            val topN = request.topN.coerceIn(1, ranked.size)
            return ApiResponse.success(
                CullResult(
                    ranked = ranked,
                    recommended = ranked.take(topN).map { it.filePath },
                    totalFrames = scores.size
                )
            )
        }
    }

    // --- process/burst/cull/status ---
    inner class GetCullStatus : ApiEndpoint<Unit, String> {
        override val path = "process/burst/cull/status"
        override val module = "burst_culling"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: Unit): ApiResponse<String> {
            return ApiResponse.success("idle")
        }
    }
}

data class CullRequest(val framePaths: List<String>, val topN: Int = 5)
data class CullResult(val ranked: List<FrameScore>, val recommended: List<String>, val totalFrames: Int)
data class FrameScore(
    val filePath: String,
    val sharpnessScore: Float,
    val compositionScore: Float,
    val exposureScore: Float,
    val overallScore: Float
)
