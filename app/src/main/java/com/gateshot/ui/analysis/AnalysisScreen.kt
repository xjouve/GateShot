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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.SportsScore
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.stringResource
import com.gateshot.R
import com.gateshot.ui.AiAnalysisState
import com.gateshot.ui.MainViewModel
import com.gateshot.coaching.pose.TechniqueFlag
import com.gateshot.coaching.pose.TechniqueReport
import com.gateshot.coaching.pose.toPromptJson
import com.gateshot.coaching.aicoach.AiCoachClient
import com.gateshot.coaching.aicoach.AiCoachReport
import com.gateshot.coaching.aicoach.ApiKeyStore
import com.gateshot.coaching.aicoach.Finding

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
            stringResource(R.string.analysis_title),
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp)
        )

        AiCoachCard(viewModel)
        Spacer(modifier = Modifier.height(4.dp))

        if (uiState.sessionName == null) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Assessment, null, tint = Color(0xFF444444), modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.analysis_no_session_title), color = Color.Gray, fontSize = 14.sp)
                    Text(stringResource(R.string.analysis_no_session_body), color = Color(0xFF666666), fontSize = 12.sp)
                }
            }
        } else {
            Text(
                stringResource(R.string.analysis_session_label, uiState.sessionName ?: "", uiState.sessionDiscipline ?: ""),
                color = Color(0xFF4FC3F7),
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            // --- Consistency Tracker ---
            AnalysisCard(
                title = stringResource(R.string.analysis_consistency_title),
                subtitle = stringResource(R.string.analysis_consistency_subtitle),
                icon = Icons.Filled.ShowChart,
                expanded = expandedSection == "consistency",
                onClick = { expandedSection = if (expandedSection == "consistency") null else "consistency" }
            ) {
                ConsistencyContent(viewModel)
            }

            // --- Turn Analysis ---
            AnalysisCard(
                title = stringResource(R.string.analysis_turn_title),
                subtitle = stringResource(R.string.analysis_turn_subtitle),
                icon = Icons.Filled.Timeline,
                expanded = expandedSection == "turn",
                onClick = { expandedSection = if (expandedSection == "turn") null else "turn" }
            ) {
                TurnAnalysisContent(viewModel)
            }

            // --- Error Patterns ---
            AnalysisCard(
                title = stringResource(R.string.analysis_errors_title),
                subtitle = stringResource(R.string.analysis_errors_subtitle),
                icon = Icons.Filled.BugReport,
                expanded = expandedSection == "errors",
                onClick = { expandedSection = if (expandedSection == "errors") null else "errors" }
            ) {
                ErrorPatternContent(viewModel)
            }

            // --- Time-to-Technique ---
            AnalysisCard(
                title = stringResource(R.string.analysis_time_title),
                subtitle = stringResource(R.string.analysis_time_subtitle),
                icon = Icons.Filled.CompareArrows,
                expanded = expandedSection == "time",
                onClick = { expandedSection = if (expandedSection == "time") null else "time" }
            ) {
                TimeToTechniqueContent(viewModel)
            }

            // --- Session Report ---
            AnalysisCard(
                title = stringResource(R.string.analysis_report_title),
                subtitle = stringResource(R.string.analysis_report_subtitle),
                icon = Icons.Filled.PictureAsPdf,
                expanded = expandedSection == "report",
                onClick = { expandedSection = if (expandedSection == "report") null else "report" }
            ) {
                SessionReportContent(viewModel, context)
            }

            // --- Before/After Progress ---
            AnalysisCard(
                title = stringResource(R.string.analysis_progress_title),
                subtitle = stringResource(R.string.analysis_progress_subtitle),
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
        Text(stringResource(R.string.analysis_consistency_empty), color = Color.Gray, fontSize = 13.sp)
    } else {
        results.forEach { (gate, variability, assessment) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.analysis_gate_label, gate), color = Color.White, fontSize = 13.sp)
                Text(
                    stringResource(R.string.analysis_variability_label, "%.0f".format(variability), assessment),
                    color = when (assessment) {
                        "consistent" -> Color(0xFF66BB6A)
                        "variable" -> Color(0xFFFFAB40)
                        else -> Color(0xFFEF5350)
                    },
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f, fill = false),
                    textAlign = TextAlign.End
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
        Text(stringResource(R.string.analysis_turn_empty), color = Color.Gray, fontSize = 13.sp)
    } else {
        // Header
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(
                stringResource(R.string.analysis_turn_col_gate),
                stringResource(R.string.analysis_turn_col_split),
                stringResource(R.string.analysis_turn_col_line),
                stringResource(R.string.analysis_turn_col_knee)
            ).forEach {
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
        Text(stringResource(R.string.analysis_errors_empty_title), color = Color.Gray, fontSize = 13.sp)
        Text(stringResource(R.string.analysis_errors_empty_body), color = Color(0xFF666666), fontSize = 12.sp)
    } else {
        errors.forEach { error ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF2A1A1A),
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        error["pattern"] ?: stringResource(R.string.analysis_errors_unknown_pattern),
                        color = Color(0xFFEF9A9A),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.analysis_errors_severity, error["severity"] ?: ""), color = Color.Gray, fontSize = 11.sp)
                        Text(stringResource(R.string.analysis_errors_trend, error["trend"] ?: ""), color = when(error["trend"]) {
                            "improving" -> Color(0xFF66BB6A)
                            "regressing" -> Color(0xFFEF5350)
                            else -> Color(0xFFFFAB40)
                        }, fontSize = 11.sp)
                        Text(stringResource(R.string.analysis_errors_count, error["count"] ?: ""), color = Color.Gray, fontSize = 11.sp)
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
        Text(stringResource(R.string.analysis_time_empty), color = Color.Gray, fontSize = 13.sp)
    } else {
        deltas.forEach { delta ->
            val deltaMs = delta["deltaMs"]?.toIntOrNull() ?: 0
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.analysis_gate_label, delta["gate"]?.toIntOrNull() ?: 0), color = Color.White, fontSize = 13.sp)
                Text(
                    stringResource(R.string.analysis_time_delta_ms, if (deltaMs > 0) "+" else "", deltaMs),
                    color = if (deltaMs > 0) Color(0xFFEF5350) else Color(0xFF66BB6A),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    delta["reason"] ?: "",
                    color = Color.Gray,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f, fill = false),
                    textAlign = TextAlign.End
                )
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
        Text(stringResource(R.string.analysis_report_intro), color = Color.Gray, fontSize = 13.sp)
        listOf(
            stringResource(R.string.analysis_report_item_1),
            stringResource(R.string.analysis_report_item_2),
            stringResource(R.string.analysis_report_item_3),
            stringResource(R.string.analysis_report_item_4),
            stringResource(R.string.analysis_report_item_5)
        ).forEach {
            Text(stringResource(R.string.analysis_report_bullet, it), color = Color(0xFFAABBCC), fontSize = 12.sp)
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
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (reportGenerated) stringResource(R.string.analysis_report_saved, reportPath.substringAfterLast("/")) else stringResource(R.string.analysis_report_generate),
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
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
        Text(stringResource(R.string.analysis_progress_empty_title), color = Color.Gray, fontSize = 13.sp)
        Text(stringResource(R.string.analysis_progress_empty_body), color = Color(0xFF666666), fontSize = 12.sp)
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

// --- AI Coach ---

@Composable
private fun AiCoachCard(viewModel: MainViewModel) {
    val clipPath = viewModel.replaySession.videoPath
    val state by viewModel.aiAnalysisState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(clipPath) {
        if (clipPath != null) viewModel.loadCachedAiAnalysis(clipPath)
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF0D1B2A),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Filled.SportsScore, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Text(stringResource(R.string.ai_coach_card_title), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            if (clipPath == null) {
                Text(stringResource(R.string.ai_coach_no_clip), color = Color.Gray, fontSize = 13.sp)
            } else {
                when (val s = state) {
                    is AiAnalysisState.Idle -> AiCoachIdleContent(viewModel)
                    is AiAnalysisState.Tracking -> AiCoachTrackingContent(viewModel, s.progress)
                    is AiAnalysisState.TechniqueReady -> AiCoachTechniqueContent(viewModel, s.report, context)
                    is AiAnalysisState.AskingClaude -> AiCoachAskingContent(viewModel)
                    is AiAnalysisState.Done -> AiCoachDoneContent(viewModel, s.report, s.aiReport, context)
                    is AiAnalysisState.Error -> AiCoachErrorContent(viewModel, s)
                }
            }
        }
    }
}

