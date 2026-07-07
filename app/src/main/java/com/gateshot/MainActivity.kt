package com.gateshot

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.gateshot.ui.GateShotMainScreen
import com.gateshot.ui.onboarding.OnboardingScreen
import com.gateshot.ui.onboarding.hasCompletedOnboarding
import com.gateshot.ui.onboarding.markOnboardingCompleted
import com.gateshot.ui.theme.GateShotTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var showOnboarding by mutableStateOf(false)
    private var pendingVideoUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        showOnboarding = !hasCompletedOnboarding(this)
        pendingVideoUri = extractVideoUri(intent)
        setupContent()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractVideoUri(intent)?.let { pendingVideoUri = it }
    }

    /** Video handed to GateShot via "Open with" (VIEW) or the share sheet (SEND). */
    private fun extractVideoUri(intent: Intent?): Uri? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            else -> null
        }
    }

    private fun setupContent() {
        setContent {
            GateShotTheme {
                if (showOnboarding) {
                    OnboardingScreen(
                        onComplete = {
                            markOnboardingCompleted(this@MainActivity)
                            showOnboarding = false
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    GateShotMainScreen(
                        modifier = Modifier.fillMaxSize(),
                        pendingVideoUri = pendingVideoUri,
                        onPendingVideoConsumed = { pendingVideoUri = null }
                    )
                }
            }
        }
    }
}
