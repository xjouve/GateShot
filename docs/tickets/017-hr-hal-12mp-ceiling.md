## TICKET-017: Hasselblad Haute Résolution — HAL caps output at 12 MP

**Status:** Known limitation (plumbing in place, waiting on vendor surface)
**Date:** 2026-04-13
**Severity:** Low (cosmetic — no regression, toggle exists for future firmware)
**Component:** `platform/.../CameraXPlatform.kt`, `platform/.../VendorCameraKeys.kt`

## Problem
GateShot needed to support the Find X9 Pro's Hasselblad Haute Résolution mode — full-sensor 50 MP (main) / 200 MP (periscope) captures, selectable in the native camera's EXPERT mode as "JPG MAX" / "RAW MAX".

## Investigation
1. Traced the native camera UI: `EXPERT → Format icon → JPG MAX` is the user-visible entry point. Selecting it disables `Photo animée` (live photos), which hints at a different capture path.
2. Harvested the full vendor tag descriptor from `dumpsys media.camera` (816 tags total, saved to `build/qa/vendor_tags.txt`).
3. Identified the relevant vendor keys:
   - `com.mediatek.control.capture.remosaicenable` (`int32`, `0x800b0006`) — per-request Quad Bayer unbin toggle.
   - `com.mediatek.control.capture.seamless.remosaicenable` (`int32`, `0x800b0007`) — seamless variant that preserves ZSL.
   - `com.oplus.ultra.high.resolution.enable` (`int32`, `0x90040084`) — Oppo's alternate enable.
   - `com.mediatek.seamlessfeature.{availableCellFullSensorIds, configCellFullSensorIds, forceSensorMode, sensorScenario, …}` — full-cell (HR) vs cell-cropped (binned) sensor readout scenarios.
   - `com.oplus.custom.jpeg.size` — Oppo-advertised JPEG sizes including `8192×6144` (50 MP 4:3), `8192×4608` (16:9), `6144×6144` (1:1), `8192×3760` (21:9).
4. Captured a test photo in the native `JPG MAX` mode at 1x zoom, dim scene. Result: **4096 × 3072 = 12.6 MP** — the same binned default as regular JPG. Even the native app's output was binned on this capture.

## Root cause
`dumpsys media.camera` confirms every public back camera reports `android.sensor.info.pixelArraySize = [4096 3072]` and the `SCALER_STREAM_CONFIGURATION_MAP` tops out at `4096×3072` for every format. The real full-sensor sizes (`8192×6144` etc.) are advertised only through the Oppo vendor characteristic `com.oplus.custom.jpeg.size` — a descriptor key, not a stream configuration. Standard Android surface allocators (`ImageReader`, CameraX `ImageCapture`) validate requested sizes against the public stream config map, so there is no way for a third-party app to allocate a buffer larger than 12 MP on this firmware. The HR path lives behind a private Oppo vendor extension that only their in-house camera app can open.

## Fix (partial)
GateShot wires the HR toggle end-to-end so the moment Oppo publishes a public HR surface (firmware update, or a previously-undiscovered Camera2 Extension) everything snaps into place without further changes:

- **`VendorCameraKeys.REMOSAIC_ENABLE`** and **`SEAMLESS_REMOSAIC_ENABLE`** keys defined (`com.mediatek.control.capture.*`).
- **`VendorCameraKeys.ULTRA_HIGH_RES_ENABLE`** defined (`com.oplus.ultra.high.resolution.enable`).
- **`CameraConfig.highResolution: Boolean`** field on the platform config.
- **`CameraCapabilities.supportsHighResolution`** + **`maxJpegSize`** populated by `readCamera2Characteristics` when it walks each back camera.
- **`buildImageCapture(config)`** pins the `ImageCapture.Builder.setTargetResolution(8192, 6144)` when HR is on, attaches the remosaic keys via `Camera2Interop.Extender`, and logs both the requested size and the actual SCALER map cap so it's obvious the HAL is clamping.
- **`applyVendorCaptureRequestOptions`** also sends the remosaic keys on the repeating request as a belt-and-braces secondary path.
- **Settings toggle** "Hasselblad Haute Résolution" in Photo Output, with a subtitle that is deliberately blunt about the HAL ceiling.
- **Dev overlay** surfaces `HR = on (remosaic)` / `ultraHR = on` in real time.
- **`MainViewModel`** triggers `rebindCameraFromPrefs()` on toggle so the new config lands via a clean session bind.

## Observed behaviour
On-device log line on app start with HR enabled:
```
HR mode: requesting 8192x6144 (50,3 MP); SCALER map caps at 4096x3072 (12,6 MP) —
actual output will match the cap until the vendor surface is exposed
```
The rebind succeeds, no `VendorCameraKeys.applySafe` rejections, but the saved JPEG is still 4096×3072. Identical result to the native app's JPG MAX under the same lighting.

## Follow-up
If a future firmware exposes 8192×6144 in the public stream config map, or if `com.mediatek.seamlessfeature.configCellFullSensorIds` turns out to be acceptable as a CameraX session parameter, the remaining work is just reading `availableCellFullSensorIds` at characteristic time and passing the ID through. No code path changes required.

## Files changed
- `platform/src/main/java/com/gateshot/platform/camera/CameraPlatform.kt`
- `platform/src/main/java/com/gateshot/platform/camera/CameraXPlatform.kt`
- `platform/src/main/java/com/gateshot/platform/camera/VendorCameraKeys.kt`
- `app/src/main/java/com/gateshot/ui/MainViewModel.kt`
- `app/src/main/java/com/gateshot/ui/settings/SettingsScreen.kt`
- `app/src/main/java/com/gateshot/ui/components/VendorKeyOverlay.kt`
