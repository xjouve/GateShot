package com.gateshot.capture.trigger

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageProxy
import dagger.hilt.android.qualifiers.ApplicationContext
import com.gateshot.core.api.ApiEndpoint
import com.gateshot.core.api.ApiResponse
import com.gateshot.core.event.AppEvent
import com.gateshot.core.event.EventBus
import com.gateshot.core.event.collect
import com.gateshot.core.mode.AppMode
import com.gateshot.core.module.FeatureModule
import com.gateshot.core.module.ModuleHealth
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
class TriggerFeatureModule @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cameraPlatform: CameraXPlatform,
    private val eventBus: EventBus
) : FeatureModule {

    override val name = "trigger"
    override val version = "0.1.0"
    override val requiredMode: AppMode? = null
    override val dependencies = listOf("camera")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val motionDetector = MotionDetector()

    private val _zones = MutableStateFlow<List<TriggerZone>>(emptyList())
    val zones: StateFlow<List<TriggerZone>> = _zones.asStateFlow()

    private val zoneStates = mutableMapOf<Int, ZoneMotionState>()
    private var nextZoneId = 1
    private var isArmed = false
    private var frameCounter = 0

    // Audio trigger
    private val audioTrigger = AudioTrigger(context) {
        // Audio beep detected — fire shutter
        eventBus.tryPublish(AppEvent.ShutterPressed(System.currentTimeMillis()))
    }
    private var isAudioArmed = false

    // Analyze every 3rd frame (~10 checks/sec at 30fps)
    private val analyzeEveryNFrames = 3

    private val frameListener: (ImageProxy) -> Unit = { imageProxy ->
        frameCounter++
        if (isArmed && _zones.value.isNotEmpty() && frameCounter % analyzeEveryNFrames == 0) {
            analyzeFrame(imageProxy)
        }
    }

    override suspend fun initialize() {
        cameraPlatform.addFrameListener(frameListener)

        eventBus.collect<AppEvent.CameraOpened>(scope) {
            motionDetector.reset()
        }
    }

    override suspend fun shutdown() {
        cameraPlatform.removeFrameListener(frameListener)
        motionDetector.reset()
        audioTrigger.stop()
    }

    override fun endpoints(): List<ApiEndpoint<*, *>> = listOf(
        AddZone(),
        RemoveZone(),
        ListZones(),
        ClearZones(),
        ArmTrigger(),
        DisarmTrigger(),
        GetTriggerStatus(),
        EnableAudioTrigger(),
        DisableAudioTrigger(),
        ConfigureAudioTrigger()
    )

    override fun healthCheck(): ModuleHealth {
        val msg = if (isArmed) "Armed, ${_zones.value.size} zones" else "Disarmed"
        return ModuleHealth(name, ModuleHealth.Status.OK, msg)
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        val currentZones = _zones.value
        if (currentZones.isEmpty()) return

        val results = motionDetector.detectMotionInZones(imageProxy, currentZones)
        val now = System.currentTimeMillis()

        for (result in results) {
            val state = zoneStates.getOrPut(result.zoneId) { ZoneMotionState(result.zoneId) }
            state.lastMotionScore = result.motionScore

            if (result.hasMotion && !state.wasMotionDetected) {
                // Motion just entered the zone — check cooldown
                val zone = currentZones.find { it.id == result.zoneId } ?: continue
                if (now - state.lastTriggerTime >= zone.cooldownMs) {
                    // TRIGGER!
                    state.lastTriggerTime = now
                    Log.i(TAG, "Zone ${result.zoneId} triggered! Motion: ${(result.motionScore * 100).toInt()}%")

                    // Fire shutter event — burst module will pick this up
                    eventBus.tryPublish(AppEvent.ShutterPressed(now))
                }
            }
            state.wasMotionDetected = result.hasMotion
        }
    }

    // --- af/zone/add ---
    inner class AddZone : ApiEndpoint<ZoneAddRequest, TriggerZone> {
        override val path = "af/zone/add"
        override val module = "trigger"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: ZoneAddRequest): ApiResponse<TriggerZone> {
            if (_zones.value.size >= 5) {
                return ApiResponse.error(400, "Maximum 5 zones allowed")
            }
            val zone = TriggerZone(
                id = nextZoneId++,
                centerX = request.centerX,
                centerY = request.centerY,
                radiusX = request.radiusX ?: 0.08f,
                radiusY = request.radiusY ?: 0.12f
            )
            _zones.value = _zones.value + zone
            zoneStates[zone.id] = ZoneMotionState(zone.id)
            // Auto-arm when first zone is added
            isArmed = true
            return ApiResponse.success(zone)
        }
    }

    // --- af/zone/remove ---
    inner class RemoveZone : ApiEndpoint<Int, Boolean> {
        override val path = "af/zone/remove"
        override val module = "trigger"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: Int): ApiResponse<Boolean> {
            _zones.value = _zones.value.filter { it.id != request }
            zoneStates.remove(request)
            if (_zones.value.isEmpty()) isArmed = false
            return ApiResponse.success(true)
        }
    }

    // --- af/zone/list ---
    inner class ListZones : ApiEndpoint<Unit, List<TriggerZone>> {
        override val path = "af/zone/list"
        override val module = "trigger"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: Unit): ApiResponse<List<TriggerZone>> {
            return ApiResponse.success(_zones.value)
        }
    }

    // --- af/zone/clear ---
    inner class ClearZones : ApiEndpoint<Unit, Boolean> {
        override val path = "af/zone/clear"
        override val module = "trigger"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: Unit): ApiResponse<Boolean> {
            _zones.value = emptyList()
            zoneStates.clear()
            isArmed = false
            motionDetector.reset()
            return ApiResponse.success(true)
        }
    }

    // --- trigger/zone/arm ---
    inner class ArmTrigger : ApiEndpoint<Unit, Boolean> {
        override val path = "trigger/zone/arm"
        override val module = "trigger"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: Unit): ApiResponse<Boolean> {
            if (_zones.value.isEmpty()) {
                return ApiResponse.error(400, "No zones configured")
            }
            isArmed = true
            motionDetector.reset()
            return ApiResponse.success(true)
        }
    }

    // --- trigger/zone/disarm ---
    inner class DisarmTrigger : ApiEndpoint<Unit, Boolean> {
        override val path = "trigger/zone/disarm"
        override val module = "trigger"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: Unit): ApiResponse<Boolean> {
            isArmed = false
            return ApiResponse.success(true)
        }
    }

    // --- trigger/zone/status ---
    inner class GetTriggerStatus : ApiEndpoint<Unit, TriggerStatus> {
        override val path = "trigger/zone/status"
        override val module = "trigger"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: Unit): ApiResponse<TriggerStatus> {
            return ApiResponse.success(
                TriggerStatus(
                    isArmed = isArmed,
                    zoneCount = _zones.value.size,
                    zoneMotionScores = zoneStates.map { (id, state) ->
                        id to state.lastMotionScore
                    }.toMap(),
                    isAudioArmed = isAudioArmed
                )
            )
        }
    }

    // --- trigger/audio/enable ---
    inner class EnableAudioTrigger : ApiEndpoint<Unit, Boolean> {
        override val path = "trigger/audio/enable"
        override val module = "trigger"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: Unit): ApiResponse<Boolean> {
            audioTrigger.start(scope)
            isAudioArmed = true
            return ApiResponse.success(true)
        }
    }

    // --- trigger/audio/disable ---
    inner class DisableAudioTrigger : ApiEndpoint<Unit, Boolean> {
        override val path = "trigger/audio/disable"
        override val module = "trigger"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: Unit): ApiResponse<Boolean> {
            audioTrigger.stop()
            isAudioArmed = false
            return ApiResponse.success(true)
        }
    }

    // --- trigger/audio/config ---
    inner class ConfigureAudioTrigger : ApiEndpoint<AudioTriggerConfig, Boolean> {
        override val path = "trigger/audio/config"
        override val module = "trigger"
        override val requiredMode: AppMode? = null

        override suspend fun handle(request: AudioTriggerConfig): ApiResponse<Boolean> {
            audioTrigger.sensitivity = request.sensitivity
            return ApiResponse.success(true)
        }
    }

    companion object {
        private const val TAG = "TriggerModule"
    }
}

data class ZoneAddRequest(
    val centerX: Float,
    val centerY: Float,
    val radiusX: Float? = null,
    val radiusY: Float? = null
)

data class TriggerStatus(
    val isArmed: Boolean,
    val zoneCount: Int,
    val zoneMotionScores: Map<Int, Float>,
    val isAudioArmed: Boolean = false
)

data class AudioTriggerConfig(
    val sensitivity: Float = 0.5f   // 0.0 = least sensitive, 1.0 = most sensitive
)
