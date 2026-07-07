package com.gateshot

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        showOnboarding = !hasCompletedOnboarding(this)
        setupContent()
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
                    GateShotMainScreen(modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}
