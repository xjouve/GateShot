package com.gateshot.ui.coaching

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gateshot.ui.MainViewModel
import com.gateshot.ui.analysis.AnalysisScreen
import com.gateshot.ui.annotation.AnnotationScreen
import com.gateshot.ui.athlete.AthleteScreen

/**
 * Combined Coach screen — tabbed container for all coaching sub-screens.
 * Keeps the bottom nav clean (5 items max) while giving access to all tools.
 *
 * Tabs: Annotate | Athletes | Analysis | Tools
 */
@Composable
fun CoachScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    // Restore the sub-tab the coach was last on; it's disposed on navigation
    var selectedTab by remember { mutableIntStateOf(viewModel.coachSession.selectedTab) }
    val tabs = listOf("Annotate", "Athletes", "Analysis", "Tools")

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Tab row
        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color(0xFF1A1A1A),
            contentColor = Color.White,
            edgePadding = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index; viewModel.coachSession.selectedTab = index },
                    text = {
                        Text(
                            title,
                            fontSize = 13.sp,
                            color = if (selectedTab == index) MaterialTheme.colorScheme.primary else Color.Gray
                        )
                    }
                )
            }
        }

        // Content
        when (selectedTab) {
            0 -> AnnotationScreen(viewModel = viewModel)
            1 -> AthleteScreen(viewModel = viewModel)
            2 -> AnalysisScreen(viewModel = viewModel)
            3 -> CoachingToolsScreen(viewModel = viewModel)
        }
    }
}
