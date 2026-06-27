package com.gateshot.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEvents
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
import androidx.compose.runtime.mutableLongStateOf
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
    val filePath: String = "",
    val isVideo: Boolean,
    val starRating: Int,
    val bibNumber: Int?,
    val timestamp: Long,
    val isBestFrame: Boolean = false
)

/**
 * Persist star ratings as a .stars sidecar file alongside the media file.
 * Simple: file contains "1" (starred) or doesn't exist (not starred).
 */
private fun loadStarRating(filePath: String): Int {
    val starsFile = java.io.File(filePath + ".starred")
    return if (starsFile.exists()) 5 else 0
}

private fun saveStarRating(filePath: String, rating: Int) {
    val starsFile = java.io.File(filePath + ".starred")
    if (rating > 0) {
        starsFile.writeText("1")
    } else {
        starsFile.delete()
    }
}

/**
 * Load bib number from a .bib sidecar file (written by BibDetectionModule).
 */
private fun loadBibNumber(filePath: String): Int? {
    val bibFile = java.io.File(filePath + ".bib")
    return if (bibFile.exists()) bibFile.readText().trim().toIntOrNull() else null
}

/**
 * Check if this frame was marked as "best" by burst culling.
 */
private fun isBestFrame(filePath: String): Boolean {
    return java.io.File(filePath + ".best").exists()
}

@Composable
fun GalleryScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val stabilizing by viewModel.stabilizeRunning.collectAsState()
    val galleryRefresh by viewModel.galleryRefresh.collectAsState()

    // Load gallery items from actual capture files on disk
    var refreshKey by remember { mutableIntStateOf(0) }
    val items = remember(uiState.shotCount, refreshKey, galleryRefresh) {
        val gateShotDir = java.io.File(context.getExternalFilesDir(null), "GateShot")
        val mediaFiles = mutableListOf<GalleryItem>()

        if (gateShotDir.exists()) {
            gateShotDir.walkTopDown()
                .filter { it.isFile && (it.extension in listOf("jpg", "jpeg", "heif", "mp4", "dng", "png")) }
                .sortedByDescending { it.lastModified() }
                .take(200)
                .forEachIndexed { index, file ->
                    mediaFiles.add(GalleryItem(
                        id = index.toLong(),
                        fileName = file.name,
                        filePath = file.absolutePath,
                        isVideo = file.extension == "mp4",
                        starRating = loadStarRating(file.absolutePath),
                        bibNumber = loadBibNumber(file.absolutePath),
                        timestamp = file.lastModified(),
                        isBestFrame = isBestFrame(file.absolutePath)
                    ))
                }
        }
        mediaFiles
    }

    var selectedFilter by remember { mutableStateOf("all") }

    // Collect distinct bib numbers for filter
    val bibNumbers = remember(items) {
        items.mapNotNull { it.bibNumber }.distinct().sorted()
    }

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

        // Filter chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val filters = mutableListOf("all" to "All", "starred" to "Starred", "video" to "Video", "best" to "Best")
            // Add bib filters dynamically
            bibNumbers.forEach { bib ->
                filters.add("bib_$bib" to "#$bib")
            }

            filters.forEach { (id, label) ->
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
        val filtered = when {
            selectedFilter == "starred" -> items.filter { it.starRating > 0 }
            selectedFilter == "video" -> items.filter { it.isVideo }
            selectedFilter == "best" -> items.filter { it.isBestFrame }
            selectedFilter.startsWith("bib_") -> {
                val bib = selectedFilter.removePrefix("bib_").toIntOrNull()
                items.filter { it.bibNumber == bib }
            }
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
                GalleryThumbnail(
                    item = item,
                    onDelete = { refreshKey++ },
                    onStarChanged = { refreshKey++ },
                    onStabilize = { viewModel.stabilizeVideo(it.filePath) },
                    stabilizing = stabilizing
                )
            }
        }
    }
}

