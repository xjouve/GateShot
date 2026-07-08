# GateShot — Session Handoff (2026-07-08)

Read this first when resuming work. It captures where the project stands after
the video-analysis pivot and everything shipped on top of it.

## What GateShot is now

A pure **ski-racing video analysis app**. Recording happens in the phone's
native camera app (its teleconverter EIS runs on a system-only camera path no
third-party app can reach — the full investigation is preserved in
`docs/tickets/020-teleconverter-video-stabilization.md`, CLOSED). GateShot
imports the footage and does everything after: replay, gate tagging, run
comparison, pose analysis, annotation, athlete tracking, stabilization, color
correction, enhanced export, course references.

## State of the world

- **Branch:** everything is merged and pushed on `main`
  (github.com/xjouve/GateShot). Working tree clean.
- **`live-eis-attempt` branch:** the parked live-EIS experiments (last state
  of the camera-app era). Pre-pivot code (vendor camera keys, SnowAnalyzer,
  HasselbladProfile tone curves, presets) is recoverable from commit
  `e25dc5b` and earlier; the deleted offline stabilizer pipeline from
  `5903127`.
- **Device:** Oppo Find X9 Pro, serial `3B15C6001PS00000`, adb at
  `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`. The installed APK
  matches `main` HEAD (`679e3a6`).
- **Build:** `.\gradlew :app:assembleDebug` (JAVA_HOME → `K:\android\jbr`).
  All JVM tests green (`.\gradlew test`).

## Commit trail of the pivot (all on main)

| Commit | What |
|---|---|
| `e25dc5b` | WIP live EIS parked (last camera-era commit) |
| `88a404d` | Phase 1: capture teardown (−12k lines), COACH default, replay decoupled |
| `5560485` | Phase 2: video import, Library, open-with/share intents |
| `864edae` | Phase 3: fixed 8 silently-dead coach features, manual gate tagging, loud EndpointRegistry |
| `bd6927d` | On-device fixes: athlete list race, pose int8 input |
| `df84209` | Phase 4: playback stabilizer + auto color toggles |
| `6b67e87` | Enhanced export (decode → GL warp/grade → encode) with jitter self-check |
| `2744ba3` | Course reference built from an imported clip |
| `556361b`, `d7c93f4`, `2860132`, `f87e5a8` | Replay/Coach control styling: selected-blue toggles; gate + autoclip made deselectable |
| `7c2fb43` | Persist Replay state across navigation (`ReplaySession`) |
| `0692e59`, `679e3a6` | Persist Coach state across navigation (`CoachSession`) |

## Load-bearing technical facts (hard-won — don't re-derive)

1. **EndpointRegistry** does exact-path lookup + unchecked cast and RETURNS
   failures; callers ignore them. It now logs loudly (`EndpointRegistry` tag).
   All coaching endpoints require `AppMode.COACH`; `ModeManager` defaults to
   COACH since the pivot.
2. **MediaMetadataRetriever orientation split:** `getFrameAtTime` /
   `getScaledFrameAtTime` apply rotation metadata (display space);
   `getFrameAtIndex` does NOT (coded space). `PlaybackStabilizer` tracks are
   therefore **coded-space**: the exporter feeds them to the GL warp directly
   (only the v-axis flips — GL v runs opposite buffer y); playback view
   translation uses `Track.displayCorrectionAt()` (coded→display rotation).
3. **Every export self-checks**: jitter re-measured on source vs output
   (normalized by the crop zoom) and shown in the badge. Positive jitter on
   stabilized footage = warp direction regression. Ground truth for sign
   experiments: synthesize shake with
   `ffmpeg -i in.mp4 -vf "crop=iw-80:ih-80:x='40+30*sin(n/2.7)':y='40+30*cos(n/3.3)'"`,
   push into the app dir (`adb shell` CAN write
   `/storage/emulated/0/Android/data/com.gateshot/files/GateShot/videos/` on
   this device), and read the export badge. Verified: −83% (rot 0), −82%
   (rot 270). ffmpeg rotation metadata: use `-display_rotation 90` input
   option + `-c copy` (ffmpeg convention is opposite Android's).
4. **Compose transforms/effects need TextureView:** the main Replay player
   inflates `stabilized_player_view.xml` (`app:surface_type="texture_view"`);
   a SurfaceView silently ignores graphicsLayer and render effects.
5. **The bundled MoveNet is int8-quantized:** input uint8 [1,192,192,3], raw
   RGB bytes, no normalization.
6. **Sidecar formats:** `<clip>.gates` = one video-position ms per line
   (written by `coach/gates/mark`, read by the Analysis cards); timing splits
   are video-position-based (`RecordSplitRequest(videoPositionMs)`).
7. **Imports are copies** into `GateShot/videos` (Photo Picker grants don't
   survive process death; the whole pipeline is file-path based). Session DB
   rows require an active session+run — `VideoImportManager.ensureSessionAndRun`
   guarantees that before publishing `NativeCaptureCompleted`.
8. **Gate auto-detection needs close-filmed gates** (measured, not assumed):
   on from-below footage, poles are desaturated slivers — zero pixels with
   r > max(g,b)+25; pole blues match sky. The reference panel states this
   honestly; overlays work unregistered.
9. **Navigation loses composable state.** The bottom-nav destinations
   (Replay, Coach, …) are disposed on tab switch, so their local `remember{}`
   state — playback position, expensive analysis results, in-progress
   drawings — is lost on return. Fix pattern (used by `ReplaySession` and
   `CoachSession` on `MainViewModel`, which outlives navigation): a plain
   VM-scoped holder; the composable initializes its `remember` state from the
   holder and snapshots it back in `DisposableEffect`'s `onDispose`. Per-item
   state that must reset when its subject changes is keyed on that subject
   (Replay state on the clip path; annotation strokes on the frame path) via
   `remember(key)`, with a `remember(key){ if (changed) reset }` running
   first. Do NOT reset in a plain `LaunchedEffect(key)` — it re-fires on every
   fresh entry and would wipe the restored state. Read-only data (athlete
   roster, Analysis cards) is intentionally NOT persisted; it re-queries so it
   stays current.

## Open items (in rough priority order)

1. **Field test on real training footage** — everything is device-verified
   with adb-driven UI tests and synthetic clips, but no human has used it on
   a training day yet. Watch: stabilizer feel, auto-color taste (gray-world
   WB may over-warm; tunables in `AutoColorAnalyzer` companion), pose
   accuracy on distant skiers, panel UX with gloves.
2. **Smarter gate detection at distance** — color thresholds can't work;
   would need shape/line detection or ML. Roadmap.
3. **BLE electronic timing** (ALGE/Microgate/Tag Heuer) — backend endpoints
   are stubs (`ConnectTimingSystem` fakes success); needs physical units.
4. Cosmetics/cleanup: optional `MainViewModel` → `AnalysisViewModel` rename;
   Library still lists old camera-era test clips on the device (user can
   delete in-app); TRAIL overlay mode is still a ghost fallback.

## Testing technique that worked (for the next session)

Drive the app over adb and read screenshots:
`adb shell input tap X Y`, `adb shell screencap -p /sdcard/s.png` + `adb pull`
(PowerShell `>` corrupts binary stdout — always screencap-to-file + pull).
Screen is 1080×2354. Check `adb logcat -d | Select-String EndpointRegistry`
after exercising features — silent failures land there now.
