# TICKET-002: Zoom selector display-only, no interaction

**Status:** Fixed  
**Date:** 2026-04-02  
**Severity:** Medium  
**Component:** ViewfinderScreen

## Problem
The zoom indicator on the viewfinder was a static display showing "1.0x" with no way for the user to change zoom. The `onZoomChanged` callback existed in the ViewModel but nothing in the UI triggered it.

## Fix
Added two zoom interaction methods:

1. **Tap to cycle** — Tapping the zoom indicator cycles through lens presets: 0.6x (ultra-wide), 1x (main), 2x (telephoto portrait), 5x (telephoto). Wraps back to 0.6x after 5x.
2. **Pinch to zoom** — Added `detectTransformGestures` on the preview for continuous zoom between 0.6x and 20x.

## Files Changed
- `app/src/main/java/com/gateshot/ui/viewfinder/ViewfinderScreen.kt`
