## TICKET-016: Settings toggles silently ignored

**Status:** Fixed
**Date:** 2026-04-13
**Severity:** Critical
**Component:** `app/.../ui/MainViewModel.kt`, `app/.../ui/settings/SettingsScreen.kt`

## Problem
Nearly every toggle in the Settings screen appeared to do nothing. The user tried OIS Off/Standard/Maximum, EIS Off/Standard/Panning, video resolution, frame rate, HDR, RAW, HEIF, JPEG quality, and AF mode — none of them had any effect on the live preview or captured files.

## Root cause
Every control in `SettingsScreen.kt` called `viewModel.saveSetting(section, key, value)`, which wrote to `SharedPreferences` and then dispatched to `MainViewModel.applyCameraSetting()`. But `applyCameraSetting` only had branches for a handful of keys — the rest silently fell through the `when` with no handler. Keys affected by the wiring gap:

- `video.ois_mode`, `video.eis_mode` — stabilization
- `video.resolution`, `video.frame_rate`, `video.hdr` — video session parameters
- `af.mode_index`, `af.face_priority`
- `camera.save_raw`, `camera.heif`, `camera.jpeg_quality`, `camera.resolution_mp`
- `tracking.*` sliders

Separately, `CameraXPlatform.open(CameraConfig())` was called with a default `CameraConfig` at startup — the persisted resolution/fps/HDR/RAW/JPEG quality prefs were **never read into the initial bind**, so the user's last session state was always dormant until they re-toggled every control.

A third issue: the `StabilizationConfig` data class only modelled `opticalStabilization: Boolean` and `videoStabilization: Boolean`. The UI exposed three modes each (Off/Standard/Maximum for OIS, Off/Standard/Panning for EIS) but the data model could only represent two states — even after wiring, "Maximum" collapsed to "on".

## Fix
1. **`StabilizationConfig`** was rewritten to take `OisMode` (`OFF/STANDARD/MAXIMUM`) and `EisMode` (`OFF/STANDARD/PANNING`) enums, defined in the platform module. Preset module call sites convert at the boundary. `CameraXPlatform.applyCaptureRequestSettings` maps the modes to `LENS_OPTICAL_STABILIZATION_MODE` / `CONTROL_VIDEO_STABILIZATION_MODE` with comments explaining that PANNING deliberately leaves video stabilization off.
2. **New `setAfMode(CameraAfMode)`** platform setter mapping `SINGLE/CONTINUOUS/PREDICTIVE/MANUAL` to `CONTROL_AF_MODE` values, applied in `applyCaptureRequestSettings` with manual focus and tap-to-focus regions taking priority.
3. **New `applyCameraSetting` branches** in `MainViewModel` for all the missing keys: ois/eis modes, AF mode/face priority, log profile, extended ISO, hardware tracking, plus `video.{resolution,frame_rate,hdr}` and `camera.{save_raw,heif,jpeg_quality,resolution_mp,hr_mode}` which trigger a full `rebindCameraFromPrefs()`.
4. **`buildCameraConfigFromPrefs()`** — new helper that reads persisted settings into a `CameraConfig` at open time, so the initial `cameraXPlatform.open(...)` no longer ignores the user's last session.
5. **`pushAllPersistedSettingsToCamera()`** — new helper that replays every wired pref through `applyCameraSetting` after each open, so toggles survive rebinds and app restarts.
6. **`rebindCameraFromPrefs()`** — new helper that closes the camera, reopens it with `buildCameraConfigFromPrefs()`, re-pushes persisted settings, and restores zoom.

## Files changed
- `platform/src/main/java/com/gateshot/platform/camera/CameraPlatform.kt`
- `platform/src/main/java/com/gateshot/platform/camera/CameraXPlatform.kt`
- `capture/preset/src/main/java/com/gateshot/capture/preset/PresetApplier.kt`
- `capture/preset/src/main/java/com/gateshot/capture/preset/PresetFeatureModule.kt`
- `capture/preset/src/main/java/com/gateshot/capture/preset/MacroMode.kt`
- `app/src/main/java/com/gateshot/ui/MainViewModel.kt`

## Verified
On-device smoke test via adb UI automation: toggled OIS Off, EIS Panning, AF Single, and Resolution 1080p in sequence. Each tap produced the expected UI highlight change (confirming `onCheckedChange` fired) and the camera rebound cleanly (`Camera opened successfully, state=OPEN` in logcat). Returning to the viewfinder after the rebind showed a live preview with persisted zoom and EV bias, confirming the push-replay path works end-to-end.