@Composable
private fun AiCoachIdleContent(viewModel: MainViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ai_coach_idle_explanation), color = Color.Gray, fontSize = 12.sp)
        Button(
            onClick = { viewModel.startTechniqueAnalysis() },
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text(stringResource(R.string.ai_coach_analyze_button), fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AiCoachTrackingContent(viewModel: MainViewModel, progress: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
        Text(
            stringResource(R.string.ai_coach_tracking_progress, (progress * 100).toInt()),
            color = Color.White, fontSize = 13.sp
        )
        OutlinedButton(onClick = { viewModel.cancelAiAnalysis() }, modifier = Modifier.height(48.dp)) {
            Text(stringResource(R.string.ai_coach_cancel))
        }
    }
}

@Composable
private fun AiCoachAskingContent(viewModel: MainViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.ai_coach_asking_claude), color = Color.White, fontSize = 13.sp)
        OutlinedButton(onClick = { viewModel.cancelAiAnalysis() }, modifier = Modifier.height(48.dp)) {
            Text(stringResource(R.string.ai_coach_cancel))
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.Gray, fontSize = 12.sp)
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SeverityChip(flag: TechniqueFlag) {
    val color = when (flag.severity) {
        3 -> Color(0xFFEF5350)
        2 -> Color(0xFFFFAB40)
        else -> Color(0xFF4FC3F7)
    }
    Surface(shape = RoundedCornerShape(16.dp), color = color.copy(alpha = 0.18f), modifier = Modifier.wrapContentWidth()) {
        Text(flag.message, color = color, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

@Composable
private fun AiCoachTechniqueContent(viewModel: MainViewModel, report: TechniqueReport, context: android.content.Context) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MetricRow(stringResource(R.string.ai_coach_metric_tracked), "${(report.trackedFraction * 100).toInt()}%")
        val kneeL = report.metricStats["kneeAngleL"]
        val kneeR = report.metricStats["kneeAngleR"]
        if (kneeL != null || kneeR != null) {
            val meanKnee = listOfNotNull(kneeL?.mean, kneeR?.mean).average().toFloat()
            val minKnee = listOfNotNull(kneeL?.min, kneeR?.min).minOrNull() ?: 0f
            MetricRow(stringResource(R.string.ai_coach_metric_knee_mean), "%.0f°".format(meanKnee))
            MetricRow(stringResource(R.string.ai_coach_metric_knee_min), "%.0f°".format(minKnee))
        }
        report.metricStats["torsoLeanDeg"]?.let {
            MetricRow(stringResource(R.string.ai_coach_metric_torso_lean), "%.0f°".format(it.mean))
        }
        report.metricStats["stanceWidthRatio"]?.let {
            MetricRow(stringResource(R.string.ai_coach_metric_stance_ratio), "%.2f".format(it.mean))
        }
        report.metricStats["shoulderTiltDeg"]?.let {
            MetricRow(stringResource(R.string.ai_coach_metric_shoulder_tilt), "%.0f°".format(it.p90))
        }

        if (report.flags.isNotEmpty()) {
            // Stack vertically: chips carry full sentences, and a Row would
            // squeeze the 2nd+ chip to zero width (letter-wrapped sliver).
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                report.flags.forEach { SeverityChip(it) }
            }
        }

        if (report.trackedFraction < 0.5f) {
            Text(stringResource(R.string.ai_coach_unreliable_note), color = Color(0xFFFFAB40), fontSize = 12.sp)
        }

        val hasKey = remember(report) { ApiKeyStore.isSet(context) }
        if (hasKey) {
            val cost = remember(report) {
                AiCoachClient.estimateCostUsd(report.keyFrames.size, report.toPromptJson().length)
            }
            Button(
                onClick = { viewModel.requestAiReport() },
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text(stringResource(R.string.ai_coach_get_report_button, "%.2f".format(cost)), fontWeight = FontWeight.Bold)
            }
        } else {
            Text(stringResource(R.string.ai_coach_no_key_hint), color = Color(0xFF4FC3F7), fontSize = 13.sp)
        }
        OutlinedButton(onClick = { viewModel.startTechniqueAnalysis() }, modifier = Modifier.height(48.dp)) {
            Text(stringResource(R.string.ai_coach_rerun_tracking), fontSize = 12.sp)
        }
    }
}

