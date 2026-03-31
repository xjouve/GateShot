# GateShot — Technical Architecture
## Core + API Endpoint Architecture

**Version:** 0.1
**Date:** 2026-03-31
**Companion document:** [FEATURE_SPEC.md](FEATURE_SPEC.md)

---

## 1. Architecture Philosophy

The app is built around a single principle: **a thin, stable Core that does very little, surrounded by independent Feature Modules that each expose their capabilities through internal API endpoints.**

Why this matters:
- **Maintainability:** A bug in burst culling cannot break the camera pipeline. A new coaching feature cannot destabilize capture.
- **Independent deployment:** Each module can be updated, tested, and iterated on without touching the core or other modules.
- **Team scalability:** Multiple developers work on different modules simultaneously with minimal merge conflicts.
- **Feature toggling:** The Shoot / Shoot+Coach mode toggle is architecturally trivial — it's just endpoint visibility, not code branching.
- **Future-proofing:** New features (a new AI model, a new timing system, a new export format) slot in as new modules with new endpoints. Zero core changes.

---

## 2. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                        GateShot App                              │
│                                                                     │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │                      UI Layer (Jetpack Compose)               │  │
│  │  Viewfinder  │  Controls  │  Gallery  │  Coach Panels  │ HUD │  │
│  └──────────────────────────┬────────────────────────────────────┘  │
│                             │                                       │
│                     Internal API Bus                                │
│              (request / response + event stream)                    │
│                             │                                       │
│  ┌──────────────────────────┴────────────────────────────────────┐  │
│  │                        CORE                                   │  │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌─────────────────┐  │  │
│  │  │ Endpoint │ │  Event   │ │ Lifecycle│ │   Permission    │  │  │
│  │  │ Registry │ │   Bus    │ │ Manager  │ │    Manager      │  │  │
│  │  └──────────┘ └──────────┘ └──────────┘ └─────────────────┘  │  │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌─────────────────┐  │  │
│  │  │  Module  │ │  Config  │ │   Error  │ │  Mode Manager   │  │  │
│  │  │  Loader  │ │  Store   │ │ Handler  │ │ (Shoot / Coach) │  │  │
│  │  └──────────┘ └──────────┘ └──────────┘ └─────────────────┘  │  │
│  └──────────────────────────┬────────────────────────────────────┘  │
│                             │                                       │
│         ┌───────────────────┼───────────────────┐                   │
│         │                   │                   │                   │
│  ┌──────┴──────┐  ┌────────┴────────┐  ┌───────┴───────┐          │
│  │   CAPTURE   │  │   PROCESSING    │  │   COACHING    │          │
│  │   MODULES   │  │    MODULES      │  │   MODULES     │          │
│  │             │  │                 │  │               │          │
│  │ Camera      │  │ Snow Exposure   │  │ Replay        │          │
│  │ Burst       │  │ Burst Culling   │  │ Comparison    │          │
│  │ Video       │  │ Bib Detection   │  │ Pose          │          │
│  │ Audio       │  │ Stabilization   │  │ Annotation    │          │
│  │ PreBuffer   │  │ Auto-Clip       │  │ Timing        │          │
│  │ Lens        │  │ Metadata        │  │ Athletes      │          │
│  │ Trigger     │  │ Export          │  │ Reports       │          │
│  │ Voice       │  │ Cloud           │  │ Team          │          │
│  └─────────────┘  └─────────────────┘  └───────────────┘          │
│                                                                     │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │                    PLATFORM LAYER                             │  │
│  │  Camera2/CameraX HAL  │  NPU/GPU  │  Sensors  │  Storage    │  │
│  └───────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 3. Core — The Thin Kernel

The Core is intentionally minimal. It does **not** capture photos, process images, or implement any feature logic. It only provides infrastructure for modules to register, communicate, and be managed.

### 3.1 Endpoint Registry

The central routing table. Every module registers its API endpoints here at startup.

```kotlin
// Core provides this interface — modules implement it
interface ApiEndpoint<Request, Response> {
    val path: String                    // e.g., "capture/burst/start"
    val module: String                  // e.g., "burst"
    val requiredMode: AppMode?          // null = always available, COACH = coach-only
    val version: Int                    // endpoint versioning for backward compat
    suspend fun handle(request: Request): ApiResponse<Response>
}

// Registry operations
interface EndpointRegistry {
    fun register(endpoint: ApiEndpoint<*, *>)
    fun unregister(path: String)
    suspend fun <Req, Res> call(path: String, request: Req): ApiResponse<Res>
    fun listEndpoints(mode: AppMode? = null): List<EndpointDescriptor>
    fun isAvailable(path: String): Boolean
}
```

**How it works:**
1. App starts. Core initializes.
2. Module Loader discovers and loads all feature modules.
3. Each module registers its endpoints with the registry.
4. UI or other modules call endpoints by path. Registry routes to the handler.
5. Mode Manager filters visibility — coach-only endpoints return `403 MODE_NOT_ACTIVE` when coach toggle is off.

### 3.2 Event Bus

Decoupled publish/subscribe system for cross-module communication. Modules never call each other directly — they emit events and subscribe to events.

```kotlin
interface EventBus {
    fun <T : AppEvent> publish(event: T)
    fun <T : AppEvent> subscribe(eventType: KClass<T>, handler: (T) -> Unit): Subscription
    fun unsubscribe(subscription: Subscription)
}

// Event categories
sealed class AppEvent {
    // Capture events
    data class ShutterPressed(val timestamp: Long) : AppEvent()
    data class BurstCompleted(val frameCount: Int, val sessionId: String) : AppEvent()
    data class VideoRecordingStarted(val sessionId: String) : AppEvent()
    data class VideoRecordingStopped(val sessionId: String, val clipUri: Uri) : AppEvent()
    data class RunDetected(val runNumber: Int, val bibNumber: Int?) : AppEvent()

    // Processing events
    data class FramesAnalyzed(val results: List<FrameScore>) : AppEvent()
    data class BibDetected(val bibNumber: Int, val confidence: Float) : AppEvent()
    data class ExposureAdjusted(val evBias: Float, val reason: String) : AppEvent()

    // Coaching events
    data class SplitRecorded(val gateNumber: Int, val timestamp: Long) : AppEvent()
    data class PoseEstimated(val skeleton: SkeletonData) : AppEvent()
    data class AnnotationAdded(val clipId: String, val annotation: Annotation) : AppEvent()

    // System events
    data class ModeChanged(val newMode: AppMode) : AppEvent()
    data class LensDetected(val lensType: LensType) : AppEvent()
    data class BatteryWarning(val tempCelsius: Float, val level: Int) : AppEvent()
    data class ModuleLoaded(val moduleName: String) : AppEvent()
    data class ModuleError(val moduleName: String, val error: Throwable) : AppEvent()
}
```

