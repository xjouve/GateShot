package com.gateshot.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun ShutterButton(
    isRecording: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Outer ring — 72dp for easy glove tap
    Surface(
        onClick = onClick,
        modifier = modifier.size(72.dp),
        shape = CircleShape,
        color = Color.Transparent,
        border = BorderStroke(4.dp, Color.White)
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Inner circle — white for photo, red for recording
            Surface(
                modifier = Modifier.size(56.dp),
                shape = CircleShape,
                color = if (isRecording) MaterialTheme.colorScheme.secondary else Color.White
            ) {}
        }
    }
}
