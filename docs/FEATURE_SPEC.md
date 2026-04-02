# GateShot — Feature Specification
## Oppo Find X9 Pro Dedicated Ski Racing Camera App

**Version:** 0.1 — Initial Feature Definition
**Date:** 2026-03-31
**Hardware target:** Oppo Find X9 Pro (16GB/512GB, Dimensity 9500, 200MP telephoto) + Hasselblad Teleconverter Kit (230mm/10x optical)

---

## App Architecture: Dual-Mode Design

The app operates in two modes, controlled by a persistent toggle in the main UI:

| Mode | Target user | What's visible |
|------|-------------|----------------|
| **Shoot** | Photographer / videographer | Full capture engine, field UX, organization & export |
| **Shoot + Coach** | Coach / analyst | Everything in Shoot, plus coaching suite overlay (analysis, athlete management, session tools) |

The toggle is accessible from the top bar at all times. Coaching features appear as an additional layer — they never replace or hide shooting features. A coach is always a shooter first.

---

## 1. Shooting Modes — Discipline-Aware Presets

Each preset configures shutter speed, burst behavior, AF strategy, exposure bias, and stabilization as a coherent package. All values are defaults — the user can override any parameter individually.

### 1.1 Slalom / Giant Slalom

- **Context:** Subject weaving through gates, 3-8m distance, rhythmic motion, brief visibility windows between gate panels.
- **Shutter priority:** 1/1000s (GS) to 1/2000s (slalom).
- **Burst mode:** Short bursts (5-8 frames) triggered per gate passage.
- **AF strategy:** Rapid re-acquisition. Subject disappears behind gate panel, reappears 0.3-0.5s later. AF must not hunt on the gate panel or netting.
- **Stabilization:** Standard OIS. Shooter is usually static, close to the action.
- **Exposure:** +1.0 to +1.5 EV snow compensation. Auto shadow-fill when racer is in shade with bright snow behind.

### 1.2 Speed Events (Downhill / Super-G)

- **Context:** Subject at 90-150 km/h, typically 10-30m away. Longer visibility windows but extreme speed.
- **Shutter priority:** 1/2000s to 1/4000s.
- **Burst mode:** Continuous high-speed burst for 2-3 seconds.
- **AF strategy:** Predictive continuous tracking. The subject moves across the frame fast — AF must lead the motion.
- **Stabilization:** Maximum OIS + EIS combined. Telephoto amplifies shake.
- **Exposure:** +1.5 to +2.0 EV. Speed venues often have long open sections with full snow exposure.

### 1.3 Panning

- **Context:** Intentional motion blur on background, sharp subject. Creates the sense of speed.
- **Shutter priority:** 1/125s to 1/250s (user-adjustable).
- **Stabilization:** Gyro-assisted, locked to horizontal axis only. Vertical stabilization active, horizontal stabilization disabled to allow the pan.
- **AF strategy:** Continuous tracking with central point priority.
- **Burst mode:** Continuous during pan gesture. Smart culling surfaces the sharpest subject frames.

### 1.4 Finish Area

- **Context:** Athletes arriving, celebrations, emotions, podium. Mixed action and portrait.
- **Orientation:** Portrait-biased (prompt to rotate if landscape detected).
- **AF strategy:** Face and eye priority.
- **Exposure:** Softer, skin-tone aware metering. Reduced snow compensation (+0.5 EV) as finish areas often have dark backgrounds (crowds, banners).
- **Burst mode:** Short bursts or single-shot. Emphasis on expression timing.

### 1.5 Atmosphere / Course Overview

- **Context:** Wide shots of the course, mountain scenery, crowds, venue setup. Editorial and social media content.
- **Orientation:** Landscape.
- **AF strategy:** Landscape / infinity focus.
- **Exposure:** Full dynamic range. HDR enabled for sky + snow scenes.
- **Stabilization:** Standard.
- **Lens:** Wide (no telephoto attachment).

### 1.6 Training Analysis (Video-First)

