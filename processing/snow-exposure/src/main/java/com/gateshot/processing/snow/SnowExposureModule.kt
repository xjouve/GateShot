package com.gateshot.processing.snow

import androidx.camera.core.ImageProxy
import android.content.Context
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SnowExposureModule @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val cameraPlatform: CameraXPlatform,
    private val eventBus: EventBus,
    private val configStore: ConfigStore,
    private val trueColorWb: TrueColorWhiteBalance
) : FeatureModule {

    override val name = "snow_exposure"
    override val version = "0.2.0"
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
    // With the preview-bitmap fallback delivering ~2fps,
    // analyze every frame (no skipping needed at this rate).
    // Read isEnabled directly from SharedPrefs each frame to
    // ensure toggle changes take effect immediately.
    private val frameListener: (ImageProxy) -> Unit = { imageProxy ->
        frameCounter++
        isEnabled = userPrefs.getBoolean("exposure_snow_compensation", true)
        if (isEnabled) {
            analyzeFrame(imageProxy)
        }
    }

    private val userPrefs get() =
        appContext.getSharedPreferences("gateshot_config", Context.MODE_PRIVATE)

    private fun refreshSettings() {
        isEnabled = userPrefs.getBoolean("exposure_snow_compensation", true)
        flatLightModeEnabled = userPrefs.getBoolean("exposure_flat_light_auto", true)
    }

    override suspend fun initialize() {
        refreshSettings()

        cameraPlatform.addFrameListener(frameListener)

        // Start True Color sensor white balance tracking
        trueColorWb.start()

        // React to preset changes and config updates
        eventBus.collect<AppEvent.PresetApplied>(scope) { refreshSettings() }

        // Observe config changes (from Settings screen)
        scope.launch {
            configStore.observeChanges("exposure", "snow_compensation").collect {
                refreshSettings()
            }
        }
    }

    override suspend fun shutdown() {
        cameraPlatform.removeFrameListener(frameListener)
        trueColorWb.stop()
    }

    override fun endpoints(): List<ApiEndpoint<*, *>> = listOf(
        GetSnowStatus(),
        SetSnowOverride(),
        EnableFlatLight(),
        DisableFlatLight(),
        AnalyzeScene(),
        GetWhiteBalance()
    )

    override fun healthCheck(): ModuleHealth {
        val analysis = _latestAnalysis.value
        val wb = trueColorWb.currentWhiteBalance.value
        val msg = buildString {
            if (analysis != null) {
                append("Snow: ${(analysis.snowCoveragePercent * 100).toInt()}%, EV: +${analysis.recommendedEvBias}")
                if (analysis.isFlatLight) append(" [FLAT LIGHT]")
            } else {
                append("Waiting for first frame analysis")
            }
            if (wb != null) {
                append(" | WB: ${wb.cctKelvin}K ${wb.lightingCondition}")
            }
        }
        return ModuleHealth(name, ModuleHealth.Status.OK, msg)
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        try {
            val analysis = analyzer.analyze(imageProxy)
            _latestAnalysis.value = analysis

            // When snow compensation is disabled, don't touch exposure —
            // let the preset or user setting control it directly.
            if (!isEnabled) return

            // Apply exposure compensation
            val finalEv = userEvOverride ?: analysis.recommendedEvBias

            cameraPlatform.setExposureCompensation(finalEv)

            // Publish event if EV changed significantly
            eventBus.tryPublish(
                AppEvent.ExposureAdjusted(finalEv, "snow=${(analysis.snowCoveragePercent * 100).toInt()}%")
            )
        } catch (e: Exception) {
            android.util.Log.e("SnowExposure", "analyzeFrame error: ${e.message}", e)
        }
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

    // --- exposure/whitebalance ---
    inner class GetWhiteBalance : ApiEndpoint<Unit, WhiteBalanceResponse?> {
        override val path = "exposure/whitebalance"
        override val module = "snow_exposure"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: Unit): ApiResponse<WhiteBalanceResponse?> {
            val wb = trueColorWb.currentWhiteBalance.value
                ?: return ApiResponse.success(null)
            return ApiResponse.success(
                WhiteBalanceResponse(
                    redGain = wb.redGain,
                    greenGain = wb.greenGain,
                    blueGain = wb.blueGain,
                    cctKelvin = wb.cctKelvin,
                    tint = wb.tint,
                    lightingCondition = wb.lightingCondition.name,
                    confidence = wb.confidence
                )
            )
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

data class WhiteBalanceResponse(
    val redGain: Float,
    val greenGain: Float,
    val blueGain: Float,
    val cctKelvin: Int,
    val tint: Float,
    val lightingCondition: String,
    val confidence: Float
)