**Key design rule:** Events are fire-and-forget. If the Burst module emits `BurstCompleted`, it doesn't know or care whether the Culling module, the Bib Detection module, or the Session Organization module are listening. Any of them might be. All of them might be. None of them might be.

### 3.3 Module Loader

Discovers, initializes, and manages the lifecycle of all feature modules.

```kotlin
interface FeatureModule {
    val name: String                        // "burst", "snow_exposure", "pose_estimation"
    val version: String                     // "1.2.0"
    val requiredMode: AppMode?              // null = always loaded, COACH = coach-only
    val dependencies: List<String>          // other module names this depends on
    val requiredCapabilities: List<String>  // "camera2", "npu", "bluetooth"

    suspend fun initialize(core: CoreServices)
    suspend fun shutdown()
    fun endpoints(): List<ApiEndpoint<*, *>>
    fun healthCheck(): ModuleHealth
}
```

- Modules are loaded in dependency order.
- If a module's required capabilities aren't available on the device, it's skipped gracefully (the app still works — you just don't get that feature).
- Hot-reload support for development builds.

### 3.4 Config Store

Centralized configuration with per-module namespacing.

```kotlin
interface ConfigStore {
    fun <T> get(module: String, key: String, default: T): T
    fun <T> set(module: String, key: String, value: T)
    fun getPreset(discipline: Discipline): PresetConfig
    fun observeChanges(module: String, key: String): Flow<Any>
    fun export(): Map<String, Map<String, Any>>    // full config dump
    fun import(config: Map<String, Map<String, Any>>)  // restore config
}
```

- Discipline presets are stored here as composite configs that set values across multiple modules simultaneously.
- User overrides are layered on top of presets (preset = base, user = override).

### 3.5 Mode Manager

Controls the Shoot / Shoot+Coach toggle.

```kotlin
enum class AppMode { SHOOT, COACH }

interface ModeManager {
    val currentMode: StateFlow<AppMode>
    fun setMode(mode: AppMode)
    fun isFeatureAvailable(requiredMode: AppMode?): Boolean
}
```

When mode changes:
1. Mode Manager publishes `ModeChanged` event.
2. Endpoint Registry updates visibility — coach-only endpoints become available or hidden.
3. UI Layer shows/hides coach panels.
4. Coach-only modules that aren't loaded yet get initialized (lazy loading).

### 3.6 Lifecycle Manager

Handles app state transitions with ski-racing-specific awareness.

```kotlin
interface LifecycleManager {
    fun onSessionStart(event: String, discipline: Discipline)
    fun onSessionEnd()
    fun onHibernate()       // quick-hibernate between runs
    fun onWake()            // instant resume from hibernate
    fun onColdStart()       // full app launch
    fun onBackgrounded()    // user switched away
    fun onForegrounded()    // user returned
}
```

- Hibernate mode keeps the camera pipeline warm but suspends UI rendering and background processing to save battery.
- Wake from hibernate to shooting-ready target: < 300ms.

### 3.7 Error Handler

Centralized error handling with module isolation.

```kotlin
interface ErrorHandler {
    fun reportError(module: String, error: Throwable, severity: Severity)
    fun getModuleHealth(): Map<String, ModuleHealth>
}

enum class Severity { INFO, WARNING, DEGRADED, CRITICAL }
```

- A module crash is contained — the module is marked DEGRADED, the user gets a non-intrusive notification, and the rest of the app keeps running.
- CRITICAL errors (camera hardware failure, storage full) surface immediately.

### 3.8 Permission Manager

Android permissions with ski-racing context.

```kotlin
interface PermissionManager {
    fun checkRequired(): List<PermissionRequest>
    fun requestPermission(permission: String): Flow<PermissionResult>
    fun getModulePermissions(module: String): List<String>
}
```

Permissions are requested per-module, on first use, with user-friendly explanations ("Voice Commands needs microphone access to hear your instructions on the slope").

---

## 4. API Endpoint Catalog

Every feature is accessible through API endpoints. The UI never talks directly to module internals — it always goes through the endpoint registry.

### 4.1 Capture Domain — `capture/*`

| Endpoint | Method | Description | Mode |
|----------|--------|-------------|------|
| `capture/camera/open` | POST | Initialize camera pipeline with config | All |
| `capture/camera/close` | POST | Release camera resources | All |
| `capture/camera/switch` | POST | Switch between front/rear/telephoto | All |
| `capture/camera/status` | GET | Current camera state, resolution, fps | All |
| `capture/photo/single` | POST | Capture single photo | All |
| `capture/photo/burst/start` | POST | Start burst capture | All |
| `capture/photo/burst/stop` | POST | Stop burst capture | All |
| `capture/photo/burst/status` | GET | Current burst state and frame count | All |
| `capture/video/start` | POST | Start video recording | All |
| `capture/video/stop` | POST | Stop video recording, return clip URI | All |
| `capture/video/status` | GET | Recording state, duration, file size | All |
| `capture/video/framerate` | POST | Set frame rate (30/60/120/240) | All |
| `capture/prebuffer/config` | POST | Configure pre-capture buffer (duration, resolution) | All |
| `capture/prebuffer/status` | GET | Buffer state, memory usage | All |
| `capture/prebuffer/save` | POST | Flush current buffer to storage | All |

### 4.2 Lens Domain — `lens/*`

| Endpoint | Method | Description | Mode |
|----------|--------|-------------|------|
| `lens/detect` | GET | Current lens status (built-in / telephoto attached) | All |
| `lens/profile/get` | GET | Active lens correction profile | All |
| `lens/profile/set` | POST | Override lens profile | All |
| `lens/zoom/set` | POST | Set zoom level (1x, 3x, 5x, custom) | All |
| `lens/zoom/get` | GET | Current zoom level | All |
| `lens/stabilization/config` | POST | Configure OIS+EIS mode (standard, tele, panning, tripod) | All |

### 4.3 Exposure & Light Domain — `exposure/*`

