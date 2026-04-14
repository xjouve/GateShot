## TICKET-018: Crash in `setupRawCapture` — "unknown device 0" race

**Status:** Fixed
**Date:** 2026-04-13
**Severity:** High (crash on launch with RAW enabled)
**Component:** `platform/.../CameraXPlatform.kt`

## Problem
After the super-EIS vendor key experiment (ticket 019) the app started crashing on launch with:
```
FATAL EXCEPTION: main
java.lang.IllegalArgumentException: getCameraCharacteristics:1372:
    Unable to retrieve camera characteristics for unknown device 0:
    No such file or directory (-2)
    at CameraXPlatform.setupRawCapture(CameraXPlatform.kt:1616)
    at CameraXPlatform.open(CameraXPlatform.kt:269)
Caused by: android.os.ServiceSpecificException: … (code 3)
```

## Root cause
`CameraXPlatform.setupRawCapture()` runs inside `open()` immediately after `readCamera2Characteristics()`, and calls `CameraManager.getCameraCharacteristics(rawCamera2Id)` synchronously. When `open()` is reached via `rebindCameraFromPrefs()` — i.e. after a previous `close()` in the same moment — the Camera2 service can briefly be in a transitional state where the ID that was just cleaned up is no longer recognised even though it's back in the device list. The query throws `ServiceSpecificException` which becomes an `IllegalArgumentException` at the public API boundary.

The bug wasn't visible until the recent rebind-heavy changes (ticket 016) started triggering `close()` + `open()` several times per startup.

`readCamera2Characteristics` and `resolveMaxJpegSizeForLens` already wrap their characteristic queries in try/catch. `setupRawCapture` was the one outlier.

## Fix
Wrap the whole `setupRawCapture` body in a try/catch that logs the transient failure and leaves `rawImageReader = null`. RAW capture becomes unavailable until the next clean open, and the user can reopen the camera to recover — no further impact.

```kotlin
private fun setupRawCapture() {
    try {
        val cameraId = rawCamera2Id ?: return
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        …
    } catch (e: Exception) {
        android.util.Log.w("CameraXPlatform", "setupRawCapture skipped: ${e.message}")
        rawImageReader = null
    }
}
```

## Files changed
- `platform/src/main/java/com/gateshot/platform/camera/CameraXPlatform.kt`

## Verified
Post-fix launch: no `FATAL EXCEPTION` in logcat, camera opens cleanly, buffer queue delivering ~24 fps.
