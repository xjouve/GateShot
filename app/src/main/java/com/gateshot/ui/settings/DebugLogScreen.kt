package com.gateshot.ui.settings

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gateshot.core.log.GateShotLogger

@Composable
fun DebugLogScreen(
    logger: GateShotLogger,
    modifier: Modifier = Modifier
) {
    val logs by logger.logFlow.collectAsState()
    var filterModule by remember { mutableStateOf<String?>(null) }
    var filterLevel by remember { mutableStateOf<GateShotLogger.Level?>(null) }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
    ) {
        // Header with export actions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A1A))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Debug Log", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.weight(1f))
            Text("${logs.size}", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))

            // Share logs button
            Surface(
                onClick = { shareLogExport(context, logger) },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.height(32.dp)
            ) {
                Text(
                    "Share",
                    color = Color.Black,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            Spacer(modifier = Modifier.width(6.dp))

            // Save to file button
            Surface(
                onClick = { saveLogToFile(context, logger) },
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF333333),
                modifier = Modifier.height(32.dp)
            ) {
                Text(
                    "Save",
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            Spacer(modifier = Modifier.width(6.dp))

            // Clear button
            Surface(
                onClick = { logger.clearLogs() },
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF333333),
                modifier = Modifier.height(32.dp)
            ) {
                Text(
                    "Clear",
                    color = Color(0xFFEF5350),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        // Filter chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            FilterChip("ALL", filterLevel == null) { filterLevel = null }
            FilterChip("ERR", filterLevel == GateShotLogger.Level.ERROR) { filterLevel = GateShotLogger.Level.ERROR }
            FilterChip("WARN", filterLevel == GateShotLogger.Level.WARN) { filterLevel = GateShotLogger.Level.WARN }
            FilterChip("INFO", filterLevel == GateShotLogger.Level.INFO) { filterLevel = GateShotLogger.Level.INFO }
            Spacer(modifier = Modifier.width(8.dp))
            // Module stats
            val stats = logger.getModuleStats()
            stats.entries.sortedByDescending { it.value.errors }.take(5).forEach { (module, stat) ->
                val hasErrors = stat.errors > 0
                FilterChip(
                    "$module${if (hasErrors) " (${stat.errors})" else ""}",
                    filterModule == module,
                    if (hasErrors) Color(0xFFEF5350) else null
                ) {
                    filterModule = if (filterModule == module) null else module
                }
            }
        }

        // Log entries
        val filtered = logs.filter { entry ->
            (filterModule == null || entry.module == filterModule) &&
            (filterLevel == null || entry.level.priority >= filterLevel!!.priority)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            items(filtered) { entry ->
                LogEntryRow(entry)
            }
        }
    }
}

@Composable
fun LogEntryRow(entry: GateShotLogger.LogEntry) {
    val levelColor = when (entry.level) {
        GateShotLogger.Level.ERROR -> Color(0xFFEF5350)
        GateShotLogger.Level.WARN -> Color(0xFFFF9800)
        GateShotLogger.Level.INFO -> Color(0xFF4FC3F7)
        GateShotLogger.Level.DEBUG -> Color(0xFF888888)
        GateShotLogger.Level.VERBOSE -> Color(0xFF555555)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        // Timestamp
        Text(
            text = entry.timeFormatted,
            color = Color(0xFF666666),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.width(4.dp))
        // Level
        Text(
            text = entry.level.tag,
            color = levelColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.width(4.dp))
        // Module
        Text(
            text = "[${entry.module}]",
            color = Color(0xFF4FC3F7),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.width(4.dp))
        // Message
        Text(
            text = entry.message,
            color = Color.White,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 2
        )
    }

    // Error details
    entry.throwable?.let { throwable ->
        Text(
            text = "  └ ${throwable::class.simpleName}: ${throwable.message}",
            color = Color(0xFFEF5350),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}

@Composable
fun FilterChip(
    label: String,
    selected: Boolean,
    tint: Color? = null,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) (tint ?: MaterialTheme.colorScheme.primary) else Color(0xFF2A2A2A),
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .height(28.dp)
    ) {
        Text(
            text = label,
            color = if (selected) Color.Black else (tint ?: Color.White),
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/**
 * Share logs via Android share intent (email, messaging, etc.)
 */
private fun shareLogExport(context: Context, logger: GateShotLogger) {
    val logText = logger.exportLogs(1000)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "GateShot Debug Log")
        putExtra(Intent.EXTRA_TEXT, logText)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(Intent.createChooser(intent, "Share debug log").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}

/**
 * Save logs to a file on the device.
 */
private fun saveLogToFile(context: Context, logger: GateShotLogger) {
    try {
        val logText = logger.exportLogs(2000)
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val file = java.io.File(context.getExternalFilesDir(null), "gateshot_log_$timestamp.txt")
        file.writeText(logText)
        Toast.makeText(context, "Log saved: ${file.name}", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Failed to save log: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
