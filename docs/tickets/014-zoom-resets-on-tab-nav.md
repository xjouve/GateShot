# TICKET-014: Zoom resets to 1x when navigating between tabs

**Status:** Fixed  
**Date:** 2026-04-02  
**Severity:** Medium  
**Component:** MainViewModel, CameraXPlatform

## Problem
After setting zoom to 5x, navigating to Settings and back to Shoot reset the actual camera zoom to 1x. The zoom indicator still displayed "5.0x" (from ViewModel state) but the viewfinder showed 1x field of view. Photos were captured at 1x despite the display.

## Root Cause
Tab navigation caused the camera to close and reopen via `bindCameraPreview()` → `open()`. The camera reopened with default zoom 1.0. The ViewModel preserved `zoomLevel` in its state but never re-applied it to the camera after reopening.

## Fix
After `cameraXPlatform.open()`, restore the saved zoom level from `_uiState.value.zoomLevel` by calling `cameraXPlatform.setZoom()`.

## Files Changed
- `app/src/main/java/com/gateshot/ui/MainViewModel.kt`
