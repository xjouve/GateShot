# TICKET-013: SR enhancement freezes the viewfinder

**Status:** Fixed  
**Date:** 2026-04-02  
**Severity:** High  
**Component:** MainViewModel

## Problem
When SR was first wired up, taking a photo at >=5x zoom froze the viewfinder for 6+ seconds while the enhancement pipeline processed the image.

## Root Cause
`enhanceCapturedPhoto()` ran on the main thread via `viewModelScope.launch` (which uses `Dispatchers.Main`). The full-resolution pixel processing (8M+ pixels through bilateral denoise and unsharp mask) blocked the UI.

An initial attempt to fix this by capturing 4 extra frames for multi-frame denoise made it worse — each `takePicture()` call blocked the camera sequentially.

## Fix
- SR enhancement runs on `Dispatchers.IO` via `viewModelScope.launch(Dispatchers.IO)`
- Removed extra burst capture (4 additional `takePicture()` calls) — single-frame enhancement only
- Viewfinder stays responsive; enhancement happens in background after the shot is taken

## Files Changed
- `app/src/main/java/com/gateshot/ui/MainViewModel.kt`
