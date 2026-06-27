# GateShot

A dedicated ski racing camera app for the Oppo Find X9 Pro, built to dramatically enhance photo and video capabilities for training, races, and World Cup events.

**GateShot** combines a professional shooting tool with a coaching analysis suite, all optimized for cold weather, bright snow, and fast-moving athletes.

## Modes

| Mode | Purpose | Presets available |
|------|---------|-------------------|
| **Shoot** | Beautiful action captures | Race, Panning |
| **Coach** | Performance analysis | Video |

Toggle between modes with the Coach button (top-left). Switching mode auto-selects the right preset and adjusts all camera settings.

## Features

### Shooting (Shoot mode)

- **3 focused presets** — Race (burst stills at gates), Video (4K@120fps coaching replay), Panning (motion blur follow-through)
- **Snow exposure compensation** — Real-time scene analysis detects snow coverage and auto-adjusts EV (+1.0 to +2.0)
- **Flat light detection** — Boosts viewfinder contrast on overcast days
- **Pre-capture buffer** — Continuously buffers 1.5s of frames; press the shutter and get frames from *before* you pressed
- **Burst capture** — Continuous burst with pre-buffer flush and auto-culling (best frame marked with trophy badge)
- **Gate-zone trigger** — Tap the viewfinder to place trigger zones. Auto-fires burst when a racer enters the zone
- **Audio trigger** — Detects start gate beep and auto-starts capture
- **Racer tracking AF** — Speed-based subject discrimination locks onto the racer, rejects officials, holds through occlusion
- **Video recording** — 4K@30/60/120fps, 1080p@240fps, 720p@480fps, Dolby Vision HDR
- **Bib number detection** — ML Kit OCR auto-detects bib numbers, tags media with .bib sidecar files
- **Hasselblad color profile** — Film-look tone curves (lifted blacks, soft highlights, warm midtones)
- **Volume button mapping** — Vol-Up = shutter, Vol-Down = cycle preset within current mode

### Coaching (Coach mode)

- **Session management** — Create sessions (event name + discipline), track runs. Session indicator in status bar
- **Instant replay** — Load last clip, variable speed 0.25x-2x, frame-accurate seeking (33ms steps)
- **Perspective-independent comparison** — Scan the course reference panorama, then film from any position. Runs are auto-warped to the same coordinate system via gate-based homography
- **Dual-player overlay** — Add runs as overlay layers with 4 modes:
  - **Ghost** — Semi-transparent overlay with adjustable opacity
  - **Wipe** — Vertical split with draggable wipe line
  - **Difference** — Color-inverted overlay (matching areas go dark, divergence glows bright)
  - **Trail** — Ghost overlay (trajectory lines require pose tracking during recording)
- **Gate-synced playback** — Gate crossing timestamps auto-detected during recording, stored as .gates sidecar files. Overlay layers sync at each gate passage
- **Autoclip** — Segments continuous video into per-run clips by audio peak detection. Jump buttons in replay
- **Pose estimation** — MoveNet Lightning skeleton overlay on paused/playing video. Shows 17 keypoints + ski-specific angles (knee, hip, torso lean)
- **Telestrator** — Draw on actual paused video frames: freehand, line, arrow, circle. High-vis colors for snow
- **Voice-over annotation** — Record audio commentary pinned to video timeline
- **Manual split timing** — Tap at each gate, compare run deltas. Persisted to disk across sessions
- **Athlete database** — Add athletes with bib numbers, age group, team. Track errors, assign drills, monitor progress
- **Timing delta display** — Gate-by-gate time comparison between overlay layers

### Gallery

- **Star ratings** — Tap to star, persisted as .starred sidecar files (survives app restarts)
- **Bib filter** — Auto-populated filter chips for each detected bib number
- **Best frame badge** — Burst culling auto-marks the sharpest frame with a trophy badge
- **Share** — System share sheet with correct MIME types via FileProvider
- **Delete** — Cascade deletes all sidecar files (.starred, .bib, .best, .gates)

### Settings

Full settings screen with per-setting control (presets set defaults, user can override):