| Endpoint | Method | Description | Mode |
|----------|--------|-------------|------|
| `exposure/auto/config` | POST | Configure auto-exposure behavior | All |
| `exposure/snow/status` | GET | Current snow detection state and EV bias | All |
| `exposure/snow/override` | POST | Manual EV override on top of snow compensation | All |
| `exposure/flatlight/enable` | POST | Activate flat-light mode | All |
| `exposure/flatlight/disable` | POST | Deactivate flat-light mode | All |
| `exposure/hdr/config` | POST | Configure HDR mode (off, auto, aggressive) | All |
| `exposure/backlit/config` | POST | Configure backlit subject handling | All |
| `exposure/preset/apply` | POST | Apply a discipline preset's exposure settings | All |
| `exposure/scene/analyze` | GET | Current scene analysis (snow %, shadow map, light direction) | All |

### 4.4 Autofocus & Tracking Domain — `af/*`

| Endpoint | Method | Description | Mode |
|----------|--------|-------------|------|
| `af/mode/set` | POST | Set AF mode (single, continuous, predictive, manual) | All |
| `af/mode/get` | GET | Current AF mode and state | All |
| `af/track/start` | POST | Start subject tracking at given screen coordinates | All |
| `af/track/stop` | POST | Release tracking lock | All |
| `af/track/status` | GET | Tracking state, confidence, subject position | All |
| `af/zone/add` | POST | Add a gate-zone trigger region | All |
| `af/zone/remove` | POST | Remove a gate-zone trigger region | All |
| `af/zone/list` | GET | List all active trigger zones | All |
| `af/zone/clear` | POST | Remove all trigger zones | All |

### 4.5 Trigger Domain — `trigger/*`

| Endpoint | Method | Description | Mode |
|----------|--------|-------------|------|
| `trigger/audio/config` | POST | Configure audio trigger (sensitivity, pattern, action) | All |
| `trigger/audio/enable` | POST | Arm audio trigger | All |
| `trigger/audio/disable` | POST | Disarm audio trigger | All |
| `trigger/audio/status` | GET | Audio trigger state, last trigger event | All |
| `trigger/zone/config` | POST | Configure zone-based auto-trigger behavior | All |
| `trigger/timing/connect` | POST | Connect to external timing system via Bluetooth | All |
| `trigger/timing/disconnect` | POST | Disconnect from timing system | All |
| `trigger/timing/status` | GET | Timing system connection state, last received split | All |
| `trigger/voice/config` | POST | Configure voice command recognition | All |
| `trigger/voice/enable` | POST | Start listening for voice commands | All |
| `trigger/voice/disable` | POST | Stop listening | All |

### 4.6 Preset Domain — `preset/*`

| Endpoint | Method | Description | Mode |
|----------|--------|-------------|------|
| `preset/list` | GET | List all available discipline presets | All |
| `preset/apply` | POST | Apply a preset (configures capture, exposure, AF, burst as a unit) | All |
| `preset/current` | GET | Currently active preset and any user overrides | All |
| `preset/customize` | POST | Modify a parameter within the current preset | All |
| `preset/save` | POST | Save current config as a custom preset | All |
| `preset/delete` | POST | Delete a custom preset | All |
| `preset/reset` | POST | Reset current preset to factory defaults | All |

### 4.7 Processing Domain — `process/*`

| Endpoint | Method | Description | Mode |
|----------|--------|-------------|------|
| `process/burst/cull` | POST | Run AI culling on a burst, return ranked frames | All |
| `process/burst/cull/status` | GET | Culling progress (for large bursts) | All |
| `process/bib/detect` | POST | Run bib detection on a frame or clip | All |
| `process/bib/detect/batch` | POST | Batch bib detection across a session | All |
| `process/stabilize/crop` | POST | Run stabilized crop-and-follow on a 4K clip | All |
| `process/stabilize/status` | GET | Stabilization processing progress | All |
| `process/autoclip/run` | POST | Segment a continuous recording into per-run clips | All |
| `process/autoclip/status` | GET | Auto-clip processing progress | All |
| `process/metadata/enrich` | POST | Add GPS, weather, discipline, bib to file metadata | All |

### 4.8 Session & Organization Domain — `session/*`

| Endpoint | Method | Description | Mode |
|----------|--------|-------------|------|
| `session/create` | POST | Start a new session (event name, discipline, date) | All |
| `session/end` | POST | End the current session | All |
| `session/current` | GET | Current session info | All |
| `session/list` | GET | List all sessions, filterable by date/event/discipline | All |
| `session/run/start` | POST | Mark start of a new run within session | All |
| `session/run/end` | POST | Mark end of current run | All |
| `session/run/list` | GET | List runs in current session | All |
| `session/media/list` | GET | List all media in a session, filterable by run/bib/type | All |
| `session/media/tag` | POST | Tag media with bib number, star rating, flag | All |
| `session/media/tag/batch` | POST | Batch-tag multiple media items | All |

### 4.9 Export & Sharing Domain — `export/*`

| Endpoint | Method | Description | Mode |
|----------|--------|-------------|------|
| `export/share/quick` | POST | One-tap share (preset: coach, press, social) | All |
| `export/share/batch` | POST | Batch export multiple items | All |
| `export/watermark/config` | POST | Configure watermark (text, position, opacity) | All |
| `export/raw/extract` | POST | Export RAW files from a session | All |
| `export/cloud/upload` | POST | Start cloud backup | All |
| `export/cloud/status` | GET | Cloud backup progress | All |
| `export/cloud/config` | POST | Configure cloud destination and priority rules | All |
| `export/federation/export` | POST | Export in federation-standard format | Coach |

### 4.10 System & Device Domain — `system/*`

| Endpoint | Method | Description | Mode |
|----------|--------|-------------|------|
| `system/mode/get` | GET | Current app mode (Shoot / Coach) | All |
| `system/mode/set` | POST | Toggle app mode | All |
| `system/battery/status` | GET | Battery level, temperature, estimated remaining time | All |
| `system/storage/status` | GET | Storage used, remaining, estimated capacity | All |
| `system/hibernate` | POST | Enter quick-hibernate mode | All |
| `system/wake` | POST | Wake from hibernate | All |
| `system/health` | GET | All module health statuses | All |
| `system/config/export` | GET | Export full app configuration | All |
| `system/config/import` | POST | Import app configuration | All |
| `system/glovemode/set` | POST | Enable/disable glove mode | All |

### 4.11 Coaching — Replay & Comparison Domain — `coach/replay/*`

