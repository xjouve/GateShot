package com.gateshot

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.gateshot.core.event.AppEvent
import com.gateshot.core.event.EventBus
import com.gateshot.ui.GateShotMainScreen
import com.gateshot.ui.theme.GateShotTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var eventBus: EventBus

    private val scope = CoroutineScope(Dispatchers.Main)

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            setupContent()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (hasPermissions()) {
            setupContent()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            // Volume Up = Shutter (photo capture / burst trigger)
            KeyEvent.KEYCODE_VOLUME_UP -> {
                scope.launch {
                    eventBus.publish(AppEvent.ShutterPressed())
                }
                true  // Consume the event — don't change volume
            }
            // Volume Down = Cycle preset
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                scope.launch {
                    eventBus.publish(AppEvent.PresetApplied("__cycle_next__"))
                }
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun hasPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun setupContent() {
        setContent {
            GateShotTheme {
                GateShotMainScreen(modifier = Modifier.fillMaxSize())
            }
        }
    }
}
