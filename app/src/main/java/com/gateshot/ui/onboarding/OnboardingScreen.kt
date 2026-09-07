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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gateshot.R
import kotlinx.coroutines.launch

data class OnboardingPage(
    val title: String,
    val description: String,
    val icon: String,   // Emoji placeholder — replace with illustrations
    val tip: String = ""
)

@Composable
fun onboardingPages(): List<OnboardingPage> = listOf(
    OnboardingPage(
        title = stringResource(R.string.onboarding_page1_title),
        description = stringResource(R.string.onboarding_page1_desc),
        icon = "🎿"
    ),
    OnboardingPage(
        title = stringResource(R.string.onboarding_page2_title),
        description = stringResource(R.string.onboarding_page2_desc),
        icon = "📹",
        tip = stringResource(R.string.onboarding_page2_tip)
    ),
    OnboardingPage(
        title = stringResource(R.string.onboarding_page3_title),
        description = stringResource(R.string.onboarding_page3_desc),
        icon = "🔁",
        tip = stringResource(R.string.onboarding_page3_tip)
    ),
    OnboardingPage(
        title = stringResource(R.string.onboarding_page4_title),
        description = stringResource(R.string.onboarding_page4_desc),
        icon = "📋"
    ),
    OnboardingPage(
        title = stringResource(R.string.onboarding_page5_title),
        description = stringResource(R.string.onboarding_page5_desc),
        icon = "🏆"
    )
)

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pages = onboardingPages()
    val pagerState = rememberPagerState(pageCount = { pages.size })
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
            OnboardingPageContent(pages[page])
        }

        // Page indicator dots
        val pageIndicatorDescription = stringResource(
            R.string.onboarding_page_indicator,
            pagerState.currentPage + 1,
            pages.size
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .semantics { contentDescription = pageIndicatorDescription },
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(pages.size) { index ->
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
                color = Color.Transparent,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(
                    text = stringResource(R.string.onboarding_skip),
                    color = Color.Gray,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )
            }

            // Next / Get Started button
            val isLastPage = pagerState.currentPage == pages.size - 1
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
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(
                    text = if (isLastPage) stringResource(R.string.onboarding_get_started) else stringResource(R.string.onboarding_next),
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
