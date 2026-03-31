package com.gateshot.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.gateshot.ui.viewfinder.ViewfinderScreen

@Composable
fun GateShotMainScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Box(modifier = modifier.fillMaxSize()) {
        ViewfinderScreen(
            uiState = uiState,
            onShutterPress = viewModel::onShutterPress,
            onModeToggle = viewModel::onModeToggle,
            onPresetSelected = viewModel::onPresetSelected,
            onZoomChanged = viewModel::onZoomChanged
        )
    }
}