- **Context:** Coaching-oriented capture. Priority is usable footage over artistic quality.
- **Resolution:** 4K at 120fps Dolby Vision (default — the X9 Pro supports this natively), with 1080p@240fps and 720p@480fps slow-motion options.
- **Framing:** Wider than competition shooting to capture full body and gate context.
- **AF strategy:** Continuous tracking, permissive (don't rack focus aggressively — coaches prefer consistent framing over cinematic focus pulls).
- **Audio:** Record ambient (start beeps, coach shouts) — useful for syncing and annotation later.
- **Auto-clip:** Detect individual runs by motion pattern or audio cue. Segment continuous recording into per-run clips automatically.

---

## 2. Snow & Light Management

### 2.1 Automatic Snow Exposure Compensation

Every camera meters snow as grey. The app applies intelligent positive EV bias:

- Detects snow-dominant frames via scene analysis.
- Applies +1.0 to +2.0 EV based on snow coverage percentage.
- Preserves highlight detail — avoids pure white blowout.
- Per-preset defaults (see section 1), user-overridable.

### 2.2 Flat Light Mode

Overcast days with diffuse light eliminate shadows and contrast. The mountain becomes a white-on-white blur.

- Real-time viewfinder enhancement: boost micro-contrast and edge definition so the shooter can *see* the racer against the snow.
- Subtle color grading: warm shift on subject, cool shift on snow, to create separation.
- Applies to both capture and viewfinder independently (viewfinder can be enhanced even if capture stays neutral).

### 2.3 Sun / Shadow Transition Handling

Racers cross from shade into direct sun constantly. This is a 3-4 stop exposure swing in a fraction of a second.

- Predictive exposure ramping: analyze the course ahead of the subject and pre-adjust.
- Real-time HDR tuned for fast subjects: no ghosting, no frame blending artifacts.
- Option: shoot bracketed burst (+0 / +1.5) and merge in post, or let the app auto-merge on capture.

### 2.4 Backlit / Golden Hour Mode

Late afternoon training. Low sun behind the racer. Classic silhouette risk.

- Expose for the subject, not the sky.
- Fill-light simulation via computational photography.
- Optional: embrace the silhouette and expose for the sky (artistic toggle).

---

## 3. Autofocus & Subject Tracking

### 3.1 Gate-Zone Predictive AF (Signature Feature)

The defining advantage over a generic camera app.

- Tap a gate (or a point on the course) in the viewfinder.
- The app pre-focuses on that zone.
- When motion enters the zone, the app fires a burst automatically.
- Result: perfectly focused shots at the exact gate passage moment, every time.
- Supports multiple zones (see 3.4).

### 3.2 Bib Number Detection

- AI-powered OCR reads bib numbers from captured frames.
- Auto-tags every burst / clip with the racer's bib number.
- Works with standard FIS bib layouts.
- Fallback: manual bib entry from a quick number pad.
- At a WC event with 60+ starters, this eliminates hours of manual photo sorting.

### 3.3 Continuous Tracking with Occlusion Recovery

- Maintains subject lock when the racer disappears behind gate panels, safety netting, or other obstacles.
- Predicts re-emergence position based on trajectory and speed.
- Does not hunt-focus on the obstacle — holds last known subject distance or predicts the next.

### 3.4 Multi-Zone Trigger

- Set 2-5 trigger zones along a visible section of the course.
- The app captures each racer at the same spots automatically.
- Produces consistent comparison shots across all starters.
- Essential for coaching ("show me every athlete at gate 14 from today").

---

## 4. Burst & Timing Intelligence

### 4.1 Pre-Capture Buffer

The most requested feature by every sports photographer who's ever missed the moment.

- The app continuously buffers the last 1.5-2 seconds of frames in memory (configurable).
- When the shutter is pressed, the saved image sequence starts *before* the press.
- Compensates for human reaction time (~200-300ms).
- Works in both photo and video modes.

### 4.2 Smart Burst Culling

A 30-frame burst at a gate passage produces 25 near-identical frames and 5 that matter.

- AI ranks frames by: sharpness (subject, not background), composition (subject position in frame), facial expression (if visible), body position (full extension, edge angle, dynamic pose).
- Surfaces top 3-5 frames immediately after burst.
- Remaining frames kept but visually de-prioritized.
- One-tap "keep best, discard rest" cleanup.

### 4.3 Audio Trigger

- Detect the electronic start gate beep to auto-start recording.
- Detect cowbells, air horns, or crowd roar for finish-area auto-capture.
- Configurable audio trigger sensitivity and pattern.
- Essential for solo shooting during training when you can't watch the start and shoot simultaneously.

### 4.4 Interval / Timing-System Sync

- Bluetooth or audio connection to timing systems (ALGE, Microgate, Tag Heuer).
- Auto-trigger capture at known split points.
- Overlay split times on corresponding frames (see section 9.9).

---

## 5. Video-Specific Features

### 5.1 High Frame Rate Capture

- 4K at 120fps with Dolby Vision: smooth slow-motion at full resolution for technique review.
- 1080p at 240fps: ultra slow-motion for edge angle, boot pressure, hand position analysis.
- 720p at 480fps: maximum slow-motion for extreme detail (edge engagement, ski flex).
- 4K Log recording at 120fps for studio-grade color grading in post.
- Quick toggle between real-time and slow-motion from the shooting interface (one tap, not buried in settings).

### 5.2 Auto-Clip by Run

- Continuous recording mode for a full training session (60-90 minutes).
- AI detects individual runs by: audio cue (start beep), motion pattern (sudden high-speed subject entry), or manual marker.
- Auto-segments into individual clips, named by run number and bib (if detected).
- Original continuous file preserved; clips are non-destructive references.

### 5.3 Stabilized Crop-and-Follow

- Shoot at 4K with a wide / static framing (phone on tripod or monopod).
- The app outputs a cropped 1080p frame that smoothly tracks and follows the racer through the scene.
- Produces a "camera operator" feel from a locked-off phone.
- Adjustable crop tightness: wide context vs. tight on the athlete.

### 5.4 Quick Slow-Motion Scrub

- In review mode, drag a finger across the timeline to scrub at variable speed.
- Pinch to zoom the timeline for frame-by-frame precision.
- Double-tap any frame to set an in/out point for export.

---

## 6. Cold Weather & Field UX

### 6.1 Glove Mode

- All touch targets minimum 14mm (standard is 7-9mm).
- Swipe gestures for primary actions: swipe up = switch mode, swipe left/right = review.
- No small toggles, sliders, or hamburger menus in shooting mode.
- Increased touch sensitivity for capacitive glove tips.

### 6.2 One-Handed Operation

- All critical shooting functions reachable with the thumb of the holding hand.
- Volume buttons mapped to: shutter (vol up), mode switch (vol down).
- Squeeze gesture (if hardware supports) for secondary action.
- No two-handed pinch or multi-touch required for any shooting function.

### 6.3 Battery & Thermal Management

- Real-time battery temperature monitoring.
- Warning at 5C: suggest insulating the phone or keeping it in a jacket pocket between runs.
- Critical warning at 0C: reduce screen brightness, pause non-essential background processing.
- Estimated remaining shooting time (based on current mode, temperature, and usage pattern).
- Quick-hibernate: lock the app into a minimal state between runs to conserve battery.

### 6.4 Quick Review Between Runs

In a race, there's approximately 20-40 seconds between starters.

- One-swipe access to the last burst / clip.
- Large keep / trash buttons.
- Auto-return to shooting mode after 5 seconds of inactivity (configurable).
- No accidental mode changes — confirm before leaving shooting mode to settings or gallery.

### 6.5 Voice Commands

- "Start recording" / "Stop recording"
- "Mark that" — flag the current moment for review
- "Switch to slow-mo" / "Switch to photo"
- "Next mode" — cycle through discipline presets
- Works with wind noise reduction active.
- Optional: voice activation ("Hey GateShot, ...") for fully hands-free operation.

### 6.6 High-Visibility Viewfinder

- High-contrast UI elements visible in direct sunlight on snow.
- Optional dark viewfinder surround to reduce glare.
- Critical info (mode, remaining shots/time, battery) always visible, never hidden.
- Color-coded recording indicator visible from 1m away (for when the phone is on a monopod above your head).

---

## 7. Hasselblad Teleconverter Kit Integration

The Hasselblad Professional Teleconverter (3.28x magnification, 13 elements in 3 groups, 3 ED elements, 9-layer AR coating, 180g) attaches magnetically to the dedicated protective case. It extends the 200MP telephoto from 70mm/5x to **230mm/10x optical zoom**.

### 7.1 Automatic Lens Detection

- Detect when the magnetic teleconverter is mounted (magnetometer change from magnetic case alignment).
- Auto-switch to "Hasselblad Telephoto" mode (matches stock camera app behavior).
- Auto-activate lens deconvolution pipeline (super-resolution module) to counteract teleconverter softness.
- Notification: "Hasselblad teleconverter detected — 230mm / 10x optical."

### 7.2 Telephoto-Specific Stabilization

- At 230mm, camera shake is magnified 10x vs wide angle. Critical for ski racing.
- Engage maximum OIS (native telephoto OIS) + EIS combined pipeline.
- Predictive stabilization: analyze gyroscope pattern and pre-compensate.
- Option: tripod/monopod detection — disable stabilization to avoid fighting the mount.

### 7.3 Quick Focal Length Toggle

- One-tap switch between zoom positions:
  - **1x** (23mm wide — course overview, atmosphere)
  - **5x** (70mm telephoto native — slalom/GS close range)
  - **10x** (230mm with teleconverter — speed events, distant subjects)
  - **13.2x** (max lossless crop from 200MP — tightest usable framing)
- Smooth digital zoom bridge between positions.
- Beyond 13.2x: super-resolution module auto-engages for quality recovery.

### 7.4 Lens Profile Correction

- Automatic vignette correction calibrated for the Hasselblad teleconverter optics.
- Lateral chromatic aberration correction (radial R/B channel shift from 13-element design).
- PSF deconvolution to recover sharpness lost through the additional optical elements.
- All corrections applied automatically when teleconverter is detected — zero user action required.

---

## 8. Organization & Workflow

### 8.1 Session Structure

Automatic organization hierarchy:

```
Event Name (e.g., "Kitzbühel WC" or "Club Training")
  └── Date (2026-01-25)
       └── Discipline (GS)
            └── Run 1
                 ├── Bib 07 — 12 photos, 1 clip
                 ├── Bib 14 — 8 photos
                 └── ...
            └── Run 2
                 └── ...
```

- Auto-created on session start. User enters event name and discipline; the rest is automatic.
- Bib-based sorting within each run (via bib detection or manual tag).
- Browsable by any level of the hierarchy.

### 8.2 Bib-Based Tagging & Filtering

- Every image and clip tagged with bib number (auto or manual).
- Filter gallery by athlete: "Show me everything from bib 7 today."
- Cross-session athlete search: "Show me all shots of bib 7 from this week."
- Bulk operations per athlete: export all, delete all, rate all.

### 8.3 Quick Share

- One-tap export to messaging apps (WhatsApp, Telegram, Signal).
- Presets: "Coach share" (1080p, moderate compression, fast), "Press share" (full resolution, metadata intact), "Social" (cropped, branded, compressed).
- Optional watermark overlay (customizable: photographer name, event, date).
- Batch share: select multiple images, export as a zip or album link.

### 8.4 RAW + JPEG Workflow

- Shoot RAW for keepers you'll edit later on desktop.
- Simultaneous JPEG for instant sharing.
- Star-rating system: 1-5 stars, applied in-app.
- Export filters: "Export all 4+ star RAW files from today."

### 8.5 Cloud Backup

- Auto-upload when connected to WiFi (hotel, lodge, team van).
- Priority upload: starred/flagged content first.
- Configurable destination: Google Drive, team NAS, FTP server, custom cloud.
- Resume interrupted uploads.
- End-of-day notification: "142 files backed up. 23 remaining — estimated 8 minutes on current connection."

### 8.6 Metadata Enrichment

- GPS coordinates embedded per shot (locate yourself on the course).
- Weather conditions at capture time (temperature, cloud cover, wind — pulled from phone sensors + weather API).
- Discipline, event name, and bib number in EXIF/XMP fields for compatibility with Lightroom, Photo Mechanic, and other desktop tools.

---

## 9. Coaching Suite (Toggle: Shoot + Coach Mode)

*All features in this section are visible only when the Coach toggle is active. They overlay on top of the full shooting interface — nothing from sections 1-8 is hidden or replaced.*

### 9.1 Instant Replay Station

- As soon as a run ends (detected by audio cue, manual tap, or timing signal), the clip is immediately available for review.
- Full-screen playback with slow-motion scrub.
- Designed for the 60-second window between "athlete finishes" and "athlete needs to see the video before hiking or chairing back up."

### 9.2 Live Comparison Overlay (Ghost)

- Select a reference run (best run of the day, a previous personal best, or a WC athlete's published footage).
- Overlay a ghosted semi-transparent silhouette of the reference on the current video.
- See line differences, timing gaps, and body position deltas in real time.
- Alignment: synced by gate passage, not by timestamp.

### 9.3 Split-Screen Sync by Gate

- Two runs displayed side-by-side.
- Synchronized at each gate passage — not by absolute time. If racer A is 0.4s behind racer B at gate 8, the split-screen still shows both athletes at gate 8 simultaneously.
- Supports: same athlete run-to-run, athlete-to-athlete, or athlete-to-reference.
- Swipe to advance gate-by-gate.

### 9.4 Freeze & Measure

- Pause on any frame.
- Overlay angle measurement tools:
  - Knee angle (flexion/extension)
  - Hip angle (inclination)
  - Upper body / torso lean
  - Ski edge angle relative to slope
  - Pole plant position
- Drag handles to position measurement lines. Numerical readout displayed.
- Save annotated frame as an image for later reference.

### 9.5 Skeleton / Pose Estimation Overlay

- AI pose estimation renders a stick-figure skeleton over the athlete.
- Track joint angles across a full turn arc (not just one frame).
- Exportable as:
  - Annotated video (skeleton overlaid)
  - Data CSV (joint angles per frame)
  - Graph (e.g., knee angle over time through a turn)
- Compare skeletons between runs or athletes.

### 9.6 Turn Analysis Dashboard

For each gate or turn in a run:

| Metric | Source |
|--------|--------|
| Entry speed (estimated) | Frame-to-frame motion analysis |
| Apex body position | Pose estimation |
| Line choice (high / optimal / low) | Trajectory analysis vs. gate position |
| Transition time to next turn | Frame count between edge changes |
| Edge angle at apex | Pose estimation + frame analysis |

- Visual scorecard per run.
- Comparative view across multiple runs.

### 9.7 Consistency Tracker

- Input: 5-20 runs on the same course (training day).
- Output: per-gate variability analysis.
  - "Gate 7: highly consistent (low variability in line and time)."
  - "Gate 12: high variability — investigate technique at this point."
- Highlights the gates where the athlete is most inconsistent — these are the training priorities.
- Trend over sessions: "Gate 12 variability has decreased 40% over the last 3 sessions."

### 9.8 Error Pattern Detection

- AI analysis across multiple runs to identify recurring technical issues:
  - "Inside hand drops at every left turn."
  - "Late pressure initiation on gates 8-12."
  - "Back seat in transition after steep-to-flat terrain change."
  - "Hip rotation insufficient on left turns vs. right turns."
- Patterns that a human eye might miss across a full day of training become visible in aggregate.
- Generates actionable alerts: "Focus drill suggestion: inside hand position, left turns."

### 9.9 Timing Integration

#### 9.9.1 Manual Split Entry
- No electronic timing available? Coach taps the screen at each split point.
- App records timestamps and overlays them on the video.
- Accuracy: approximately +/- 0.1s (human reaction time), but useful for relative comparison between runs.

#### 9.9.2 Electronic Timing Sync
- Bluetooth / WiFi connection to timing systems (ALGE, Microgate, Tag Heuer).
- Overlay actual split times on corresponding video frames.
- See exactly what 0.15s looks like between two athletes' turns at gate 22.

#### 9.9.3 Time-to-Technique Correlation
- Automatic pairing of timing deltas with video evidence.
- "Run 3 was 0.4s slower than run 1. Here's where the time was lost." — jumps directly to the relevant gate with video comparison.
- Per-section analysis: "Gained 0.1s in section 1 (better line), lost 0.5s in section 2 (late edge change) — net delta: -0.4s."

### 9.10 Voice-Over Annotation

- Coach records audio commentary directly on a clip.
- Timeline-pinned: audio is attached to specific moments, not the whole clip.
- Playable by the athlete later (on their own device or via shared link).
- "Watch your inside hand here... now see how your hips open up... this is where you lost the 0.2s."

### 9.11 Telestrator / Draw-on-Frame

- Draw on video frames in real time or on paused frames.
- Tools: freehand, straight line, arrow, circle, angle arc.
- Colors: high-visibility preset palette (red, yellow, green on white snow).
- Drawings persist on the clip as an overlay layer (non-destructive, toggleable).
- Common use: circle the inside hand, draw the ideal line, mark where edge change should happen.

### 9.12 Ideal Line Drawing

- Load or photograph a course overview (from the side of the slope).
- Coach traces the ideal racing line on the image.
- Overlay the athlete's estimated actual line (from video position tracking).
- Visual delta between ideal and actual.
- Save per-course for reuse across training sessions.

### 9.13 Drill Library

- Tag any clip as a drill reference: "This is what a proper angulation looks like at GS speed."
- Organize drills by category: edge work, pole plant, transition, body position, line choice.
- Reusable across athletes and seasons.
- Link drills to detected errors: "You have an inside hand issue — here's the reference drill."

### 9.14 Athlete Profiles

Each racer has a persistent profile:

- **Bio:** Name, bib number(s), age group, discipline focus.
- **Media:** All captured clips and photos, filterable by date and event.
- **Technical notes:** Coach's free-text notes on strengths, weaknesses, and focus areas.
- **Error log:** AI-detected recurring issues, with trend (improving / stable / regressing).
- **Progress timeline:** Before/after comparisons across the season. Visual proof of development.
- **Drill assignments:** Which drills are assigned and whether reference clips are attached.

### 9.15 Session Report

Auto-generated end-of-day summary:

- Number of runs per athlete.
- Best and worst runs (by timing or coach rating).
- Flagged moments and coach annotations.
- AI-detected error patterns from the session.
- Attached key frames and annotated clips.
- Exportable as PDF (for parents, federation, school).
- Exportable as data package (for federation databases).

### 9.16 Before / After Progress View

- Pull up the same drill, gate, or technique from two different dates.
- Side-by-side or overlay comparison.
- Visual proof of progression (or regression).
- Linked to athlete profile timeline.
- Use case: parent meetings, federation reviews, athlete motivation.

### 9.17 Multi-Camera Merge

- Two coaches filming the same run from different angles (e.g., front and side).
- Sync clips by audio (start beep) or manual alignment.
- Multi-angle review: switch between angles or view picture-in-picture.
- Essential for understanding 3D body position from 2D video.

### 9.18 Remote Coaching / Async Review

- Share a clip + annotations with a remote coach, biomechanics expert, or federation analyst.
- Recipient can add their own annotations and send back.
- Threaded conversation on a clip: "Look at frame 142" — "I see it, here's my suggestion."
- Works across time zones. A coach in Europe can review and annotate an athlete's session in North America overnight.

### 9.19 Team Feed

- Shared space where all coaches on a team see flagged clips and notes from the day.
- No more "did you see racer 7's third run?" — it's tagged, annotated, and in the feed.
- Filterable by athlete, discipline, priority.
- Push notifications for high-priority flags.

### 9.20 Federation Export Format

- Standardized video export formats matching national federation requirements.
- Metadata fields compatible with FIS databases.
- Naming conventions per federation standards.
- Batch export for end-of-camp or end-of-season reporting.

---

## 10. Priority Roadmap

### Phase 1 — MVP (Capture Foundation)

The app is usable for daily shooting at a ski race or training session.

- [ ] Discipline presets (Slalom/GS, Speed, Training Analysis)
- [ ] Snow exposure compensation (automatic)
- [ ] Pre-capture buffer (1.5s)
- [ ] Glove-friendly UI with one-handed operation
- [ ] Session organization (event / date / discipline / run)
- [ ] Quick review between runs
- [ ] Hasselblad telephoto auto-detection
- [ ] RAW + JPEG capture
- [ ] Basic voice commands (start / stop / mark)
- [ ] Instant replay station (coaching toggle)
- [ ] Voice-over annotation (coaching toggle)
- [ ] Manual split entry (coaching toggle)

### Phase 2 — Smart Shooting

The app becomes significantly better than any generic camera app for ski racing.

- [ ] Gate-zone predictive AF trigger
- [ ] Smart burst culling (AI frame ranking)
- [ ] Audio trigger (start beep detection)
- [ ] Flat light mode
- [ ] Sun/shadow transition handling
- [ ] Stabilized crop-and-follow (4K to 1080p)
- [ ] Auto-clip by run
- [ ] Quick share with presets
- [ ] Split-screen sync by gate (coaching)
- [ ] Freeze & measure angles (coaching)
- [ ] Athlete profiles (coaching)
- [ ] Consistency tracker (coaching)
- [ ] Session report (coaching)

### Phase 3 — AI Analysis & Connected Ecosystem

The app becomes a competitive advantage for athletes and teams.

- [ ] Bib number detection & auto-tagging
- [ ] Continuous tracking with occlusion recovery
- [ ] Multi-zone trigger
- [ ] Skeleton / pose estimation overlay
- [ ] Turn analysis dashboard
- [ ] Error pattern detection
- [ ] Live comparison overlay (ghost run)
- [ ] Time-to-technique correlation
- [ ] Electronic timing system integration
- [ ] Telestrator / draw-on-frame
- [ ] Ideal line drawing
- [ ] Multi-camera merge
- [ ] Remote coaching / async review
- [ ] Team feed
- [ ] Cloud backup with priority queuing
- [ ] Federation export format
- [ ] Drill library
- [ ] Before/after progress view

---

## 11. Technical Considerations (Preliminary)

### Hardware — Oppo Find X9 Pro Camera System

| Sensor | Resolution | Sensor Size | Aperture | Focal Length | Features |
|--------|-----------|-------------|----------|-------------|----------|
| Main (wide) | 50MP | 1/1.28" | f/1.5 | 23mm | PDAF, OIS |
| Ultra-wide | 50MP | — | f/2.0 | 15mm (120° FoV) | — |
| Telephoto | **200MP** | **1/1.56"** | f/2.1 | 70mm (5x) | Periscope, PDAF, OIS |
| True Color | — | — | — | — | 9 spectral channels, 48 zones |

- **Hasselblad Teleconverter:** 3.28x magnification = 230mm equivalent (10x optical). 13 elements in 3 groups, 3 ED elements, 9-layer AR coating.
- **Lossless crop:** Up to 13.2x from 200MP sensor.
- **Digital zoom:** Up to 200x (photo), 50x (video).
- **Note:** The main camera has the **largest sensor** (1/1.28") but the 200MP telephoto (1/1.56") has the highest resolution, enabling lossless crop zoom.

### Video Capabilities

| Mode | Resolution | Frame Rate |
|------|-----------|------------|
| Standard | 4K | 30/60/120 fps |
| Dolby Vision | 4K | 120 fps |
| Log recording | 4K | 120 fps |
| Slow-motion | 4K | 120 fps |
| Slow-motion | 1080p | 240 fps |
| Slow-motion | 720p | 480 fps |

### Platform
- Native Android (Kotlin) for direct Camera2 / CameraX API access.
- Oppo Find X9 Pro runs ColorOS 16 (Android 16). Full Camera2 API support required.
- SoC: **MediaTek Dimensity 9500** — octa-core, dedicated APU for AI inference.

### Performance Constraints
- Pre-capture buffer requires continuous frame capture to a ring buffer in memory. 16GB RAM is generous — allocate up to 2GB for buffer at 4K.
- AI features (bib detection, pose estimation, burst culling) must run on-device for field use (no reliable internet on a mountainside). Leverage the MediaTek Dimensity 9500 APU via NNAPI.
- 4K 120fps capture confirmed supported. 1080p 240fps for slow-motion analysis.
- 200MP telephoto produces ~60MB per RAW frame — burst buffer must account for this.

### Storage
- A full day of shooting at 200MP can produce 100-200GB. 512GB internal storage provides ~3 full days.
- No micro-SD — USB-C external storage for extended events.
- Aggressive thumbnail and proxy workflow: browse proxies, export originals.
- **7500 mAh battery** (silicon-carbon anode) with 80W wired / 50W wireless charging — generous for all-day shooting.

### Connectivity
- Primary operation must be fully offline. No internet dependency for any capture or analysis feature.
- WiFi: cloud backup, sharing, team feed sync.
- Bluetooth: timing system integration, remote shutter (monopod use).

### Cold Weather
- Minimum operating temperature for the Oppo Find X9 Pro must be tested (typically -10C to -20C for modern smartphones).
- Battery drain increases 2-3x below 0C. All battery estimates must account for temperature.
- Touchscreen capacitive sensitivity decreases in cold. Glove mode adjusts touch thresholds.

---

*This is a living document. Features will be refined as development progresses and field testing provides feedback.*
