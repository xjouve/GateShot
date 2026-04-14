package com.gateshot.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gateshot.platform.camera.VendorKeyReport

/**
 * Floating dev overlay showing the vendor-key state currently sent to the
 * HAL plus any silent rejections from `VendorCameraKeys.applySafe`.
 *
 * Hidden by default; toggle via Settings → Developer → "Vendor key overlay".
 * The values reflect what the app *asked* the HAL to apply — not necessarily
 * what the HAL acted on, since vendor tags can silently be dropped. A
 * `failures` entry next to a key proves the HAL rejected it; an empty
 * failures list combined with no visible effect points at a wrong numeric
 * mapping (the value was accepted but did nothing useful).
 */
@Composable
fun VendorKeyOverlay(
    report: VendorKeyReport,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xCC000000))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(
            text = "VENDOR KEYS",
            color = Color(0xFF4FC3F7),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
        Row(label = "flip", value = if (report.flipModeApplied) "[0,1] @${"%.1f".format(report.zoomRatio)}x" else "off")
        Row(label = "eis", value = report.eisModeNumeric.toString())
        Row(label = "stab", value = report.oplusStabModeNumeric.toString())
        Row(label = "log", value = if (report.logProfile) "on" else "off")
        Row(label = "hwTrack", value = if (report.hardwareTracking) "on" else "off")
        Row(label = "extISO", value = if (report.extendedIso) "on" else "off")
        Row(label = "HR", value = if (report.highResolution) "on (remosaic)" else "off")
        Row(label = "ultraHR", value = if (report.ultraHighRes) "on" else "off")
        Row(label = "aiShut", value = if (report.aiShutter) "on" else "off")
        Row(label = "bracket", value = report.bracketMode.toString())
        Row(label = "mdptz", value = report.mdptzMode.toString())
        Row(label = "asd", value = if (report.asdMode) "on" else "off")
        Row(label = "ae.met", value = listOf("avg", "spot", "matrix").getOrElse(report.aeMetering) { "?" })
        Row(label = "smvr", value = report.smvrMode.toString())
        Row(label = "fastMo", value = if (report.fastMotion) "on" else "off")
        Row(label = "torch", value = report.proTorch.toString())
        Row(label = "filter", value = report.filterPreset.toString())
        report.manualWbKelvin?.let { Row(label = "wb.K", value = "${it}K") }

        // Result-only readouts from the capture callback (HAL → us)
        if (report.luxIndex != null || report.awbCct != null ||
            report.asdSceneOplus != null || report.motionFrames != null) {
            Text(
                text = "READOUTS",
                color = Color(0xFF66BB6A),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp)
            )
            report.luxIndex?.let { Row(label = "lux", value = it.toString()) }
            report.avgBrightness?.let { Row(label = "brt", value = it.toString()) }
            report.awbCct?.let { Row(label = "cct", value = "${it}K") }
            report.asdSceneOplus?.let { Row(label = "scene", value = it.toString()) }
            report.motionFrames?.let { Row(label = "motion", value = it.toString()) }
            report.aiShutterMotion?.let { Row(label = "aiShutMo", value = it.toString()) }
            report.teleEisActive?.let { Row(label = "teleEIS", value = if (it) "yes" else "no") }
        }

        if (report.failures.isNotEmpty()) {
            Text(
                text = "REJECTED",
                color = Color(0xFFEF5350),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp)
            )
            report.failures.forEach { (key, err) ->
                Text(
                    text = "${key.substringAfterLast('.')}: $err",
                    color = Color(0xFFFFAB91),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun Row(label: String, value: String) {
    Text(
        text = "$label=$value",
        color = Color.White,
        fontSize = 8.sp,
        lineHeight = 10.sp,
        fontFamily = FontFamily.Monospace
    )
}