| Endpoint | Method | Description | Mode |
|----------|--------|-------------|------|
| `coach/replay/load` | POST | Load a clip for instant replay | Coach |
| `coach/replay/play` | POST | Play, pause, set speed, seek | Coach |
| `coach/replay/status` | GET | Current replay state | Coach |
| `coach/compare/split` | POST | Set up split-screen with two clips, sync mode | Coach |
| `coach/compare/ghost` | POST | Set up ghost overlay (reference + current) | Coach |
| `coach/compare/sync/gate` | POST | Sync two clips by gate number | Coach |
| `coach/compare/sync/manual` | POST | Sync two clips by manual alignment point | Coach |

### 4.12 Coaching — Analysis Domain — `coach/analysis/*`

| Endpoint | Method | Description | Mode |
|----------|--------|-------------|------|
| `coach/analysis/pose/run` | POST | Run pose estimation on a clip | Coach |
| `coach/analysis/pose/status` | GET | Pose estimation progress | Coach |
| `coach/analysis/pose/result` | GET | Get skeleton data for a clip | Coach |
| `coach/analysis/angles/measure` | POST | Measure angles on a specific frame | Coach |
| `coach/analysis/turn/analyze` | POST | Run turn analysis on a clip | Coach |
| `coach/analysis/turn/result` | GET | Get turn analysis dashboard data | Coach |
| `coach/analysis/consistency/run` | POST | Run consistency analysis across multiple runs | Coach |
| `coach/analysis/consistency/result` | GET | Get consistency report | Coach |
| `coach/analysis/errors/detect` | POST | Run error pattern detection across runs | Coach |
| `coach/analysis/errors/result` | GET | Get detected error patterns | Coach |
| `coach/analysis/line/ideal/set` | POST | Set ideal racing line on course image | Coach |
| `coach/analysis/line/compare` | POST | Compare actual vs. ideal line | Coach |

### 4.13 Coaching — Annotation Domain — `coach/annotate/*`

| Endpoint | Method | Description | Mode |
|----------|--------|-------------|------|
| `coach/annotate/voiceover/start` | POST | Start recording voice-over on a clip at current position | Coach |
| `coach/annotate/voiceover/stop` | POST | Stop voice-over recording | Coach |
| `coach/annotate/voiceover/list` | GET | List voice-over annotations on a clip | Coach |
| `coach/annotate/voiceover/delete` | POST | Delete a voice-over annotation | Coach |
| `coach/annotate/draw/save` | POST | Save telestrator drawing on a frame | Coach |
| `coach/annotate/draw/list` | GET | List drawings on a clip | Coach |
| `coach/annotate/draw/delete` | POST | Delete a drawing annotation | Coach |

### 4.14 Coaching — Athletes Domain — `coach/athlete/*`

| Endpoint | Method | Description | Mode |
|----------|--------|-------------|------|
| `coach/athlete/create` | POST | Create athlete profile | Coach |
| `coach/athlete/update` | POST | Update athlete profile | Coach |
| `coach/athlete/get` | GET | Get athlete profile by ID or bib | Coach |
| `coach/athlete/list` | GET | List all athletes, filterable | Coach |
| `coach/athlete/delete` | POST | Delete athlete profile | Coach |
| `coach/athlete/media` | GET | Get all media for an athlete across sessions | Coach |
| `coach/athlete/errors` | GET | Get error history and trends for an athlete | Coach |
| `coach/athlete/progress` | GET | Get progress timeline for an athlete | Coach |
| `coach/athlete/drill/assign` | POST | Assign a drill to an athlete | Coach |
| `coach/athlete/drill/list` | GET | List assigned drills for an athlete | Coach |

### 4.15 Coaching — Timing Domain — `coach/timing/*`

| Endpoint | Method | Description | Mode |
|----------|--------|-------------|------|
| `coach/timing/split/record` | POST | Record a manual split (coach taps screen) | Coach |
| `coach/timing/split/list` | GET | List splits for a run | Coach |
| `coach/timing/split/delete` | POST | Delete a split | Coach |
| `coach/timing/sync/video` | POST | Sync timing data with video clip | Coach |
| `coach/timing/correlate` | POST | Run time-to-technique correlation analysis | Coach |
| `coach/timing/correlate/result` | GET | Get correlation results | Coach |

### 4.16 Coaching — Team & Sharing Domain — `coach/team/*`

| Endpoint | Method | Description | Mode |
|----------|--------|-------------|------|
| `coach/team/feed/get` | GET | Get team feed (paginated) | Coach |
| `coach/team/feed/post` | POST | Post a clip + note to team feed | Coach |
| `coach/team/feed/flag` | POST | Flag a feed item as high-priority | Coach |
| `coach/team/share/clip` | POST | Share annotated clip for async review | Coach |
| `coach/team/share/inbox` | GET | Get incoming shared clips and annotations | Coach |
| `coach/team/multicam/sync` | POST | Sync two clips from different angles | Coach |
| `coach/team/multicam/view` | GET | Get multi-angle view configuration | Coach |

### 4.17 Coaching — Reports Domain — `coach/report/*`

| Endpoint | Method | Description | Mode |
|----------|--------|-------------|------|
| `coach/report/session/generate` | POST | Generate session report | Coach |
| `coach/report/session/status` | GET | Report generation progress | Coach |
| `coach/report/session/get` | GET | Get generated report data | Coach |
| `coach/report/session/export` | POST | Export report as PDF or data package | Coach |
| `coach/report/progress/generate` | POST | Generate before/after progress report for an athlete | Coach |
| `coach/report/drill/list` | GET | List all drills in the library | Coach |
| `coach/report/drill/create` | POST | Create a drill reference from a clip | Coach |
| `coach/report/drill/delete` | POST | Delete a drill from the library | Coach |

---

## 5. Module Specifications

### 5.1 Capture Modules

#### 5.1.1 Camera Module (`camera`)
**Responsibility:** Owns the Camera2/CameraX pipeline. The only module that talks to camera hardware.

- Opens/closes camera device.
- Configures resolution, frame rate, output surfaces.
- Provides frame stream to other modules (via shared `ImageReader` or `SurfaceTexture`).
- Handles lens switching (wide / ultra-wide / telephoto).
- No processing — raw frames only.

**Depends on:** Platform Layer (Camera2 HAL).
**Publishes:** `CameraOpened`, `CameraClosed`, `FrameAvailable`.
**Subscribes to:** `LensDetected` (from Lens module — to auto-switch config).

