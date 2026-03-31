package com.gateshot.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gateshot.core.mode.AppMode
import com.gateshot.ui.MainUiState

@Composable
fun StatusBar(
    uiState: MainUiState,
    onModeToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(Color(0x88000000))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // App name + mode
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "GATESHOT",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(12.dp))

            // Mode toggle — large touch target for gloves
            Surface(
                onClick = onModeToggle,
                shape = RoundedCornerShape(16.dp),
                color = if (uiState.mode == AppMode.COACH)
                    MaterialTheme.colorScheme.primary
                else
                    Color(0xFF444444),
                modifier = Modifier.size(width = 80.dp, height = 32.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Filled.School,
                        contentDescription = "Coach mode",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Coach",
                        color = Color.White,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Battery + temp
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Temperature warning
            val tempColor = when {
                uiState.batteryTemp <= 0f -> MaterialTheme.colorScheme.error
                uiState.batteryTemp <= 5f -> MaterialTheme.colorScheme.secondary
                else -> Color.White
            }
            Icon(
                Icons.Filled.Thermostat,
                contentDescription = "Temperature",
                tint = tempColor,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = "${uiState.batteryTemp.toInt()}°",
                color = tempColor,
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Battery
            Icon(
                Icons.Filled.BatteryFull,
                contentDescription = "Battery",
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = "${uiState.batteryLevel}%",
                color = Color.White,
                fontSize = 12.sp
            )
        }
    }
}
