package com.gateshot.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.gateshot.ui.navigation.GateShotNavHost

@Composable
fun GateShotMainScreen(modifier: Modifier = Modifier) {
    GateShotNavHost(modifier = modifier.fillMaxSize())
}