- **Video recording** — Resolution (720p/1080p/4K), frame rate (30-480fps, adapts to resolution), Dolby Vision HDR, OIS mode (Off/Standard/Maximum), EIS mode (Off/Standard/Panning)
- **Autofocus** — AF mode (Single/Continuous/Predictive/Manual), face priority
- **Camera** — Manual exposure (ISO/shutter), white balance (auto or manual CCT), flash
- **Depth & Bokeh** — Software bokeh with adjustable blur strength
- **Photo output** — Resolution (12/50/200MP), JPEG quality, ND filter, RAW (DNG), HEIF
- **Racer tracking** — Min speed threshold, AF region size, occlusion hold time
- **Audio trigger** — Enable/sensitivity
- **Motion trigger** — Sensitivity, cooldown
- **Pre-capture buffer** — Duration (0.5-3.0s)
- **Snow exposure** — Auto compensation toggle, manual EV bias, flat light detection
- **Zoom enhancement** — Multi-frame denoise + sharpening for telephoto (5x+), enhanced upscale at 13.2x+
- **Color profile** — Hasselblad toggle
- **Export** — Watermark toggle
- **Storage** — Usage breakdown (photos/videos count and size, free space)

### Field UX

- **Glove mode** — All touch targets minimum 14mm, 56dp preset buttons
- **One-handed operation** — Volume buttons for shutter and preset cycling
- **Battery monitoring** — Real temperature readings, cold warnings at 5C / 0C
- **Screen always on** — Prevents sleep during shooting
- **Hasselblad telephoto** — Auto-detect magnetic lens, lens-optimized stabilization

## Architecture

Core + API endpoint architecture with 19 independent Gradle modules:

```
:core                        — Thin kernel (EventBus, EndpointRegistry, ModuleLoader, ConfigStore)
:platform                    — Hardware abstraction (CameraX, Sensors, Storage)
:capture:camera              — CameraX pipeline + VideoCapture
:capture:burst               — Pre-capture ring buffer + burst
:capture:preset              — 3 discipline presets (Race, Video, Panning)
:capture:trigger             — Gate-zone motion trigger + audio trigger
:capture:tracking            — Racer AF tracking + gate crossing detection
:session                     — Room database (Session/Run/Media)
:processing:snow-exposure    — Snow EV compensation + flat light
:processing:burst-culling    — Frame ranking + .best sidecar writing
:processing:bib-detection    — Bib number OCR + .bib sidecar writing
:processing:autoclip         — Audio-based run segmentation
:processing:export           — Share + watermark
:processing:super-resolution — Frame denoise, deconvolution, enhanced upscale
:coaching:replay             — ExoPlayer dual-player replay + perspective correction + overlay engine
:coaching:timing             — Split timing with disk persistence
:coaching:annotation         — Voice-over + telestrator drawing on video frames
:coaching:athlete            — Athlete database with error/drill/progress tracking
:coaching:pose               — MoveNet pose estimation + ski angle computation
```

**Design rules:**
- Modules communicate only via EventBus (fire-and-forget events)
- Every feature is accessible through API endpoints
- Coach-only endpoints return 403 when coach toggle is off
- No module depends on the UI layer
- Presets write to SharedPreferences so Settings screen always reflects active preset
- Per-file metadata uses sidecar files (.starred, .bib, .best, .gates) for portability

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Camera | CameraX + Camera2 interop |
| DI | Hilt (Dagger) |
| Database | Room |
| Video Playback | ExoPlayer (Media3) |
| Pose Estimation | TFLite MoveNet Lightning (int8, 2.8MB) |
| Bib Detection | ML Kit Text Recognition |
| Async | Coroutines + Flow |
| Build | Gradle KTS with version catalog |

## Hardware Target

- **Oppo Find X9 Pro** — MediaTek Dimensity 9500, 16GB RAM, 512GB storage, 7500 mAh battery
- **Camera system:** 50MP main (1/1.28", f/1.5, 23mm) + 50MP ultra-wide (15mm) + **200MP telephoto** (1/1.56", f/2.1, 70mm, 5x optical, OIS) + True Color sensor (9 spectral channels)
- **Hasselblad Teleconverter Kit** — 3.28x magnification, 230mm equivalent, 10x optical zoom, 13 elements/3 groups
- **Video:** 4K@120fps Dolby Vision, 1080p@240fps, 720p@480fps slow-motion
- **Lossless zoom:** up to 13.2x from 200MP crop, 200x digital max
- Android 16 (ColorOS 16), API 29+ target

## Building

### Prerequisites

- **Android Studio** (2024.3+) — download from https://developer.android.com/studio
- **JDK 17+** — Android Studio bundles JDK 21 at `<Android Studio>/jbr/`
- **Android SDK API 35** — installed via Android Studio SDK Manager

### Build from command line