#### 5.1.2 Burst Module (`burst`)
**Responsibility:** High-speed sequential capture.

- Receives trigger (shutter press, zone trigger, audio trigger) and captures N frames at max rate.
- Configurable burst length, frame rate, and trigger mode.
- Writes frames to a staging area (memory-mapped file for speed).
- Emits `BurstCompleted` with frame references for downstream processing.

**Depends on:** `camera`.
**Publishes:** `BurstStarted`, `BurstCompleted`.
**Subscribes to:** `ShutterPressed`, `ZoneTriggered`, `AudioTriggered`.

#### 5.1.3 Pre-Capture Buffer Module (`prebuffer`)
**Responsibility:** Continuous ring buffer of recent frames.

- Captures frames continuously from the camera pipeline into a circular memory buffer.
- On trigger, saves buffer contents (the 1.5-2s *before* the trigger) plus ongoing capture.
- Memory budget: configurable, default 1.5GB ring buffer at 4K 30fps (~45 frames * 33MB each).
- Zero-copy handoff to Burst module when shutter pressed.

**Depends on:** `camera`.
**Publishes:** `BufferFlushed`.
**Subscribes to:** `ShutterPressed` (to flush buffer), `SystemHibernate` (to pause buffering).

#### 5.1.4 Video Module (`video`)
**Responsibility:** Continuous video recording with variable frame rates.

- Standard recording (30/60fps) and high-frame-rate (120/240fps).
- Manages MediaRecorder or MediaCodec pipeline.
- Audio capture (ambient sound for sync and coaching).
- File segmentation for long sessions (avoid single 50GB files).

**Depends on:** `camera`.
**Publishes:** `VideoRecordingStarted`, `VideoRecordingStopped`.
**Subscribes to:** `AudioTriggered` (auto-start), `VoiceCommand`.

#### 5.1.5 Audio Module (`audio`)
**Responsibility:** Audio capture, analysis, and triggering.

- Continuous audio monitoring for trigger patterns (start beep, cowbell).
- Configurable trigger profiles with frequency/amplitude patterns.
- Audio recording for video clips.
- Wind noise detection and reduction for voice commands.

**Depends on:** Platform Layer (AudioRecord).
**Publishes:** `AudioTriggered`, `AudioPatternDetected`.
**Subscribes to:** none (self-driven, always listening when armed).

#### 5.1.6 Lens Module (`lens`)
**Responsibility:** Hasselblad telephoto detection and lens management.

- Monitors magnetometer for telephoto attachment events.
- Applies lens correction profiles (vignette, CA, distortion).
- Manages zoom state and focal length presets.
- Configures stabilization mode per lens type.

**Depends on:** `camera`, Platform Layer (sensors).
**Publishes:** `LensDetected`, `LensRemoved`, `ZoomChanged`.
**Subscribes to:** none (sensor-driven).

#### 5.1.7 Voice Command Module (`voice`)
**Responsibility:** Speech recognition for hands-free control.

- On-device speech recognition (no internet required).
- Limited vocabulary optimized for ski racing commands.
- Wind noise compensation via spectral filtering.
- Emits recognized commands as events — does not execute them directly.

**Depends on:** Platform Layer (speech recognition engine).
**Publishes:** `VoiceCommand`.
**Subscribes to:** none (microphone-driven).

### 5.2 Processing Modules

#### 5.2.1 Snow Exposure Module (`snow_exposure`)
**Responsibility:** Scene-aware exposure compensation for snow.

- Analyzes each viewfinder frame for snow coverage percentage.
- Computes EV bias based on snow %, current metering, and active preset.
- Publishes exposure adjustments — Camera module applies them.
- Flat light detection and viewfinder enhancement.
- Sun/shadow boundary detection for predictive exposure ramping.

**Depends on:** `camera` (frame stream).
**Publishes:** `ExposureAdjusted`, `SceneAnalyzed`, `FlatLightDetected`.
**Subscribes to:** `PresetApplied` (to adjust base EV).

#### 5.2.2 Burst Culling Module (`burst_culling`)
**Responsibility:** AI-powered frame ranking within a burst.

- Receives burst frame set from Burst module.
- Runs on-device ML models for: sharpness scoring, composition scoring, face/body position scoring.
- Returns ranked frame list with scores and top-N recommendation.
- Runs on NPU for speed — target < 2 seconds for a 30-frame burst.

**Depends on:** Platform Layer (NPU / TFLite).
**Publishes:** `FramesAnalyzed`.
**Subscribes to:** `BurstCompleted`.

#### 5.2.3 Bib Detection Module (`bib_detection`)
**Responsibility:** OCR-based bib number recognition.

- Runs text detection + OCR on captured frames.
- Trained on FIS standard bib layouts (large numbers, high contrast).
- Returns bib number + confidence + bounding box.
- Auto-tags session media with detected bib.
- Batch mode for processing an entire session retroactively.

**Depends on:** Platform Layer (NPU / TFLite or ML Kit).
**Publishes:** `BibDetected`.
**Subscribes to:** `BurstCompleted`, `VideoRecordingStopped`.

#### 5.2.4 Stabilization Module (`stabilization`)
**Responsibility:** Post-capture stabilization and crop-and-follow.

- EIS pipeline (real-time) integrated with camera preview.
- Post-capture stabilized crop: 4K input → 1080p stabilized output following the subject.
- Subject tracking for crop window (reuses AF tracking data).
- Processing can run in background while user continues shooting.

**Depends on:** `camera`, Platform Layer (GPU compute).
**Publishes:** `StabilizationCompleted`.
**Subscribes to:** `VideoRecordingStopped` (auto-process if configured).

#### 5.2.5 Auto-Clip Module (`autoclip`)
**Responsibility:** Segment continuous recordings into per-run clips.

- Analyzes audio track for start beep patterns.
- Analyzes motion track for run start/end patterns.
- Creates non-destructive clip references (in/out points on the original file).
- Names clips by run number and bib (if detected).

**Depends on:** `audio`, `bib_detection`.
**Publishes:** `RunDetected`.
**Subscribes to:** `VideoRecordingStopped`.

#### 5.2.6 Metadata Module (`metadata`)
**Responsibility:** Enrich media files with contextual metadata.

- Writes EXIF/XMP fields: GPS, weather, discipline, event name, bib number.
- Pulls weather data from phone sensors (temperature, pressure) and weather API when online.
- Embeds session hierarchy info for desktop tool compatibility.

