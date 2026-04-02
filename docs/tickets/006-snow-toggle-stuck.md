# TICKET-006: Snow compensation stuck after toggling off/on

**Status:** Fixed  
**Date:** 2026-04-02  
**Severity:** High  
**Component:** SnowExposureModule, CameraXPlatform

## Problem
After toggling auto snow compensation OFF and then back ON in Settings, the snow module never resumed dynamic EV adjustment. The viewfinder stayed at the manual EV value.

## Root Cause (2 issues)

### 1. Analysis loop not restarting after tab navigation
Navigating from Shoot to Settings and back caused the camera to close and reopen. The analysis fallback thread used a boolean `analysisFallbackRunning` which stayed `true` from the first launch, preventing `startAnalysisFallback()` from launching a new thread. The old thread had already exited because `CameraState` changed during the close.

### 2. Single-thread executor serialization
The `analysisExecutor` was a single-thread executor. Old tasks blocked new tasks from starting. When the old loop took time to exit, the new task was queued and sometimes missed the `CameraState.OPEN` window.

### 3. SnowExposureModule reading from wrong source
`isEnabled` was read from `ConfigStore` (only updated on `PresetApplied` events), not from `SharedPreferences` (the user's actual toggle state). On fresh install, ConfigStore defaulted to `true` regardless of the Settings toggle.

## Fix
1. Replaced boolean flag with a **generation counter** (`analysisGeneration`). Each `open()` increments the generation; old threads see the mismatch and exit cleanly.
2. Replaced single-thread executor with a **dedicated `Thread`** per generation — no blocking between old and new loops.
3. `SnowExposureModule` now reads `isEnabled` directly from `SharedPreferences` on every frame, ensuring immediate response to toggle changes.
4. Removed the 2-second sleep delay that was causing race conditions during camera restarts.

## Files Changed
- `platform/src/main/java/com/gateshot/platform/camera/CameraXPlatform.kt`
- `processing/snow-exposure/src/main/java/com/gateshot/processing/snow/SnowExposureModule.kt`
