package com.gateshot.capture.trigger

import kotlinx.serialization.Serializable

@Serializable
data class TriggerZone(
    val id: Int,
    // Normalized coordinates (0.0 - 1.0) relative to the frame
    val centerX: Float,
    val centerY: Float,
    val radiusX: Float = 0.08f,   // ~8% of frame width
    val radiusY: Float = 0.12f,   // ~12% of frame height
    val isArmed: Boolean = true,
    val cooldownMs: Long = 500    // Min time between triggers for same zone
) {
    fun containsPoint(x: Float, y: Float): Boolean {
        val dx = (x - centerX) / radiusX
        val dy = (y - centerY) / radiusY
        return (dx * dx + dy * dy) <= 1f
    }
}

data class ZoneMotionState(
    val zoneId: Int,
    var lastMotionScore: Float = 0f,
    var lastTriggerTime: Long = 0,
    var wasMotionDetected: Boolean = false
)
