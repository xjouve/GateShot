package com.gateshot.ui.annotation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gateshot.ui.MainViewModel

enum class DrawTool { FREEHAND, LINE, ARROW, CIRCLE }

data class DrawingStroke(
    val points: List<Offset>,
    val color: Color,
    val strokeWidth: Float,
    val tool: DrawTool
)

@Composable
fun AnnotationScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    var selectedTool by remember { mutableStateOf(DrawTool.FREEHAND) }
    var selectedColor by remember { mutableStateOf(Color.Red) }
    var strokeWidth by remember { mutableStateOf(4f) }
    var isRecordingVoice by remember { mutableStateOf(false) }

    val strokes = remember { mutableStateListOf<DrawingStroke>() }
    val currentPoints = remember { mutableStateListOf<Offset>() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A1A))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Annotate", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Row {
                IconButton(onClick = { if (strokes.isNotEmpty()) strokes.removeLast() }) {
                    Icon(Icons.Filled.Undo, "Undo", tint = Color.White)
                }
                IconButton(onClick = { strokes.clear() }) {
                    Icon(Icons.Filled.Delete, "Clear all", tint = Color(0xFFEF5350))
                }
                IconButton(onClick = { viewModel.onSaveAnnotatedFrame() }) {
                    Icon(Icons.Filled.Save, "Save", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // Drawing canvas over video frame
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF111111))
                .pointerInput(selectedTool, selectedColor) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            currentPoints.clear()
                            currentPoints.add(offset)
                        },
                        onDrag = { change, _ ->
                            currentPoints.add(change.position)
                        },
                        onDragEnd = {
                            if (currentPoints.isNotEmpty()) {
                                strokes.add(
                                    DrawingStroke(
                                        points = currentPoints.toList(),
                                        color = selectedColor,
                                        strokeWidth = strokeWidth,
                                        tool = selectedTool
                                    )
                                )
                                currentPoints.clear()
                            }
                        }
                    )
                }
        ) {
            // Video frame canvas — shows the paused frame from the replay player
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("Tap pause on replay to capture frame", color = Color(0xFF444444), fontSize = 14.sp)
            }

            // Drawing overlay
            Canvas(modifier = Modifier.fillMaxSize()) {
                // Completed strokes
                strokes.forEach { stroke ->
                    drawStroke(stroke)
                }
                // Current active stroke
                if (currentPoints.isNotEmpty()) {
                    drawStroke(
                        DrawingStroke(currentPoints.toList(), selectedColor, strokeWidth, selectedTool)
                    )
                }
            }
        }

        // Tool palette — large buttons for gloves
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A1A))
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Drawing tools
            listOf(
                DrawTool.FREEHAND to Icons.Filled.Draw,
                DrawTool.LINE to Icons.Filled.Timeline,
                DrawTool.ARROW to Icons.Filled.ArrowForward,
                DrawTool.CIRCLE to Icons.Filled.RadioButtonUnchecked
            ).forEach { (tool, icon) ->
                Surface(
                    onClick = { selectedTool = tool },
                    shape = RoundedCornerShape(8.dp),
                    color = if (selectedTool == tool) MaterialTheme.colorScheme.primary else Color(0xFF333333),
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, tool.name, tint = if (selectedTool == tool) Color.Black else Color.White, modifier = Modifier.size(24.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Color picks — high-vis colors for snow
            listOf(Color.Red, Color.Yellow, Color(0xFF4FC3F7), Color.Green).forEach { color ->
                Surface(
                    onClick = { selectedColor = color },
                    shape = CircleShape,
                    color = color,
                    modifier = Modifier.size(36.dp),
                    shadowElevation = if (selectedColor == color) 4.dp else 0.dp
                ) {
                    if (selectedColor == color) {
                        Box(contentAlignment = Alignment.Center) {
                            Surface(
                                shape = CircleShape,
                                color = Color.White,
                                modifier = Modifier.size(12.dp)
                            ) {}
                        }
                    }
                }
            }
        }

        // Voice-over controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A1A))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                onClick = {
                    isRecordingVoice = !isRecordingVoice
                    if (isRecordingVoice) {
                        viewModel.startVoiceRecording()
                    } else {
                        viewModel.stopVoiceRecording()
                    }
                },
                shape = RoundedCornerShape(24.dp),
                color = if (isRecordingVoice) MaterialTheme.colorScheme.error else Color(0xFF333333),
                modifier = Modifier.size(width = 200.dp, height = 48.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        if (isRecordingVoice) Icons.Filled.MicOff else Icons.Filled.Mic,
                        "Voice-over",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (isRecordingVoice) "Stop Voice-Over" else "Record Voice-Over",
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStroke(stroke: DrawingStroke) {
    if (stroke.points.size < 2) return

    when (stroke.tool) {
        DrawTool.FREEHAND -> {
            val path = Path().apply {
                moveTo(stroke.points.first().x, stroke.points.first().y)
                for (i in 1 until stroke.points.size) {
                    lineTo(stroke.points[i].x, stroke.points[i].y)
                }
            }
            drawPath(
                path,
                color = stroke.color,
                style = Stroke(width = stroke.strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
        DrawTool.LINE -> {
            drawLine(
                color = stroke.color,
                start = stroke.points.first(),
                end = stroke.points.last(),
                strokeWidth = stroke.strokeWidth,
                cap = StrokeCap.Round
            )
        }
        DrawTool.ARROW -> {
            val start = stroke.points.first()
            val end = stroke.points.last()
            drawLine(stroke.color, start, end, stroke.strokeWidth, cap = StrokeCap.Round)
            // Arrowhead
            val dx = end.x - start.x
            val dy = end.y - start.y
            val len = kotlin.math.sqrt(dx * dx + dy * dy)
            if (len > 0) {
                val ux = dx / len
                val uy = dy / len
                val headLen = 20f
                val headWidth = 10f
                val p1 = Offset(end.x - headLen * ux + headWidth * uy, end.y - headLen * uy - headWidth * ux)
                val p2 = Offset(end.x - headLen * ux - headWidth * uy, end.y - headLen * uy + headWidth * ux)
                drawLine(stroke.color, end, p1, stroke.strokeWidth, cap = StrokeCap.Round)
                drawLine(stroke.color, end, p2, stroke.strokeWidth, cap = StrokeCap.Round)
            }
        }
        DrawTool.CIRCLE -> {
            val center = stroke.points.first()
            val edge = stroke.points.last()
            val radius = kotlin.math.sqrt(
                (edge.x - center.x).let { it * it } + (edge.y - center.y).let { it * it }
            )
            drawCircle(
                color = stroke.color,
                radius = radius,
                center = center,
                style = Stroke(width = stroke.strokeWidth)
            )
        }
    }
}
