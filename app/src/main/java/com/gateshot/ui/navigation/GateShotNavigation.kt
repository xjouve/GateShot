package com.gateshot.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.gateshot.core.mode.AppMode
import com.gateshot.ui.MainViewModel
import com.gateshot.ui.annotation.AnnotationScreen
import com.gateshot.ui.gallery.GalleryScreen
import com.gateshot.ui.replay.ReplayScreen
import com.gateshot.ui.settings.SettingsScreen
import com.gateshot.ui.viewfinder.ViewfinderScreen

sealed class Screen(val route: String, val label: String, val icon: ImageVector, val coachOnly: Boolean = false) {
    data object Viewfinder : Screen("viewfinder", "Shoot", Icons.Filled.Camera)
    data object Gallery : Screen("gallery", "Gallery", Icons.Filled.PhotoLibrary)
    data object Replay : Screen("replay", "Replay", Icons.Filled.SlowMotionVideo, coachOnly = true)
    data object Annotation : Screen("annotation", "Annotate", Icons.Filled.Draw, coachOnly = true)
    data object Settings : Screen("settings", "Settings", Icons.Filled.Settings)
}

val allScreens = listOf(Screen.Viewfinder, Screen.Gallery, Screen.Replay, Screen.Annotation, Screen.Settings)

@Composable
fun GateShotNavHost(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = hiltViewModel()
) {
    val navController = rememberNavController()
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    val visibleScreens = allScreens.filter { screen ->
        !screen.coachOnly || uiState.mode == AppMode.COACH
    }

    Scaffold(
        bottomBar = {
            GateShotBottomBar(
                navController = navController,
                screens = visibleScreens
            )
        },
        containerColor = Color.Black
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Viewfinder.route,
            modifier = modifier.padding(padding)
        ) {
            composable(Screen.Viewfinder.route) {
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
                    onClearTriggerZones = viewModel::onClearTriggerZones,
                    onTrackingToggle = viewModel::onTrackingToggle
                )
            }

            composable(Screen.Gallery.route) {
                GalleryScreen(viewModel = viewModel)
            }

            composable(Screen.Replay.route) {
                ReplayScreen(viewModel = viewModel)
            }

            composable(Screen.Annotation.route) {
                AnnotationScreen(viewModel = viewModel)
            }

            composable(Screen.Settings.route) {
                SettingsScreen(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun GateShotBottomBar(
    navController: NavHostController,
    screens: List<Screen>
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    NavigationBar(
        containerColor = Color(0xFF1A1A1A),
        contentColor = Color.White
    ) {
        screens.forEach { screen ->
            NavigationBarItem(
                icon = {
                    Icon(screen.icon, contentDescription = screen.label)
                },
                label = { Text(screen.label, fontSize = 11.sp) },
                selected = currentRoute == screen.route,
                onClick = {
                    if (currentRoute != screen.route) {
                        navController.navigate(screen.route) {
                            popUpTo(Screen.Viewfinder.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color(0xFF4FC3F7),
                    selectedTextColor = Color(0xFF4FC3F7),
                    unselectedIconColor = Color.Gray,
                    unselectedTextColor = Color.Gray,
                    indicatorColor = Color(0xFF2A2A2A)
                )
            )
        }
    }
}
