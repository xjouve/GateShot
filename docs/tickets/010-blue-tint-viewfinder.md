# TICKET-010: Blue tint on viewfinder

**Status:** Fixed  
**Date:** 2026-04-02  
**Severity:** High  
**Component:** TrueColorWhiteBalance

## Problem
After fixing the green tint (TICKET-001), the viewfinder displayed a blue color cast. White objects appeared blue.

## Root Cause
In `TrueColorWhiteBalance.cctToRgbGains()`, the cool light (>6500K) correction was inverted. The comment said "reduce blue, boost red" but the code did the opposite:
- `rGain = 1.0 - t * 0.15` (reduced red, should boost)
- `bGain = 1.0 + t * 0.3` (boosted blue, should reduce)

WB gains are multipliers applied to raw sensor data. To correct for blue-ish cool/shade light, red must be boosted and blue reduced. The formulas were backwards.

## Fix
Swapped the gain directions for cool light:
- `rGain = 1.0 + t * 0.15` (boost red to warm up)
- `bGain = 1.0 - t * 0.3` (reduce blue to cancel cool cast)

## Files Changed
- `processing/snow-exposure/src/main/java/com/gateshot/processing/snow/TrueColorWhiteBalance.kt`
