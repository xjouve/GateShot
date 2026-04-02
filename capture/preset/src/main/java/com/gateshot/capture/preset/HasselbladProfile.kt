package com.gateshot.capture.preset

import android.hardware.camera2.CaptureRequest

/**
 * Hasselblad Natural Color tone curves for the Oppo Find X9 Pro.
 *
 * Applied via Camera2 TONEMAP_CURVE with CONTRAST_CURVE mode.
 * Characteristics: lifted blacks, soft highlight rolloff, warm midtones.
 */
object HasselbladProfile {

    data class TonemapCurve(
        val red: FloatArray,
        val green: FloatArray,
        val blue: FloatArray
    )

    // Oplus vendor tags for hardware WB control
    val KEY_OPLUS_WB_CCT: CaptureRequest.Key<Int> = CaptureRequest.Key(
        "com.oplus.manualWB.color_temperature", Int::class.java
    )
    val KEY_OPLUS_WB_TINT: CaptureRequest.Key<Int> = CaptureRequest.Key(
        "com.oplus.manualWB.color_tone", Int::class.java
    )

    // Tone curves derived from Oppo Find X9 Pro's native Hasselblad
    // Haute Résolution mode. The Hasselblad look is subtle: slight shadow
    // lift, gentle S-curve contrast, warm-neutral tonality, soft highlights.
    // Per-channel differences are concentrated in the shadows.

    // Near-identity curves with subtle Hasselblad character:
    // - Slight shadow lift (blacks not crushed)
    // - Gentle highlight rolloff (soft clipping)
    // - Red slightly warm in shadows, blue slightly cool-suppressed

    // Curves must be BRIGHTER than identity because CONTRAST_CURVE mode
    // replaces the ISP's default tone mapping (which aggressively boosts
    // brightness). These curves approximate the ISP's default S-curve
    // with Hasselblad character: warm shadows, soft highlight rolloff.

    // Red: warm shadow lift, ISP-matched S-curve brightness
    private val CURVE_RED = floatArrayOf(
        0.000f, 0.035f, 0.067f, 0.120f, 0.133f, 0.220f, 0.200f, 0.330f,
        0.267f, 0.430f, 0.333f, 0.520f, 0.400f, 0.600f, 0.467f, 0.665f,
        0.533f, 0.725f, 0.600f, 0.778f, 0.667f, 0.825f, 0.733f, 0.865f,
        0.800f, 0.900f, 0.867f, 0.932f, 0.933f, 0.960f, 1.000f, 0.990f
    )

    // Green: neutral shadow lift, ISP-matched S-curve brightness
    private val CURVE_GREEN = floatArrayOf(
        0.000f, 0.020f, 0.067f, 0.110f, 0.133f, 0.210f, 0.200f, 0.320f,
        0.267f, 0.420f, 0.333f, 0.510f, 0.400f, 0.590f, 0.467f, 0.658f,
        0.533f, 0.720f, 0.600f, 0.775f, 0.667f, 0.822f, 0.733f, 0.862f,
        0.800f, 0.898f, 0.867f, 0.930f, 0.933f, 0.958f, 1.000f, 0.990f
    )

    // Blue: cooler shadow lift (warmth), ISP-matched S-curve brightness
    private val CURVE_BLUE = floatArrayOf(
        0.000f, 0.015f, 0.067f, 0.100f, 0.133f, 0.195f, 0.200f, 0.305f,
        0.267f, 0.405f, 0.333f, 0.495f, 0.400f, 0.578f, 0.467f, 0.648f,
        0.533f, 0.712f, 0.600f, 0.768f, 0.667f, 0.816f, 0.733f, 0.856f,
        0.800f, 0.892f, 0.867f, 0.924f, 0.933f, 0.952f, 1.000f, 0.985f
    )

    fun buildTonemapCurve(): TonemapCurve = TonemapCurve(
        red = CURVE_RED,
        green = CURVE_GREEN,
        blue = CURVE_BLUE
    )
}
