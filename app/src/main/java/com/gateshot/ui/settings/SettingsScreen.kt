package com.gateshot.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
fun SettingsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
    ) {
        // Header
        Text(
            text = "Settings",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp)
        )

        // --- Racer Tracking ---
        SettingsSection("Racer Tracking") {
            var trackingEnabled by remember { mutableStateOf(false) }
            var trackingSpeed by remember { mutableFloatStateOf(8f) }
            var afRegionSize by remember { mutableFloatStateOf(0.15f) }
            var occlusionTimeout by remember { mutableFloatStateOf(20f) }

            SettingsToggle(
                title = "Enable AF Tracking",
                subtitle = "Locks focus on the fastest-moving subject (racer)",
                checked = trackingEnabled,
                onCheckedChange = {
                    trackingEnabled = it
                    if (it) viewModel.onTrackingToggle() else viewModel.onTrackingToggle()
                }
            )
            SettingsSlider(
                title = "Min racer speed",
                subtitle = "Lower = more sensitive (may track officials)",
                value = trackingSpeed,
                range = 3f..20f,
                unit = " px/frame",
                onValueChange = { trackingSpeed = it }
            )
            SettingsSlider(
                title = "AF region size",
                subtitle = "Size of focus area around tracked racer",
                value = afRegionSize,
                range = 0.05f..0.3f,
                unit = "",
                formatValue = { "${(it * 100).toInt()}% of frame" },
                onValueChange = { afRegionSize = it }
            )
            SettingsSlider(
                title = "Occlusion hold time",
                subtitle = "How long to hold AF when racer is hidden behind gate",
                value = occlusionTimeout,
                range = 5f..60f,
                unit = " frames",
                formatValue = { "${it.toInt()} frames (${(it / 30 * 1000).toInt()}ms)" },
                onValueChange = { occlusionTimeout = it }
            )
        }

        // --- Audio Trigger ---
        SettingsSection("Audio Trigger") {
            var audioEnabled by remember { mutableStateOf(false) }
            var audioSensitivity by remember { mutableFloatStateOf(0.5f) }

            SettingsToggle(
                title = "Enable audio trigger",
                subtitle = "Auto-fires shutter when start gate beep is detected",
                checked = audioEnabled,
                onCheckedChange = { audioEnabled = it }
            )
            SettingsSlider(
                title = "Sensitivity",
                subtitle = "Higher = triggers on quieter sounds (may false-trigger on cowbells)",
                value = audioSensitivity,
                range = 0.1f..1.0f,
                unit = "",
                formatValue = { "${(it * 100).toInt()}%" },
                onValueChange = { audioSensitivity = it }
            )
        }

        // --- Pre-Capture Buffer ---
        SettingsSection("Pre-Capture Buffer") {
            var bufferDuration by remember { mutableFloatStateOf(1.5f) }

            SettingsSlider(
                title = "Buffer duration",
                subtitle = "Seconds of frames kept before shutter press",
                value = bufferDuration,
                range = 0.5f..3.0f,
                unit = "s",
                onValueChange = { bufferDuration = it }
            )
            Text(
                text = "Memory usage: ~${(bufferDuration * 30 * 33).toInt()} MB at 4K",
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        // --- Snow Exposure ---
        SettingsSection("Snow Exposure") {
            var snowAuto by remember { mutableStateOf(true) }
            var manualEv by remember { mutableFloatStateOf(1.5f) }
            var flatLightAuto by remember { mutableStateOf(true) }

            SettingsToggle(
                title = "Auto snow compensation",
                subtitle = "Automatically detects snow and adjusts exposure",
                checked = snowAuto,
                onCheckedChange = { snowAuto = it }
            )
            if (!snowAuto) {
                SettingsSlider(
                    title = "Manual EV bias",
                    subtitle = "Fixed exposure compensation",
                    value = manualEv,
                    range = 0f..3f,
                    unit = " EV",
                    onValueChange = { manualEv = it }
                )
            }
            SettingsToggle(
                title = "Auto flat light detection",
                subtitle = "Boosts viewfinder contrast on overcast days",
                checked = flatLightAuto,
                onCheckedChange = { flatLightAuto = it }
            )
        }

        // --- Super Resolution ---
        SettingsSection("Zoom Enhancement") {
            var srEnabled by remember { mutableStateOf(true) }

            SettingsToggle(
                title = "Auto enhance zoom",
                subtitle = "Multi-frame denoise + AI upscale beyond 13.2x",
                checked = srEnabled,
                onCheckedChange = { srEnabled = it }
            )
            Text(
                text = "Pipeline: denoise at 5x+, deconvolution with teleconverter, AI upscale at 13.2x+",
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        // --- Export ---
        SettingsSection("Export & Sharing") {
            var watermarkEnabled by remember { mutableStateOf(false) }
            var watermarkText by remember { mutableStateOf("GateShot") }

            SettingsToggle(
                title = "Watermark on Social shares",
                subtitle = "Adds text watermark to Social preset exports",
                checked = watermarkEnabled,
                onCheckedChange = { watermarkEnabled = it }
            )
            if (watermarkEnabled) {
                Text(
                    text = "Watermark: \"$watermarkText\"",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }

        // --- Session ---
        SettingsSection("Session") {
            Text(
                text = "Current session settings are configured when creating a new session (event name, discipline, date).",
                color = Color.Gray,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        // --- About ---
        SettingsSection("About") {
            Text(
                text = "GateShot v0.1.0",
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Text(
                text = "Ski Racing Camera & Coaching App",
                color = Color.Gray,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Text(
                text = "Oppo Find X9 Pro + Hasselblad Teleconverter",
                color = Color.Gray,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "19 modules • 15 feature modules • Camera2/CameraX",
                color = Color(0xFF666666),
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = title.uppercase(),
            color = MaterialTheme.colorScheme.primary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        content()
        HorizontalDivider(color = Color(0xFF2A2A2A), modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
fun SettingsToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = Color.White, fontSize = 15.sp)
            Text(text = subtitle, color = Color.Gray, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = Color.Gray,
                uncheckedTrackColor = Color(0xFF333333)
            )
        )
    }
}

@Composable
fun SettingsSlider(
    title: String,
    subtitle: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String,
    formatValue: ((Float) -> String)? = null,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = title, color = Color.White, fontSize = 15.sp)
            Text(
                text = formatValue?.invoke(value) ?: "${"%.1f".format(value)}$unit",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Text(text = subtitle, color = Color.Gray, fontSize = 12.sp)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Color(0xFF444444)
            )
        )
    }
}
