# TICKET-001: Green tint on viewfinder

**Status:** Fixed  
**Date:** 2026-04-02  
**Severity:** High  
**Component:** TrueColorWhiteBalance, CameraXPlatform

## Problem
The camera viewfinder displayed a strong green tint across the entire preview.

## Root Cause
Two issues in the white balance pipeline:

1. **TrueColorWhiteBalance.cctToRgbGains()** — WB gain normalization divided all channels by the maximum channel value. When green was the max (from negative tint correction), red and blue got scaled below 1.0, producing a green-dominant image. `RggbChannelVector` gains should be normalized to green=1.0 (the reference channel), not max=1.0.

2. **CameraXPlatform.applyCaptureRequestSettings()** — Used `COLOR_CORRECTION_MODE_TRANSFORM_MATRIX` without providing a color correction transform matrix. This mode requires a full 3x3 matrix; without it, ISP behavior is undefined.

## Fix
- `TrueColorWhiteBalance.kt`: Changed normalization from `maxOf(r,g,b)` to `gGain` so green is always 1.0.
- `CameraXPlatform.kt`: Changed to `COLOR_CORRECTION_MODE_FAST` which applies WB gains directly.

## Files Changed
- `processing/snow-exposure/src/main/java/com/gateshot/processing/snow/TrueColorWhiteBalance.kt`
- `platform/src/main/java/com/gateshot/platform/camera/CameraXPlatform.kt`
