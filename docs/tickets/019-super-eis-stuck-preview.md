## TICKET-019: Super-EIS vendor keys leave preview permanently black

**Status:** Fixed (reverted + rebind on EIS change + re-entrancy guard)
**Date:** 2026-04-13
**Severity:** Critical (unrecoverable black preview)
**Component:** `platform/.../CameraXPlatform.kt`, `app/.../ui/MainViewModel.kt`

## Problem
1. User reported that video stabilization in GateShot was noticeably worse than the native Oppo camera app even with EIS set to Standard.
2. The full vendor tag inventory revealed four additional Oppo EIS keys that the native app sets but GateShot did not:
   - `com.oplus.eis.workon` (`byte`) — master engine enable
   - `com.oplus.camera.video.eis.mode` (`byte`) — Oppo's per-mode byte (distinct from MediaTek's `com.mediatek.eisfeature.eismode` int)
   - `com.oplus.video.super.eis.scenes` (`int`) — super-EIS scene selector
   - `com.oplus.eis.bypass.stream` (`int`) — per-stream bypass mask
3. We wired all four to fire on every repeating request whenever our `EisMode` was non-OFF.
4. After install, the preview went black when EIS was enabled **and stayed black** even after toggling EIS back to OFF. No `FATAL EXCEPTION`, no `applySafe` rejection — just a stuck session.

## Root cause
The four super-EIS keys almost certainly need to be set as **session parameters at session bind time**, not on a live repeating request. Setting `com.oplus.eis.workon = 1` on an already-bound session told the HAL to engage the super-EIS pipeline, which apparently reconfigured the stream internally and dropped the preview surface. Setting it back to `0` on the same session did not re-allocate the surface — the session was stuck until a full rebind.

This matches the general MediaTek pattern: most `*feature` vendor keys accept live updates, but a few (EIS engine state, HR remosaic, tracking AF arming) are session-level gates that expect a fresh session config.

## Fix
1. **Reverted** the four super-EIS keys from `applyVendorCaptureRequestOptions`. The constants remain in `VendorCameraKeys.kt` for a future session-parameter path, but they're no longer sent on the repeating request. Comment in the code explains why.
2. **EIS toggle now triggers a full camera rebind** in `MainViewModel.applyCameraSetting`:
   ```kotlin
   section == "video" && (key == "ois_mode" || key == "eis_mode") -> {
       cameraXPlatform.setStabilization(StabilizationConfig(…))
       if (!pushingPersistedSettings) rebindCameraFromPrefs()
   }
   ```
   Even without the super-EIS keys, a clean session bind with the new EIS state is more reliable than a mid-session flip.
3. **Re-entrancy guard** on `pushAllPersistedSettingsToCamera`:
   ```kotlin
   private var pushingPersistedSettings: Boolean = false
   …
   pushingPersistedSettings = true
   try { keys.forEach { (section, key) -> applyCameraSetting(section, key, value) } }
   finally { pushingPersistedSettings = false }
   ```
   Without this, the startup pref replay hit the EIS branch → triggered a rebind → which called `pushAllPersistedSettingsToCamera` again → which hit the EIS branch again → infinite loop. Observed as multiple "Camera opened successfully" messages within one second at launch. The guard short-circuits rebind triggers when the push is already in-flight; applied to every rebind-triggering branch (EIS, OIS, resolution, fps, HDR, RAW, HEIF, JPEG quality, HR mode).

## Files changed
- `platform/src/main/java/com/gateshot/platform/camera/CameraXPlatform.kt`
- `app/src/main/java/com/gateshot/ui/MainViewModel.kt`

## Verified
Post-fix launch: single "Camera opened successfully" log, ~24 fps buffer queue, no `applySafe` rejections. Toggling EIS Standard / Off produces one clean rebind per toggle and the preview stays live.

## Follow-up (2026-04-14)
Re-landed the four super-EIS keys, this time via `Camera2Interop.Extender.setCaptureRequestOption` on the `Preview.Builder` and `VideoCapture.Builder` in `open()` / `buildVideoCapture()`. Unlike the per-request `CaptureRequestOptions` path that caused the black screen, the extender injects keys into the initial session configuration, so the super-EIS pipeline is engaged at session creation rather than flipped on a live session. The rebind-on-EIS-change guard from the original fix is what makes this safe to toggle at runtime.

New helper: `VendorCameraKeys.applySafeExtender(extender, key, value)` — session-level analogue of `applySafe`, routes failures into the same `failureSink` so the dev overlay still surfaces silent rejections.

Wired keys (all in `applySuperEisSessionKeys` on `CameraXPlatform`):
- `com.oplus.eis.workon` (byte) = 1 when EIS non-OFF
- `com.oplus.camera.video.eis.mode` (byte) = OFF:0 / STANDARD:1 / PANNING:3
- `com.oplus.video.super.eis.scenes` (int) = 1 when EIS non-OFF
- `com.oplus.eis.bypass.stream` (int) = 0 (apply EIS to all streams)
