package com.gateshot.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.gateshot.capture.trigger.TriggerZone

@Composable
fun ZoneOverlay(
    zones: List<TriggerZone>,
    isArmed: Boolean,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        zones.forEach { zone ->
            val cx = zone.centerX * w
            val cy = zone.centerY * h
            val rx = zone.radiusX * w
            val ry = zone.radiusY * h

            // Zone ellipse
            val zoneColor = if (isArmed) Color(0xFF4FC3F7) else Color.Gray
            drawOval(
                color = zoneColor.copy(alpha = 0.3f),
                topLeft = Offset(cx - rx, cy - ry),
                size = Size(rx * 2, ry * 2)
            )
            drawOval(
                color = zoneColor,
                topLeft = Offset(cx - rx, cy - ry),
                size = Size(rx * 2, ry * 2),
                style = Stroke(width = 2f)
            )

            // Zone ID label
            val label = "Z${zone.id}"
            val textResult = textMeasurer.measure(
                label,
                style = TextStyle(fontSize = 12.sp, color = Color.White)
            )
            drawText(
                textResult,
                topLeft = Offset(cx - textResult.size.width / 2, cy - textResult.size.height / 2)
            )
        }
    }
}
