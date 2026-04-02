# TICKET-009: Phone screen turns off while app is in use

**Status:** Fixed  
**Date:** 2026-04-02  
**Severity:** Medium  
**Component:** MainActivity

## Problem
The phone screen would turn off after the system timeout while GateShot was in the foreground, interrupting the camera preview and snow analysis. Critical for field use during ski racing where the photographer may not touch the screen for extended periods.

## Fix
Added `FLAG_KEEP_SCREEN_ON` to the activity window in `MainActivity.onCreate()`. The screen stays on as long as GateShot is in the foreground and automatically allows sleep when the app goes to the background. No extra permissions required.

## Files Changed
- `app/src/main/java/com/gateshot/MainActivity.kt`
