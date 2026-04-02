# TICKET-003: Preset modes (SL/GS, DH/SG, etc.) not applying camera settings

**Status:** Fixed  
**Date:** 2026-04-02  
**Severity:** High  
**Component:** PresetFeatureModule, CameraFeatureModule

## Problem
Tapping preset buttons (SL/GS, DH/SG, PAN, FIN, WIDE, TRAIN) highlighted the selected button but did not change any camera settings. All presets behaved identically.

## Root Cause
`PresetFeatureModule.applyPreset()` wrote settings to `ConfigStore`, but almost nothing read from ConfigStore. Camera shutter speed, stabilization, exposure compensation, and face detection values were written but never applied to the camera hardware. Only 3 ConfigStore reads existed across the entire codebase (burst frame count and 2 snow exposure settings).

## Fix
- `PresetFeatureModule` now directly calls `CameraXPlatform` methods to apply:
  - Shutter speed via `setManualExposure()`
  - EV compensation via `setExposureCompensation()`
  - OIS/EIS via `setStabilization()`
  - Face detection via `setIspPipeline()`
- Added `parseShutterSpeed()` to convert fraction strings ("1/2000") to nanoseconds.
- `presetDisplayName` in the ViewModel now updates when switching presets.

## Files Changed
- `capture/preset/src/main/java/com/gateshot/capture/preset/PresetFeatureModule.kt`
- `app/src/main/java/com/gateshot/ui/MainViewModel.kt`
