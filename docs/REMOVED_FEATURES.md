# Removed features

This document records features that were removed from GateShot because they did
not work on the locked production CPH2791 firmware, had no working backend, or
were unbundled stubs. They are documented here so they can be reimplemented later
if the situation changes (e.g. a rooted/unlocked unit, a real backend, or a
bundled model).

The removals were done as part of the "offline stabilizer + cleanup" pass. See
also `docs/tickets/` and the stabilizer work in `build/qa/camspike/`.

---

## Group 1 — Vendor experiments + HR / Ultra-HR

**What they were:** a set of MediaTek/Oppo vendor Camera2 keys exposed as
toggles in Settings → "Vendor experiments", plus a developer "Vendor key
overlay" that surfaced live HAL state, plus the Hasselblad Haute Résolution /
Ultra-HR (Quad-Bayer remosaic) capture path.

**Why removed:** none of these visibly worked on this locked firmware. HR/Ultra-HR
is capped by the public stream-configuration map at 12 MP (the full-sensor
surface is gated behind a private extension), and the experiment toggles
(AI shutter, AEB bracketing, AI scene/ASD, slow-mo SMVR, hyperlapse, pro torch,
XCD filter) were unverified gimmicks the HAL silently dropped. They added UI
clutter and dead capture-request traffic without benefit for ski racing.

**Removed:**
- `app/.../ui/components/VendorKeyOverlay.kt` — deleted.
- `app/.../ui/settings/SettingsScreen.kt` — removed the "Vendor experiments"
  section's gimmick controls and the "Developer → Vendor key overlay" toggle,
  and the camera "Hasselblad Haute Résolution" (`hr_mode`) toggle.
- `app/.../ui/viewfinder/ViewfinderScreen.kt` — removed the vendor-overlay
  rendering; relabelled the native-camera delegate button "HR" → "OEM" (the
  button itself is **kept** — it is a working escape hatch to the native Oppo
  camera, see "Kept" below).
- `app/.../ui/MainViewModel.kt` — removed `showVendorOverlay`, the
  `vendorKeyReport` getter, the `hr_mode` config/rebind, and the
  `applyCameraSetting` handlers for ai_shutter / bracket_mode / ai_scene /
  smvr_mode / fast_motion / pro_torch / filter_preset / ultra_hr / vendor_overlay.
- `platform/.../camera/CameraPlatform.kt` — removed `CameraConfig.highResolution`,
  the `vendorKeyReport` member, and the setters `setAiShutter`, `setBracketMode`,
  `setAiScene`, `setSmvrMode`, `setFastMotion`, `setProTorch`, `setFilterPreset`,
  `setUltraHighResolution`.
- `platform/.../camera/CameraXPlatform.kt` — removed the corresponding `active*`
  state, setters, the experiment/HR key applications in
  `applyVendorCaptureRequestOptions`, the whole `VendorKeyReport` publish path,
  the `RESULT_*` readouts in `vendorResultCallback` (the EisDiag HAL-stabilization
  ground-truth logging is **kept**), and the HR blocks + `resolveMaxJpegSizeForLens`
  in `buildImageCapture`.
- `platform/.../camera/VendorCameraKeys.kt` — removed the `VendorKeyReport` data
  class, the result-only keys + `readSafe`, and the request keys: `REMOSAIC_ENABLE`,
  `SEAMLESS_REMOSAIC_ENABLE`, `ULTRA_HIGH_RES_ENABLE`, `AI_SHUTTER_*`,
  `BRACKET_MODE*`, `ASD_MODE`, `AI_SCENE_APP_ENABLE`, `SMVR_*`,
  `FAST_MOTION_ENABLE`, `PRO_TORCH_MODE`, `APP_FILTER_TYPE`.

**Kept (intentionally, despite living in the same files):**
- All **tracking** infrastructure — software `SubjectTracker`, hardware tracking
  AF (`TRACKING_AF_MODE/REGION/CANCEL` + `setHardwareTracking`), and the **MDPTZ**
  hardware subject-framing keys (`MDPTZ_MODE` / `MDPTZ_PICKUP_ROI` / `setMdptzMode`)
  retained as a tracking-enhancement hook (no longer has a settings toggle; driven
  programmatically).
- **Exposure / WB controls** that work and matter for snow: `setAeMetering`
  (spot metering vs snow blow-out), `setExposureRoi` (tap-to-focus ROIs), and
  `setManualWb` (Kelvin/tint). Moved under a renamed "Exposure & white balance"
  settings section.
