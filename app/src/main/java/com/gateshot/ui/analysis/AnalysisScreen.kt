package com.gateshot.ui.analysis

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gateshot.ui.MainViewModel

/**
 * Analysis screen — consolidates all coaching analysis tools:
 * - Consistency tracker (per-gate variability across runs)
 * - Turn analysis dashboard (entry speed, apex, line choice per gate)
 * - Error pattern detection (recurring technique issues)
 * - Time-to-technique correlation (timing deltas linked to video evidence)
 * - Session report (PDF export of the training day)
 * - Before/after progress view (cross-session comparison)
 */
@Composable
fun AnalysisScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var expandedSection by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
    ) {
        // Header
        Text(
            "Analysis",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp)
        )

        if (uiState.sessionName == null) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Assessment, null, tint = Color(0xFF444444), modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Start a training session first", color = Color.Gray, fontSize = 14.sp)
                    Text("Analysis requires recorded runs with gate timestamps", color = Color(0xFF666666), fontSize = 12.sp)
                }
            }
        } else {
            Text(
                "Session: ${uiState.sessionName} (${uiState.sessionDiscipline})",
                color = Color(0xFF4FC3F7),
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            // --- Consistency Tracker ---
            AnalysisCard(
                title = "Consistency Tracker",
                subtitle = "Per-gate variability across all runs in this session",
                icon = Icons.Filled.ShowChart,
                expanded = expandedSection == "consistency",
                onClick = { expandedSection = if (expandedSection == "consistency") null else "consistency" }
            ) {
                ConsistencyContent(viewModel)
            }

            // --- Turn Analysis ---
            AnalysisCard(
                title = "Turn Analysis",
                subtitle = "Entry speed, apex position, line choice per gate",
                icon = Icons.Filled.Timeline,
                expanded = expandedSection == "turn",
                onClick = { expandedSection = if (expandedSection == "turn") null else "turn" }
            ) {
                TurnAnalysisContent(viewModel)
            }

            // --- Error Patterns ---
            AnalysisCard(
                title = "Error Patterns",
                subtitle = "Recurring technique issues detected across runs",
                icon = Icons.Filled.BugReport,
                expanded = expandedSection == "errors",
                onClick = { expandedSection = if (expandedSection == "errors") null else "errors" }
            ) {
                ErrorPatternContent(viewModel)
            }

            // --- Time-to-Technique ---
            AnalysisCard(
                title = "Time-to-Technique",
                subtitle = "Where time was gained or lost, linked to video",
                icon = Icons.Filled.CompareArrows,
                expanded = expandedSection == "time",
                onClick = { expandedSection = if (expandedSection == "time") null else "time" }
            ) {
                TimeToTechniqueContent(viewModel)
            }

            // --- Session Report ---
            AnalysisCard(
                title = "Session Report",
                subtitle = "Generate PDF summary of today's training",
                icon = Icons.Filled.PictureAsPdf,
                expanded = expandedSection == "report",
                onClick = { expandedSection = if (expandedSection == "report") null else "report" }
            ) {
                SessionReportContent(viewModel, context)
            }

            // --- Before/After Progress ---
            AnalysisCard(
                title = "Progress View",
                subtitle = "Compare technique across sessions over time",
                icon = Icons.Filled.Assessment,
                expanded = expandedSection == "progress",
                onClick = { expandedSection = if (expandedSection == "progress") null else "progress" }
            ) {
                ProgressContent(viewModel)
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun AnalysisCard(
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
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

// --- Consistency Tracker ---
@Composable
private fun ConsistencyContent(viewModel: MainViewModel) {
    var results by remember { mutableStateOf<List<Triple<Int, Float, String>>>(emptyList()) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.runConsistencyAnalysis { results = it }
    }

    if (results.isEmpty()) {
        Text("Need 3+ runs with gate timestamps for analysis", color = Color.Gray, fontSize = 13.sp)
    } else {
        results.forEach { (gate, variability, assessment) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Gate $gate", color = Color.White, fontSize = 13.sp)
                Text(
                    "${"%.0f".format(variability)}ms spread — $assessment",
                    color = when (assessment) {
                        "consistent" -> Color(0xFF66BB6A)
                        "variable" -> Color(0xFFFFAB40)
                        else -> Color(0xFFEF5350)
                    },
                    fontSize = 13.sp
                )
            }
        }
    }
}

// --- Turn Analysis ---
@Composable
private fun TurnAnalysisContent(viewModel: MainViewModel) {
    var metrics by remember { mutableStateOf<List<Map<String, String>>>(emptyList()) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.runTurnAnalysis { metrics = it }
    }

    if (metrics.isEmpty()) {
        Text("Need runs with gate timestamps and pose data", color = Color.Gray, fontSize = 13.sp)
    } else {
        // Header
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("Gate", "Split", "Line", "Knee").forEach {
                Text(it, color = Color(0xFF8899AA), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            }
        }
        metrics.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(row["gate"] ?: "", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Text(row["split"] ?: "", color = Color(0xFF4FC3F7), fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Text(row["line"] ?: "", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Text(row["knee"] ?: "", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            }
        }
    }
}

// --- Error Patterns ---
@Composable
private fun ErrorPatternContent(viewModel: MainViewModel) {
    var errors by remember { mutableStateOf<List<Map<String, String>>>(emptyList()) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.getErrorPatterns { errors = it }
    }

    if (errors.isEmpty()) {
        Text("No recurring error patterns detected yet", color = Color.Gray, fontSize = 13.sp)
        Text("Record more runs to build pattern data", color = Color(0xFF666666), fontSize = 12.sp)
    } else {
        errors.forEach { error ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF2A1A1A),
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        error["pattern"] ?: "Unknown pattern",
                        color = Color(0xFFEF9A9A),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Severity: ${error["severity"]}", color = Color.Gray, fontSize = 11.sp)
                        Text("Trend: ${error["trend"]}", color = when(error["trend"]) {
                            "improving" -> Color(0xFF66BB6A)
                            "regressing" -> Color(0xFFEF5350)
                            else -> Color(0xFFFFAB40)
                        }, fontSize = 11.sp)
                        Text("Count: ${error["count"]}", color = Color.Gray, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

// --- Time-to-Technique ---
@Composable
private fun TimeToTechniqueContent(viewModel: MainViewModel) {
    var deltas by remember { mutableStateOf<List<Map<String, String>>>(emptyList()) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.getTimeToTechniqueCorrelation { deltas = it }
    }

    if (deltas.isEmpty()) {
        Text("Need 2+ runs with gate timestamps for comparison", color = Color.Gray, fontSize = 13.sp)
    } else {
        deltas.forEach { delta ->
            val deltaMs = delta["deltaMs"]?.toIntOrNull() ?: 0
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Gate ${delta["gate"]}", color = Color.White, fontSize = 13.sp)
                Text(
                    "${if (deltaMs > 0) "+" else ""}${deltaMs}ms",
                    color = if (deltaMs > 0) Color(0xFFEF5350) else Color(0xFF66BB6A),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(delta["reason"] ?: "", color = Color.Gray, fontSize = 11.sp)
            }
        }
    }
}

// --- Session Report ---
@Composable
private fun SessionReportContent(viewModel: MainViewModel, context: android.content.Context) {
    var reportGenerated by remember { mutableStateOf(false) }
    var reportPath by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Generate a PDF summary including:", color = Color.Gray, fontSize = 13.sp)
        listOf(
            "Run count and timing splits",
            "Best/worst runs by gate timing",
            "Flagged moments and annotations",
            "Detected error patterns",
            "Key frames from starred media"
        ).forEach {
            Text("  - $it", color = Color(0xFFAABBCC), fontSize = 12.sp)
        }

        Surface(
            onClick = {
                viewModel.generateSessionReport(context) { path ->
                    reportPath = path
                    reportGenerated = true
                }
            },
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (reportGenerated) "Report saved: ${reportPath.substringAfterLast("/")}" else "Generate PDF Report",
                color = Color.Black,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 10.dp)
            )
        }
    }
}

// --- Progress View ---
@Composable
private fun ProgressContent(viewModel: MainViewModel) {
    var progressData by remember { mutableStateOf<List<Map<String, String>>>(emptyList()) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.getProgressTimeline { progressData = it }
    }

    if (progressData.isEmpty()) {
        Text("Progress tracking builds over multiple sessions", color = Color.Gray, fontSize = 13.sp)
        Text("Come back after a few training days to see trends", color = Color(0xFF666666), fontSize = 12.sp)
    } else {
        progressData.forEach { entry ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(entry["date"] ?: "", color = Color.Gray, fontSize = 12.sp)
                Text(entry["metric"] ?: "", color = Color.White, fontSize = 13.sp)
                Text(entry["value"] ?: "", color = Color(0xFF4FC3F7), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
