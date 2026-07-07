package com.gateshot.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.gateshot.ui.gallery.GalleryScreen
import com.gateshot.ui.replay.ReplayScreen
import com.gateshot.ui.settings.SettingsScreen

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Gallery : Screen("gallery", "Library", Icons.Filled.PhotoLibrary)
    data object Replay : Screen("replay", "Replay", Icons.Filled.SlowMotionVideo)
    data object Coach : Screen("coach", "Coach", Icons.Filled.School)
    data object Settings : Screen("settings", "Settings", Icons.Filled.Settings)
}

val allScreens = listOf(Screen.Gallery, Screen.Replay, Screen.Coach, Screen.Settings)

@Composable
fun GateShotNavHost(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = hiltViewModel()
) {
    val navController = rememberNavController()
    val uiState by viewModel.uiState.collectAsState()

    // Session creation dialog — offered once at startup when no session is
    // active; also reachable later from the session flow.
    var showSessionDialog by remember { mutableStateOf(false) }
    var sessionEventName by remember { mutableStateOf("") }
    var sessionDiscipline by remember { mutableStateOf("SL") }

    LaunchedEffect(Unit) {
        if (uiState.sessionName == null) {
            showSessionDialog = true
        }
    }

    if (showSessionDialog) {
        AlertDialog(
            onDismissRequest = { showSessionDialog = false },
            title = { Text("Start Training Session") },
            text = {
                androidx.compose.foundation.layout.Column(
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = sessionEventName,
                        onValueChange = { sessionEventName = it },
                        label = { Text("Event name") },
                        placeholder = { Text("e.g. Courchevel Training") },
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
                }) { Text("Start") }
            },
            dismissButton = {
                TextButton(onClick = { showSessionDialog = false }) { Text("Skip") }
            }
        )
    }

    Scaffold(
        bottomBar = {
            GateShotBottomBar(
                navController = navController,
                screens = allScreens
            )
        },
        containerColor = Color.Black
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Gallery.route,
            modifier = modifier.padding(padding)
        ) {
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
                            popUpTo(Screen.Gallery.route) { saveState = true }
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
