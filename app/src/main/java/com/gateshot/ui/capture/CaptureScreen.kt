package com.gateshot.ui.capture

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.ui.viewinterop.AndroidView
import com.gateshot.ui.MainViewModel
import java.io.File

@Composable
fun CaptureScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    var controller by remember { mutableStateOf<TeleCapture?>(null) }
    var recording by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(false) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var stabOn by remember { mutableStateOf(true) }
    var zoomLevel by remember { mutableStateOf(10) }
    var permissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        permissionGranted = results[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!permissionGranted) error = "Camera permission is required"
    }
    LaunchedEffect(Unit) {
        if (!permissionGranted || ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }
    DisposableEffect(activity) {
        val previousOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            controller?.close()
            if (previousOrientation != null) activity.requestedOrientation = previousOrientation
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Portrait 9:16 viewfinder: 1080p sensor frames shown upright in portrait.
        Box(Modifier.fillMaxWidth().aspectRatio(9f / 16f).align(Alignment.Center).background(Color.DarkGray)) {
            if (permissionGranted) {
                AndroidView(factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                                val capture = TeleCapture(ctx, this@apply)
                                capture.stabilizer.enabled = stabOn
                                capture.setZoomLevel(zoomLevel)
                                capture.onError = { message -> post { error = message } }
                                capture.onRecording = { value -> post { recording = value } }
                                capture.onSaved = { file: File -> post {
                                    viewModel.onNativeCaptureComplete(file.absolutePath, true)
                                    viewModel.openVideoInReplay(file.absolutePath)
                                } }
                                controller = capture
                                capture.open()
                                ready = true
                            }
                            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
                            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                                controller?.close()
                                controller = null
                                ready = false
                                return true
                            }
                        }
                    }
                }, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().pointerInput(controller) {
                    detectTapGestures { offset ->
                        focusPoint = offset
                        controller?.focusAt(offset.x / size.width, offset.y / size.height)
                    }
                }) {
                    focusPoint?.let { p ->
                        Canvas(Modifier.fillMaxSize()) {
                            drawCircle(Color.Yellow, radius = 36.dp.toPx(), center = p, style = Stroke(2.dp.toPx()))
                        }
                    }
                }
            }
        }
        Text("Back", color = Color.White,
            modifier = Modifier.align(Alignment.TopStart).clickable { onBack() }.padding(16.dp))
        Text("Tap racer to focus", color = Color.LightGray, fontSize = 12.sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp))
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            error?.let { Text(it, color = Color(0xFFFF7B7B), modifier = Modifier.padding(8.dp)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                for (level in listOf(10, 20, 30)) {
                    val selected = level == zoomLevel
                    Text("${level}x", color = if (selected) Color(0xFFFFB300) else Color.White,
                        fontSize = if (selected) 16.sp else 14.sp,
                        modifier = Modifier
                            .padding(horizontal = 6.dp)
                            .background(Color(0x99000000), CircleShape)
                            .clickable {
                                zoomLevel = level
                                controller?.setZoomLevel(level)
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = {
                    stabOn = !stabOn
                    controller?.stabilizer?.enabled = stabOn
                }) {
                    Text(if (stabOn) "Stab ON" else "Stab OFF", color = Color.White)
                }
                Spacer(Modifier.width(16.dp))
                Button(onClick = { if (recording) controller?.stop() else controller?.start() },
                    enabled = ready && error == null) {
                    Text(if (recording) "Stop" else "Record")
                }
            }
        }
    }
}
