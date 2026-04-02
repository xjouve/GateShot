# TICKET-012: SR enhancement produces no visible difference

**Status:** Fixed  
**Date:** 2026-04-02  
**Severity:** High  
**Component:** SuperResolutionModule, TelephotoOptimizer

## Problem
Even when SR ran successfully (6+ seconds processing), the enhanced image was visually identical to the original. No perceptible improvement in sharpness or noise.

## Root Cause (3 issues)

### 1. Spatial denoise too aggressive
The bilateral denoise used radius=2 (5x5 kernel) with colorThreshold=30, which smoothed out texture and fine detail — making images softer, not sharper. At OPTICAL_NATIVE (5x), denoise ran unnecessarily on already-clean camera output.

### 2. Sharpening too weak
OPTICAL_NATIVE used sharpenStrength=0.2 — barely perceptible. Combined with JPEG re-encoding at quality 95, the subtle sharpening was erased by compression artifacts.

### 3. Pipeline order: denoise before sharpen
Denoise destroyed detail, then sharpening tried to recover it — a losing combination.

## Fix
- **Disabled denoise for OPTICAL_NATIVE and OPTICAL_TELE** zones — camera output is clean enough
- **Increased sharpening strengths**: 0.2→0.6 (native), 0.4→0.7 (tele), 0.5→0.8 (crop), 0.6→0.8 (enhanced), 0.7→0.9 (digital)
- **Reduced denoise aggressiveness**: radius 2→1, colorThreshold 30→15
- **Reordered pipeline**: deconvolve → denoise → sharpen (sharpen runs last to restore edges after denoise)
- **Increased sharpening kernel radius** from 1 to 2 (5x5 unsharp mask)
- **Increased JPEG save quality** from 95 to 100 to preserve sharpening detail
- **Added `_before.jpg` copy** saved before enhancement for A/B comparison

## Files Changed
- `processing/super-resolution/src/main/java/com/gateshot/processing/sr/SuperResolutionModule.kt`
- `processing/super-resolution/src/main/java/com/gateshot/processing/sr/TelephotoOptimizer.kt`
- `app/src/main/java/com/gateshot/ui/MainViewModel.kt`
