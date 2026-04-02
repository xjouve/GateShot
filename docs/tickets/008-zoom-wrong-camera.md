# TICKET-008: 1x zoom already zoomed in — wrong default camera

**Status:** Fixed  
**Date:** 2026-04-02  
**Severity:** Medium  
**Component:** CameraPlatform

## Problem
At 1x zoom, the viewfinder appeared already zoomed in. The field of view was narrow like a telephoto lens.

## Root Cause
`CameraConfig` defaulted to `lens = CameraLens.TELEPHOTO` (70mm equivalent, camera ID 4). The camera selector picked the telephoto sub-camera, so 1x digital zoom on a telephoto physical lens showed a narrow field of view.

## Fix
Changed `CameraConfig` default from `CameraLens.TELEPHOTO` to `CameraLens.MAIN` (23mm equivalent). The main wide camera is now the default, and CameraX's digital zoom handles zoom levels above 1x.

## Files Changed
- `platform/src/main/java/com/gateshot/platform/camera/CameraPlatform.kt`
