# TICKET-011: Super-resolution pipeline never called on shutter press

**Status:** Fixed  
**Date:** 2026-04-02  
**Severity:** Critical  
**Component:** MainViewModel, SuperResolutionModule

## Problem
Taking photos with "Auto enhance zoom" enabled at any zoom level produced identical results to SR off. The SR module had endpoints registered but nothing called them.

## Root Cause
`onShutterPress()` called `cameraXPlatform.takePicture()` directly and saved the result. It never invoked the `enhance/photo` endpoint. The SR module's `EnhancePhoto`, `EnhanceBurst`, and other endpoints existed but were never triggered from the capture flow.

## Fix
- `onShutterPress()` now checks `sr_auto_enhance` setting and zoom level (>=5x)
- When SR is enabled, launches `enhanceCapturedPhoto()` on `Dispatchers.IO` to avoid freezing the viewfinder
- `enhanceCapturedPhoto()` loads the captured JPEG, extracts pixel data, calls `enhance/photo` endpoint, and saves the enhanced result back

## Files Changed
- `app/src/main/java/com/gateshot/ui/MainViewModel.kt`
