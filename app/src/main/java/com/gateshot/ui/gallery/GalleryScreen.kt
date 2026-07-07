package com.gateshot.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timer
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
    val filePath: String = "",
    val timestamp: Long,
    val hasGates: Boolean = false
)

@Composable
fun GalleryScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val galleryRefresh by viewModel.galleryRefresh.collectAsState()

    // Load videos from GateShot storage (in-app recordings historically,
    // imported clips going forward)
    var refreshKey by remember { mutableIntStateOf(0) }
    val items = remember(refreshKey, galleryRefresh) {
        val videoDir = java.io.File(context.getExternalFilesDir(null), "GateShot/videos")
        videoDir.listFiles()
            ?.filter { it.isFile && it.extension == "mp4" }
            ?.sortedByDescending { it.lastModified() }
            ?.take(200)
            ?.mapIndexed { index, file ->
                GalleryItem(
                    id = index.toLong(),
                    fileName = file.name,
                    filePath = file.absolutePath,
                    timestamp = file.lastModified(),
                    hasGates = java.io.File(file.parent, file.nameWithoutExtension + ".gates").exists()
                )
            }
            ?: emptyList()
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
                text = "Library",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${items.size} videos",
                color = Color.Gray,
                fontSize = 14.sp
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(items) { item ->
                GalleryThumbnail(
                    item = item,
                    onDelete = { refreshKey++ }
                )
            }
        }
    }
}

@Composable
fun GalleryThumbnail(
    item: GalleryItem,
    onDelete: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showFullPreview by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    // Open in external player when tapped
    if (showFullPreview && item.filePath.isNotEmpty()) {
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
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(Color(0xFF2A2A2A), RoundedCornerShape(4.dp))
            .clickable { showFullPreview = true }
    ) {
        // Video thumbnail
        if (item.filePath.isNotEmpty()) {
            val bitmap = remember(item.filePath) {
                try {
                    android.media.ThumbnailUtils.createVideoThumbnail(
                        java.io.File(item.filePath),
                        android.util.Size(320, 320),
                        null
                    )
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

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "Play",
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(40.dp)
            )
        }

        // Gate-timing badge (top-left) — this clip has tagged gates
        if (item.hasGates) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Icon(Icons.Filled.Timer, null, tint = Color.Black, modifier = Modifier.size(10.dp))
                    Text("Gates", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
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
                if (item.filePath.isNotEmpty()) {
                    val file = java.io.File(item.filePath)
                    if (file.exists()) {
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            context, "${context.packageName}.fileprovider", file
                        )
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "video/mp4"
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
            IconButton(onClick = {
                if (item.filePath.isNotEmpty()) {
                    java.io.File(item.filePath).delete()
                    // Clean up analysis sidecars
                    val base = java.io.File(item.filePath)
                    java.io.File(base.parent, base.nameWithoutExtension + ".gates").delete()
                    onDelete()
                }
            }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Color(0xFFEF5350), modifier = Modifier.size(20.dp))
            }
        }
    }
}
