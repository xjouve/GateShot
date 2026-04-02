package com.gateshot.platform.camera

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.gateshot.platform.sensor.SensorPlatform
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thermal-aware frame processing throttle.
 *
 * During sustained 4K@120fps recording with the Dimensity 9500, the SoC
 * temperature rises. Android's thermal framework will eventually throttle
 * CPU/GPU clocks, causing frame drops and recording stutters.
 *
 * This module monitors thermal state and proactively reduces processing
 * load BEFORE Android forces throttling:
 *
 * THERMAL STATES:
 * - NOMINAL (< 38°C battery): Full processing — all listeners active
 * - WARM (38-42°C): Reduce non-critical processing — skip bib detection, slow snow analysis
 * - HOT (42-45°C): Minimal processing — only buffer + tracker
 * - CRITICAL (> 45°C): Emergency — stop recording, alert user
 *
 * THROTTLE ACTIONS:
 * 1. Increase frame skip intervals for low-priority listeners
 * 2. Disable AI upscaling (CPU/NPU intensive)
 * 3. Reduce analysis resolution (process at 720p instead of 4K)
 * 4. Disable face detection and gate detection
 * 5. As last resort: lower video resolution or frame rate
 *
 * Battery temperature is the most accessible thermal proxy on Android.
 * On API 30+, we also check PowerManager thermal status.
 */
@Singleton
class ThermalThrottle @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sensorPlatform: SensorPlatform
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    enum class ThermalState {
        NOMINAL,    // Full processing, no restrictions
        WARM,       // Reduce non-critical processing
        HOT,        // Minimal processing only
        CRITICAL    // Emergency — stop intensive operations
    }

    data class ThermalStatus(
        val state: ThermalState,
        val batteryTempCelsius: Float,
        val platformThermalStatus: Int,    // PowerManager.THERMAL_STATUS_*
        val processingBudgetMultiplier: Float,  // 1.0 = full, 0.5 = half budget
        val aiUpscalingAllowed: Boolean,
        val faceDetectionAllowed: Boolean,
        val gateDetectionAllowed: Boolean,
        val maxAnalysisResolution: Int      // Max width for analysis frames
    )

    private val _thermalStatus = MutableStateFlow(ThermalStatus(
        state = ThermalState.NOMINAL,
        batteryTempCelsius = 25f,
        platformThermalStatus = 0,
        processingBudgetMultiplier = 1f,
        aiUpscalingAllowed = true,
        faceDetectionAllowed = true,
        gateDetectionAllowed = true,
        maxAnalysisResolution = 3840
    ))
    val thermalStatus: StateFlow<ThermalStatus> = _thermalStatus.asStateFlow()

    // Thermal thresholds (battery temperature in °C)
    companion object {
        private const val TEMP_WARM = 38f
        private const val TEMP_HOT = 42f
        private const val TEMP_CRITICAL = 45f
        private const val POLL_INTERVAL_MS = 5000L
    }

    private var isMonitoring = false

    fun startMonitoring() {
        if (isMonitoring) return
        isMonitoring = true

        scope.launch {
            while (isMonitoring) {
                updateThermalState()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stopMonitoring() {
        isMonitoring = false
    }

    private fun updateThermalState() {
        val batteryTemp = sensorPlatform.getBatteryTemperature() ?: 25f

        // Check Android thermal framework status (API 29+)
        val platformStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                powerManager.currentThermalStatus
            } catch (_: Exception) { 0 }
        } else 0

        // Determine thermal state from both sources
        val state = when {
            batteryTemp >= TEMP_CRITICAL || platformStatus >= 4 -> ThermalState.CRITICAL
            batteryTemp >= TEMP_HOT || platformStatus >= 3 -> ThermalState.HOT
            batteryTemp >= TEMP_WARM || platformStatus >= 2 -> ThermalState.WARM
            else -> ThermalState.NOMINAL
        }

        _thermalStatus.value = ThermalStatus(
            state = state,
            batteryTempCelsius = batteryTemp,
            platformThermalStatus = platformStatus,
            processingBudgetMultiplier = when (state) {
                ThermalState.NOMINAL -> 1.0f
                ThermalState.WARM -> 0.7f
                ThermalState.HOT -> 0.4f
                ThermalState.CRITICAL -> 0.1f
            },
            aiUpscalingAllowed = state == ThermalState.NOMINAL || state == ThermalState.WARM,
            faceDetectionAllowed = state == ThermalState.NOMINAL,
            gateDetectionAllowed = state != ThermalState.CRITICAL,
            maxAnalysisResolution = when (state) {
                ThermalState.NOMINAL -> 3840
                ThermalState.WARM -> 1920
                ThermalState.HOT -> 1280
                ThermalState.CRITICAL -> 640
            }
        )
    }

    /**
     * Adjust the FrameProcessorThrottle based on current thermal state.
     * Called by the camera pipeline to apply thermal-aware frame budgets.
     */
    fun applyToThrottle(throttle: FrameProcessorThrottle) {
        val status = _thermalStatus.value
        val baseBudget = throttle.getTotalLoadMs()

        // Reduce effective frame budget based on thermal state
        // This causes the throttle to skip more low-priority listeners
        when (status.state) {
            ThermalState.NOMINAL -> { /* No adjustment needed */ }
            ThermalState.WARM -> {
                // Simulate a tighter budget so low-priority listeners get skipped
                throttle.setTargetFps(20)  // Pretend we're at 20fps budget
            }
            ThermalState.HOT -> {
                throttle.setTargetFps(15)
            }
            ThermalState.CRITICAL -> {
                throttle.setTargetFps(10)
            }
        }
    }
}