private fun findingLocation(context: android.content.Context, f: Finding): String? {
    val ts = f.timestampMs
    val gate = f.gateIndex
    return when {
        ts != null -> context.getString(R.string.ai_coach_at_time, ts / 1000f)
        gate != null -> context.getString(R.string.ai_coach_at_gate, gate)
        else -> null
    }
}

@Composable
private fun FindingRow(finding: Finding, context: android.content.Context) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(finding.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(finding.detail, color = Color(0xFFAABBCC), fontSize = 12.sp)
        findingLocation(context, finding)?.let {
            Text(it, color = Color.Gray, fontSize = 11.sp)
        }
    }
}

/** Pure formatter for the plain-text share intent, kept separate from Context/Compose. */
internal fun buildAiCoachShareText(
    report: AiCoachReport,
    correctionsLabel: String,
    strengthsLabel: String,
    drillsLabel: String
): String = buildString {
    append("${report.overallScore}/10\n\n")
    append(report.summary).append("\n\n")
    if (report.corrections.isNotEmpty()) {
        append("$correctionsLabel:\n")
        report.corrections.sortedBy { it.priority }.forEach { append("- ${it.title}: ${it.detail}\n") }
        append("\n")
    }
    if (report.strengths.isNotEmpty()) {
        append("$strengthsLabel:\n")
        report.strengths.sortedBy { it.priority }.forEach { append("- ${it.title}: ${it.detail}\n") }
        append("\n")
    }
    if (report.drills.isNotEmpty()) {
        append("$drillsLabel:\n")
        report.drills.forEach { append("- ${it.name}: ${it.description}\n") }
    }
}

