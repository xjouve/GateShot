# GateShot

Ski racing **video analysis** for coaches and athletes, built for the Oppo Find X9 Pro.

**Film with the phone's native camera app** — its stabilization (especially at the Hasselblad teleconverter) runs on a privileged camera path no third-party app can reach — then **import the footage into GateShot** to break it down: replay, overlay comparison, gate timing, pose analysis, annotation, and athlete tracking.

> **Why not a camera app?** GateShot started as a second camera app. Months of reverse-engineering established that the native app's teleconverter stabilization runs on a system-only camera (device 6, `SYSTEM_CAMERA` permission) that a normally-installed app on a non-rooted phone cannot use (see `docs/tickets/020`). Recording belongs to the native app; analysis is where GateShot adds value.

## Workflow

Home opens without a phone-wide media grid. **Record with GateShot** opens an in-app Camera2 viewfinder on the Find X9 Pro's logical rear camera at 3.03×, which engages physical periscope lens 4. Tap a racer in the preview to set an AF region and enable the MediaTek tracking-AF request. GateShot records an MP4 directly into its own Library. It does not open Oppo Camera or the phone-wide media picker.

GateShot stores recorded and shared clips in app-specific storage. Removing the app also removes those clips, so back up important coaching videos before uninstalling GateShot.

1. **Film** runs in GateShot with the periscope telephoto viewfinder.
2. **Review** recordings in GateShot's Library, or use *Open with / Share to GateShot* from another app for an existing video.
3. **Analyze**: replay in slow motion, mark gates, compare runs, draw on frames, record voice-overs, generate session reports.

## Features

### Library
- Video grid with thumbnails, gate-tagged badge, share, delete
- GateShot recordings in the GateShot-only video grid
- `ACTION_VIEW` / `ACTION_SEND` intent filters — open or share any video straight into GateShot
- Imported clips are copied into app storage and recorded in the session database; capture time is preserved for ordering

### Replay
- ExoPlayer dual-player review: variable speed 0.25x–2x, frame stepping (33ms), ±5s skip
- **Supplementary stabilization** — one-tap toggle: phase-correlation motion analysis, Gaussian path smoothing, playback-time warp with a modest 1.15× crop; the correction sign is verified closed-loop against re-measured jitter
- **Auto color correction** — one-tap toggle: gray-world white balance + snow-aware exposure lift + gentle contrast/saturation, applied as a playback render effect (files never modified)
- **Enhanced export** — bake the active enhancements into a shareable `<name>_enhanced.mp4` (hardware decode → GL warp/grade → encode, audio kept); every export self-checks by re-measuring jitter and reports the result
- **Manual gate tagging** — flag button marks the current position; tick marks on the scrubber; editable gate list (tap-to-seek, delete). Stored as `.gates` sidecar files that power the analysis features
- **Run comparison overlays** — Ghost (adjustable opacity), Wipe (draggable split), Difference (divergence glows), gate-synced playback
- **Course reference from a clip** — build the perspective-correction reference from an imported video (a slow pan across the course, or a wide static view); panning is measured frame-to-frame to pick stitch anchors, gates are auto-detected when filmed close enough
- **Pose estimation** — MoveNet Lightning skeleton overlay with ski-specific angles (knee, hip, torso lean)
- **Autoclip** — segments continuous video into per-run clips by audio peaks
- **Split timing** — mark splits at video positions, compare run deltas

### Coach tools
- **Telestrator** — draw on paused frames (freehand, line, arrow, circle), high-vis colors for snow; saved annotated frames land in the frames browser
- **Voice-over annotation** — audio commentary pinned to the video timeline
- **Athlete database** — roster with bibs/age group/team, error patterns, drills, progress timeline
- **Analysis dashboard** — gate consistency tracker, turn analysis, time-to-technique correlation, session report PDF
- **Session management** — sessions (event + discipline) and runs, backed by Room

## Architecture

Core + API-endpoint architecture with independent Gradle modules:

