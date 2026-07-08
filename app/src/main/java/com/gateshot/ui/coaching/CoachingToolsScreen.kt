package com.gateshot.ui.coaching

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gateshot.ui.MainViewModel

/**
 * Coaching Tools screen — local coaching tools.
 * - Ideal line drawing (trace the ideal racing line on a course overview photo)
 *
 * The backend-less tools (multi-camera audio sync, remote-coaching package
 * export, team feed, cloud backup) were removed — see docs/REMOVED_FEATURES.md.
 */
@Composable
fun CoachingToolsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val session = viewModel.coachSession
    var expandedSection by remember { mutableStateOf(session.toolsExpandedSection) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "Coaching Tools",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp)
        )

        // --- Ideal Line Drawing ---
        ToolCard(
            title = "Ideal Line",
            subtitle = "Draw the ideal racing line on a course overview photo",
            icon = Icons.Filled.Route,
            expanded = expandedSection == "line",
            onClick = {
                expandedSection = if (expandedSection == "line") null else "line"
                session.toolsExpandedSection = expandedSection
            }
        ) {
            IdealLineContent(viewModel, context)
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun ToolCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    expanded: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (expanded) Color(0xFF0D1B2A) else Color(0xFF1A1A1A),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(subtitle, color = Color.Gray, fontSize = 12.sp)
                }
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = Color(0xFF2A3A4A))
                Spacer(modifier = Modifier.height(12.dp))
                content()
            }
        }
    }
}

// --- Ideal Line Drawing ---
@Composable
private fun IdealLineContent(viewModel: MainViewModel, context: android.content.Context) {
    // The drawn line survives navigation via the VM
    val session = viewModel.coachSession
    val linePoints = remember { mutableStateListOf<Offset>().also { it.addAll(session.idealLinePoints) } }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { session.idealLinePoints = linePoints.toList() }
    }
    var courseImage by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("1. Take a photo of the course from the side", color = Color.Gray, fontSize = 13.sp)
        Text("2. Draw the ideal racing line below", color = Color.Gray, fontSize = 13.sp)

        // Load latest photo as course overview
        val latestPhoto = remember {
            val dir = java.io.File(context.getExternalFilesDir(null), "GateShot/photos")
            dir.listFiles()?.filter { it.extension in listOf("jpg", "jpeg") }
                ?.maxByOrNull { it.lastModified() }
        }

        if (latestPhoto != null && courseImage == null) {
            courseImage = remember {
                try {
                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                    android.graphics.BitmapFactory.decodeFile(latestPhoto.absolutePath, opts)
                } catch (_: Exception) { null }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp)
                .background(Color(0xFF111111), RoundedCornerShape(8.dp))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, _ -> linePoints.add(change.position) }
                    )
                }
        ) {
            courseImage?.let { bmp ->
                androidx.compose.foundation.Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Course",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Draw ideal line
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (linePoints.size > 1) {
                    val path = Path().apply {
                        moveTo(linePoints[0].x, linePoints[0].y)
                        for (i in 1 until linePoints.size) {
                            lineTo(linePoints[i].x, linePoints[i].y)
                        }
                    }
                    drawPath(
                        path,
                        color = Color(0xFF66BB6A),
                        style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
            }

            if (linePoints.isEmpty() && courseImage == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No course photo available", color = Color(0xFF444444), fontSize = 13.sp)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                onClick = { linePoints.clear() },
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF333333)
            ) {
                Text("Clear", color = Color.White, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
            Surface(
                onClick = {
                    viewModel.saveIdealLine(linePoints.map { it.x to it.y })
                },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primary
            ) {
                Text("Save Line", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
    }
}