**Depends on:** Platform Layer (sensors, location, network).
**Publishes:** `MetadataEnriched`.
**Subscribes to:** `BurstCompleted`, `VideoRecordingStopped`, `BibDetected`.

#### 5.2.7 Export Module (`export`)
**Responsibility:** All output operations — sharing, cloud backup, federation export.

- Share presets (coach, press, social) with size/quality/watermark configurations.
- Cloud backup with priority queue and resume.
- Federation export with standardized naming and format.
- Watermark overlay engine.
- Batch operations.

**Depends on:** Platform Layer (network, share intents).
**Publishes:** `ExportCompleted`, `CloudBackupProgress`.
**Subscribes to:** none (demand-driven via API calls).

### 5.3 Coaching Modules

#### 5.3.1 Replay Module (`replay`)
**Responsibility:** Instant playback and comparison views.

- Fast video loading with frame-accurate seeking.
- Variable-speed playback (0.1x to 4x).
- Split-screen renderer with gate-synced alignment.
- Ghost overlay compositor (semi-transparent reference layer).

**Depends on:** Platform Layer (MediaCodec, GPU).
**Publishes:** `ReplayStarted`, `ReplayFrameChanged`.
**Subscribes to:** `VideoRecordingStopped` (auto-load latest), `RunDetected`.

#### 5.3.2 Pose Estimation Module (`pose`)
**Responsibility:** AI body tracking and skeleton overlay.

- Runs pose estimation model (MoveNet or custom) on video frames.
- Outputs 17-33 keypoint skeleton per frame.
- Computes joint angles (knee, hip, torso, edge angle).
- Generates skeleton overlay video.
- Exports angle data as CSV for external analysis.

**Depends on:** Platform Layer (NPU / TFLite).
**Publishes:** `PoseEstimated`.
**Subscribes to:** none (demand-driven via API calls — pose estimation is expensive).

#### 5.3.3 Annotation Module (`annotation`)
**Responsibility:** Voice-over, drawing, and markup tools.

- Voice-over recording pinned to video timeline positions.
- Telestrator drawing engine: freehand, line, arrow, circle, angle arc.
- Drawing stored as vector overlays (non-destructive).
- Annotation export (baked into video or as sidecar data).

**Depends on:** Platform Layer (Canvas, audio).
**Publishes:** `AnnotationAdded`, `AnnotationDeleted`.
**Subscribes to:** none (user-driven).

#### 5.3.4 Timing Module (`timing`)
**Responsibility:** Split timing and time-to-technique correlation.

- Manual split recording (screen tap → timestamp).
- Bluetooth integration with ALGE, Microgate, Tag Heuer timing systems.
- Timing data overlay on video frames.
- Time-to-technique correlation engine: links timing deltas to video evidence.

**Depends on:** Platform Layer (Bluetooth).
**Publishes:** `SplitRecorded`, `TimingDataReceived`.
**Subscribes to:** `VideoRecordingStopped` (to sync timing with clip).

#### 5.3.5 Athlete Module (`athlete`)
**Responsibility:** Athlete profile management and progress tracking.

- CRUD for athlete profiles.
- Links media, annotations, timing data, and error patterns to athletes.
- Progress timeline generation.
- Drill assignment and tracking.

**Depends on:** Local database.
**Publishes:** `AthleteUpdated`.
**Subscribes to:** `BibDetected` (auto-link media to athlete by bib).

#### 5.3.6 Analysis Module (`analysis`)
**Responsibility:** Turn analysis, consistency tracking, and error detection.

- Turn-by-turn analysis dashboard using pose + timing + tracking data.
- Consistency metrics across multiple runs.
- Error pattern detection via ML model trained on common ski racing technique issues.
- Ideal line comparison engine.

**Depends on:** `pose`, `timing`.
**Publishes:** `AnalysisCompleted`, `ErrorPatternDetected`.
**Subscribes to:** none (demand-driven — analysis is compute-intensive).

#### 5.3.7 Report Module (`report`)
**Responsibility:** Session reports and progress reports.

- Auto-aggregates session data: runs, athletes, annotations, timing, errors.
- Generates structured report with key frames and annotated clips.
- PDF export engine.
- Federation-format data package export.

**Depends on:** `athlete`, `analysis`, `annotation`.
**Publishes:** `ReportGenerated`.
**Subscribes to:** `SessionEnded` (prompt for report generation).

#### 5.3.8 Team Module (`team`)
**Responsibility:** Multi-coach collaboration and sharing.

- Team feed: shared clip + annotation timeline.
- Async review: send/receive annotated clips between coaches.
- Multi-camera sync: align clips from different angles by audio fingerprint.
- Push notifications for flagged content.

**Depends on:** Platform Layer (network), `annotation`.
**Publishes:** `TeamFeedUpdated`, `SharedClipReceived`.
**Subscribes to:** none (user-driven and network-driven).

---

## 6. Data Architecture

### 6.1 Local Database (Room)

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Session    │────<│     Run      │────<│    Media     │
│              │     │              │     │              │
│ id           │     │ id           │     │ id           │
│ event_name   │     │ session_id   │     │ run_id       │
│ discipline   │     │ run_number   │     │ type (photo/ │
│ date         │     │ start_time   │     │   video)     │
│ location_gps │     │ end_time     │     │ file_uri     │
│ weather      │     │              │     │ bib_number   │
└─────────────┘     └──────────────┘     │ star_rating  │
                                          │ thumbnail_uri│
                                          │ metadata_json│
                                          └──────┬───────┘
                                                 │
                          ┌──────────────────────┼──────────────────┐
                          │                      │                  │
                   ┌──────┴──────┐      ┌───────┴──────┐  ┌───────┴───────┐
                   │ Annotation  │      │    Split     │  │  PoseData     │
                   │             │      │              │  │               │
                   │ id          │      │ id           │  │ id            │
                   │ media_id    │      │ run_id       │  │ media_id      │
                   │ type        │      │ gate_number  │  │ frame_number  │
                   │ timestamp   │      │ timestamp    │  │ keypoints_json│
                   │ data_json   │      │ source       │  │ angles_json   │
                   └─────────────┘      │ (manual/     │  └───────────────┘
                                        │  electronic) │
                                        └──────────────┘

