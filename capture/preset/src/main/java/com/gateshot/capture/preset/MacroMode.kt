package com.gateshot.capture.preset

import com.gateshot.platform.camera.CameraXPlatform
import com.gateshot.platform.camera.ManualExposure
import com.gateshot.platform.camera.StabilizationConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Macro mode for equipment inspection using the telephoto's 10cm minimum focus.
 *
 * The Oppo Find X9 Pro's 200MP telephoto can focus down to 10cm, enabling
 * detailed close-up photography that's valuable for ski racing equipment work:
 *
 * - **Ski edge inspection**: Check edge angle, burrs, rust, and sharpness.
 *   A 200MP macro at 10cm resolves individual edge striations.
 * - **Wax condition**: Inspect base texture and wax coverage. Different wax
 *   patterns are visible at macro distances.
 * - **Binding settings**: Document DIN settings, forward pressure marks,
 *   and boot-to-binding interface for each athlete.
 * - **Boot buckle positions**: Record micro-adjust positions for consistent
 *   setup across race days.
 * - **Bib number documentation**: Close-up of bib for file organization.
 *
 * TECHNICAL NOTES:
 * At 10cm with a 70mm equivalent lens:
 * - Field of view ≈ 30mm × 22mm (approximately a postage stamp)
 * - At 200MP (16384 × 12288), this gives ~550 pixels/mm = 1.8µm/pixel
 * - Depth of field at f/2.1 and 10cm: ~0.5mm — extremely shallow
 * - OIS is critical (maximum mode) because any shake at this magnification
 *   moves the frame by many pixels
 * - We use CONTROL_AF_MODE_MACRO or manual focus with focus distance set
 *   to the minimum focus distance reported by the camera
 */
@Singleton
class MacroMode @Inject constructor(
    private val camera: CameraXPlatform
) {
    data class MacroConfig(
        val autoFocus: Boolean = true,       // Use AF macro mode vs manual focus
        val focusDistanceDioptres: Float = 10f,  // 10 dioptres = 10cm focus distance
        val stabilization: Boolean = true,    // Maximum OIS (critical for macro)
        val flashAssist: Boolean = false      // LED flash as focus assist light
    )

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private var savedConfig: MacroConfig = MacroConfig()

    /**
     * Enter macro mode.
     *
     * Sets focus distance to minimum (10cm), enables maximum OIS, and configures
     * exposure for close-up work (slightly stopped down for more DoF, moderate ISO).
     */
    fun enter(config: MacroConfig = MacroConfig()) {
        savedConfig = config

        if (config.autoFocus) {
            // Let Camera2 handle macro focus with continuous AF
            // AF will hunt less if we set a hint focus distance near minimum
            camera.setFocusDistance(0f)  // 0 = clear manual override, use AF
        } else {
            // Manual focus locked at minimum distance
            val minFocusDist = camera.capabilities?.minFocusDistance ?: 10f
            camera.setFocusDistance(minFocusDist)
        }

        // Maximum OIS — at 10cm magnification, any shake is catastrophic
        camera.setStabilization(StabilizationConfig(
            opticalStabilization = true,
            videoStabilization = false  // No EIS crop — we need every pixel
        ))

        // Exposure for macro: moderate shutter speed (1/250 is fast enough since
        // equipment is stationary), low ISO for maximum detail
        camera.setManualExposure(ManualExposure(
            shutterSpeedNs = 4_000_000,  // 1/250 second
            iso = 100,
            enabled = true
        ))

        _isActive.value = true
    }

    /**
     * Exit macro mode, restoring normal focus and exposure settings.
     */
    fun exit() {
        camera.setFocusDistance(0f)  // Clear manual focus
        camera.setManualExposure(ManualExposure(enabled = false))  // Back to auto
        _isActive.value = false
    }

    /**
     * Fine-tune focus distance for manual macro work.
     * Dioptres = 1 / distance_in_meters.
     * 10 dioptres = 10cm, 5 dioptres = 20cm, etc.
     */
    fun setFocusDistance(dioptres: Float) {
        val maxDioptres = camera.capabilities?.minFocusDistance ?: 10f
        camera.setFocusDistance(dioptres.coerceIn(0f, maxDioptres))
    }

    /**
     * Quick macro preset for common inspection tasks.
     */
    fun applyInspectionPreset(type: InspectionType) {
        when (type) {
            InspectionType.SKI_EDGE -> {
                // Edges need maximum sharpness — manual focus, lowest ISO
                enter(MacroConfig(autoFocus = false, focusDistanceDioptres = 10f))
                camera.setManualExposure(ManualExposure(
                    shutterSpeedNs = 2_000_000, // 1/500 (edges are reflective)
                    iso = 100,
                    enabled = true
                ))
            }
            InspectionType.WAX_CONDITION -> {
                // Wax texture is subtle — autofocus works, need good lighting
                enter(MacroConfig(autoFocus = true, flashAssist = true))
            }
            InspectionType.BINDING_SETTINGS -> {
                // DIN numbers on bindings — slightly further back (15-20cm)
                enter(MacroConfig(autoFocus = true, focusDistanceDioptres = 6f))
            }
            InspectionType.BOOT_BUCKLE -> {
                // Buckle micro-adjust positions — moderate macro
                enter(MacroConfig(autoFocus = true, focusDistanceDioptres = 7f))
            }
            InspectionType.BIB_CLOSEUP -> {
                // Bib numbers — furthest macro distance
                enter(MacroConfig(autoFocus = true, focusDistanceDioptres = 4f))
            }
        }
    }

    enum class InspectionType {
        SKI_EDGE,
        WAX_CONDITION,
        BINDING_SETTINGS,
        BOOT_BUCKLE,
        BIB_CLOSEUP
    }
}
