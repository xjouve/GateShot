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

        // --- Camera Controls ---
        SettingsSection("Camera") {
            var manualMode by remember { mutableStateOf(viewModel.loadSettingBool("camera", "manual_mode", false)) }
            var iso by remember { mutableFloatStateOf(viewModel.loadSettingFloat("camera", "iso", 400f)) }
            var shutterSpeed by remember { mutableFloatStateOf(viewModel.loadSettingFloat("camera", "shutter_speed", 500f)) }
            var wbMode by remember { mutableStateOf(viewModel.loadSettingBool("camera", "auto_wb", true)) }
            var wbTemp by remember { mutableFloatStateOf(viewModel.loadSettingFloat("camera", "wb_temperature", 5500f)) }
            var flashMode by remember { mutableStateOf(viewModel.loadSettingBool("camera", "flash", false)) }
            var rawEnabled by remember { mutableStateOf(viewModel.loadSettingBool("camera", "save_raw", false)) }
            var outputFormat by remember { mutableStateOf(viewModel.loadSettingBool("camera", "heif", false)) }

            SettingsToggle(
                title = "Manual exposure",
                subtitle = "Set ISO and shutter speed manually (disables auto exposure)",
                checked = manualMode,
                onCheckedChange = { manualMode = it; viewModel.saveSetting("camera", "manual_mode", it) }
            )
            if (manualMode) {
                SettingsSlider(
                    title = "ISO",
                    subtitle = "Sensor sensitivity (100-19200)",
                    value = iso,
                    range = 100f..6400f,
                    unit = "",
                    formatValue = { "${it.toInt()}" },
                    onValueChange = { iso = it; viewModel.saveSetting("camera", "iso", it) }
                )
                SettingsSlider(
                    title = "Shutter speed",
                    subtitle = "Exposure time",
                    value = shutterSpeed,
                    range = 4f..8000f,
                    unit = "",
                    formatValue = { "1/${it.toInt()}s" },
                    onValueChange = { shutterSpeed = it; viewModel.saveSetting("camera", "shutter_speed", it) }
                )
            }
            SettingsToggle(
                title = "Auto white balance",
                subtitle = "Let the camera choose white balance automatically",
                checked = wbMode,
                onCheckedChange = { wbMode = it; viewModel.saveSetting("camera", "auto_wb", it) }
            )
            if (!wbMode) {
                SettingsSlider(
                    title = "Color temperature",
                    subtitle = "Manual white balance (2000K warm — 10000K cool)",
                    value = wbTemp,
                    range = 2000f..10000f,
                    unit = "K",
                    formatValue = { "${it.toInt()}K" },
                    onValueChange = { wbTemp = it; viewModel.saveSetting("camera", "wb_temperature", it) }
                )
            }
            SettingsToggle(
                title = "Flash",
                subtitle = "Enable flash for photo capture",
                checked = flashMode,
                onCheckedChange = { flashMode = it; viewModel.saveSetting("camera", "flash", it) }
            )
        }

        // --- Depth & Bokeh ---
        SettingsSection("Depth & Bokeh") {
            var bokehEnabled by remember { mutableStateOf(viewModel.loadSettingBool("camera", "bokeh_enabled", false)) }
            var bokehLevel by remember { mutableFloatStateOf(viewModel.loadSettingFloat("camera", "bokeh_level", 0.5f)) }

            SettingsToggle(
                title = "Software bokeh",
                subtitle = "Simulate shallow depth of field (background blur)",
                checked = bokehEnabled,
                onCheckedChange = { bokehEnabled = it; viewModel.saveSetting("camera", "bokeh_enabled", it) }
            )
            if (bokehEnabled) {
                SettingsSlider(
                    title = "Blur strength",
                    subtitle = "f/1.4 (max blur) to f/16 (sharp background)",
                    value = bokehLevel,
                    range = 0f..1f,
                    unit = "",
                    formatValue = {
                        val fStop = 1.4f + (1f - it) * 14.6f  // Map 0-1 to f/1.4-f/16
                        "f/${"%.1f".format(fStop)}"
                    },
                    onValueChange = { bokehLevel = it; viewModel.saveSetting("camera", "bokeh_level", it) }
                )
            }
        }

        // --- Photo Output ---
        SettingsSection("Photo Output") {
            var resolution by remember { mutableFloatStateOf(viewModel.loadSettingFloat("camera", "resolution_mp", 12f)) }
            var jpegQuality by remember { mutableFloatStateOf(viewModel.loadSettingFloat("camera", "jpeg_quality", 95f)) }
            var rawEnabled by remember { mutableStateOf(viewModel.loadSettingBool("camera", "save_raw", false)) }
            var outputFormat by remember { mutableStateOf(viewModel.loadSettingBool("camera", "heif", false)) }
            var ndFilter by remember { mutableFloatStateOf(viewModel.loadSettingFloat("camera", "nd_filter", 0f)) }

            SettingsSlider(
                title = "Resolution",
                subtitle = "Output megapixels",
                value = resolution,
                range = 12f..200f,
                unit = " MP",
                formatValue = {
                    when {
                        it < 25f -> "12 MP"
                        it < 100f -> "50 MP"
                        else -> "200 MP"
                    }
                },
                onValueChange = {
                    val snapped = when {
                        it < 25f -> 12f
                        it < 100f -> 50f
                        else -> 200f
                    }
                    resolution = snapped
                    viewModel.saveSetting("camera", "resolution_mp", snapped)
                }
            )
            SettingsSlider(
                title = "JPEG quality",
                subtitle = "Compression level (higher = larger file, better quality)",
                value = jpegQuality,
                range = 70f..100f,
                unit = "%",
                formatValue = { "${it.toInt()}%" },
                onValueChange = { jpegQuality = it; viewModel.saveSetting("camera", "jpeg_quality", it) }
            )
            SettingsSlider(
                title = "ND filter",
                subtitle = "Simulated neutral density filter (darken for slower shutters in bright light)",
                value = ndFilter,
                range = 0f..6f,
                unit = "",
                formatValue = {
                    if (it < 0.5f) "Off"
                    else "ND${Math.pow(2.0, it.toDouble()).toInt()}"
                },
                onValueChange = { ndFilter = it; viewModel.saveSetting("camera", "nd_filter", it) }
            )
            SettingsToggle(
                title = "Save RAW (DNG)",
                subtitle = "Save a RAW file alongside JPEG for post-processing",
                checked = rawEnabled,
                onCheckedChange = { rawEnabled = it; viewModel.saveSetting("camera", "save_raw", it) }
            )
            SettingsToggle(
                title = "HEIF format",
                subtitle = "Save photos as HEIF instead of JPEG (smaller files, same quality)",
                checked = outputFormat,
                onCheckedChange = { outputFormat = it; viewModel.saveSetting("camera", "heif", it) }
            )
        }

        // --- Racer Tracking ---
        SettingsSection("Racer Tracking") {
            var trackingEnabled by remember { mutableStateOf(viewModel.loadSettingBool("tracking", "enabled", false)) }
            var trackingSpeed by remember { mutableFloatStateOf(viewModel.loadSettingFloat("tracking", "min_speed", 8f)) }
            var afRegionSize by remember { mutableFloatStateOf(viewModel.loadSettingFloat("tracking", "af_region_size", 0.15f)) }
            var occlusionTimeout by remember { mutableFloatStateOf(viewModel.loadSettingFloat("tracking", "occlusion_timeout", 20f)) }

            SettingsToggle(
                title = "Enable AF Tracking",
                subtitle = "Locks focus on the fastest-moving subject (racer)",
                checked = trackingEnabled,
                onCheckedChange = {
                    trackingEnabled = it
                    viewModel.saveSetting("tracking", "enabled", it)
                    viewModel.onTrackingToggle()
                }
            )
            SettingsSlider(
                title = "Min racer speed",
                subtitle = "Lower = more sensitive (may track officials)",
                value = trackingSpeed,
                range = 3f..20f,
                unit = " px/frame",
                onValueChange = { trackingSpeed = it; viewModel.saveSetting("tracking", "min_speed", it) }
            )
            SettingsSlider(
                title = "AF region size",
                subtitle = "Size of focus area around tracked racer",
                value = afRegionSize,
                range = 0.05f..0.3f,
                unit = "",
                formatValue = { "${(it * 100).toInt()}% of frame" },
                onValueChange = { afRegionSize = it; viewModel.saveSetting("tracking", "af_region_size", it) }
            )
            SettingsSlider(
                title = "Occlusion hold time",
                subtitle = "How long to hold AF when racer is hidden behind gate",
                value = occlusionTimeout,
                range = 5f..60f,
                unit = " frames",
                formatValue = { "${it.toInt()} frames (${(it / 30 * 1000).toInt()}ms)" },
                onValueChange = { occlusionTimeout = it; viewModel.saveSetting("tracking", "occlusion_timeout", it) }
            )
        }

        // --- Audio Trigger ---
        SettingsSection("Audio Trigger") {
            var audioEnabled by remember { mutableStateOf(viewModel.loadSettingBool("trigger", "audio_enabled", false)) }
            var audioSensitivity by remember { mutableFloatStateOf(viewModel.loadSettingFloat("trigger", "audio_sensitivity", 0.5f)) }

            SettingsToggle(
                title = "Enable audio trigger",
                subtitle = "Auto-fires shutter when start gate beep is detected",
                checked = audioEnabled,
                onCheckedChange = { audioEnabled = it; viewModel.saveSetting("trigger", "audio_enabled", it) }
            )
            SettingsSlider(
                title = "Sensitivity",
                subtitle = "Higher = triggers on quieter sounds (may false-trigger on cowbells)",
                value = audioSensitivity,
                range = 0.1f..1.0f,
                unit = "",
                formatValue = { "${(it * 100).toInt()}%" },
                onValueChange = { audioSensitivity = it; viewModel.saveSetting("trigger", "audio_sensitivity", it) }
            )
        }

        // --- Pre-Capture Buffer ---
        SettingsSection("Pre-Capture Buffer") {
            var bufferDuration by remember { mutableFloatStateOf(viewModel.loadSettingFloat("burst", "buffer_duration", 1.5f)) }

            SettingsSlider(
                title = "Buffer duration",
                subtitle = "Seconds of frames kept before shutter press",
                value = bufferDuration,
                range = 0.5f..3.0f,
                unit = "s",
                onValueChange = { bufferDuration = it; viewModel.saveSetting("burst", "buffer_duration", it) }
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
            var snowAuto by remember { mutableStateOf(viewModel.loadSettingBool("exposure", "snow_compensation", true)) }
            var manualEv by remember { mutableFloatStateOf(viewModel.loadSettingFloat("exposure", "ev_bias", 1.5f)) }
            var flatLightAuto by remember { mutableStateOf(viewModel.loadSettingBool("exposure", "flat_light_auto", true)) }

            SettingsToggle(
                title = "Auto snow compensation",
                subtitle = "Automatically detects snow and adjusts exposure",
                checked = snowAuto,
                onCheckedChange = { snowAuto = it; viewModel.saveSetting("exposure", "snow_compensation", it) }
            )
            if (!snowAuto) {
                SettingsSlider(
                    title = "Manual EV bias",
                    subtitle = "Fixed exposure compensation",
                    value = manualEv,
                    range = 0f..3f,
                    unit = " EV",
                    onValueChange = { manualEv = it; viewModel.saveSetting("exposure", "ev_bias", it) }
                )
            }
            SettingsToggle(
                title = "Auto flat light detection",
                subtitle = "Boosts viewfinder contrast on overcast days",
                checked = flatLightAuto,
                onCheckedChange = { flatLightAuto = it; viewModel.saveSetting("exposure", "flat_light_auto", it) }
            )
        }

        // --- Super Resolution ---
        SettingsSection("Zoom Enhancement") {
            var srEnabled by remember { mutableStateOf(viewModel.loadSettingBool("sr", "auto_enhance", true)) }

            SettingsToggle(
                title = "Auto enhance zoom",
                subtitle = "Multi-frame denoise + AI upscale beyond 13.2x",
                checked = srEnabled,
                onCheckedChange = { srEnabled = it; viewModel.saveSetting("sr", "auto_enhance", it) }
            )
            Text(
                text = "Pipeline: denoise at 5x+, deconvolution with teleconverter, AI upscale at 13.2x+",
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        // --- Color Profile ---
        SettingsSection("Color Profile") {
            var hasselbladEnabled by remember {
                mutableStateOf(viewModel.loadSettingBool("color", "hasselblad_enabled", false))
            }

            SettingsToggle(
                title = "Hasselblad color profile",
                subtitle = "Film-look tone curves: lifted blacks, soft highlights, warm midtones",
                checked = hasselbladEnabled,
                onCheckedChange = {
                    hasselbladEnabled = it
                    viewModel.saveSetting("color", "hasselblad_enabled", it)
                }
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
                onCheckedChange = { watermarkEnabled = it; viewModel.saveSetting("export", "watermark_enabled", it) }
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