- The EIS/gyro feed, flip, pro-ISO, movie-LOG, and HDR vendor keys (the native
  super-EIS attempt) — left wired and harmless; superseded by our own offline
  stabilizer but not torn out to avoid destabilizing the recording session
  (see TICKET-019).
- The native-camera delegate button (relabelled "OEM") — a working fallback to
  the OEM capture pipeline.

**To reimplement:** restore the keys in `VendorCameraKeys.kt` and the toggles in
`SettingsScreen.kt`. Only worthwhile on a rooted/unlocked unit where the HAL
honours them (HR full-sensor surface, super-EIS engagement).

---

## Group 2 — Super-resolution (unbundled-model stub)

**What it was:** the `:processing:super-resolution` module (`enhance/photo`,
`enhance/burst`, `enhance/config`, `enhance/status`, `enhance/crop-follow`
endpoints) plus its engine classes (FrameFuser, AiUpscaler, LensDeconvolution,
GyroAssist, LowLightFusion, FrameAligner, HdrMerge, TelephotoOptimizer,
VideoFrameExtractor). It claimed multi-frame denoise + deconvolution + AI upscale
for telephoto stills at ≥5× zoom, triggered automatically after capture.

**Why removed:** no AI upscaler model was ever bundled (the module had no
`assets/` dir), so the "AI upscale" path silently fell back to bicubic — i.e. the
headline feature did nothing real. ~2800 LOC of dead pipeline. (Contrast with the
pose module, which **does** ship a real MoveNet model and was kept.)

**Removed:**
- `processing/super-resolution/` — entire module deleted.
- `settings.gradle.kts` — `include(":processing:super-resolution")` removed.
- `app/build.gradle.kts` — project dependency removed.
- `app/.../di/AppModule.kt` — `superResolutionModule` param + set entry removed.
- `app/.../ui/MainViewModel.kt` — `enhanceCapturedPhoto()` and its post-capture
  invocation removed.
- `app/.../ui/settings/SettingsScreen.kt` — "Zoom Enhancement" section removed.

**Kept:** Hasselblad color grading (`applyHasselbladGrading`, a separate working
post-capture step), and the autoclip/export modules.

**To reimplement:** the real win would be a bundled, validated super-resolution
TFLite model + the FrameFuser multi-frame alignment. The deleted engine classes
are recoverable from git history (commit before this cleanup) if revived.

---

## Group 3 — Backend-less coach tools

**What they were:** six "coaching" features wired to UI but with no working
backend — they wrote/read local files or returned placeholder data:
- **Multi-camera audio sync** — pick two angles, "sync by audio" (only computed a
  duration/beep offset, no actual multi-view playback).
- **Remote coaching package export** — zip a clip + drawings + timing for a coach
  who has no app to receive it.
- **Team feed** — a local `team_feed.tsv` masquerading as a shared workspace.
- **Cloud backup** — a WorkManager job that only *listed* files as "ready for
  WiFi upload"; there was no cloud destination.
- **Federation export** — renamed files to FIS-style names + a metadata CSV.
- **Voice commands** — `SpeechRecognizer` mapping phrases to actions; unreliable
  hands-free control, not core to the on-mountain workflow.

**Why removed:** none had a real backend or delivered the implied capability; they
were UI scaffolding over local-file stubs.

**Removed:**
- `app/.../CloudBackupWorker.kt` — deleted.
- `app/.../ui/coaching/CoachingToolsScreen.kt` — removed the Multi-Camera,
  Remote Coaching, Team Feed, and Cloud Backup tool cards + their content
  composables. **Ideal Line drawing is kept** (a real local tool); the "Tools"
  tab in `CoachScreen` therefore stays.
- `app/.../ui/settings/SettingsScreen.kt` — removed the "Voice Commands" section
  and the "Federation Export" button (kept the Watermark toggle).
- `app/.../ui/MainViewModel.kt` — removed `mergeMultiCamera`,
  `exportCoachingPackage`, `getTeamFeedItems`, `postToTeamFeed`,
  `scheduleCloudBackup`, `runManualBackup`, `startVoiceCommands`,
  `stopVoiceCommands`, `handleVoiceCommand`, `exportFederationFormat`, and the
  `CloudBackupWorker` import.
- `app/build.gradle.kts` — removed the now-unused WorkManager dependency.

**Kept:** `RECORD_AUDIO` permission (still needed for video audio and the audio
start-gate `AudioTrigger`), Ideal Line drawing, and the watermark export toggle.
Local data files (`team_feed.tsv`, `reference/multi_camera_sync.txt`) are simply
no longer written.

**To reimplement:** these need a real backend (auth + storage + a coach-side app /
web) before they're worth restoring. Logic is recoverable from git history.


