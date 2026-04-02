# TICKET-004: Settings changes don't override preset values

**Status:** Fixed  
**Date:** 2026-04-02  
**Severity:** High  
**Component:** MainViewModel, PresetFeatureModule, SnowExposureModule

## Problem
User settings (e.g., EV bias slider, snow compensation toggle) did not take effect. Changing values in Settings had no visible impact on the viewfinder.

## Root Cause (3 layers)

### 1. EV compensation via wrong API
`setExposureCompensation()` used CameraX's `setExposureCompensationIndex()` which is a separate API from Camera2Interop's `setCaptureRequestOptions()`. The TrueColorWhiteBalance module pushed WB gains via Camera2Interop multiple times per second, and each batch **replaced** all Camera2 options — effectively resetting the EV compensation set via the CameraX API.

### 2. AE disabled by presets
Presets set a shutter speed via `setManualExposure()` with `iso = null`. This triggered `CONTROL_AE_MODE_OFF` in `applyCaptureRequestSettings()`, which disabled auto-exposure entirely. With AE off, `CONTROL_AE_EXPOSURE_COMPENSATION` has no effect.

### 3. SnowExposureModule overwriting EV
The snow module ran `setExposureCompensation()` ~6x/sec even when snow compensation was disabled, constantly overwriting any user-set EV value.

## Fix
1. Routed EV compensation through `applyCaptureRequestSettings()` as `CONTROL_AE_EXPOSURE_COMPENSATION` in the Camera2Interop batch, so it persists across WB updates.
2. Only disable AE (`CONTROL_AE_MODE_OFF`) when **both** shutter speed AND ISO are set (full manual mode). Preset shutter-speed-only mode keeps AE on.
3. `SnowExposureModule.analyzeFrame()` now returns immediately when `isEnabled == false`, leaving the camera EV untouched.
4. `saveSetting()` in the ViewModel mirrors values to both SharedPreferences and ConfigStore, and applies camera-relevant settings immediately.
5. `PresetFeatureModule` reads user overrides from SharedPreferences before applying preset defaults.

## Files Changed
- `platform/src/main/java/com/gateshot/platform/camera/CameraXPlatform.kt`
- `processing/snow-exposure/src/main/java/com/gateshot/processing/snow/SnowExposureModule.kt`
- `app/src/main/java/com/gateshot/ui/MainViewModel.kt`
- `capture/preset/src/main/java/com/gateshot/capture/preset/PresetFeatureModule.kt`
