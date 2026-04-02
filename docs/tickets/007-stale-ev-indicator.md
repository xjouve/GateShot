# TICKET-007: EV indicator showing stale/incorrect value

**Status:** Fixed  
**Date:** 2026-04-02  
**Severity:** Medium  
**Component:** MainViewModel, PresetFeatureModule

## Problem
The viewfinder displayed "+0.5 EV" even when the user's manual EV setting was 0.0 and auto snow compensation was off.

## Root Cause
1. The `ExposureAdjusted` event collector in the ViewModel unconditionally set `currentEvBias = event.evBias`, overwriting the user's actual setting with the preset's default EV.
2. When snow comp was off, the preset's `evBias` fallback used `preset.exposure.evBias` (e.g., 0.5 for Finish) instead of `0f` as the default when no SharedPreference existed.
3. The `presetDisplayName` was never updated when switching presets, and `currentEvBias` wasn't refreshed on preset change to reflect the user's override.

## Fix
1. `ExposureAdjusted` collector now checks `snow_compensation` setting: when off, shows the user's manual EV from SharedPreferences instead of the event's value.
2. `PresetFeatureModule` uses `0f` as the default manual EV (not the preset's evBias) when no SharedPreference exists.
3. `saveSetting` for `ev_bias` and `snow_compensation` immediately updates `currentEvBias` in the UI state.
4. `PresetApplied` handler updates both `presetDisplayName` and `currentEvBias` based on actual settings.

## Files Changed
- `app/src/main/java/com/gateshot/ui/MainViewModel.kt`
- `capture/preset/src/main/java/com/gateshot/capture/preset/PresetFeatureModule.kt`