```
:core                  — Thin kernel (EventBus, EndpointRegistry, ModuleLoader, ConfigStore)
:session               — Room database (Session/Run/Media)
:processing:autoclip   — Audio-based run segmentation
:processing:export     — Share + watermark
:processing:stabilize  — Image-based motion estimation/warp primitives (future playback smoothing)
:coaching:replay       — ExoPlayer replay + overlay engine + gate sidecars + perspective correction
:coaching:timing       — Split timing (video-position based) with disk persistence
:coaching:annotation   — Voice-over + telestrator + annotated frame rendering
:coaching:athlete      — Athlete database with error/drill/progress tracking
:coaching:pose         — MoveNet pose estimation + ski angle computation
```

**Design rules:**
- Modules communicate via EventBus and typed `ApiEndpoint`s in the `EndpointRegistry`
- The registry logs loudly on unknown paths or request-type mismatches (failures are returned, not thrown)
- No module depends on the UI layer
- Per-file metadata uses sidecar files (`.gates`) for portability

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| DI | Hilt (Dagger) |
| Database | Room |
| Video Playback | ExoPlayer (Media3) |
| Pose Estimation | TFLite MoveNet Lightning (int8, 2.8MB) |
| Async | Coroutines + Flow |
| Build | Gradle KTS with version catalog |

## Hardware Target

- **Oppo Find X9 Pro** (recording device; GateShot itself runs on any Android 10+ phone)
- Android 16 (ColorOS 16), minSdk 29

## Building

### Prerequisites

- **Android Studio** (2024.3+), **JDK 17+**, **Android SDK API 35**

### Build from command line

```bash
git clone https://github.com/xjouve/GateShot.git
cd GateShot

# Windows PowerShell:
$env:JAVA_HOME = "K:\android\jbr"
# Linux/Mac:
export JAVA_HOME="/path/to/android-studio/jbr"

./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

### Install

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### First launch

No upfront permissions. The microphone permission is requested only when recording a voice-over.

## UI Navigation

4 bottom tabs:

| Tab | Purpose |
|-----|---------|
| Library | Video grid, import, open in Replay |
| Replay | Player, gate tagging, overlays, pose, autoclip, splits |
| Coach | Annotate / Athletes / Analysis / Tools sub-tabs |
| Settings | Export, storage, about |

## AI racer analysis

On the Coach → Analysis tab, **Analyze racer** tracks the skier through the clip
on-device (motion-saliency localization + MoveNet pose) and reports knee/hip
angles, torso lean, shoulder tilt, stance width and hands position per run and
per gate section, with honest tracking coverage and heuristic flags. **Get AI
coaching report** then sends cropped key frames and the metrics to Claude
(`claude-opus-5`, your own Anthropic API key from Settings) and returns a
structured coaching report: score, strengths, prioritized corrections with
timestamps, drills, and a confidence note. Reports are cached next to the clip
and can be shared as text.

## Release build

`.\gradlew :app:assembleRelease` produces a minified, resource-shrunk APK
(version 1.0.0). Set `GATESHOT_KEYSTORE`, `GATESHOT_KEYSTORE_PASSWORD`,
`GATESHOT_KEY_ALIAS` and `GATESHOT_KEY_PASSWORD` (environment or
`local.properties`) to sign with a production key; without them the debug
keystore is used so the APK still installs for testing.

## Roadmap

- **Electronic timing** — BLE protocol integration with ALGE/Microgate/Tag Heuer units
- **Gate detection at distance** — the color-based detector needs gates filmed reasonably close; distant thin poles are indistinguishable from sky/terrain without a smarter detector

## History

The camera-app era of GateShot (presets, snow exposure, burst, triggers, tracking AF, vendor HAL work) lives in git history before the `video-analysis-pivot` merge; the live-EIS experiments are preserved on the `live-eis-attempt` branch. Reusable pieces (SnowAnalyzer, Hasselblad tone curves) are recoverable from commit `e25dc5b` and earlier.

## License

Proprietary — all rights reserved.
