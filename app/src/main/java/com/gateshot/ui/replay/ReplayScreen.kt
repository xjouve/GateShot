package com.gateshot.ui.replay

import android.net.Uri
import android.view.ViewGroup
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
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.gateshot.ui.MainViewModel
import kotlinx.coroutines.delay
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun ReplayScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var showSplitScreen by remember { mutableStateOf(false) }
    var totalDuration by remember { mutableLongStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }

    // Find the most recent video file to load
    val videoFile = remember {
        val videoDir = File(context.getExternalFilesDir(null), "GateShot/videos")
        videoDir.listFiles()
            ?.filter { it.extension == "mp4" }
            ?.maxByOrNull { it.lastModified() }
    }

    // Create ExoPlayer instance
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = false
        }
    }

    // Load video when available
    LaunchedEffect(videoFile) {
        if (videoFile != null && videoFile.exists()) {
            val mediaItem = MediaItem.fromUri(Uri.fromFile(videoFile))
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
        }
    }

    // Listen to player state changes
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    totalDuration = exoPlayer.duration
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Update position while playing
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            if (!isSeeking) {
                currentPosition = exoPlayer.currentPosition
            }
            delay(100)
        }
    }

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
                text = if (videoFile != null) videoFile.name else "Replay",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Row {
                Surface(
                    onClick = { showSplitScreen = !showSplitScreen },
                    shape = RoundedCornerShape(8.dp),
                    color = if (showSplitScreen) MaterialTheme.colorScheme.primary else Color(0xFF444444),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.ViewColumn, "Split screen", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    onClick = { viewModel.onRecordSplit(currentPosition) },
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

        // Video player area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF111111)),
            contentAlignment = Alignment.Center
        ) {
            if (videoFile != null && videoFile.exists()) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = false  // We have our own controls
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No clip loaded", color = Color.Gray, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Record a video, then come here to review", color = Color(0xFF666666), fontSize = 12.sp)
                }
            }
        }

        // Speed indicator + presets
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.25f, 0.5f, 1.0f, 2.0f).forEach { speed ->
                    Surface(
                        onClick = {
                            playbackSpeed = speed
                            exoPlayer.setPlaybackSpeed(speed)
                        },
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
            val maxDuration = if (totalDuration > 0) totalDuration.toFloat() else 1f
            Slider(
                value = currentPosition.toFloat().coerceIn(0f, maxDuration),
                onValueChange = {
                    isSeeking = true
                    currentPosition = it.toLong()
                },
                onValueChangeFinished = {
                    exoPlayer.seekTo(currentPosition)
                    isSeeking = false
                },
                valueRange = 0f..maxDuration,
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
                Text(formatTime(currentPosition), color = Color.Gray, fontSize = 11.sp)
                Text(formatTime(totalDuration), color = Color.Gray, fontSize = 11.sp)
            }
        }

        // Transport controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A1A))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Frame back (~33ms at 30fps)
            IconButton(onClick = {
                val newPos = (exoPlayer.currentPosition - 33).coerceAtLeast(0)
                exoPlayer.seekTo(newPos)
                currentPosition = newPos
            }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.SkipPrevious, "Frame back", tint = Color.White, modifier = Modifier.size(28.dp))
            }
            // Rewind 5s
            IconButton(onClick = {
                val newPos = (exoPlayer.currentPosition - 5000).coerceAtLeast(0)
                exoPlayer.seekTo(newPos)
                currentPosition = newPos
            }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.FastRewind, "Rewind", tint = Color.White, modifier = Modifier.size(28.dp))
            }
            // Play/Pause
            Surface(
                onClick = {
                    if (exoPlayer.isPlaying) {
                        exoPlayer.pause()
                    } else {
                        exoPlayer.play()
                    }
                },
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
            IconButton(onClick = {
                val newPos = (exoPlayer.currentPosition + 5000).coerceAtMost(exoPlayer.duration)
                exoPlayer.seekTo(newPos)
                currentPosition = newPos
            }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.FastForward, "Forward", tint = Color.White, modifier = Modifier.size(28.dp))
            }
            // Frame forward
            IconButton(onClick = {
                val newPos = (exoPlayer.currentPosition + 33).coerceAtMost(exoPlayer.duration)
                exoPlayer.seekTo(newPos)
                currentPosition = newPos
            }, modifier = Modifier.size(48.dp)) {
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
