package com.gateshot.processing.stabilize

import android.content.Context
import com.gateshot.core.api.ApiEndpoint
import com.gateshot.core.api.ApiResponse
import com.gateshot.core.event.AppEvent
import com.gateshot.core.event.EventBus
import com.gateshot.core.mode.AppMode
import com.gateshot.core.module.FeatureModule
import com.gateshot.core.module.ModuleHealth
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline video stabilizer: ports our gyro + optical-residual hybrid (which
 * beats the native super-EIS on the jitter metric) into an on-device endpoint
 * that stabilizes a recorded clip using its `<clip>_gyro.csv` sidecar.
 */
@Singleton
class StabilizeModule @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventBus: EventBus
) : FeatureModule {

    override val name = "stabilize"
    override val version = "0.1.0"
    override val requiredMode: AppMode? = null

    private val _progress = MutableStateFlow(0f)
    /** 0..1 progress of the in-flight stabilization (UI observes this). */
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val runMutex = Mutex()

    override suspend fun initialize() {}
    override suspend fun shutdown() {}

    override fun endpoints(): List<ApiEndpoint<*, *>> = listOf(RunStabilize())

    override fun healthCheck() = ModuleHealth(name, ModuleHealth.Status.OK)

    inner class RunStabilize : ApiEndpoint<StabilizeRequest, StabilizeResult> {
        override val path = "process/stabilize/run"
        override val module = "stabilize"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: StabilizeRequest): ApiResponse<StabilizeResult> {
            val clip = File(request.clipPath)
            if (!clip.exists()) return ApiResponse.error(404, "Clip not found: ${request.clipPath}")
            val gyroCsv = request.gyroCsvPath
                ?: File(clip.parentFile, "${clip.nameWithoutExtension}_gyro.csv").absolutePath

            return runMutex.withLock {
                _running.value = true
                _progress.value = 0f
                val started = System.nanoTime()
                try {
                    val result = withContext(Dispatchers.Default) {
                        StabilizerPipeline().stabilize(
                            clipPath = request.clipPath,
                            gyroCsvPath = gyroCsv,
                            sigma = request.sigma,
                            cropFrac = request.crop,
                            resSigma = request.resSigma
                        ) { p -> _progress.value = p.coerceIn(0f, 1f) }
                    }
                    val elapsedMs = (System.nanoTime() - started) / 1_000_000
                    eventBus.publish(
                        AppEvent.VideoStabilizationCompleted(result.outputPath, elapsedMs)
                    )
                    ApiResponse.success(
                        StabilizeResult(
                            outputPath = result.outputPath,
                            frames = result.frames,
                            syncOffsetMs = result.syncOffsetMs,
                            r2x = result.r2x,
                            r2y = result.r2y,
                            fellBackToOptical = result.fellBackToOptical,
                            elapsedMs = elapsedMs
                        )
                    )
                } catch (e: Exception) {
                    ApiResponse.error(500, "Stabilization failed: ${e.message}")
                } finally {
                    _running.value = false
                    _progress.value = 0f
                }
            }
        }
    }
}

@Serializable
data class StabilizeRequest(
    val clipPath: String,
    val gyroCsvPath: String? = null,
    val sigma: Float = 18f,
    val crop: Float = 0.12f,
    val resSigma: Float = 12f
)

@Serializable
data class StabilizeResult(
    val outputPath: String,
    val frames: Int,
    val syncOffsetMs: Float,
    val r2x: Float,
    val r2y: Float,
    val fellBackToOptical: Boolean,
    val elapsedMs: Long
)