@Composable
private fun AiCoachDoneContent(
    viewModel: MainViewModel,
    report: TechniqueReport,
    aiReport: AiCoachReport,
    context: android.content.Context
) {
    val correctionsLabel = stringResource(R.string.ai_coach_corrections_title)
    val strengthsLabel = stringResource(R.string.ai_coach_strengths_title)
    val drillsLabel = stringResource(R.string.ai_coach_drills_title)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primary, modifier = Modifier.wrapContentWidth()) {
            Text(
                stringResource(R.string.ai_coach_score_format, aiReport.overallScore),
                color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
        Text(aiReport.summary, color = Color.White, fontSize = 13.sp)

        if (aiReport.corrections.isNotEmpty()) {
            Text(correctionsLabel, color = Color(0xFF8899AA), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            aiReport.corrections.sortedBy { it.priority }.forEach { FindingRow(it, context) }
        }
        if (aiReport.strengths.isNotEmpty()) {
            Text(strengthsLabel, color = Color(0xFF8899AA), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            aiReport.strengths.sortedBy { it.priority }.forEach { FindingRow(it, context) }
        }
        if (aiReport.drills.isNotEmpty()) {
            Text(drillsLabel, color = Color(0xFF8899AA), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            aiReport.drills.forEach {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(it.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(it.description, color = Color(0xFFAABBCC), fontSize = 12.sp)
                }
            }
        }
        Text(aiReport.confidenceNote, color = Color(0xFF666666), fontSize = 11.sp)
        Text(
            stringResource(R.string.ai_coach_footer_format, aiReport.model, java.text.DateFormat.getDateTimeInstance().format(java.util.Date(aiReport.createdAtMs))),
            color = Color(0xFF555555), fontSize = 10.sp
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val text = buildAiCoachShareText(aiReport, correctionsLabel, strengthsLabel, drillsLabel)
                val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_TEXT, text)
                }
                context.startActivity(android.content.Intent.createChooser(sendIntent, null))
            }, modifier = Modifier.height(48.dp)) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.ai_coach_share))
            }
            OutlinedButton(onClick = { viewModel.startTechniqueAnalysis() }, modifier = Modifier.height(48.dp)) {
                Text(stringResource(R.string.ai_coach_reanalyze))
            }
        }
    }
}

@Composable
private fun AiCoachErrorContent(viewModel: MainViewModel, error: AiAnalysisState.Error) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(error.message, color = Color(0xFFEF5350), fontSize = 13.sp)
        Button(
            onClick = {
                if (error.technique != null) viewModel.requestAiReport() else viewModel.startTechniqueAnalysis()
            },
            modifier = Modifier.height(48.dp)
        ) {
            Text(stringResource(R.string.ai_coach_retry))
        }
    }
}