┌─────────────┐     ┌─────────────┐     ┌──────────────┐
│   Athlete   │────<│ AthleteMedia │     │    Drill     │
│             │     │  (junction)  │     │              │
│ id          │     │ athlete_id   │     │ id           │
│ name        │     │ media_id     │     │ category     │
│ bib_numbers │     └──────────────┘     │ title        │
│ age_group   │                          │ description  │
│ discipline  │     ┌─────────────┐      │ media_id     │
│ notes       │────<│ ErrorPattern │     └──────────────┘
│ created_at  │     │             │
└─────────────┘     │ id          │
                    │ athlete_id  │
                    │ pattern_type│
                    │ description │
                    │ first_seen  │
                    │ last_seen   │
                    │ trend       │
                    └─────────────┘
```

### 6.2 File Storage Layout

```
/sdcard/GateShot/
├── sessions/
│   └── 2026-01-25_kitzbuehel-wc_gs/
│       ├── session.json                    # session metadata
│       ├── run_01/
│       │   ├── photos/
│       │   │   ├── bib07_burst001_001.dng  # RAW
│       │   │   ├── bib07_burst001_001.jpg  # JPEG
│       │   │   └── ...
│       │   ├── video/
│       │   │   ├── bib07_run01.mp4
│       │   │   ├── bib07_run01_slomo.mp4
│       │   │   └── bib07_run01_stabilized.mp4
│       │   ├── annotations/
│       │   │   ├── bib07_run01_voiceover_001.aac
│       │   │   └── bib07_run01_drawings.json
│       │   └── analysis/
│       │       ├── bib07_run01_pose.csv
│       │       └── bib07_run01_turns.json
│       └── run_02/
│           └── ...
├── athletes/
│   └── profiles.json
├── drills/
│   └── edge_work_gs_reference.mp4
├── exports/
│   └── reports/
│       └── 2026-01-25_session_report.pdf
├── config/
│   ├── presets/
│   │   └── custom_slalom_night.json
│   └── app_config.json
└── .cache/
    ├── thumbnails/
    └── proxies/
```

### 6.3 Configuration Format

Discipline presets are stored as composite configs that span multiple modules:

```json
{
  "preset_name": "slalom_gs",
  "display_name": "Slalom / Giant Slalom",
  "version": 1,
  "modules": {
    "camera": {
      "resolution": "4K",
      "frame_rate": 60
    },
    "burst": {
      "mode": "short",
      "frame_count": 8,
      "trigger": "shutter_or_zone"
    },
    "snow_exposure": {
      "ev_bias": 1.5,
      "flat_light_auto": true
    },
    "af": {
      "mode": "continuous_predictive",
      "reacquisition_speed": "fast",
      "occlusion_hold": true
    },
    "stabilization": {
      "ois": "standard",
      "eis": "off"
    },
    "lens": {
      "default_zoom": "3x"
    }
  },
  "user_overrides": {}
}
```

---

## 7. Platform Layer

The platform layer abstracts Android hardware and OS APIs. Modules never call Android APIs directly — they go through platform interfaces that can be mocked for testing.

```kotlin
// Platform interfaces (selected)
interface CameraPlatform {
    fun openCamera(config: CameraConfig): CameraSession
    fun getSupportedConfigs(): List<CameraConfig>
    fun getFrameRateRange(): Pair<Int, Int>
}

interface SensorPlatform {
    fun getMagnetometerReading(): Flow<MagnetometerData>
    fun getGyroscopeReading(): Flow<GyroscopeData>
    fun getTemperature(): Float?
    fun getGpsLocation(): Location?
}

interface StoragePlatform {
    fun getAvailableSpace(): Long
    fun writeFile(uri: Uri, data: ByteArray)
    fun readFile(uri: Uri): ByteArray
    fun getExternalStoragePaths(): List<String>
}

interface NpuPlatform {
    fun loadModel(modelPath: String): InferenceSession
    fun isNpuAvailable(): Boolean
    fun getNpuCapabilities(): NpuCapabilities
}

interface BluetoothPlatform {
    fun scan(serviceUuids: List<UUID>): Flow<BluetoothDevice>
    fun connect(device: BluetoothDevice): BluetoothConnection
}
```

---

## 8. Data Flow Examples

### 8.1 Shutter Press → Photo Saved

```
User presses shutter (or volume-up button)
    │
    ▼
UI Layer publishes ShutterPressed event
    │
    ├──▶ PreBuffer Module: flushes ring buffer (1.5s of frames before press)
    │
    ├──▶ Burst Module: starts burst capture (8 frames at max rate)
    │       │
    │       ▼
    │    BurstCompleted event (30 frames: 22 buffer + 8 burst)
    │       │
    │       ├──▶ Burst Culling Module: ranks frames, publishes FramesAnalyzed
    │       │
    │       ├──▶ Bib Detection Module: scans for bib numbers, publishes BibDetected
    │       │
    │       └──▶ Session Module: files frames into current run folder
    │
    └──▶ Snow Exposure Module: (continuously running) adjusts EV for next frame
```

### 8.2 Coach Reviews a Run

```
Coach taps "Replay" after run ends
    │
    ▼
UI calls coach/replay/load with latest clip
    │
    ▼
Replay Module loads clip, provides frame-accurate player
    │
    ▼
Coach taps "Compare" and selects a previous run
    │
    ▼
UI calls coach/compare/split with both clip IDs
    │
    ▼
Replay Module renders split-screen view
    │
    ▼
Coach taps "Sync by Gate" and selects gate 5
    │
    ▼
UI calls coach/compare/sync/gate with gate_number=5
    │
    ▼
Replay Module aligns both clips to gate 5 passage
    │
    ▼
Coach pauses and taps "Measure"
    │
    ▼
UI calls coach/analysis/angles/measure with frame data
    │
    ▼
Analysis Module computes knee angle, displays overlay
    │
    ▼
Coach records voice annotation
    │
    ▼
UI calls coach/annotate/voiceover/start and /stop
    │
    ▼
Annotation Module saves audio pinned to timeline position
```

### 8.3 Gate-Zone Auto-Trigger

```
Photographer taps two points on viewfinder to set trigger zones
    │
    ▼
UI calls af/zone/add for each zone
    │
    ▼
AF Module registers zones and begins monitoring frame stream
    │
    ▼
Racer enters Zone 1
    │
    ▼
AF Module publishes ZoneTriggered(zone=1)
    │
    ├──▶ Burst Module: fires short burst
    │
    └──▶ PreBuffer Module: flushes buffer around trigger moment
    │
    ▼
Racer enters Zone 2 (0.8s later)
    │
    ▼
