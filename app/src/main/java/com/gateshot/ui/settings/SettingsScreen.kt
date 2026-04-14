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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gateshot.ui.MainViewModel

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

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
            var extendedIso by remember { mutableStateOf(viewModel.loadSettingBool("camera", "extended_iso", false)) }
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
                SettingsToggle(
                    title = "Extended ISO range",
                    subtitle = "Unlock the Pro-mode sensitivity range (>6400) via vendor key",
                    checked = extendedIso,
                    onCheckedChange = {
                        extendedIso = it
                        viewModel.saveSetting("camera", "extended_iso", it)
                    }
                )
                SettingsSlider(
                    title = "ISO",
                    subtitle = if (extendedIso) "Sensor sensitivity (100-25600 extended)"
                               else "Sensor sensitivity (100-6400)",
                    value = iso.coerceAtMost(if (extendedIso) 25600f else 6400f),
                    range = 100f..(if (extendedIso) 25600f else 6400f),
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

        // --- Video Recording ---
        SettingsSection("Video Recording") {
            var videoResolution by remember { mutableIntStateOf(
                viewModel.loadSettingFloat("video", "resolution", 2160f).toInt()
            ) }
            var videoFps by remember { mutableIntStateOf(
                viewModel.loadSettingFloat("video", "frame_rate", 30f).toInt()
            ) }
            var hdrEnabled by remember { mutableStateOf(
                viewModel.loadSettingBool("video", "hdr", false)
            ) }
            var oisMode by remember { mutableIntStateOf(
                viewModel.loadSettingFloat("video", "ois_mode", 1f).toInt()
            ) }
            var eisMode by remember { mutableIntStateOf(
                viewModel.loadSettingFloat("video", "eis_mode", 0f).toInt()
            ) }

            // Resolution selector
            Text("Resolution", color = Color.White, fontSize = 15.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(720 to "720p", 1080 to "1080p", 2160 to "4K").forEach { (res, label) ->
                    Surface(
                        onClick = {
                            videoResolution = res
                            viewModel.saveSetting("video", "resolution", res.toFloat())
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = if (videoResolution == res) MaterialTheme.colorScheme.primary else Color(0xFF333333),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            label,
                            color = if (videoResolution == res) Color.Black else Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }

            // Frame rate selector
            Text("Frame rate", color = Color.White, fontSize = 15.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val availableFps = when (videoResolution) {
                    720 -> listOf(30, 60, 120, 240, 480)
                    1080 -> listOf(30, 60, 120, 240)
                    else -> listOf(30, 60, 120) // 4K
                }
                availableFps.forEach { fps ->
                    Surface(
                        onClick = {
                            videoFps = fps
                            viewModel.saveSetting("video", "frame_rate", fps.toFloat())
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = if (videoFps == fps) MaterialTheme.colorScheme.primary else Color(0xFF333333),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            "${fps}fps",
                            color = if (videoFps == fps) Color.Black else Color.White,
                            fontSize = if (availableFps.size > 4) 11.sp else 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }
            if (videoFps >= 120) {
                Text(
                    if (videoFps >= 240) "Slow-motion: ${videoFps / 30}x slower playback"
                    else "High frame rate: ${videoFps / 30}x slower in slow-mo",
                    color = Color(0xFF4FC3F7),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            SettingsToggle(
                title = "Dolby Vision HDR",
                subtitle = "10-bit HDR video (Android 13+, higher file sizes)",
                checked = hdrEnabled,
                onCheckedChange = { hdrEnabled = it; viewModel.saveSetting("video", "hdr", it) }
            )

            var logProfile by remember { mutableStateOf(viewModel.loadSettingBool("video", "log_profile", false)) }
            SettingsToggle(
                title = "LOG color profile",
                subtitle = "Flat tone curve for grading — needs colour correction in post",
                checked = logProfile,
                onCheckedChange = {
                    logProfile = it
                    viewModel.saveSetting("video", "log_profile", it)
                }
            )

            // OIS mode
            Text("Optical stabilization", color = Color.White, fontSize = 15.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(0 to "Off", 1 to "Standard", 2 to "Maximum").forEach { (mode, label) ->
                    Surface(
                        onClick = {
                            oisMode = mode
                            viewModel.saveSetting("video", "ois_mode", mode.toFloat())
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = if (oisMode == mode) MaterialTheme.colorScheme.primary else Color(0xFF333333),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            label,
                            color = if (oisMode == mode) Color.Black else Color.White,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }

            // EIS mode
            Text("Electronic stabilization", color = Color.White, fontSize = 15.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(0 to "Off", 1 to "Standard", 2 to "Panning").forEach { (mode, label) ->
                    Surface(
                        onClick = {
                            eisMode = mode
                            viewModel.saveSetting("video", "eis_mode", mode.toFloat())
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = if (eisMode == mode) MaterialTheme.colorScheme.primary else Color(0xFF333333),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            label,
                            color = if (eisMode == mode) Color.Black else Color.White,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }
            Text(
                "Panning: horizontal axis unlocked for smooth pans",
                color = Color.Gray,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        // --- Autofocus ---
        SettingsSection("Autofocus") {
            var afMode by remember { mutableIntStateOf(
                viewModel.loadSettingFloat("af", "mode_index", 2f).toInt()
            ) }
            var facePriority by remember { mutableStateOf(
                viewModel.loadSettingBool("af", "face_priority", false)
            ) }

            // AF mode selector
            Text("AF mode", color = Color.White, fontSize = 15.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    0 to "Single",
                    1 to "Cont.",
                    2 to "Predictive",
                    3 to "Manual"
                ).forEach { (mode, label) ->
                    Surface(
                        onClick = {
                            afMode = mode
                            viewModel.saveSetting("af", "mode_index", mode.toFloat())
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = if (afMode == mode) MaterialTheme.colorScheme.primary else Color(0xFF333333),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            label,
                            color = if (afMode == mode) Color.Black else Color.White,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }
            Text(
                when (afMode) {
                    0 -> "Single: focuses once on half-press, locks"
                    1 -> "Continuous: re-focuses continuously on the center"
                    2 -> "Predictive: anticipates racer motion for sharp tracking shots"
                    3 -> "Manual: focus distance set manually (tap focus ring)"
                    else -> ""
                },
                color = Color.Gray,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )

            SettingsToggle(
                title = "Face priority",
                subtitle = "Prefer faces over fastest-moving subject for AF",
                checked = facePriority,
                onCheckedChange = { facePriority = it; viewModel.saveSetting("af", "face_priority", it) }
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

            var hrMode by remember { mutableStateOf(viewModel.loadSettingBool("camera", "hr_mode", false)) }
            SettingsToggle(
                title = "Hasselblad Haute Résolution",
                subtitle = "Requests full-sensor remosaic (50 MP main / 200 MP periscope) via vendor keys. On this firmware the HAL gates the larger surface behind a private extension so actual output stays at 12 MP until Oppo exposes it publicly. Toggle rebinds the camera and shows request state in the dev overlay.",
                checked = hrMode,
                onCheckedChange = {
                    hrMode = it
                    viewModel.saveSetting("camera", "hr_mode", it)
                }
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
            var hardwareTracking by remember {
                mutableStateOf(viewModel.loadSettingBool("tracking", "hardware", false))
            }
            SettingsToggle(
                title = "Use hardware tracking AF",
                subtitle = "MediaTek native tracker via vendor key — faster but less tunable than the software tracker",
                checked = hardwareTracking,
                onCheckedChange = {
                    hardwareTracking = it
                    viewModel.saveSetting("tracking", "hardware", it)
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

        // --- Motion Trigger ---
        SettingsSection("Motion Trigger") {
            var motionSensitivity by remember { mutableFloatStateOf(
                viewModel.loadSettingFloat("trigger", "motion_sensitivity", 0.5f)
            ) }
            var motionCooldown by remember { mutableFloatStateOf(
                viewModel.loadSettingFloat("trigger", "motion_cooldown", 500f)
            ) }

            SettingsSlider(
                title = "Motion sensitivity",
                subtitle = "How much movement in a trigger zone fires the shutter",
                value = motionSensitivity,
                range = 0.1f..1f,
                unit = "",
                formatValue = {
                    when {
                        it < 0.3f -> "Low"
                        it < 0.7f -> "Medium"
                        else -> "High"
                    } + " (${(it * 100).toInt()}%)"
                },
                onValueChange = { motionSensitivity = it; viewModel.saveSetting("trigger", "motion_sensitivity", it) }
            )
            SettingsSlider(
                title = "Trigger cooldown",
                subtitle = "Minimum time between triggers for the same zone",
                value = motionCooldown,
                range = 200f..3000f,
                unit = "ms",
                formatValue = { "${it.toInt()}ms" },
                onValueChange = { motionCooldown = it; viewModel.saveSetting("trigger", "motion_cooldown", it) }
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
                subtitle = "Multi-frame denoise + sharpening for telephoto shots (5x+)",
                checked = srEnabled,
                onCheckedChange = { srEnabled = it; viewModel.saveSetting("sr", "auto_enhance", it) }
            )
            Text(
                text = "Pipeline: denoise at 5x+, deconvolution with teleconverter, enhanced upscale at 13.2x+",
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

        // --- Voice Commands ---
        SettingsSection("Voice Commands") {
            var voiceEnabled by remember { mutableStateOf(
                viewModel.loadSettingBool("voice", "enabled", false)
            ) }

            SettingsToggle(
                title = "Enable voice commands",
                subtitle = "\"Start recording\", \"Stop\", \"Photo\", \"Slow-mo\", \"Next mode\"",
                checked = voiceEnabled,
                onCheckedChange = {
                    voiceEnabled = it
                    viewModel.saveSetting("voice", "enabled", it)
                    if (it) viewModel.startVoiceCommands() else viewModel.stopVoiceCommands()
                }
            )
            if (voiceEnabled) {
                Text(
                    "Commands: \"start recording\", \"stop\", \"mark\", \"slow-mo\", \"photo\", \"next mode\"",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }

        // --- Export ---
        SettingsSection("Export & Sharing") {
            var watermarkEnabled by remember { mutableStateOf(
                viewModel.loadSettingBool("export", "watermark_enabled", false)
            ) }
            var fedExportStatus by remember { mutableStateOf("") }

            SettingsToggle(
                title = "Watermark on Social shares",
                subtitle = "Adds \"GateShot\" watermark to exported photos/videos",
                checked = watermarkEnabled,
                onCheckedChange = { watermarkEnabled = it; viewModel.saveSetting("export", "watermark_enabled", it) }
            )

            // Federation export
            Surface(
                onClick = {
                    fedExportStatus = "Exporting..."
                    viewModel.exportFederationFormat(context) { status ->
                        fedExportStatus = status
                    }
                },
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF333333),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Federation Export", color = Color.White, fontSize = 15.sp)
                    Text("FIS-compatible naming + metadata CSV", color = Color.Gray, fontSize = 12.sp)
                }
            }
            if (fedExportStatus.isNotBlank()) {
                Text(fedExportStatus, color = Color(0xFF4FC3F7), fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp))
            }
        }

        // --- Storage ---
        SettingsSection("Storage") {
            val uiState by viewModel.uiState.collectAsState()
            val context = LocalContext.current
            val videoDir = remember {
                java.io.File(context.getExternalFilesDir(null), "GateShot/videos")
            }
            val photoDir = remember {
                java.io.File(context.getExternalFilesDir(null), "GateShot/photos")
            }
            val videoCount = remember { videoDir.listFiles()?.count { it.extension == "mp4" } ?: 0 }
            val photoCount = remember { photoDir.listFiles()?.size ?: 0 }
            val videoSizeMb = remember {
                (videoDir.listFiles()?.sumOf { it.length() } ?: 0L) / (1024 * 1024)
            }
            val photoSizeMb = remember {
                (photoDir.listFiles()?.sumOf { it.length() } ?: 0L) / (1024 * 1024)
            }

            Text(
                text = "Free space: ${"%.1f".format(uiState.storageRemainingGb)} GB",
                color = Color.White,
                fontSize = 15.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            Text(
                text = "Photos: $photoCount files (${photoSizeMb} MB)",
                color = Color.Gray,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
            Text(
                text = "Videos: $videoCount files (${videoSizeMb} MB)",
                color = Color.Gray,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
            Text(
                text = "Total: ${photoSizeMb + videoSizeMb} MB used by GateShot",
                color = Color.Gray,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }

        // --- Vendor experiments ---
        SettingsSection("Vendor experiments") {
            var aiShutter by remember { mutableStateOf(viewModel.loadSettingBool("vendor", "ai_shutter", false)) }
            SettingsToggle(
                title = "AI shutter (best-shot picker)",
                subtitle = "Hardware best-frame selection from a short burst around the shutter press. Vendor key: com.oplus.aishutter.enable",
                checked = aiShutter,
                onCheckedChange = { aiShutter = it; viewModel.saveSetting("vendor", "ai_shutter", it) }
            )

            var bracketMode by remember { mutableFloatStateOf(viewModel.loadSettingFloat("vendor", "bracket_mode", 0f)) }
            SettingsSlider(
                title = "Exposure bracketing",
                subtitle = "0 = off, 1 = AEB ±1 EV, 2 = wider AEB. Vendor key: com.oplus.BracketMode",
                value = bracketMode,
                range = 0f..3f,
                unit = "",
                formatValue = { "${it.toInt()}" },
                onValueChange = { bracketMode = it; viewModel.saveSetting("vendor", "bracket_mode", it) }
            )

            var mdptzMode by remember { mutableFloatStateOf(viewModel.loadSettingFloat("vendor", "mdptz_mode", 0f)) }
            SettingsSlider(
                title = "Hardware mdptz subject framing",
                subtitle = "Motion-Directed Pan/Tilt/Zoom on the periscope. 0 = off, 1+ = follow. Vendor key: com.mediatek.mdptzfeature.mdptzMode",
                value = mdptzMode,
                range = 0f..3f,
                unit = "",
                formatValue = { "${it.toInt()}" },
                onValueChange = { mdptzMode = it; viewModel.saveSetting("vendor", "mdptz_mode", it) }
            )

            var aiScene by remember { mutableStateOf(viewModel.loadSettingBool("vendor", "ai_scene", false)) }
            SettingsToggle(
                title = "AI scene detection (ASD)",
                subtitle = "Let the HAL classify the scene (snow / portrait / fast-motion / etc.). Vendor keys: com.mediatek.facefeature.asdmode + com.oplus.ai.scene.app.enable",
                checked = aiScene,
                onCheckedChange = { aiScene = it; viewModel.saveSetting("vendor", "ai_scene", it) }
            )

            var aeMetering by remember { mutableFloatStateOf(viewModel.loadSettingFloat("vendor", "ae_metering", 0f)) }
            SettingsSlider(
                title = "AE metering mode",
                subtitle = "0 = average, 1 = spot, 2 = matrix. Spot prevents snow blow-out on the racer. Vendor key: com.mediatek.3afeature.aeMeteringMode",
                value = aeMetering,
                range = 0f..2f,
                unit = "",
                formatValue = { listOf("Average", "Spot", "Matrix").getOrElse(it.toInt()) { "?" } },
                onValueChange = { aeMetering = it; viewModel.saveSetting("vendor", "ae_metering", it) }
            )

            var smvrMode by remember { mutableFloatStateOf(viewModel.loadSettingFloat("vendor", "smvr_mode", 0f)) }
            SettingsSlider(
                title = "Slow-motion (SMVR)",
                subtitle = "MediaTek slow-mo path. 0=off, 1=120, 2=240, 3=480, 4=960 fps. Vendor key: com.mediatek.smvrfeature.smvrMode",
                value = smvrMode,
                range = 0f..4f,
                unit = "",
                formatValue = { listOf("off", "120 fps", "240 fps", "480 fps", "960 fps").getOrElse(it.toInt()) { "?" } },
                onValueChange = { smvrMode = it; viewModel.saveSetting("vendor", "smvr_mode", it) }
            )

            var fastMotion by remember { mutableStateOf(viewModel.loadSettingBool("vendor", "fast_motion", false)) }
            SettingsToggle(
                title = "Hyperlapse (fast motion)",
                subtitle = "Time-lapse video recording. Vendor key: com.oplus.fastmotion.mode.enable",
                checked = fastMotion,
                onCheckedChange = { fastMotion = it; viewModel.saveSetting("vendor", "fast_motion", it) }
            )

            var proTorch by remember { mutableFloatStateOf(viewModel.loadSettingFloat("vendor", "pro_torch", 0f)) }
            SettingsSlider(
                title = "Pro torch",
                subtitle = "Manual torch intensity ramp (0 = off). Vendor key: com.oplus.ProTorchMode",
                value = proTorch,
                range = 0f..3f,
                unit = "",
                formatValue = { "${it.toInt()}" },
                onValueChange = { proTorch = it; viewModel.saveSetting("vendor", "pro_torch", it) }
            )

            var filterPreset by remember { mutableFloatStateOf(viewModel.loadSettingFloat("vendor", "filter_preset", 0f)) }
            SettingsSlider(
                title = "Hasselblad XCD filter",
                subtitle = "0 = Original, 1+ = vendor LUT presets matching the native filter strip. Vendor key: com.oplus.app.filter.type",
                value = filterPreset,
                range = 0f..7f,
                unit = "",
                formatValue = { "${it.toInt()}" },
                onValueChange = { filterPreset = it; viewModel.saveSetting("vendor", "filter_preset", it) }
            )

            var wbKelvin by remember { mutableFloatStateOf(viewModel.loadSettingFloat("vendor", "manual_wb_kelvin", 0f)) }
            SettingsSlider(
                title = "Manual WB temperature (Kelvin)",
                subtitle = "0 = use auto. Otherwise overrides via vendor com.oplus.manualWB.color_temperature",
                value = wbKelvin,
                range = 0f..10000f,
                unit = "K",
                formatValue = { if (it < 100f) "auto" else "${it.toInt()}K" },
                onValueChange = { wbKelvin = it; viewModel.saveSetting("vendor", "manual_wb_kelvin", it) }
            )

            var ultraHr by remember { mutableStateOf(viewModel.loadSettingBool("vendor", "ultra_hr", false)) }
            SettingsToggle(
                title = "Alternate ultra-HR enable",
                subtitle = "Sibling key to remosaicenable: com.oplus.ultra.high.resolution.enable. Same caveat — capped by the public stream config map.",
                checked = ultraHr,
                onCheckedChange = { ultraHr = it; viewModel.saveSetting("vendor", "ultra_hr", it) }
            )
        }

        // --- Developer ---
        SettingsSection("Developer") {
            var vendorOverlay by remember {
                mutableStateOf(viewModel.loadSettingBool("dev", "vendor_overlay", false))
            }
            SettingsToggle(
                title = "Vendor key overlay",
                subtitle = "Show live flipmode / eismode / stab / tracking / LOG values + any HAL rejections in the viewfinder",
                checked = vendorOverlay,
                onCheckedChange = {
                    vendorOverlay = it
                    viewModel.saveSetting("dev", "vendor_overlay", it)
                }
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
