package com.gateshot.ui

import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.gateshot.ui.navigation.GateShotNavHost

@Composable
fun GateShotMainScreen(
    modifier: Modifier = Modifier,
    pendingVideoUri: Uri? = null,
    onPendingVideoConsumed: () -> Unit = {}
) {
    GateShotNavHost(
        modifier = modifier.fillMaxSize(),
        pendingVideoUri = pendingVideoUri,
        onPendingVideoConsumed = onPendingVideoConsumed
    )
}
