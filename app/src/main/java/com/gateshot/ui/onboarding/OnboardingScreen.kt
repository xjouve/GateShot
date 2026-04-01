package com.gateshot.ui.onboarding

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

data class OnboardingPage(
    val title: String,
    val description: String,
    val icon: String,   // Emoji placeholder — replace with illustrations
    val tip: String = ""
)

val onboardingPages = listOf(
    OnboardingPage(
        title = "Welcome to GateShot",
        description = "The ski racing camera and coaching app for the Oppo Find X9 Pro.\n\nBuilt for photographers, videographers, and coaches — from club training to World Cup events.",
        icon = "🎿"
    ),
    OnboardingPage(
        title = "Discipline Presets",
        description = "Tap a preset on the left side of the viewfinder. Each one configures shutter speed, burst, AF, exposure, and stabilization for the specific situation.",
        icon = "🏔️",
        tip = "Volume Down cycles presets — no need to touch the screen"
    ),
    OnboardingPage(
        title = "Hasselblad Teleconverter",
        description = "Attach the magnetic teleconverter to get 10x optical zoom (230mm). GateShot auto-detects it and enables lens deconvolution for maximum sharpness.",
        icon = "🔭",
        tip = "At slalom distance (3-8m), the native 5x telephoto is usually enough"
    ),
    OnboardingPage(
        title = "Gate-Zone Trigger",
        description = "Long-press the viewfinder to place a trigger zone on a gate. GateShot auto-fires a burst when a racer enters the zone.\n\nDouble-tap to clear all zones.",
        icon = "🎯",
        tip = "Place zones slightly ahead of the gate for perfect timing"
    ),
    OnboardingPage(
        title = "Racer Tracking",
        description = "Tap the AF button to enable tracking. GateShot locks onto the fastest-moving subject (the racer) and ignores officials.\n\nThe tracker holds focus even when the racer goes behind a gate panel.",
        icon = "🔒",
        tip = "Green bracket = locked. Orange = holding through occlusion."
    ),
    OnboardingPage(
        title = "Coach Mode",
        description = "Tap the Coach toggle to unlock replay, overlay, timing, and annotation tools.\n\nCapture a blank reference panorama before training to enable perspective-corrected run overlays.",
        icon = "📋",
        tip = "Replay, Annotate, and Settings tabs appear in Coach mode"
    ),
    OnboardingPage(
        title = "Ready to Shoot",
        description = "Volume Up = Shutter\nVolume Down = Next Preset\n\nGlove-friendly buttons, snow-aware exposure, pre-capture buffer — everything you need on the slope.\n\nGood luck out there! 🏁",
        icon = "📸"
    )
)

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pagerState = rememberPagerState(pageCount = { onboardingPages.size })
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Pages
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { page ->
            OnboardingPageContent(onboardingPages[page])
        }

        // Page indicator dots
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(onboardingPages.size) { index ->
                Surface(
                    shape = CircleShape,
                    color = if (index == pagerState.currentPage)
                        MaterialTheme.colorScheme.primary
                    else
                        Color(0xFF444444),
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(8.dp)
                ) {}
            }
        }

        // Buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Skip button
            Surface(
                onClick = onComplete,
                shape = RoundedCornerShape(24.dp),
                color = Color.Transparent
            ) {
                Text(
                    text = "Skip",
                    color = Color.Gray,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )
            }

            // Next / Get Started button
            val isLastPage = pagerState.currentPage == onboardingPages.size - 1
            Surface(
                onClick = {
                    if (isLastPage) {
                        onComplete()
                    } else {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    }
                },
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primary
            ) {
                Text(
                    text = if (isLastPage) "Get Started" else "Next",
                    color = Color.Black,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun OnboardingPageContent(page: OnboardingPage) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Icon
        Text(
            text = page.icon,
            fontSize = 72.sp,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // Title
        Text(
            text = page.title,
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Description
        Text(
            text = page.description,
            color = Color(0xFFCCCCCC),
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp
        )

        // Tip
        if (page.tip.isNotEmpty()) {
            Spacer(modifier = Modifier.height(20.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF1A2A1A)
            ) {
                Text(
                    text = "💡 ${page.tip}",
                    color = Color(0xFF66BB6A),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
    }
}

/**
 * Check if onboarding has been completed.
 */
fun hasCompletedOnboarding(context: Context): Boolean {
    val prefs = context.getSharedPreferences("gateshot_prefs", Context.MODE_PRIVATE)
    return prefs.getBoolean("onboarding_completed", false)
}

fun markOnboardingCompleted(context: Context) {
    val prefs = context.getSharedPreferences("gateshot_prefs", Context.MODE_PRIVATE)
    prefs.edit().putBoolean("onboarding_completed", true).apply()
}
