package com.gateshot.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.res.stringResource
import com.gateshot.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.gateshot.ui.MainViewModel
import com.gateshot.ui.coaching.CoachScreen
import com.gateshot.ui.capture.CaptureScreen
import com.gateshot.ui.gallery.GalleryScreen
import com.gateshot.ui.home.HomeScreen
import com.gateshot.ui.replay.ReplayScreen
import com.gateshot.ui.settings.SettingsScreen

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Home : Screen("home", "Home", Icons.Filled.Home)
    data object Gallery : Screen("gallery", "Library", Icons.Filled.PhotoLibrary)
    data object Replay : Screen("replay", "Replay", Icons.Filled.SlowMotionVideo)
    data object Coach : Screen("coach", "Coach", Icons.Filled.School)
    data object Settings : Screen("settings", "Settings", Icons.Filled.Settings)
    data object Capture : Screen("capture", "Capture", Icons.Filled.SlowMotionVideo)
}

val allScreens = listOf(Screen.Home, Screen.Gallery, Screen.Replay, Screen.Coach, Screen.Settings)

@Composable
fun GateShotNavHost(
    modifier: Modifier = Modifier,
    pendingVideoUri: android.net.Uri? = null,
    onPendingVideoConsumed: () -> Unit = {},
    viewModel: MainViewModel = hiltViewModel()
) {
    val navController = rememberNavController()
    val currentEntry by navController.currentBackStackEntryAsState()

    // A clip is ready for review (Library tap or external open) — switch tab
    LaunchedEffect(Unit) {
        viewModel.openInReplay.collect {
            navController.navigate(Screen.Replay.route) {
                popUpTo(Screen.Home.route) { saveState = true }
                launchSingleTop = true
            }
        }
    }

    // App-wide error/message channel (see MainViewModel.uiMessages) — surfaced
    // as a Snackbar regardless of which tab is currently showing.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.uiMessages.collect { msg ->
            snackbarHostState.showSnackbar(msg.text)
        }
    }

    // Video handed to GateShot via Open with / Share
    LaunchedEffect(pendingVideoUri) {
        pendingVideoUri?.let { uri ->
            viewModel.onOpenExternalVideo(uri)
            onPendingVideoConsumed()
        }
    }

    // Session setup is optional and opened from Home.
    var showSessionDialog by remember { mutableStateOf(false) }
    var sessionEventName by remember { mutableStateOf("") }
    var sessionDiscipline by remember { mutableStateOf("SL") }

    if (showSessionDialog) {
        AlertDialog(
            onDismissRequest = { showSessionDialog = false },
            title = { Text(stringResource(R.string.session_dialog_title)) },
            text = {
                androidx.compose.foundation.layout.Column(
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = sessionEventName,
                        onValueChange = { sessionEventName = it },
                        label = { Text(stringResource(R.string.session_dialog_event_name)) },
                        placeholder = { Text(stringResource(R.string.session_dialog_event_hint)) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors()
                    )
                    androidx.compose.foundation.layout.Row(
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("SL", "GS", "SG", "DH").forEach { disc ->
                            androidx.compose.material3.Surface(
                                onClick = { sessionDiscipline = disc },
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                color = if (sessionDiscipline == disc)
                                    MaterialTheme.colorScheme.primary
                                else Color(0xFFE0E0E0)
                            ) {
                                Text(
                                    disc,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = sessionEventName.ifBlank { "Training" }
                    viewModel.onCreateSession(name, sessionDiscipline)
                    showSessionDialog = false
                }) { Text(stringResource(R.string.session_dialog_start)) }
            },
            dismissButton = {
                TextButton(onClick = { showSessionDialog = false }) { Text(stringResource(R.string.session_dialog_skip)) }
            }
        )
    }

    Scaffold(
        bottomBar = {
            if (currentEntry?.destination?.route != Screen.Capture.route) {
                GateShotBottomBar(navController = navController, screens = allScreens)
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = Color.Black
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = modifier.padding(padding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(onStartSession = { showSessionDialog = true },
                    onRecord = { navController.navigate(Screen.Capture.route) })
            }

            composable(Screen.Capture.route) {
                CaptureScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }

            composable(Screen.Gallery.route) {
                GalleryScreen(viewModel = viewModel)
            }

            composable(Screen.Replay.route) {
                ReplayScreen(viewModel = viewModel)
            }

            composable(Screen.Coach.route) {
                CoachScreen(viewModel = viewModel)
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
                            popUpTo(Screen.Home.route) { saveState = true }
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
