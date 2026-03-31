package com.gateshot.ui.replay

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SplitscreenTop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gateshot.ui.MainViewModel

@Composable
fun ReplayScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableFloatStateOf(0f) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var showSplitScreen by remember { mutableStateOf(false) }
    val totalDuration = 30_000f  // Placeholder 30s

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
            Text(
                text = "Replay",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Row {
                // Split screen toggle
                Surface(
                    onClick = { showSplitScreen = !showSplitScreen },
                    shape = RoundedCornerShape(8.dp),
                    color = if (showSplitScreen) MaterialTheme.colorScheme.primary else Color(0xFF444444),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.SplitscreenTop, "Split screen", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                // Split timing button
                Surface(
                    onClick = { /* Record split */ },
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF444444),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Timer, "Record split", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        // Video area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF111111)),
            contentAlignment = Alignment.Center
        ) {
            if (showSplitScreen) {
                // Split screen layout
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .background(Color(0xFF1A1A1A)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("RUN 1", color = Color(0xFF4FC3F7), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Video A", color = Color.Gray, fontSize = 14.sp)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .background(Color(0xFF1A1A1A)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("RUN 2", color = Color(0xFFFF7043), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Video B", color = Color.Gray, fontSize = 14.sp)
                        }
                    }
                }
            } else {
                // Single video
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No clip loaded", color = Color.Gray, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Record a video, then come here to review", color = Color(0xFF666666), fontSize = 12.sp)
                }
            }
        }

        // Speed indicator
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A1A))
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Speed, "Speed", tint = Color.Gray, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("${playbackSpeed}x", color = Color.White, fontSize = 12.sp)
            }
            // Speed presets — large buttons for gloves
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.25f, 0.5f, 1.0f, 2.0f).forEach { speed ->
                    Surface(
                        onClick = { playbackSpeed = speed },
                        shape = RoundedCornerShape(8.dp),
                        color = if (playbackSpeed == speed) MaterialTheme.colorScheme.primary else Color(0xFF333333),
                        modifier = Modifier.size(width = 48.dp, height = 32.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "${speed}x",
                                color = if (playbackSpeed == speed) Color.Black else Color.White,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }

        // Timeline scrubber
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A1A))
                .padding(horizontal = 16.dp)
        ) {
            Slider(
                value = currentPosition,
                onValueChange = { currentPosition = it },
                valueRange = 0f..totalDuration,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = Color(0xFF444444)
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatTime(currentPosition.toLong()), color = Color.Gray, fontSize = 11.sp)
                Text(formatTime(totalDuration.toLong()), color = Color.Gray, fontSize = 11.sp)
            }
        }

        // Transport controls — extra large for gloves
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A1A))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Frame back
            IconButton(onClick = { currentPosition = (currentPosition - 33).coerceAtLeast(0f) }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.SkipPrevious, "Frame back", tint = Color.White, modifier = Modifier.size(28.dp))
            }
            // Rewind 5s
            IconButton(onClick = { currentPosition = (currentPosition - 5000).coerceAtLeast(0f) }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.FastRewind, "Rewind", tint = Color.White, modifier = Modifier.size(28.dp))
            }
            // Play/Pause — largest button
            Surface(
                onClick = { isPlaying = !isPlaying },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        "Play/Pause",
                        tint = Color.Black,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
            // Forward 5s
            IconButton(onClick = { currentPosition = (currentPosition + 5000).coerceAtMost(totalDuration) }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.FastForward, "Forward", tint = Color.White, modifier = Modifier.size(28.dp))
            }
            // Frame forward
            IconButton(onClick = { currentPosition = (currentPosition + 33).coerceAtMost(totalDuration) }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.SkipNext, "Frame forward", tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val millis = (ms % 1000) / 10
    return "%d:%02d.%02d".format(minutes, seconds, millis)
}
