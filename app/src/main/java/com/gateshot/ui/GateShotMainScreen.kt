package com.gateshot.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.hilt.navigation.compose.hiltViewModel
import com.gateshot.ui.viewfinder.ViewfinderScreen

@Composable
fun GateShotMainScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    Box(modifier = modifier.fillMaxSize()) {
        ViewfinderScreen(
            uiState = uiState,
            cameraXPlatform = viewModel.cameraXPlatform,
            onCameraPreviewReady = { previewView ->
                viewModel.bindCameraPreview(previewView, lifecycleOwner)
            },
            onShutterPress = viewModel::onShutterPress,
            onModeToggle = viewModel::onModeToggle,
            onPresetSelected = viewModel::onPresetSelected,
            onZoomChanged = viewModel::onZoomChanged,
            onVideoToggle = viewModel::onVideoToggle,
            onAddTriggerZone = viewModel::onAddTriggerZone,
            onClearTriggerZones = viewModel::onClearTriggerZones
        )
    }
}
