package com.gateshot.ui.viewfinder

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Lens
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.SportsScore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gateshot.core.mode.AppMode
import com.gateshot.ui.MainUiState
import com.gateshot.ui.components.PresetSelector
import com.gateshot.ui.components.ShutterButton
import com.gateshot.ui.components.StatusBar

@Composable
fun ViewfinderScreen(
    uiState: MainUiState,
    onShutterPress: () -> Unit,
    onModeToggle: () -> Unit,
    onPresetSelected: (String) -> Unit,
    onZoomChanged: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Camera preview area (placeholder — CameraX PreviewView will be injected here)
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "VIEWFINDER",
                color = Color.Gray,
                fontSize = 14.sp
            )
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
                        text = "${uiState.zoomLevel}x",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Shutter button — extra large for glove mode (min 14mm = ~56dp)
            ShutterButton(
                isRecording = uiState.isRecording,
                onClick = onShutterPress
            )
        }

        // Bottom bar — shot count + lens status
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

            // Lens indicator
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

            // Storage remaining
            Text(
                text = "${uiState.storageRemainingGb.toInt()} GB",
                color = Color.White,
                fontSize = 14.sp
            )
        }
    }
}
