# TICKET-005: Snow exposure module receives no frames — analysis never runs

**Status:** Fixed  
**Date:** 2026-04-02  
**Severity:** Critical  
**Component:** CameraXPlatform, SnowExposureModule

## Problem
The auto snow compensation feature never analyzed the scene or adjusted exposure. The `SnowExposureModule` was initialized but its frame listener was never called.

## Root Cause
The Oppo Find X9 Pro's camera (ID 4, telephoto; later ID 0, main) supports only **2 simultaneous streams**. CameraX's `ImageAnalysis` use case required a 3rd stream (via `StreamSharing` or direct). The binding silently failed or dropped `ImageAnalysis`, so no frames were ever delivered to `frameListeners`.

Additionally:
- `PreviewView.getBitmap()` returns `null` when using `ImplementationMode.PERFORMANCE` (SurfaceView renders directly to hardware).
- `ImageCapture.takePicture()` fails with "Failed to submit capture request" when only preview is bound.

## Fix
Replaced `ImageAnalysis` use case with a `PreviewView.getBitmap()` polling fallback:

1. Removed `ImageAnalysis` from camera binding entirely — the X9 Pro can't support it.
2. Changed `PreviewView` to `ImplementationMode.COMPATIBLE` (TextureView) so `getBitmap()` works.
3. Added `startAnalysisFallback()` which runs on a dedicated thread:
   - Waits for `CameraState.OPEN`
   - Calls `view.getBitmap()` on the main thread via Handler every 500ms
   - Converts RGBA bitmap to Y (luminance) plane via `BitmapImageProxy`
   - Feeds the proxy to all registered frame listeners
4. Used a generation counter (`analysisGeneration`) to cleanly stop/restart the fallback across camera open/close cycles (tab navigation).

## Files Changed
- `platform/src/main/java/com/gateshot/platform/camera/CameraXPlatform.kt` (major rework of binding and analysis pipeline)
- `app/src/main/java/com/gateshot/ui/viewfinder/ViewfinderScreen.kt` (COMPATIBLE mode)
