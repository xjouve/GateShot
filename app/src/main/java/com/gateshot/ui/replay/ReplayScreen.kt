package com.gateshot.ui.replay

import android.net.Uri
import android.view.ViewGroup
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Panorama
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.gateshot.R
import com.gateshot.processing.stabilize.EnhancedExporter
import com.gateshot.processing.stabilize.PlaybackStabilizer
import com.gateshot.ui.MainViewModel
import com.gateshot.videoenhance.AutoColorAnalyzer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** How close the playhead must be to a marked gate for the flag button to
 *  treat them as the same gate (so a second tap un-marks it). */
private const val GATE_TOGGLE_TOLERANCE_MS = 300L

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun ReplayScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val replayState by viewModel.replayState.collectAsState()
    val context = LocalContext.current

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
    val videoPath = videoFile?.absolutePath

    // Replay state that must survive Replay ↔ Coach navigation lives on the VM
    // (this composable is disposed on tab switch). Reset it only when a
    // DIFFERENT clip loads; otherwise the state initializers below restore the
    // saved values. Keyed on videoPath so it runs before them and re-runs when
    // the clip changes.
    val session = viewModel.replaySession
    remember(videoPath) {
        if (session.videoPath != videoPath) session.resetFor(videoPath)
    }

    // Persistent per-clip state — restores from the session on return, resets
    // (via resetFor above) when the clip changes.
    var currentPosition by remember(videoPath) { mutableLongStateOf(session.positionMs) }
    var playbackSpeed by remember(videoPath) { mutableFloatStateOf(session.playbackSpeed) }
    var showSplitScreen by remember(videoPath) { mutableStateOf(session.showSplitScreen) }
    var showOverlayPanel by remember(videoPath) { mutableStateOf(session.showOverlayPanel) }
    var showPose by remember(videoPath) { mutableStateOf(session.showPose) }
    var clipSegments by remember(videoPath) { mutableStateOf(session.clipSegments) }
    var overlayClipUri by remember(videoPath) { mutableStateOf(session.overlayClipUri) }
    var overlayOpacity by remember(videoPath) { mutableFloatStateOf(session.overlayOpacity) }
    var wipePosition by remember(videoPath) { mutableFloatStateOf(session.wipePosition) }
    var stabEnabled by remember(videoPath) { mutableStateOf(session.stabEnabled) }
    var stabTrack by remember(videoPath) { mutableStateOf(session.stabTrack) }
    var colorEnabled by remember(videoPath) { mutableStateOf(session.colorEnabled) }
    var colorMatrix by remember(videoPath) { mutableStateOf(session.colorMatrix) }

    // Transient UI state — fine to reset when the screen is re-entered
    var isPlaying by remember { mutableStateOf(false) }
    var totalDuration by remember { mutableLongStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }
    var poseKeypoints by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }
    var poseAngles by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    var showSessionDialog by remember { mutableStateOf(false) }
    var selectedVideoIndex by remember { mutableIntStateOf(-1) }

    // Manually tagged gate timestamps for this clip (sidecar-backed)
    var gateTimestamps by remember { mutableStateOf<List<Long>>(emptyList()) }
    var showGateList by remember { mutableStateOf(false) }
    // Timestamp pending a delete confirmation (null = no confirmation showing)
    var pendingDeleteGate by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(videoFile) {
        if (videoFile != null) {
            viewModel.listGates(videoFile.absolutePath) { gateTimestamps = it }
        } else {
            gateTimestamps = emptyList()
        }
    }

    // Enhancement analysis progress (transient — the results live in the session)
    val enhanceScope = rememberCoroutineScope()
    var stabAnalyzing by remember { mutableStateOf(false) }
    var stabProgress by remember { mutableFloatStateOf(0f) }
    var stabError by remember { mutableStateOf<String?>(null) }
    var stabAnalyzeJob by remember { mutableStateOf<Job?>(null) }
    var stabDx by remember { mutableFloatStateOf(0f) }
    var stabDy by remember { mutableFloatStateOf(0f) }
    var colorAnalyzing by remember { mutableStateOf(false) }
    var colorError by remember { mutableStateOf<String?>(null) }
    var colorAnalyzeJob by remember { mutableStateOf<Job?>(null) }

    // Export of the enhanced clip (stabilization/color baked into a new MP4)
    var exporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableFloatStateOf(0f) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var exportFailed by remember { mutableStateOf(false) }
    var exportJob by remember { mutableStateOf<Job?>(null) }

    // Localized strings needed inside non-composable closures below
    val stabFailedMsg = stringResource(R.string.replay_stabilize_failed)
    val colorFailedMsg = stringResource(R.string.replay_color_failed)
    val exportFailedMsg = stringResource(R.string.replay_export_failed)

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

    // Per-display-frame stabilization correction lookup. The view translation
    // acts in display space; the track stores coded-space corrections.
    LaunchedEffect(stabEnabled, stabTrack) {
        val track = stabTrack
        if (stabEnabled && track != null) {
            while (true) {
                withFrameNanos { }
                val (dx, dy) = track.displayCorrectionAt(exoPlayer.currentPosition)
                stabDx = dx
                stabDy = dy
            }
        } else {
            stabDx = 0f
            stabDy = 0f
        }
    }

    // Load video when available; restore the saved position/speed and, if the
    // pose overlay was left on, re-run the estimate (keypoints aren't persisted).
    LaunchedEffect(videoFile) {
        if (videoFile != null && videoFile.exists()) {
            val mediaItem = MediaItem.fromUri(Uri.fromFile(videoFile))
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.setPlaybackSpeed(playbackSpeed)
            if (currentPosition > 0) exoPlayer.seekTo(currentPosition)
            if (showPose) {
                viewModel.estimatePose(videoFile.absolutePath, currentPosition) { kp, angles ->
                    poseKeypoints = kp
                    poseAngles = angles
                }
            }
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
            // Snapshot the current state so returning to Replay restores it
            session.videoPath = videoPath
            session.positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            session.playbackSpeed = playbackSpeed
            session.showOverlayPanel = showOverlayPanel
            session.showSplitScreen = showSplitScreen
            session.showPose = showPose
            session.overlayClipUri = overlayClipUri
            session.overlayOpacity = overlayOpacity
            session.wipePosition = wipePosition
            session.clipSegments = clipSegments
            session.stabEnabled = stabEnabled
            session.stabTrack = stabTrack
            session.colorEnabled = colorEnabled
            session.colorMatrix = colorMatrix
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
                text = videoFile?.name ?: stringResource(R.string.replay_title_fallback),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // The gate nearest the playhead (within tap tolerance) — the flag
            // button reflects and toggles this, so a gate can be un-marked.
            val gateAtPlayhead = gateTimestamps
                .minByOrNull { kotlin.math.abs(it - currentPosition) }
                ?.takeIf { kotlin.math.abs(it - currentPosition) <= GATE_TOGGLE_TOLERANCE_MS }

            val cdOverlay = stringResource(R.string.replay_cd_overlay_toggle)
            val cdSplit = stringResource(R.string.replay_cd_split_screen_toggle)
            val cdRecordSplit = stringResource(R.string.replay_cd_record_split)
            val cdMarkGate = stringResource(R.string.replay_cd_mark_gate)
            val cdUnmarkGate = stringResource(R.string.replay_cd_unmark_gate)
            val cdAutoclip = stringResource(R.string.replay_cd_autoclip_toggle)
            val cdPose = stringResource(R.string.replay_cd_pose_toggle)

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Overlay layers toggle — Box adds an invisible >=48dp touch
                // target around the (visually unchanged) 40dp Surface.
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Surface(
                        onClick = { showOverlayPanel = !showOverlayPanel },
                        shape = RoundedCornerShape(8.dp),
                        color = if (showOverlayPanel) MaterialTheme.colorScheme.primary else Color(0xFF444444),
                        modifier = Modifier
                            .size(40.dp)
                            .semantics { toggleableState = ToggleableState(showOverlayPanel) }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.Layers, cdOverlay,
                                tint = if (showOverlayPanel) Color.Black else Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Surface(
                        onClick = { showSplitScreen = !showSplitScreen },
                        shape = RoundedCornerShape(8.dp),
                        color = if (showSplitScreen) MaterialTheme.colorScheme.primary else Color(0xFF444444),
                        modifier = Modifier
                            .size(40.dp)
                            .semantics { toggleableState = ToggleableState(showSplitScreen) }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.ViewColumn, cdSplit,
                                tint = if (showSplitScreen) Color.Black else Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
                // Record split — one-shot action, no persistent state
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Surface(
                        onClick = { viewModel.onRecordSplit(currentPosition) },
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF444444),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Timer, cdRecordSplit, tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                // Toggle a gate at the current position: lit when the playhead
                // is on a marked gate; tap adds one, tap again removes it.
                // 56dp touch target — gate marking is a primary, frequently
                // used control that must work reliably with gloves.
                Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    Surface(
                        onClick = {
                            val path = videoFile?.absolutePath ?: return@Surface
                            if (gateAtPlayhead != null) {
                                viewModel.deleteGate(path, gateAtPlayhead) { gateTimestamps = it }
                            } else {
                                viewModel.markGate(path, currentPosition) { gateTimestamps = it }
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = if (gateAtPlayhead != null) MaterialTheme.colorScheme.primary else Color(0xFF444444),
                        modifier = Modifier
                            .size(40.dp)
                            .semantics { toggleableState = ToggleableState(gateAtPlayhead != null) }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.Flag,
                                if (gateAtPlayhead != null) cdUnmarkGate else cdMarkGate,
                                tint = if (gateAtPlayhead != null) Color.Black else Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
                // Autoclip toggle: lit while segments are shown; tap again hides them
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Surface(
                        onClick = {
                            if (clipSegments.isNotEmpty()) {
                                clipSegments = emptyList()
                            } else if (videoFile != null) {
                                viewModel.onRunAutoclip(videoFile.absolutePath) { segments ->
                                    clipSegments = segments
                                }
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = if (clipSegments.isNotEmpty()) MaterialTheme.colorScheme.primary else Color(0xFF444444),
                        modifier = Modifier
                            .size(40.dp)
                            .semantics { toggleableState = ToggleableState(clipSegments.isNotEmpty()) }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.ContentCut, cdAutoclip,
                                tint = if (clipSegments.isNotEmpty()) Color.Black else Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
                // Pose toggle
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
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
                        modifier = Modifier
                            .size(40.dp)
                            .semantics { toggleableState = ToggleableState(showPose) }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.Accessibility, cdPose,
                                tint = if (showPose) Color.Black else Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
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
                Text(stringResource(R.string.replay_runs_label), color = Color(0xFF8899AA), fontSize = 11.sp)
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
                            stringResource(R.string.replay_run_number, i + 1),
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
                currentVideoPath = videoFile?.absolutePath,
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
                // TextureView-backed so the stabilization warp and color
                // render effect actually reach the pixels (a SurfaceView
                // ignores both — ticket 020).
                AndroidView(
                    factory = { ctx ->
                        (android.view.LayoutInflater.from(ctx)
                            .inflate(com.gateshot.R.layout.stabilized_player_view, null) as PlayerView)
                            .apply {
                                player = exoPlayer
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .graphicsLayer {
                            if (stabEnabled && stabTrack != null) {
                                val crop = stabTrack?.cropFactor ?: 1f
                                scaleX = crop
                                scaleY = crop
                                translationX = stabDx * size.width
                                translationY = stabDy * size.height
                            }
                            val matrix = colorMatrix
                            if (colorEnabled && matrix != null) {
                                renderEffect = android.graphics.RenderEffect
                                    .createColorFilterEffect(
                                        android.graphics.ColorMatrixColorFilter(matrix)
                                    ).asComposeRenderEffect()
                            }
                        }
                )

                // Export-enhanced button — appears once an enhancement is active
                if ((stabEnabled && stabTrack != null) || (colorEnabled && colorMatrix != null) || exporting || exportMessage != null) {
                    fun startExport() {
                        val file = videoFile ?: return
                        val outFile = run {
                            var candidate = File(file.parent, file.nameWithoutExtension + "_enhanced.mp4")
                            var i = 1
                            while (candidate.exists()) {
                                candidate = File(file.parent, file.nameWithoutExtension + "_enhanced_$i.mp4")
                                i++
                            }
                            candidate
                        }
                        exporting = true
                        exportProgress = 0f
                        exportMessage = null
                        exportFailed = false
                        exoPlayer.pause()
                        exportJob = enhanceScope.launch {
                            val result = EnhancedExporter().export(
                                srcPath = file.absolutePath,
                                outPath = outFile.absolutePath,
                                track = if (stabEnabled) stabTrack else null,
                                colorMatrix = if (colorEnabled) colorMatrix?.array?.copyOf() else null
                            ) { exportProgress = it }
                            exporting = false
                            if (result != null) {
                                exportFailed = false
                                viewModel.onNativeCaptureComplete(result.outputPath, isVideo = true)
                                val jitterPct = result.jitterChangePercent?.takeIf { it != 0 }
                                val jitterNote = jitterPct?.let {
                                    context.getString(R.string.replay_export_jitter_note, if (it > 0) "+" else "", it)
                                } ?: ""
                                exportMessage = context.getString(
                                    R.string.replay_export_saved,
                                    File(result.outputPath).name,
                                    jitterNote
                                )
                            } else {
                                exportFailed = true
                                exportMessage = exportFailedMsg
                            }
                        }
                    }
                    Surface(
                        onClick = onClick@{
                            if (exporting || videoFile == null) return@onClick
                            startExport()
                        },
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xCC000000),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                when {
                                    exporting -> stringResource(R.string.replay_exporting_progress, (exportProgress * 100).toInt())
                                    exportMessage != null -> exportMessage!!
                                    else -> stringResource(R.string.replay_export_enhanced_clip)
                                },
                                color = if (exportFailed) Color(0xFFEF5350) else Color(0xFF4FC3F7),
                                fontSize = 10.sp
                            )
                            if (exporting) {
                                Text(
                                    stringResource(R.string.replay_cancel),
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable {
                                        exportJob?.cancel()
                                        exporting = false
                                        exportMessage = null
                                        exportFailed = false
                                    }
                                )
                            }
                            if (exportFailed) {
                                Text(
                                    stringResource(R.string.replay_retry),
                                    color = Color(0xFF4FC3F7),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable { startExport() }
                                )
                            }
                        }
                    }
                }

                // Stabilization + color analysis status (loading / error / result)
                if (stabAnalyzing || stabError != null || (stabEnabled && stabTrack != null) ||
                    colorAnalyzing || colorError != null || (colorEnabled && colorMatrix != null)
                ) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (stabAnalyzing || stabError != null || (stabEnabled && stabTrack != null)) {
                            Surface(shape = RoundedCornerShape(4.dp), color = Color(0xCC000000)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        when {
                                            stabAnalyzing -> stringResource(R.string.replay_analyzing_motion, (stabProgress * 100).toInt())
                                            stabError != null -> stabError!!
                                            else -> {
                                                val red = ((stabTrack?.jitterReduction ?: 0f) * 100).toInt()
                                                if (red > 0) stringResource(R.string.replay_stabilized_with_jitter, red)
                                                else stringResource(R.string.replay_stabilized)
                                            }
                                        },
                                        color = if (stabError != null) Color(0xFFEF5350) else Color(0xFF66BB6A),
                                        fontSize = 10.sp
                                    )
                                    if (stabAnalyzing) {
                                        Text(
                                            stringResource(R.string.replay_cancel),
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.clickable {
                                                stabAnalyzeJob?.cancel()
                                                stabAnalyzing = false
                                            }
                                        )
                                    }
                                    if (stabError != null) {
                                        val file = videoFile
                                        Text(
                                            stringResource(R.string.replay_retry),
                                            color = Color(0xFF4FC3F7),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.clickable {
                                                if (file != null) {
                                                    stabAnalyzing = true
                                                    stabProgress = 0f
                                                    stabError = null
                                                    stabAnalyzeJob = enhanceScope.launch {
                                                        val track = PlaybackStabilizer()
                                                            .analyze(file.absolutePath) { stabProgress = it }
                                                        stabTrack = track
                                                        stabAnalyzing = false
                                                        stabEnabled = track != null
                                                        if (track == null) stabError = stabFailedMsg
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        if (colorAnalyzing || colorError != null || (colorEnabled && colorMatrix != null)) {
                            Surface(shape = RoundedCornerShape(4.dp), color = Color(0xCC000000)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        when {
                                            colorAnalyzing -> stringResource(R.string.replay_analyzing_color)
                                            colorError != null -> colorError!!
                                            else -> stringResource(R.string.replay_cd_color_toggle)
                                        },
                                        color = if (colorError != null) Color(0xFFEF5350) else Color(0xFF66BB6A),
                                        fontSize = 10.sp
                                    )
                                    if (colorAnalyzing) {
                                        Text(
                                            stringResource(R.string.replay_cancel),
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.clickable {
                                                colorAnalyzeJob?.cancel()
                                                colorAnalyzing = false
                                            }
                                        )
                                    }
                                    if (colorError != null) {
                                        val file = videoFile
                                        Text(
                                            stringResource(R.string.replay_retry),
                                            color = Color(0xFF4FC3F7),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.clickable {
                                                if (file != null) {
                                                    colorAnalyzing = true
                                                    colorError = null
                                                    colorAnalyzeJob = enhanceScope.launch {
                                                        val matrix = AutoColorAnalyzer().analyze(file.absolutePath)
                                                        colorMatrix = matrix
                                                        colorAnalyzing = false
                                                        colorEnabled = matrix != null
                                                        if (matrix == null) colorError = colorFailedMsg
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

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
                                    stringResource(R.string.replay_trail_hint),
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
                            stringResource(R.string.replay_overlay_mode_label, replayState.overlayMode),
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
                                        stringResource(R.string.replay_pose_angle_label, label, angle.toInt()),
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
                            Text(stringResource(R.string.replay_perspective_ok), color = Color(0xFF66BB6A), fontSize = 10.sp)
                        }
                    }
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.replay_no_clip_loaded), color = Color.Gray, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.replay_no_clip_hint), color = Color(0xFF666666), fontSize = 12.sp)
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
            val cdStabilize = stringResource(R.string.replay_cd_stabilize_toggle)
            val cdColor = stringResource(R.string.replay_cd_color_toggle)
            val stabAnalyzingState = stringResource(R.string.replay_stabilize_analyzing_state)
            val colorAnalyzingState = stringResource(R.string.replay_color_analyzing_state)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Supplementary stabilization toggle (analyzes on first use).
                // Box gives a >=48dp touch target around the unchanged 48x32dp Surface.
                Box(modifier = Modifier.size(width = 48.dp, height = 48.dp), contentAlignment = Alignment.Center) {
                    Surface(
                        onClick = {
                            val file = videoFile ?: return@Surface
                            when {
                                stabAnalyzing -> { /* analysis running */ }
                                stabEnabled -> stabEnabled = false
                                stabTrack != null -> stabEnabled = true
                                else -> {
                                    stabAnalyzing = true
                                    stabProgress = 0f
                                    stabError = null
                                    stabAnalyzeJob = enhanceScope.launch {
                                        val track = PlaybackStabilizer()
                                            .analyze(file.absolutePath) { stabProgress = it }
                                        stabTrack = track
                                        stabAnalyzing = false
                                        stabEnabled = track != null
                                        if (track == null) stabError = stabFailedMsg
                                    }
                                }
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = when {
                            stabAnalyzing -> Color(0xFF666633)
                            stabEnabled -> MaterialTheme.colorScheme.primary
                            else -> Color(0xFF333333)
                        },
                        modifier = Modifier
                            .size(width = 48.dp, height = 32.dp)
                            .semantics {
                                toggleableState = if (stabAnalyzing) ToggleableState.Indeterminate else ToggleableState(stabEnabled)
                                if (stabAnalyzing) stateDescription = stabAnalyzingState
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.Vibration, cdStabilize,
                                tint = if (stabEnabled) Color.Black else Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                // Auto color correction toggle (analyzes on first use)
                Box(modifier = Modifier.size(width = 48.dp, height = 48.dp), contentAlignment = Alignment.Center) {
                    Surface(
                        onClick = {
                            val file = videoFile ?: return@Surface
                            when {
                                colorAnalyzing -> { /* analysis running */ }
                                colorEnabled -> colorEnabled = false
                                colorMatrix != null -> colorEnabled = true
                                else -> {
                                    colorAnalyzing = true
                                    colorError = null
                                    colorAnalyzeJob = enhanceScope.launch {
                                        val matrix = AutoColorAnalyzer().analyze(file.absolutePath)
                                        colorMatrix = matrix
                                        colorAnalyzing = false
                                        colorEnabled = matrix != null
                                        if (matrix == null) colorError = colorFailedMsg
                                    }
                                }
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = when {
                            colorAnalyzing -> Color(0xFF666633)
                            colorEnabled -> MaterialTheme.colorScheme.primary
                            else -> Color(0xFF333333)
                        },
                        modifier = Modifier
                            .size(width = 48.dp, height = 32.dp)
                            .semantics {
                                toggleableState = if (colorAnalyzing) ToggleableState.Indeterminate else ToggleableState(colorEnabled)
                                if (colorAnalyzing) stateDescription = colorAnalyzingState
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.AutoFixHigh, cdColor,
                                tint = if (colorEnabled) Color.Black else Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.25f, 0.5f, 1.0f, 2.0f).forEach { speed ->
                    // Box gives a >=48dp touch target — speed is a primary,
                    // frequently used control — around the unchanged 48x32dp Surface.
                    Box(modifier = Modifier.size(width = 48.dp, height = 48.dp), contentAlignment = Alignment.Center) {
                        Surface(
                            onClick = {
                                playbackSpeed = speed
                                exoPlayer.setPlaybackSpeed(speed)
                                overlayPlayer.setPlaybackSpeed(speed)
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = if (playbackSpeed == speed) MaterialTheme.colorScheme.primary else Color(0xFF333333),
                            modifier = Modifier
                                .size(width = 48.dp, height = 32.dp)
                                .semantics { selected = playbackSpeed == speed }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    stringResource(R.string.replay_speed_label, speed),
                                    color = if (playbackSpeed == speed) Color.Black else Color.White,
                                    fontSize = 11.sp
                                )
                            }
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
            // Gate tick marks aligned with the scrubber
            if (gateTimestamps.isNotEmpty() && totalDuration > 0) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                ) {
                    gateTimestamps.forEach { ts ->
                        val x = (ts.toFloat() / maxDuration).coerceIn(0f, 1f) * size.width
                        drawLine(
                            color = Color(0xFFFFAB40),
                            start = androidx.compose.ui.geometry.Offset(x, 0f),
                            end = androidx.compose.ui.geometry.Offset(x, size.height),
                            strokeWidth = 3f
                        )
                    }
                }
            }
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
                if (gateTimestamps.isNotEmpty()) {
                    Text(
                        stringResource(R.string.replay_gates_count, gateTimestamps.size),
                        color = Color(0xFFFFAB40),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { showGateList = true }
                    )
                }
                Text(formatTime(totalDuration), color = Color.Gray, fontSize = 11.sp)
            }
        }

        // Gate list dialog — tap to seek, delete to remove
        if (showGateList && videoFile != null) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showGateList = false },
                title = { Text(stringResource(R.string.replay_gates_dialog_title, gateTimestamps.size)) },
                text = {
                    Column {
                        gateTimestamps.forEachIndexed { index, ts ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    stringResource(R.string.replay_gate_row_label, index + 1, formatTime(ts)),
                                    fontSize = 14.sp,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            exoPlayer.seekTo(ts)
                                            currentPosition = ts
                                            showGateList = false
                                        }
                                )
                                IconButton(
                                    onClick = { pendingDeleteGate = ts },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(Icons.Filled.Delete, stringResource(R.string.replay_cd_delete_gate), tint = Color(0xFFEF5350))
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { showGateList = false }) {
                        Text(stringResource(R.string.replay_close))
                    }
                }
            )
        }

        // Delete-gate confirmation — destructive action, confirm before removing
        val gateToDelete = pendingDeleteGate
        if (gateToDelete != null && videoFile != null) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { pendingDeleteGate = null },
                title = { Text(stringResource(R.string.replay_confirm_delete_gate_title)) },
                text = { Text(stringResource(R.string.replay_confirm_delete_gate_text, formatTime(gateToDelete))) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        viewModel.deleteGate(videoFile.absolutePath, gateToDelete) {
                            gateTimestamps = it
                        }
                        pendingDeleteGate = null
                    }) {
                        Text(stringResource(R.string.replay_delete), color = Color(0xFFEF5350))
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { pendingDeleteGate = null }) {
                        Text(stringResource(R.string.replay_cancel))
                    }
                }
            )
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
                Icon(Icons.Filled.SkipPrevious, stringResource(R.string.replay_cd_frame_back), tint = Color.White, modifier = Modifier.size(28.dp))
            }
            // Rewind 5s
            IconButton(onClick = {
                seekBoth(exoPlayer.currentPosition - 5000)
            }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.FastRewind, stringResource(R.string.replay_cd_rewind), tint = Color.White, modifier = Modifier.size(28.dp))
            }
            // Play/Pause — syncs both players. Already a 64dp touch target,
            // above even the 56dp glove-friendly ideal for this most-used control.
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
                modifier = Modifier
                    .size(64.dp)
                    .semantics { toggleableState = ToggleableState(isPlaying) }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (isPlaying) stringResource(R.string.replay_cd_pause) else stringResource(R.string.replay_cd_play),
                        tint = Color.Black,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
            // Forward 5s
            IconButton(onClick = {
                seekBoth(exoPlayer.currentPosition + 5000)
            }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.FastForward, stringResource(R.string.replay_cd_forward), tint = Color.White, modifier = Modifier.size(28.dp))
            }
            // Frame forward
            IconButton(onClick = {
                seekBoth(exoPlayer.currentPosition + 33)
            }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.SkipNext, stringResource(R.string.replay_cd_frame_forward), tint = Color.White, modifier = Modifier.size(28.dp))
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
    currentVideoPath: String?,
    onVideoSelected: (File) -> Unit,
    overlayOpacity: Float = 0.5f,
    onOpacityChanged: (Float) -> Unit = {},
    wipePosition: Float = 0.5f,
    onWipeChanged: (Float) -> Unit = {},
    onClearOverlay: () -> Unit = {}
) {
    var showVideoList by remember { mutableStateOf(false) }
    var showClearLayersConfirm by remember { mutableStateOf(false) }
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
            stringResource(R.string.replay_course_reference_header),
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
                // Build in progress
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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
                        stringResource(R.string.replay_building_reference, replayState.referenceFramesCaptured),
                        color = Color.White,
                        fontSize = 13.sp
                    )
                }
            } else if (replayState.hasReference) {
                // Reference captured — text column takes the slack so the
                // button keeps its intrinsic single-line width
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF66BB6A), modifier = Modifier.size(18.dp))
                    Column(modifier = Modifier.padding(end = 8.dp)) {
                        if (replayState.referenceGateCount > 0) {
                            Text(
                                stringResource(R.string.replay_gates_detected, replayState.referenceGateCount),
                                color = Color.White,
                                fontSize = 13.sp
                            )
                            Text(
                                stringResource(R.string.replay_perspective_ready),
                                color = Color(0xFF66BB6A),
                                fontSize = 11.sp
                            )
                        } else {
                            Text(
                                stringResource(R.string.replay_reference_ready_no_gates),
                                color = Color.White,
                                fontSize = 13.sp
                            )
                            Text(
                                stringResource(R.string.replay_perspective_needs_closer_gates),
                                color = Color(0xFFFFAB40),
                                fontSize = 11.sp
                            )
                        }
                    }
                }
                // Rebuild from the loaded clip
                Surface(
                    onClick = {
                        currentVideoPath?.let { viewModel.buildCourseReference(it) }
                    },
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF333333)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Filled.Panorama, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Text(stringResource(R.string.replay_rebuild_from_clip), color = Color.White, fontSize = 13.sp)
                    }
                }
            } else {
                // No reference yet — text column takes the slack so the
                // button keeps its intrinsic single-line width
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        stringResource(R.string.replay_build_reference_hint),
                        color = Color(0xFFAABBCC),
                        fontSize = 12.sp
                    )
                    Text(
                        stringResource(R.string.replay_build_reference_hint2),
                        color = Color(0xFF667788),
                        fontSize = 11.sp
                    )
                }
                Surface(
                    onClick = {
                        currentVideoPath?.let { viewModel.buildCourseReference(it) }
                    },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Filled.Panorama, null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        Text(stringResource(R.string.replay_build_reference), color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // --- Section 2: Overlay Layers ---
        if (replayState.hasReference) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(R.string.replay_overlay_layers_header),
                color = Color(0xFF8899AA),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            // Overlay mode selector — displayed label is localized; the raw
            // mode value sent to the ViewModel (GHOST/DIFFERENCE/TRAIL/WIPE)
            // is left untouched since it's a protocol value, not display text.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                overlayModes.forEach { mode ->
                    val modeLabel = when (mode) {
                        "GHOST" -> stringResource(R.string.replay_overlay_mode_ghost)
                        "DIFFERENCE" -> stringResource(R.string.replay_overlay_mode_difference)
                        "TRAIL" -> stringResource(R.string.replay_overlay_mode_trail)
                        "WIPE" -> stringResource(R.string.replay_overlay_mode_wipe)
                        else -> mode
                    }
                    Surface(
                        onClick = {
                            selectedMode = mode
                            viewModel.onSetOverlayMode(mode)
                        },
                        shape = RoundedCornerShape(6.dp),
                        color = if (selectedMode == mode) MaterialTheme.colorScheme.primary else Color(0xFF2A3A4A),
                        modifier = Modifier
                            .weight(1f)
                            .semantics { selected = selectedMode == mode }
                    ) {
                        Text(
                            modeLabel,
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
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Filled.ChevronLeft, stringResource(R.string.replay_cd_previous_gate), tint = Color.White)
                }
                Text(
                    stringResource(R.string.replay_current_gate, replayState.currentGate + 1),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                IconButton(
                    onClick = { viewModel.onNavigateGate("next") },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Filled.ChevronRight, stringResource(R.string.replay_cd_next_gate), tint = Color.White)
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
                        stringResource(R.string.replay_add_run_comparison),
                        color = Color(0xFF4FC3F7),
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (replayState.overlayLayerCount > 0) {
                        Text(
                            stringResource(R.string.replay_layers_count, replayState.overlayLayerCount),
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
                        stringResource(R.string.replay_no_recorded_videos),
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
                                        stringResource(R.string.replay_size_mb, sizeMb),
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
                    Text(stringResource(R.string.replay_opacity_label), color = Color(0xFF8899AA), fontSize = 11.sp)
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
                    Text(stringResource(R.string.replay_percent, (overlayOpacity * 100).toInt()), color = Color.White, fontSize = 11.sp)
                }
            }

            // Wipe position slider (for WIPE mode)
            if (replayState.overlayLayerCount > 0 && selectedMode == "WIPE") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.replay_wipe_label), color = Color(0xFF8899AA), fontSize = 11.sp)
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
                    onClick = { showClearLayersConfirm = true },
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF3A2020)
                ) {
                    Text(
                        stringResource(R.string.replay_clear_all_layers),
                        color = Color(0xFFEF9A9A),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                    )
                }
            }
            if (showClearLayersConfirm) {
                AlertDialog(
                    onDismissRequest = { showClearLayersConfirm = false },
                    title = { Text(stringResource(R.string.replay_confirm_clear_layers_title)) },
                    text = { Text(stringResource(R.string.replay_confirm_clear_layers_text)) },
                    confirmButton = {
                        TextButton(onClick = {
                            showClearLayersConfirm = false
                            onClearOverlay()
                        }) { Text(stringResource(R.string.replay_clear)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClearLayersConfirm = false }) {
                            Text(stringResource(R.string.replay_cancel))
                        }
                    }
                )
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
