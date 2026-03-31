package com.gateshot.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gateshot.ui.MainViewModel

data class GalleryItem(
    val id: Long,
    val fileName: String,
    val isVideo: Boolean,
    val starRating: Int,
    val bibNumber: Int?,
    val timestamp: Long
)

@Composable
fun GalleryScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    // Placeholder gallery items — in production, loaded from Room via session endpoints
    val items = remember {
        (1..uiState.shotCount.coerceAtLeast(6)).map { i ->
            GalleryItem(
                id = i.toLong(),
                fileName = "IMG_${"$i".padStart(4, '0')}.jpg",
                isVideo = i % 5 == 0,
                starRating = if (i % 3 == 0) 4 else 0,
                bibNumber = if (i % 4 == 0) (i * 7) % 60 + 1 else null,
                timestamp = System.currentTimeMillis() - (i * 30_000L)
            )
        }
    }

    var selectedFilter by remember { mutableStateOf("all") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A1A))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Gallery",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${items.size} items",
                color = Color.Gray,
                fontSize = 14.sp
            )
        }

        // Filter chips — large touch targets
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("all" to "All", "starred" to "Starred", "video" to "Video").forEach { (id, label) ->
                Surface(
                    onClick = { selectedFilter = id },
                    shape = RoundedCornerShape(20.dp),
                    color = if (selectedFilter == id) MaterialTheme.colorScheme.primary else Color(0xFF333333),
                    modifier = Modifier.height(40.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    ) {
                        Text(
                            text = label,
                            color = if (selectedFilter == id) Color.Black else Color.White,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        // Grid
        val filtered = when (selectedFilter) {
            "starred" -> items.filter { it.starRating > 0 }
            "video" -> items.filter { it.isVideo }
            else -> items
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(filtered) { item ->
                GalleryThumbnail(item = item)
            }
        }
    }
}

@Composable
fun GalleryThumbnail(
    item: GalleryItem,
    modifier: Modifier = Modifier
) {
    var starRating by remember { mutableIntStateOf(item.starRating) }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(Color(0xFF2A2A2A), RoundedCornerShape(4.dp))
            .clickable { /* Open full preview */ }
    ) {
        // Placeholder thumbnail
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            if (item.isVideo) {
                Icon(
                    Icons.Filled.Videocam,
                    contentDescription = "Video",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            } else {
                Text(
                    text = item.fileName.takeLast(8),
                    color = Color.Gray,
                    fontSize = 10.sp
                )
            }
        }

        // Video play icon
        if (item.isVideo) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "Play",
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(40.dp)
            )
        }

        // Bib badge
        item.bibNumber?.let { bib ->
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
            ) {
                Text(
                    text = "#$bib",
                    color = Color.Black,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }

        // Star indicator
        if (starRating > 0) {
            Icon(
                Icons.Filled.Star,
                contentDescription = "Starred",
                tint = Color(0xFFFFD700),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(16.dp)
            )
        }

        // Bottom action bar — keep/trash (glove-friendly 48dp targets)
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xAA000000)),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            IconButton(onClick = { starRating = if (starRating > 0) 0 else 5 }, modifier = Modifier.size(48.dp)) {
                Icon(
                    if (starRating > 0) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = "Rate",
                    tint = if (starRating > 0) Color(0xFFFFD700) else Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = { /* Share */ }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Share, contentDescription = "Share", tint = Color.White, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = { /* Delete */ }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Color(0xFFEF5350), modifier = Modifier.size(20.dp))
            }
        }
    }
}
