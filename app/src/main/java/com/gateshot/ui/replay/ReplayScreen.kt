package com.gateshot.ui.replay

import android.net.Uri
import android.view.ViewGroup
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Panorama
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    val replayState by viewModel.replayState.collectAsState()
    val context = LocalContext.current

    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var showSplitScreen by remember { mutableStateOf(false) }
    var showOverlayPanel by remember { mutableStateOf(false) }
    var totalDuration by remember { mutableLongStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }
    // Autoclip segments
    var clipSegments by remember { mutableStateOf<List<Pair<Long, Long>>>(emptyList()) }
    // Pose skeleton overlay
    var showPose by remember { mutableStateOf(false) }
    var poseKeypoints by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }
    var poseAngles by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    // Session dialog
    var showSessionDialog by remember { mutableStateOf(false) }
    var selectedVideoIndex by remember { mutableIntStateOf(-1) }
    var overlayClipUri by remember { mutableStateOf<String?>(null) }
    var overlayOpacity by remember { mutableFloatStateOf(0.5f) }
    var wipePosition by remember { mutableFloatStateOf(0.5f) }

    // Load the clip chosen in the Library, falling back to the most recent one
    val selectedVideoPath by viewModel.selectedVideoPath.collectAsState()
    val videoFile = remember(selectedVideoPath) {
        selectedVideoPath?.let { File(it) }?.takeIf { it.exists() }
            ?: run {
                val videoDir = File(context.getExternalFilesDir(null), "GateShot/videos")
                videoDir.listFiles()
                    ?.filter { it.extension == "mp4" }
                    ?.maxByOrNull { it.lastModified() }
            }
    }

    // Create ExoPlayer instance for the reference (main) video
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = false
        }
    }

    // Create a second ExoPlayer for the overlay comparison layer
    val overlayPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = false
            volume = 0f  // Mute overlay — only reference audio plays
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

    // Load overlay video when a layer is added
    LaunchedEffect(overlayClipUri) {
        val uri = overlayClipUri
        if (uri != null) {
            overlayPlayer.setMediaItem(MediaItem.fromUri(uri))
            overlayPlayer.prepare()
            overlayPlayer.setPlaybackSpeed(playbackSpeed)
        } else {
            overlayPlayer.stop()
            overlayPlayer.clearMediaItems()
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
            overlayPlayer.release()
        }
    }

    // Sync overlay player with main player during playback
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            if (!isSeeking) {
                currentPosition = exoPlayer.currentPosition
                // Keep overlay player in sync — chase the main player's position
                if (overlayClipUri != null && overlayPlayer.mediaItemCount > 0) {
                    val drift = kotlin.math.abs(overlayPlayer.currentPosition - exoPlayer.currentPosition)
                    if (drift > 200) { // Re-sync if drifted more than 200ms
                        overlayPlayer.seekTo(exoPlayer.currentPosition)
                    }
                    if (exoPlayer.isPlaying && !overlayPlayer.isPlaying) {
                        overlayPlayer.play()
                    } else if (!exoPlayer.isPlaying && overlayPlayer.isPlaying) {
                        overlayPlayer.pause()
                    }
                }
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
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Overlay layers toggle
                Surface(
                    onClick = { showOverlayPanel = !showOverlayPanel },
                    shape = RoundedCornerShape(8.dp),
                    color = if (showOverlayPanel) MaterialTheme.colorScheme.primary else Color(0xFF444444),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Layers, "Overlay", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
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
                // Autoclip button
                Surface(
                    onClick = {
                        if (videoFile != null) {
                            viewModel.onRunAutoclip(videoFile.absolutePath) { segments ->
                                clipSegments = segments
                            }
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                    color = if (clipSegments.isNotEmpty()) MaterialTheme.colorScheme.primary else Color(0xFF444444),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.ContentCut, "Autoclip", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
                // Pose toggle
                Surface(
                    onClick = {
                        showPose = !showPose
                        if (showPose && videoFile != null) {
                            viewModel.estimatePose(videoFile.absolutePath, currentPosition) { kp, angles ->
                                poseKeypoints = kp
                                poseAngles = angles
                            }
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                    color = if (showPose) MaterialTheme.colorScheme.primary else Color(0xFF444444),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Accessibility, "Pose", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        // Autoclip segments bar (when segments detected)
        if (clipSegments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0D1B2A))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Runs:", color = Color(0xFF8899AA), fontSize = 11.sp)
                clipSegments.forEachIndexed { i, (startMs, _) ->
                    Surface(
                        onClick = {
                            exoPlayer.seekTo(startMs)
                            if (overlayClipUri != null) overlayPlayer.seekTo(startMs)
                            currentPosition = startMs
                        },
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF2A3A4A)
                    ) {
                        Text(
                            "Run ${i + 1}",
                            color = Color(0xFF4FC3F7),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        // Perspective & Overlay panel (collapsible)
        if (showOverlayPanel) {
            PerspectiveOverlayPanel(
                replayState = replayState,
                viewModel = viewModel,
                onVideoSelected = { file ->
                    val uri = Uri.fromFile(file).toString()
                    overlayClipUri = uri
                    viewModel.onAddOverlayLayer(uri, file.nameWithoutExtension)
                },
                overlayOpacity = overlayOpacity,
                onOpacityChanged = { overlayOpacity = it },
                wipePosition = wipePosition,
                onWipeChanged = { wipePosition = it },
                onClearOverlay = {
                    overlayClipUri = null
                    viewModel.onClearOverlay()
                }
            )
        }

        // Video player area — dual player with overlay compositing
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF111111)),
            contentAlignment = Alignment.Center
        ) {
            if (videoFile != null && videoFile.exists()) {
                // Reference (main) video — always fully opaque, on bottom.
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = false
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Overlay layer — composited on top based on overlay mode
                if (overlayClipUri != null) {
                    val overlayMode = replayState.overlayMode

                    when (overlayMode) {
                        "GHOST" -> {
                            // Semi-transparent overlay
                            AndroidView(
                                factory = { ctx ->
                                    PlayerView(ctx).apply {
                                        player = overlayPlayer
                                        useController = false
                                        layoutParams = ViewGroup.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer(alpha = overlayOpacity)
                            )
                        }
                        "WIPE" -> {
                            // Vertical wipe: overlay visible on the right side of wipe line
                            AndroidView(
                                factory = { ctx ->
                                    PlayerView(ctx).apply {
                                        player = overlayPlayer
                                        useController = false
                                        layoutParams = ViewGroup.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clipToBounds()
                                    .drawWithContent {
                                        clipRect(
                                            left = size.width * wipePosition,
                                            top = 0f,
                                            right = size.width,
                                            bottom = size.height
                                        ) {
                                            this@drawWithContent.drawContent()
                                        }
                                        // Draw wipe line
                                        drawLine(
                                            color = Color.White,
                                            start = androidx.compose.ui.geometry.Offset(size.width * wipePosition, 0f),
                                            end = androidx.compose.ui.geometry.Offset(size.width * wipePosition, size.height),
                                            strokeWidth = 3f
                                        )
                                    }
                            )
                        }
                        "DIFFERENCE" -> {
                            // Color-inverted overlay: matching areas cancel to dark,
                            // differing areas (racer in different position) glow bright.
                            // Uses color inversion matrix so overlapping identical pixels → black.
                            AndroidView(
                                factory = { ctx ->
                                    PlayerView(ctx).apply {
                                        player = overlayPlayer
                                        useController = false
                                        layoutParams = ViewGroup.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        alpha = overlayOpacity
                                        // Invert colors so when composited over the base,
                                        // identical pixels cancel toward mid-gray and
                                        // differences stand out
                                        this.renderEffect = android.graphics.RenderEffect
                                            .createColorFilterEffect(
                                                android.graphics.ColorMatrixColorFilter(floatArrayOf(
                                                    -1f, 0f, 0f, 0f, 255f,
                                                    0f, -1f, 0f, 0f, 255f,
                                                    0f, 0f, -1f, 0f, 255f,
                                                    0f, 0f, 0f, 1f, 0f
                                                ))
                                            ).asComposeRenderEffect()
                                    }
                            )
                        }
                        "TRAIL" -> {
                            // Trail mode: show ghost overlay + instruction
                            // True trajectory lines require pose tracking during each run,
                            // which is not yet recorded. Show ghost as fallback.
                            AndroidView(
                                factory = { ctx ->
                                    PlayerView(ctx).apply {
                                        player = overlayPlayer
                                        useController = false
                                        layoutParams = ViewGroup.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer(alpha = overlayOpacity * 0.4f)
                            )
                            // Trail mode info badge
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xCC000000),
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(8.dp)
                            ) {
                                Text(
                                    "Trail: enable pose tracking during recording for trajectory lines",
                                    color = Color(0xFFFFAB40),
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                        else -> {
                            AndroidView(
                                factory = { ctx ->
                                    PlayerView(ctx).apply {
                                        player = overlayPlayer
                                        useController = false
                                        layoutParams = ViewGroup.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer(alpha = overlayOpacity)
                            )
                        }
                    }

                    // Overlay layer label badge
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xCC000000),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                    ) {
                        Text(
                            "Overlay: ${replayState.overlayMode}",
                            color = Color(0xFF4FC3F7),
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                // Pose skeleton overlay
                if (showPose && poseKeypoints.isNotEmpty()) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        // Draw keypoints
                        poseKeypoints.forEach { (x, y) ->
                            drawCircle(
                                color = Color.Green,
                                radius = 6f,
                                center = androidx.compose.ui.geometry.Offset(x * w, y * h)
                            )
                        }
                        // Draw skeleton connections (MoveNet 17-keypoint topology)
                        val connections = listOf(
                            0 to 1, 0 to 2, 1 to 3, 2 to 4,          // head
                            5 to 6, 5 to 7, 7 to 9, 6 to 8, 8 to 10, // arms
                            5 to 11, 6 to 12, 11 to 12,               // torso
                            11 to 13, 13 to 15, 12 to 14, 14 to 16    // legs
                        )
                        connections.forEach { (a, b) ->
                            if (a < poseKeypoints.size && b < poseKeypoints.size) {
                                val (ax, ay) = poseKeypoints[a]
                                val (bx, by) = poseKeypoints[b]
                                drawLine(
                                    color = Color(0xFF4FC3F7),
                                    start = androidx.compose.ui.geometry.Offset(ax * w, ay * h),
                                    end = androidx.compose.ui.geometry.Offset(bx * w, by * h),
                                    strokeWidth = 3f
                                )
                            }
                        }
                    }
                    // Angle labels
                    if (poseAngles.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xCC000000),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                poseAngles.entries.take(4).forEach { (name, angle) ->
                                    val label = name.replace("Angle", "")
                                        .replace("left", "L ")
                                        .replace("right", "R ")
                                    Text(
                                        "$label: ${angle.toInt()}°",
                                        color = Color(0xFF4FC3F7),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }

                // Perspective correction badge
                if (replayState.hasReference && replayState.perspectiveCorrectionActive) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xCC000000),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Filled.Tune, null, tint = Color(0xFF66BB6A), modifier = Modifier.size(14.dp))
                            Text("Perspective OK", color = Color(0xFF66BB6A), fontSize = 10.sp)
                        }
                    }
                }
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
                            overlayPlayer.setPlaybackSpeed(speed)
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
                    if (overlayClipUri != null && overlayPlayer.mediaItemCount > 0) {
                        overlayPlayer.seekTo(currentPosition)
                    }
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
            // Helper: seek both players to a position
            fun seekBoth(posMs: Long) {
                val pos = posMs.coerceIn(0L, exoPlayer.duration.coerceAtLeast(1))
                exoPlayer.seekTo(pos)
                if (overlayClipUri != null && overlayPlayer.mediaItemCount > 0) {
                    overlayPlayer.seekTo(pos)
                }
                currentPosition = pos
            }

            // Frame back (~33ms at 30fps)
            IconButton(onClick = {
                seekBoth(exoPlayer.currentPosition - 33)
            }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.SkipPrevious, "Frame back", tint = Color.White, modifier = Modifier.size(28.dp))
            }
            // Rewind 5s
            IconButton(onClick = {
                seekBoth(exoPlayer.currentPosition - 5000)
            }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.FastRewind, "Rewind", tint = Color.White, modifier = Modifier.size(28.dp))
            }
            // Play/Pause — syncs both players
            Surface(
                onClick = {
                    if (exoPlayer.isPlaying) {
                        exoPlayer.pause()
                        overlayPlayer.pause()
                        // Capture current frame for annotation screen
                        if (videoFile != null) {
                            viewModel.captureFrameForAnnotation(
                                videoFile.absolutePath,
                                exoPlayer.currentPosition
                            )
                        }
                    } else {
                        exoPlayer.play()
                        if (overlayClipUri != null) overlayPlayer.play()
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
                seekBoth(exoPlayer.currentPosition + 5000)
            }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.FastForward, "Forward", tint = Color.White, modifier = Modifier.size(28.dp))
            }
            // Frame forward
            IconButton(onClick = {
                seekBoth(exoPlayer.currentPosition + 33)
            }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.SkipNext, "Frame forward", tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
    }
}

// =============================================================================
// Perspective & Overlay Panel
// =============================================================================

/**
 * Collapsible panel for course reference capture and overlay layer management.
 * This is the key UI for the "film from any perspective" feature.
 */
@Composable
private fun PerspectiveOverlayPanel(
    replayState: com.gateshot.coaching.replay.ReplayState,
    viewModel: MainViewModel,
    onVideoSelected: (File) -> Unit,
    overlayOpacity: Float = 0.5f,
    onOpacityChanged: (Float) -> Unit = {},
    wipePosition: Float = 0.5f,
    onWipeChanged: (Float) -> Unit = {},
    onClearOverlay: () -> Unit = {}
) {
    var showVideoList by remember { mutableStateOf(false) }
    val overlayModes = listOf("GHOST", "DIFFERENCE", "TRAIL", "WIPE")
    var selectedMode by remember { mutableStateOf(replayState.overlayMode) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0D1B2A))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // --- Section 1: Course Reference ---
        Text(
            "COURSE REFERENCE",
            color = Color(0xFF8899AA),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (replayState.isCapturingReference) {
                // Capturing in progress
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Pulsing red dot
                    val pulseColor by animateColorAsState(
                        targetValue = if (replayState.referenceFramesCaptured % 2 == 0)
                            Color.Red else Color(0xFFFF5555),
                        label = "pulse"
                    )
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(pulseColor, CircleShape)
                    )
                    Text(
                        "Panning... ${replayState.referenceFramesCaptured} frames",
                        color = Color.White,
                        fontSize = 13.sp
                    )
                }
                Surface(
                    onClick = { viewModel.onStopReferenceCapture() },
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFB71C1C)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Filled.Stop, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Text("Stop", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            } else if (replayState.hasReference) {
                // Reference captured
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF66BB6A), modifier = Modifier.size(18.dp))
                    Column {
                        Text(
                            "${replayState.referenceGateCount} gates detected",
                            color = Color.White,
                            fontSize = 13.sp
                        )
                        Text(
                            "Perspective correction ready",
                            color = Color(0xFF66BB6A),
                            fontSize = 11.sp
                        )
                    }
                }
                // Re-capture button
                Surface(
                    onClick = { viewModel.onStartReferenceCapture() },
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF333333)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Filled.Panorama, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Text("Re-scan", color = Color.White, fontSize = 13.sp)
                    }
                }
            } else {
                // No reference yet
                Column {
                    Text(
                        "Scan the blank course before training",
                        color = Color(0xFFAABBCC),
                        fontSize = 12.sp
                    )
                    Text(
                        "Pan slowly left-to-right across all gates",
                        color = Color(0xFF667788),
                        fontSize = 11.sp
                    )
                }
                Surface(
                    onClick = { viewModel.onStartReferenceCapture() },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Filled.CameraAlt, null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        Text("Scan Course", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // --- Section 2: Overlay Layers ---
        if (replayState.hasReference) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "OVERLAY LAYERS",
                color = Color(0xFF8899AA),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            // Overlay mode selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                overlayModes.forEach { mode ->
                    Surface(
                        onClick = {
                            selectedMode = mode
                            viewModel.onSetOverlayMode(mode)
                        },
                        shape = RoundedCornerShape(6.dp),
                        color = if (selectedMode == mode) MaterialTheme.colorScheme.primary else Color(0xFF2A3A4A),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            mode.lowercase().replaceFirstChar { it.uppercase() },
                            color = if (selectedMode == mode) Color.Black else Color(0xFFAABBCC),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    }
                }
            }

            // Gate navigation
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { viewModel.onNavigateGate("prev") },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Filled.ChevronLeft, "Previous gate", tint = Color.White)
                }
                Text(
                    "Gate ${replayState.currentGate + 1}",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                IconButton(
                    onClick = { viewModel.onNavigateGate("next") },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Filled.ChevronRight, "Next gate", tint = Color.White)
                }
            }

            // Add layer button + video list
            Surface(
                onClick = { showVideoList = !showVideoList },
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF2A3A4A),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.Add, null, tint = Color(0xFF4FC3F7), modifier = Modifier.size(18.dp))
                    Text(
                        "Add run for comparison",
                        color = Color(0xFF4FC3F7),
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (replayState.overlayLayerCount > 0) {
                        Text(
                            "${replayState.overlayLayerCount} layers",
                            color = Color(0xFF667788),
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // Video file list for adding layers
            if (showVideoList) {
                val videos = remember { viewModel.getRecordedVideos() }
                if (videos.isEmpty()) {
                    Text(
                        "No recorded videos yet",
                        color = Color(0xFF667788),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 8.dp, top = 4.dp)
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1A2A3A), RoundedCornerShape(8.dp))
                            .padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        videos.take(8).forEach { file ->
                            Surface(
                                onClick = {
                                    onVideoSelected(file)
                                    showVideoList = false
                                },
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF223344)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        file.nameWithoutExtension,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    val sizeMb = file.length() / (1024 * 1024)
                                    Text(
                                        "${sizeMb}MB",
                                        color = Color(0xFF667788),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Opacity slider (for GHOST / TRAIL modes)
            if (replayState.overlayLayerCount > 0 && selectedMode != "WIPE") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Opacity", color = Color(0xFF8899AA), fontSize = 11.sp)
                    Slider(
                        value = overlayOpacity,
                        onValueChange = onOpacityChanged,
                        valueRange = 0.1f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF4FC3F7),
                            activeTrackColor = Color(0xFF4FC3F7),
                            inactiveTrackColor = Color(0xFF2A3A4A)
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Text("${(overlayOpacity * 100).toInt()}%", color = Color.White, fontSize = 11.sp)
                }
            }

            // Wipe position slider (for WIPE mode)
            if (replayState.overlayLayerCount > 0 && selectedMode == "WIPE") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Wipe", color = Color(0xFF8899AA), fontSize = 11.sp)
                    Slider(
                        value = wipePosition,
                        onValueChange = onWipeChanged,
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color(0xFF2A3A4A)
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Clear overlay
            if (replayState.overlayLayerCount > 0) {
                Surface(
                    onClick = onClearOverlay,
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF3A2020)
                ) {
                    Text(
                        "Clear all layers",
                        color = Color(0xFFEF9A9A),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                    )
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val millis = (ms % 1000) / 10
    return "%d:%02d.%02d".format(minutes, seconds, millis)
}