```bash
# Clone
git clone https://github.com/xjouve/GateShot.git
cd GateShot

# Point to a JDK 17+ (use Android Studio's bundled JDK if no other is installed)
# Windows PowerShell:
$env:JAVA_HOME = "K:\android\jbr"
# Linux/Mac:
export JAVA_HOME="/path/to/android-studio/jbr"

# Build debug APK
./gradlew assembleDebug
```

The APK will be at:
```
app/build/outputs/apk/debug/app-debug.apk
```

### Build from Android Studio

1. **File > Open** — select the project root folder
2. Wait for Gradle sync to complete (downloads ~1GB of dependencies on first run)
3. Click the green **Run** button (or `Shift+F10`)

### Install on Oppo Find X9 Pro

1. **Enable Developer Mode** on the phone:
   - Settings > About Phone > tap "Build Number" 7 times
   - Settings > System > Developer Options > enable **USB Debugging**
   - Enable **Install via USB** if prompted

2. **Install via USB + ADB:**
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

3. **Or via Android Studio:** connect the phone via USB, select it in the device dropdown, click Run

### First launch

The app will request **Camera** and **Microphone** permissions on first launch. Grant both to enable all features (video recording, audio trigger, voice-over annotation).

## UI Navigation

**SHOOT mode** (3 bottom tabs):

| Tab | Purpose |
|-----|---------|
| Shoot | Camera preview, burst, zoom, trigger zones, tracking |
| Gallery | Browse media, star, filter by bib/best, share, delete |
| Settings | All camera/video/tracking/exposure/output settings |

**COACH mode** (5 bottom tabs):

| Tab | Purpose |
|-----|---------|
| Shoot | Camera preview (auto-switches to Video preset) |
| Gallery | Browse media with bib filter + best-frame badges |
| Replay | Dual-player video with overlay, autoclip, pose skeleton |
| Coach | Tabbed container with 4 sub-screens (see below) |
| Settings | All settings + voice commands + federation export |

**Coach tab sub-screens:**

| Sub-tab | Purpose |
|---------|---------|
| Annotate | Draw on paused video frames (freehand, arrow, circle), voice-over |
| Athletes | Manage roster (name, bibs, age group, team), track errors and drills |
| Analysis | Consistency tracker, turn analysis, error patterns, time-to-technique, session report PDF, progress view |
| Tools | Ideal line drawing, multi-camera merge, remote coaching export, team feed, cloud backup |

## Project Structure

```
app/                    — Android app, Compose UI, DI wiring, navigation
  ui/viewfinder/        — Main shooting screen with camera preview
  ui/gallery/           — Media browser with filter/star/share/best-frame badge
  ui/replay/            — Dual-player video replay with overlay modes + pose skeleton
  ui/coaching/          — Coach tab: tabbed container (CoachScreen)
  ui/annotation/        — Drawing canvas on video frames + voice-over
  ui/athlete/           — Athlete management (roster, bibs, age groups, teams)
  ui/analysis/          — Analysis dashboard (consistency, turns, errors, time-to-technique, reports, progress)
  ui/settings/          — Full settings with video/AF/trigger/exposure/voice/export sections
  ui/components/        — Shared UI (ShutterButton, StatusBar, PresetSelector, ZoneOverlay)
  ui/navigation/        — Jetpack Navigation (3 tabs SHOOT, 5 tabs COACH) + session dialog
core/                   — Kernel: EventBus, EndpointRegistry, ModuleLoader, ConfigStore
platform/               — CameraX, sensor readings, storage abstraction
capture/                — Camera, burst, preset, trigger, tracking modules
session/                — Room database + session management
processing/             — Snow exposure, burst culling, bib detection, auto-clip, export, SR
coaching/               — Replay, timing, annotation, athlete, pose modules
docs/                   — Feature spec, technical architecture, field testing, user guide
```

## Known Limitations

- **Neural super-resolution** — Uses enhanced bicubic upscale at 13.2x+; no dedicated SR TFLite model shipped. Denoise + deconvolution pipeline handles the bulk of quality improvement.
- **Electronic timing** — Bluetooth framework ready (connect/disconnect/status endpoints), but protocol-level integration with ALGE/Microgate/Tag Heuer hardware requires field testing with actual units.
- **Stabilized crop-and-follow** — Endpoint and data model ready; frame-by-frame crop extraction from 4K video not yet running in real-time during playback.
- **Cloud backup** — WorkManager schedules uploads on WiFi; actual upload destination (Google Drive / FTP / custom) requires user configuration not yet in UI.
- **Trail overlay mode** — Shows ghost overlay with info label; true trajectory lines require pose tracking data recorded during each run.

## License

Proprietary — all rights reserved.
