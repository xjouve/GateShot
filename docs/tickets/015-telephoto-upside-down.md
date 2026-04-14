## TICKET-015: Telephoto viewfinder upside down at >3x zoom

**Status:** Fixed
**Date:** 2026-04-13
**Severity:** High
**Component:** `platform/.../CameraXPlatform.kt`

## Problem
When the user zoomed past 3x, the logical back camera engaged the Hasselblad periscope module and the preview, captured JPEGs, and recorded videos all came out rotated 180°. Every other GateShot feature worked normally at the 1x / 2x logical camera; only the periscope path was affected.

## Root cause
On the Oppo Find X9 Pro the periscope sensor is physically mounted rotated 180° relative to the main sensor. MediaTek's public `android.sensor.orientation` characteristic reports the same value for every physical back camera, so CameraX's standard rotation pipeline doesn't compensate for the flip. Oppo's own camera app corrects it via a private HAL extension; third-party Camera2 sees the flipped frames as-is.

## Initial fix (working)
View-level rotation for the preview plus a target-rotation offset for the capture surfaces, triggered off the current zoom ratio:

- `CameraXPlatform.applyTelephotoUpsideDownFix(zoomRatio)` is called from both `setZoom()` and the end of `open()`.
- When `zoomRatio >= TELEPHOTO_ENGAGE_ZOOM` (3.0f), it sets:
  - `previewView.rotation = 180f` (View-level transform — guaranteed to flip what's drawn)
  - `imageCapture.targetRotation = (baseRotation + 2) and 3` (adds 180° to JPEG EXIF)
  - `videoCapture.targetRotation = same` (adds 180° to the MP4 rotation hint)
- When zoom drops back below 3x the values are reset to no flip.

## Vendor-key attempt (reverted)
The MediaTek vendor descriptor advertises `com.mediatek.control.capture.flipmode` (`int32[2] = {hflip, vflip}`) which looks like the correct ISP-level flip control. We wired it up (`VendorCameraKeys.FLIP_MODE`) and set `{0, 1}` when zoom ≥ 3x, expecting to drop the View-level rotation entirely. The HAL accepted the key silently but did not actually flip anything — visible periscope frames at 5x were still upside down. The vendor key is left in place (sending `{0, 0}` always) so the dev overlay can still surface any future rejection diagnostics; the real flip remains the View-level rotation.

## Files changed
- `platform/src/main/java/com/gateshot/platform/camera/CameraXPlatform.kt`
- `platform/src/main/java/com/gateshot/platform/camera/VendorCameraKeys.kt` (key constant)

## Verified
Visual confirmation at 5.0x on device: scene (desk, pencils, hand) renders right-side up; preset column on left, shutter on right. Zoom round-trip 1x → 5x → 1x keeps the pipeline alive with no frame-rate degradation (~26 fps buffer queue).
