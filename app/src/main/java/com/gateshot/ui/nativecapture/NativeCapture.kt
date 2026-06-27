package com.gateshot.ui.nativecapture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.FileProvider
import java.io.File

/**
 * Delegates capture to whichever app handles `ACTION_IMAGE_CAPTURE` /
 * `ACTION_VIDEO_CAPTURE` — on the Find X9 Pro that is the Oppo camera app,
 * which can reach features (full-sensor HR, real SuperEIS) that we cannot
 * drive from Camera2/CameraX directly. The user sees the native UI; the
 * captured file lands back in GateShot's session storage so the gallery
 * treats it like any other shot.
 */
class NativeCaptureLauncher internal constructor(
    private val context: Context,
    private val launcher: ActivityResultLauncher<Intent>,
    private val pendingFile: MutableState<File?>,
    private val pendingIsVideo: MutableState<Boolean>
) {
    fun capturePhoto() {
        val dir = File(context.getExternalFilesDir(null), "GateShot/photos").apply { mkdirs() }
        val out = File(dir, "native_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        pendingFile.value = out
        pendingIsVideo.value = false
        launcher.launch(intent)
    }

    fun captureVideo() {
        val dir = File(context.getExternalFilesDir(null), "GateShot/videos").apply { mkdirs() }
        val out = File(dir, "native_${System.currentTimeMillis()}.mp4")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
        val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        pendingFile.value = out
        pendingIsVideo.value = true
        launcher.launch(intent)
    }
}

@Composable
fun rememberNativeCaptureLauncher(
    context: Context,
    onCaptured: (absolutePath: String, isVideo: Boolean) -> Unit
): NativeCaptureLauncher {
    val pendingFile = remember { mutableStateOf<File?>(null) }
    val pendingIsVideo = remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val file = pendingFile.value
        // Many OEM camera apps write the file and return RESULT_OK with no
        // data. Some return RESULT_CANCELED but still produce the file (race
        // with the user backing out). Treat the file's existence + non-zero
        // size as the source of truth.
        val captured = file != null && file.exists() && file.length() > 0
        if (captured && (result.resultCode == Activity.RESULT_OK || (file?.length() ?: 0) > 0)) {
            onCaptured(file!!.absolutePath, pendingIsVideo.value)
        } else {
            file?.takeIf { it.exists() && it.length() == 0L }?.delete()
        }
        pendingFile.value = null
    }
    return remember(launcher) {
        NativeCaptureLauncher(context, launcher, pendingFile, pendingIsVideo)
    }
}