@Composable
fun GalleryThumbnail(
    item: GalleryItem,
    onDelete: () -> Unit = {},
    onStarChanged: () -> Unit = {},
    onStabilize: (GalleryItem) -> Unit = {},
    stabilizing: Boolean = false,
    modifier: Modifier = Modifier
) {
    var starRating by remember { mutableIntStateOf(item.starRating) }
    var showFullPreview by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    // Full-screen preview dialog
    if (showFullPreview && item.filePath.isNotEmpty()) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showFullPreview = false }) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { showFullPreview = false },
                contentAlignment = Alignment.Center
            ) {
                if (item.isVideo) {
                    androidx.compose.runtime.LaunchedEffect(item.filePath) {
                        try {
                            val file = java.io.File(item.filePath)
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context, "${context.packageName}.fileprovider", file
                            )
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "video/mp4")
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            android.util.Log.e("Gallery", "Video open failed: ${e.message}", e)
                        }
                        showFullPreview = false
                    }
                    Text("Opening video...", color = Color.White, fontSize = 16.sp)
                } else {
                    val fullBitmap = remember(item.filePath) {
                        try {
                            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
                            android.graphics.BitmapFactory.decodeFile(item.filePath, opts)
                        } catch (_: Exception) { null }
                    }
                    if (fullBitmap != null) {
                        Image(
                            bitmap = fullBitmap.asImageBitmap(),
                            contentDescription = item.fileName,
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(Color(0xFF2A2A2A), RoundedCornerShape(4.dp))
            .clickable { showFullPreview = true }
    ) {
        // Thumbnail from file
        if (item.filePath.isNotEmpty()) {
            val bitmap = remember(item.filePath) {
                try {
                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }
                    android.graphics.BitmapFactory.decodeFile(item.filePath, opts)
                } catch (_: Exception) { null }
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = item.fileName,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Video play icon
        if (item.isVideo) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "Play",
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(40.dp)
                )
            }
        }

        // Bib badge (top-left)
        item.bibNumber?.let { bib ->
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
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

        // Best frame badge (top-left, below bib)
        if (item.isBestFrame) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Color(0xFFFFD700),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 4.dp, top = if (item.bibNumber != null) 28.dp else 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Icon(Icons.Filled.EmojiEvents, null, tint = Color.Black, modifier = Modifier.size(10.dp))
                    Text("Best", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Star indicator (top-right)
        if (starRating > 0) {
            Icon(
                Icons.Filled.Star,
                contentDescription = "Starred",
                tint = Color(0xFFFFD700),
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(16.dp)
            )
        }

        // Bottom action bar
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xAA000000)),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            IconButton(onClick = {
                starRating = if (starRating > 0) 0 else 5
                saveStarRating(item.filePath, starRating)
                onStarChanged()
            }, modifier = Modifier.size(48.dp)) {
                Icon(
                    if (starRating > 0) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = "Rate",
                    tint = if (starRating > 0) Color(0xFFFFD700) else Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = {
                if (item.filePath.isNotEmpty()) {
                    val file = java.io.File(item.filePath)
                    if (file.exists()) {
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            context, "${context.packageName}.fileprovider", file
                        )
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = if (item.isVideo) "video/mp4" else "image/jpeg"
                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(android.content.Intent.createChooser(intent, "Share").apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    }
                }
            }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Share, contentDescription = "Share", tint = Color.White, modifier = Modifier.size(20.dp))
            }
            // Stabilize — videos only. Disabled while a job is running.
            if (item.isVideo && !item.fileName.endsWith("_stab.mp4")) {
                IconButton(
                    onClick = { if (item.filePath.isNotEmpty()) onStabilize(item) },
                    enabled = !stabilizing,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Filled.AutoFixHigh,
                        contentDescription = "Stabilize",
                        tint = if (stabilizing) Color.Gray else Color(0xFF4FC3F7),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            IconButton(onClick = {
                if (item.filePath.isNotEmpty()) {
                    java.io.File(item.filePath).delete()
                    // Clean up sidecar files
                    java.io.File(item.filePath + ".starred").delete()
                    java.io.File(item.filePath + ".bib").delete()
                    java.io.File(item.filePath + ".best").delete()
                    onDelete()
                }
            }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Color(0xFFEF5350), modifier = Modifier.size(20.dp))
            }
        }
    }
}
