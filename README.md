# GateShot

A dedicated ski racing camera app for the Oppo Find X9 Pro, built to dramatically enhance photo and video capabilities for training, races, and World Cup events.

**GateShot** combines a professional shooting tool with a coaching analysis suite, all optimized for cold weather, bright snow, and fast-moving athletes.

## Features

### Shooting (always available)
- **6 discipline presets** — Slalom/GS, Speed (DH/SG), Panning, Finish Area, Atmosphere, Training Analysis
- **Snow exposure compensation** — Real-time scene analysis detects snow coverage and auto-adjusts EV (+1.0 to +2.0)
- **Pre-capture buffer** — Continuously buffers 1.5s of frames. Press the shutter and get the frames from *before* you pressed
- **Burst capture** — Short or continuous burst with pre-buffer flush
- **Gate-zone trigger** — Long-press the viewfinder to place trigger zones. Auto-fires burst when a racer enters the zone
- **Audio trigger** — Detects start gate beep and auto-starts capture
- **Video recording** — 4K/1080p with audio, dedicated record button
- **Smart burst culling** — AI ranks frames by sharpness, composition, and exposure
- **Bib number detection** — Auto-detects bib numbers for media tagging
- **Auto-clip** — Segments continuous training video into per-run clips
- **Session organization** — Event > Date > Discipline > Run hierarchy with Room database
- **Quick share** — Coach/Press/Social presets with watermark engine
- **Volume button mapping** — Vol-Up = shutter, Vol-Down = cycle preset

### Coaching (toggle: Shoot + Coach)
- **Instant replay** — Load last clip, variable speed 0.1x-4x, frame-accurate seeking
- **Split-screen comparison** — Two runs side-by-side, gate-synced
- **Manual split timing** — Tap at each gate, overlay on video, compare run deltas
- **Voice-over annotation** — Record audio pinned to video timeline
- **Telestrator** — Draw on paused frames: freehand, line, arrow, circle. High-vis colors for snow

### Field UX
- **Glove mode** — All touch targets minimum 14mm, swipe gestures
- **One-handed operation** — Volume buttons for shutter and preset cycling
- **Battery monitoring** — Real temperature readings, cold warnings at 5°C / 0°C
- **Hasselblad telephoto** — Auto-detect magnetic lens, tele-optimized stabilization

## Architecture

Core + API endpoint architecture with 17 independent Gradle modules:

```
:core                        — Thin kernel (EventBus, EndpointRegistry, ModuleLoader)
:platform                    — Hardware abstraction (CameraX, Sensors)
:capture:camera              — CameraX pipeline + VideoCapture
:capture:burst               — Pre-capture ring buffer + burst
:capture:preset              — 6 discipline presets
:capture:trigger             — Gate-zone motion trigger + audio trigger
:session                     — Room database (Session/Run/Media)
:processing:snow-exposure    — Snow EV compensation + flat light
:processing:burst-culling    — AI frame ranking
:processing:bib-detection    — Bib number OCR
:processing:autoclip         — Audio-based run segmentation
:processing:export           — Share + watermark
:coaching:replay             — ExoPlayer replay + split-screen
:coaching:timing             — Manual split timing
:coaching:annotation         — Voice-over + telestrator drawing
```

**Design rules:**
- Modules communicate only via EventBus (fire-and-forget events)
- Every feature is accessible through API endpoints
- Coach-only endpoints return 403 when coach toggle is off
- No module depends on the UI layer
- Platform layer is abstracted behind interfaces for testability

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin |
| UI | Jetpack Compose |
| Camera | CameraX (Camera2 + VideoCapture) |
| DI | Hilt (Dagger) |
| Database | Room |
| Video Playback | ExoPlayer (Media3) |
| Async | Coroutines + Flow |
| Build | Gradle KTS with version catalog |

## Hardware Target

- **Oppo Find X9 Pro** (16GB RAM / 512GB storage)
- **Hasselblad Telephoto Lens Kit** (magnetic mount)
- Android 10+ (API 29)

## Building

```bash
# Clone
git clone https://github.com/xjouve/GateShot.git
cd GateShot

# Open in Android Studio (Arctic Fox+) and sync Gradle
# Or build from command line:
./gradlew assembleDebug
```

## Project Structure

```
app/                    — Android app, Compose UI, DI wiring, navigation
  ui/viewfinder/        — Main shooting screen with camera preview
  ui/gallery/           — Media browser with filter/star/share
  ui/replay/            — Video replay with speed controls
  ui/annotation/        — Drawing canvas + voice-over
  ui/components/        — Shared UI (ShutterButton, StatusBar, PresetSelector, ZoneOverlay)
  ui/navigation/        — Jetpack Navigation with bottom bar
core/                   — Kernel: EventBus, EndpointRegistry, ModuleLoader, ConfigStore
platform/               — CameraX, sensor readings, storage abstraction
capture/                — Camera, burst, preset, trigger modules
session/                — Room database + session management
processing/             — Snow exposure, burst culling, bib detection, auto-clip, export
coaching/               — Replay, timing, annotation modules
docs/                   — Feature spec + technical architecture
```

## License

Proprietary — all rights reserved.
