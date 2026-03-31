package com.gateshot.processing.snow

import androidx.camera.core.ImageProxy
import com.gateshot.core.api.ApiEndpoint
import com.gateshot.core.api.ApiResponse
import com.gateshot.core.config.ConfigStore
import com.gateshot.core.event.AppEvent
import com.gateshot.core.event.EventBus
import com.gateshot.core.event.collect
import com.gateshot.core.mode.AppMode
import com.gateshot.core.module.FeatureModule
import com.gateshot.core.module.ModuleHealth
import com.gateshot.platform.camera.CameraPlatform
import com.gateshot.platform.camera.CameraXPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SnowExposureModule @Inject constructor(
    private val cameraPlatform: CameraXPlatform,
    private val eventBus: EventBus,
    private val configStore: ConfigStore
) : FeatureModule {

    override val name = "snow_exposure"
    override val version = "0.1.0"
    override val requiredMode: AppMode? = null
    override val dependencies = listOf("camera")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val analyzer = SnowAnalyzer()

    private val _latestAnalysis = MutableStateFlow<SceneAnalysis?>(null)
    val latestAnalysis: StateFlow<SceneAnalysis?> = _latestAnalysis.asStateFlow()

    private var isEnabled = true
    private var flatLightModeEnabled = false
    private var userEvOverride: Float? = null

    // Analyze every Nth frame (don't burn CPU on every single frame)
    private var frameCounter = 0
    private val analyzeEveryNFrames = 5  // ~6 analyses/sec at 30fps

    private val frameListener: (ImageProxy) -> Unit = { imageProxy ->
        frameCounter++
        if (isEnabled && frameCounter % analyzeEveryNFrames == 0) {
            analyzeFrame(imageProxy)
        }
    }

    override suspend fun initialize() {
        cameraPlatform.addFrameListener(frameListener)

        // React to preset changes
        eventBus.collect<AppEvent.PresetApplied>(scope) {
            // Read snow compensation settings from config
            isEnabled = configStore.get("exposure", "snow_compensation", true)
            flatLightModeEnabled = configStore.get("exposure", "flat_light_auto", true)
        }
    }

    override suspend fun shutdown() {
        cameraPlatform.removeFrameListener(frameListener)
    }

    override fun endpoints(): List<ApiEndpoint<*, *>> = listOf(
        GetSnowStatus(),
        SetSnowOverride(),
        EnableFlatLight(),
        DisableFlatLight(),
        AnalyzeScene()
    )

    override fun healthCheck(): ModuleHealth {
        val analysis = _latestAnalysis.value
        val msg = if (analysis != null) {
            "Snow: ${(analysis.snowCoveragePercent * 100).toInt()}%, EV: +${analysis.recommendedEvBias}" +
                if (analysis.isFlatLight) " [FLAT LIGHT]" else ""
        } else {
            "Waiting for first frame analysis"
        }
        return ModuleHealth(name, ModuleHealth.Status.OK, msg)
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        try {
            val analysis = analyzer.analyze(imageProxy)
            _latestAnalysis.value = analysis

            // Apply exposure compensation
            val effectiveEv = userEvOverride ?: analysis.recommendedEvBias
            val presetBaseEv = configStore.get("exposure", "ev_bias", 0f)
            val finalEv = if (isEnabled) effectiveEv else presetBaseEv

            cameraPlatform.setExposureCompensation(finalEv)

            // Publish event if EV changed significantly
            eventBus.tryPublish(
                AppEvent.ExposureAdjusted(finalEv, "snow=${(analysis.snowCoveragePercent * 100).toInt()}%")
            )
        } catch (_: Exception) { }
    }

    // --- exposure/snow/status ---
    inner class GetSnowStatus : ApiEndpoint<Unit, SnowStatusResponse> {
        override val path = "exposure/snow/status"
        override val module = "snow_exposure"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: Unit): ApiResponse<SnowStatusResponse> {
            val analysis = _latestAnalysis.value
            return ApiResponse.success(
                SnowStatusResponse(
                    enabled = isEnabled,
                    snowCoveragePercent = analysis?.snowCoveragePercent ?: 0f,
                    currentEvBias = analysis?.recommendedEvBias ?: 0f,
                    isFlatLight = analysis?.isFlatLight ?: false,
                    hasShadowTransition = analysis?.hasShadowTransition ?: false,
                    userOverride = userEvOverride
                )
            )
        }
    }

    // --- exposure/snow/override ---
    inner class SetSnowOverride : ApiEndpoint<SnowOverrideRequest, Boolean> {
        override val path = "exposure/snow/override"
        override val module = "snow_exposure"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: SnowOverrideRequest): ApiResponse<Boolean> {
            userEvOverride = if (request.clearOverride) null else request.evBias
            return ApiResponse.success(true)
        }
    }

    // --- exposure/flatlight/enable ---
    inner class EnableFlatLight : ApiEndpoint<Unit, Boolean> {
        override val path = "exposure/flatlight/enable"
        override val module = "snow_exposure"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: Unit): ApiResponse<Boolean> {
            flatLightModeEnabled = true
            return ApiResponse.success(true)
        }
    }

    // --- exposure/flatlight/disable ---
    inner class DisableFlatLight : ApiEndpoint<Unit, Boolean> {
        override val path = "exposure/flatlight/disable"
        override val module = "snow_exposure"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: Unit): ApiResponse<Boolean> {
            flatLightModeEnabled = false
            return ApiResponse.success(true)
        }
    }

    // --- exposure/scene/analyze ---
    inner class AnalyzeScene : ApiEndpoint<Unit, SceneAnalysis?> {
        override val path = "exposure/scene/analyze"
        override val module = "snow_exposure"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: Unit): ApiResponse<SceneAnalysis?> {
            return ApiResponse.success(_latestAnalysis.value)
        }
    }
}

data class SnowStatusResponse(
    val enabled: Boolean,
    val snowCoveragePercent: Float,
    val currentEvBias: Float,
    val isFlatLight: Boolean,
    val hasShadowTransition: Boolean,
    val userOverride: Float?
)

data class SnowOverrideRequest(
    val evBias: Float = 0f,
    val clearOverride: Boolean = false
)
