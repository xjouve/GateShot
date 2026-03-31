package com.gateshot.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class PresetInfo(
    val id: String,
    val shortName: String,
    val displayName: String
)

val PRESETS = listOf(
    PresetInfo("slalom_gs", "SL/GS", "Slalom / GS"),
    PresetInfo("speed", "DH/SG", "Speed Events"),
    PresetInfo("panning", "PAN", "Panning"),
    PresetInfo("finish", "FIN", "Finish Area"),
    PresetInfo("atmosphere", "WIDE", "Atmosphere"),
    PresetInfo("training", "TRAIN", "Training Analysis")
)

@Composable
fun PresetSelector(
    currentPreset: String,
    displayName: String,
    onPresetSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PRESETS.forEach { preset ->
            val isActive = preset.id == currentPreset
            // Each button is 56x56dp — large enough for gloves
            Surface(
                onClick = { onPresetSelected(preset.id) },
                shape = RoundedCornerShape(12.dp),
                color = if (isActive)
                    MaterialTheme.colorScheme.primary
                else
                    Color(0x88000000),
                modifier = Modifier.size(56.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = preset.shortName,
                        color = if (isActive) Color.Black else Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
