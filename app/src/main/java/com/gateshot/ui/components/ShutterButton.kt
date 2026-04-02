package com.gateshot.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    var lastClickTime by remember { mutableLongStateOf(0L) }

    // Outer ring — 72dp for easy glove tap
    Box(
        modifier = modifier
            .size(72.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                val now = System.currentTimeMillis()
                if (now - lastClickTime > 500) {  // 500ms debounce
                    lastClickTime = now
                    onClick()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Outer ring
        Surface(
            modifier = Modifier.size(72.dp),
            shape = CircleShape,
            color = Color.Transparent,
            border = BorderStroke(4.dp, Color.White)
        ) {}

        // Inner circle — white for photo, red for recording
        Surface(
            modifier = Modifier.size(56.dp),
            shape = CircleShape,
            color = if (isRecording) MaterialTheme.colorScheme.secondary else Color.White
        ) {}
    }
}
