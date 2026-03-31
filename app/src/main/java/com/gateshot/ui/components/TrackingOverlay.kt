package com.gateshot.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp

@Composable
fun TrackingOverlay(
    targetX: Float,
    targetY: Float,
    regionSize: Float,
    hasLock: Boolean,
    isOccluded: Boolean,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val cx = targetX * w
        val cy = targetY * h
        val boxW = regionSize * w
        val boxH = regionSize * h

        val color = when {
            isOccluded -> Color(0xFFFF9800)    // Orange — occluded, holding
            hasLock -> Color(0xFF4CAF50)         // Green — locked on racer
            else -> Color(0xFF9E9E9E)            // Gray — scanning
        }

        // Tracking bracket corners (not a full rectangle — cleaner look)
        val cornerLen = boxW * 0.25f
        val strokeWidth = 3f

        // Top-left corner
        drawLine(color, Offset(cx - boxW / 2, cy - boxH / 2), Offset(cx - boxW / 2 + cornerLen, cy - boxH / 2), strokeWidth)
        drawLine(color, Offset(cx - boxW / 2, cy - boxH / 2), Offset(cx - boxW / 2, cy - boxH / 2 + cornerLen), strokeWidth)
        // Top-right corner
        drawLine(color, Offset(cx + boxW / 2, cy - boxH / 2), Offset(cx + boxW / 2 - cornerLen, cy - boxH / 2), strokeWidth)
        drawLine(color, Offset(cx + boxW / 2, cy - boxH / 2), Offset(cx + boxW / 2, cy - boxH / 2 + cornerLen), strokeWidth)
        // Bottom-left corner
        drawLine(color, Offset(cx - boxW / 2, cy + boxH / 2), Offset(cx - boxW / 2 + cornerLen, cy + boxH / 2), strokeWidth)
        drawLine(color, Offset(cx - boxW / 2, cy + boxH / 2), Offset(cx - boxW / 2, cy + boxH / 2 - cornerLen), strokeWidth)
        // Bottom-right corner
        drawLine(color, Offset(cx + boxW / 2, cy + boxH / 2), Offset(cx + boxW / 2 - cornerLen, cy + boxH / 2), strokeWidth)
        drawLine(color, Offset(cx + boxW / 2, cy + boxH / 2), Offset(cx + boxW / 2, cy + boxH / 2 - cornerLen), strokeWidth)

        // Center crosshair (small)
        val crossSize = 8f
        drawLine(color, Offset(cx - crossSize, cy), Offset(cx + crossSize, cy), 2f)
        drawLine(color, Offset(cx, cy - crossSize), Offset(cx, cy + crossSize), 2f)

        // Status label
        val label = when {
            isOccluded -> "HOLDING"
            hasLock -> "LOCKED"
            else -> "SCAN"
        }
        val textResult = textMeasurer.measure(
            label,
            style = TextStyle(fontSize = 10.sp, color = color)
        )
        drawText(
            textResult,
            topLeft = Offset(cx - textResult.size.width / 2, cy - boxH / 2 - textResult.size.height - 4)
        )
    }
}
