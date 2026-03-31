package com.gateshot.ui.viewfinder

import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lens
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.gateshot.platform.camera.CameraXPlatform
import com.gateshot.ui.MainUiState
import com.gateshot.ui.components.PresetSelector
import com.gateshot.ui.components.ShutterButton
import com.gateshot.ui.components.StatusBar
import com.gateshot.ui.components.TrackingOverlay
import com.gateshot.ui.components.ZoneOverlay

@Composable
fun ViewfinderScreen(
    uiState: MainUiState,
    cameraXPlatform: CameraXPlatform,
    onCameraPreviewReady: (PreviewView) -> Unit,
    onShutterPress: () -> Unit,
    onModeToggle: () -> Unit,
    onPresetSelected: (String) -> Unit,
    onZoomChanged: (Float) -> Unit,
    onVideoToggle: () -> Unit,
    onAddTriggerZone: (Float, Float) -> Unit,
    onClearTriggerZones: () -> Unit,
    onTrackingToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var currentZoom by remember { mutableFloatStateOf(1f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Live camera preview
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                    onCameraPreviewReady(this)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        currentZoom = (currentZoom * zoom).coerceIn(1f, 10f)
                        onZoomChanged(currentZoom)
                    }
                }
        )

        // Trigger zone overlay + long-press to add zone
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = { offset ->
                            val normX = offset.x / size.width
                            val normY = offset.y / size.height
                            onAddTriggerZone(normX, normY)
                        },
                        onDoubleTap = {
                            onClearTriggerZones()
                        }
                    )
                }
        ) {
            if (uiState.triggerZones.isNotEmpty()) {
                ZoneOverlay(
                    zones = uiState.triggerZones,
                    isArmed = uiState.triggerArmed
                )
            }
            // Tracking overlay — shows AF lock bracket on the racer
            if (uiState.trackingEnabled && uiState.trackingHasLock) {
                TrackingOverlay(
                    targetX = uiState.trackingTargetX,
                    targetY = uiState.trackingTargetY,
                    regionSize = uiState.trackingRegionSize,
                    hasLock = uiState.trackingHasLock,
                    isOccluded = uiState.trackingOccluded
                )
            }
        }

        // Top bar — status + mode toggle
        StatusBar(
            uiState = uiState,
            onModeToggle = onModeToggle,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
        )

        // Preset selector — left side
        PresetSelector(
            currentPreset = uiState.currentPreset,
            displayName = uiState.presetDisplayName,
            onPresetSelected = onPresetSelected,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 8.dp)
        )

        // Right side controls — shutter + zoom
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Zoom indicator
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0x88000000),
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "%.1fx".format(uiState.zoomLevel),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Shutter button — extra large for glove mode
            ShutterButton(
                isRecording = false,
                onClick = onShutterPress
            )

            // Video record button — red circle, toggles recording
            Surface(
                onClick = onVideoToggle,
                shape = CircleShape,
                color = if (uiState.isRecording) MaterialTheme.colorScheme.error else Color(0xFF880000),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (uiState.isRecording) {
                        // Stop icon (square)
                        Surface(
                            modifier = Modifier.size(18.dp),
                            shape = RoundedCornerShape(2.dp),
                            color = Color.White
                        ) {}
                    } else {
                        // Record icon (circle)
                        Surface(
                            modifier = Modifier.size(20.dp),
                            shape = CircleShape,
                            color = Color.Red
                        ) {}
                    }
                }
            }

            // Racer tracking toggle — crosshair icon
            Surface(
                onClick = onTrackingToggle,
                shape = RoundedCornerShape(8.dp),
                color = if (uiState.trackingEnabled)
                    (if (uiState.trackingHasLock) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary)
                else Color(0x88000000),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = if (uiState.trackingEnabled) "AF" else "AF",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Bottom bar — shot count + lens status + storage
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color(0x88000000))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Shot count
            Text(
                text = "${uiState.shotCount} shots",
                color = Color.White,
                fontSize = 14.sp
            )

            // Snow EV indicator
            if (uiState.currentEvBias > 0f) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "+${"%.1f".format(uiState.currentEvBias)} EV",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (uiState.isFlatLight) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "FLAT",
                            color = MaterialTheme.colorScheme.secondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (uiState.lensAttached) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Lens,
                        contentDescription = "Telephoto attached",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "TELE",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Text(
                text = "${uiState.storageRemainingGb.toInt()} GB",
                color = Color.White,
                fontSize = 14.sp
            )
        }
    }
}