AF Module publishes ZoneTriggered(zone=2)
    │
    └──▶ Same burst + buffer flow
    │
    ▼
Result: consistent, perfectly-timed captures at both gates
        for every racer, automatically
```

---

## 9. Performance Targets

| Operation | Target | Constraint |
|-----------|--------|------------|
| App cold start to shooting-ready | < 2s | Including camera init |
| Hibernate wake to shooting-ready | < 300ms | Camera pipeline stays warm |
| Shutter press to first frame saved | < 50ms | Excluding pre-buffer (which is instant) |
| Burst culling (30 frames) | < 2s | NPU inference |
| Bib detection (single frame) | < 200ms | NPU inference |
| Pose estimation (single frame) | < 100ms | NPU inference |
| Pose estimation (full clip, 60s at 30fps) | < 60s | Background processing |
| Pre-capture buffer memory | < 1.5GB | Ring buffer at 4K 30fps |
| Video recording start latency | < 100ms | From trigger to first frame recorded |
| Split-screen render | 60fps | GPU compositing |
| Auto-clip segmentation (60min session) | < 30s | Audio + motion analysis |
| Session report generation | < 15s | Aggregation + PDF render |

---

## 10. Testing Strategy

### 10.1 Module Isolation

Every module is testable in isolation because:
- Modules communicate only through events and API endpoints.
- Platform layer is abstracted behind interfaces — mock implementations for testing.
- No module holds a direct reference to another module.

```kotlin
// Example: testing Burst Culling without a real camera
@Test
fun burstCulling_ranksSharpFramesHigher() {
    val mockFrames = generateTestFrames(sharp = 5, blurry = 25)
    val cullingModule = BurstCullingModule(mockNpu = FakeNpu())

    val result = cullingModule.handleCullRequest(CullRequest(mockFrames))

    // Top 5 should all be the sharp frames
    assertThat(result.ranked.take(5)).allMatch { it.sharpnessScore > 0.8 }
}
```

### 10.2 Integration Testing

- **Camera integration:** Real device tests on Oppo Find X9 Pro hardware. Automated via Android Test Orchestrator.
- **Event flow tests:** Verify that publishing event A results in the expected cascade through modules B, C, D.
- **Endpoint contract tests:** Verify request/response schemas for every API endpoint.

### 10.3 Field Testing Protocol

- **Cold chamber testing:** Verify app behavior at -10C, -20C (battery, touch, camera performance).
- **Bright light testing:** Viewfinder visibility, exposure accuracy on snow-covered slopes.
- **Real-ski-racing sessions:** Invite club coaches to use the app during actual training. Collect crash reports, UX feedback, and timing data.

---

## 11. Build & Module Dependency Graph

```
                    ┌──────────┐
                    │   Core   │
                    └────┬─────┘
                         │
          ┌──────────────┼──────────────┐
          │              │              │
    ┌─────┴─────┐  ┌─────┴─────┐  ┌────┴──────┐
    │ Platform  │  │  Capture  │  │ Processing│
    │  Layer    │  │  Modules  │  │  Modules  │
    └───────────┘  └─────┬─────┘  └─────┬─────┘
                         │              │
                         │    ┌─────────┘
                         │    │
                   ┌─────┴────┴───┐
                   │   Coaching   │
                   │   Modules    │
                   └──────────────┘
```

**Dependency rules:**
- Core depends on nothing (except Kotlin stdlib).
- Platform Layer depends on nothing (except Android SDK).
- Capture Modules depend on Core + Platform Layer.
- Processing Modules depend on Core + Platform Layer.
- Coaching Modules depend on Core + Platform Layer + may consume events from Capture/Processing.
- **No module depends on the UI layer.**
- **No horizontal dependencies between modules in the same group** (Burst does not import Snow Exposure). They communicate via events only.

### Gradle Module Structure

```
:app                          # Android app shell, DI wiring, UI
:core                         # Core kernel (endpoint registry, event bus, etc.)
:platform                     # Platform abstraction layer
:capture:camera               # Camera module
:capture:burst                # Burst module
:capture:prebuffer            # Pre-capture buffer module
:capture:video                # Video recording module
:capture:audio                # Audio capture and trigger module
:capture:lens                 # Lens detection and management
:capture:voice                # Voice command recognition
:processing:snow-exposure     # Snow/light exposure compensation
:processing:burst-culling     # AI burst frame ranking
:processing:bib-detection     # OCR bib number recognition
:processing:stabilization     # Video stabilization and crop-follow
:processing:autoclip          # Run segmentation
:processing:metadata          # EXIF/XMP enrichment
:processing:export            # Sharing, cloud backup, federation export
:coaching:replay              # Instant replay and comparison
:coaching:pose                # Pose estimation and skeleton
:coaching:annotation          # Voice-over, telestrator, drawing
:coaching:timing              # Split timing and correlation
:coaching:athlete             # Athlete profile management
:coaching:analysis            # Turn analysis, consistency, error detection
:coaching:report              # Session and progress reports
:coaching:team                # Team feed, sharing, multi-camera
:ml-models                   # Shared ML model assets (bib, pose, culling)
```

---

## 12. Technology Stack Summary

| Layer | Technology | Rationale |
|-------|-----------|-----------|
| Language | Kotlin | Native Android, coroutines for async, type safety |
| UI | Jetpack Compose | Modern declarative UI, performant, glove-mode friendly custom components |
| Camera | Camera2 + CameraX | Full manual control (Camera2) with lifecycle convenience (CameraX) |
| DI | Hilt (Dagger) | Compile-time dependency injection, module-aware |
| Database | Room | SQLite abstraction, reactive queries, type-safe |
| Async | Kotlin Coroutines + Flow | Structured concurrency, backpressure-aware streams |
| ML inference | TensorFlow Lite + NNAPI | On-device inference with NPU acceleration |
| Video playback | ExoPlayer (Media3) | Frame-accurate seeking, variable-speed playback |
| Image processing | RenderScript / Vulkan Compute | GPU-accelerated image processing |
| Bluetooth | Android Bluetooth API | Timing system connectivity |
| Serialization | kotlinx.serialization | Fast, compile-time safe JSON handling |
| Testing | JUnit5 + Turbine + MockK | Unit + Flow testing + mocking |
| Build | Gradle KTS with convention plugins | Multi-module build management |

---

*This document defines the architectural foundation. Implementation begins with Core + Camera Module + one discipline preset (Slalom/GS) to validate the architecture end-to-end before building out remaining modules.*
