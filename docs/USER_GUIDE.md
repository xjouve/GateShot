# GateShot — User Guide
## Ski Racing Camera & Coaching App for Oppo Find X9 Pro

---

## Getting Started

### Install

1. Transfer `GateShot.apk` to your Oppo Find X9 Pro
2. Open the file and tap Install (enable "Install from unknown sources" if prompted)
3. Launch GateShot — grant Camera and Microphone permissions when asked

### First Launch

The app opens in **Shoot mode** — the main viewfinder with the camera live. You're ready to shoot.

The bottom navigation bar has:
- **Shoot** — viewfinder (always visible)
- **Gallery** — review your shots (always visible)
- **Replay** — video replay and comparison (Coach mode only)
- **Annotate** — draw on frames and voice-over (Coach mode only)

---

## The Viewfinder

```
┌─────────────────────────────────────────────┐
│ GATESHOT  [Coach]       🌡️ 12°  🔋 87%     │  ← Status bar
│                                             │
│  ┌──────┐                        ┌────┐     │
│  │SL/GS │                        │5.0x│     │  ← Zoom level
│  ├──────┤                        └────┘     │
│  │DH/SG │        LIVE CAMERA                │
│  ├──────┤         PREVIEW         ◯         │  ← Shutter button
│  │ PAN  │                                   │
│  ├──────┤                        ⬤          │  ← Video record
│  │ FIN  │                                   │
│  ├──────┤                        [AF]       │  ← Tracking toggle
│  │ WIDE │                                   │
│  ├──────┤                                   │
│  │TRAIN │                                   │
│  └──────┘                                   │
│                                             │
│  42 shots    +1.5 EV        TELE    380 GB  │  ← Bottom bar
└─────────────────────────────────────────────┘
    Presets                    Controls
```

### Controls at a Glance

| Control | Action |
|---------|--------|
| **White circle** (right) | Take a photo / burst |
| **Red circle** (right) | Start/stop video recording |
| **[AF] button** (right) | Toggle racer tracking on/off |
| **Preset buttons** (left) | Switch discipline preset |
| **[Coach] toggle** (top) | Switch between Shoot / Shoot+Coach mode |
| **Pinch** on viewfinder | Zoom in/out |
| **Long-press** on viewfinder | Place a trigger zone |
| **Double-tap** on viewfinder | Clear all trigger zones |
| **Volume Up** | Shutter (same as white circle) |
| **Volume Down** | Cycle to next preset |

---

## Discipline Presets

Tap a preset on the left side of the viewfinder. Each preset configures shutter speed, burst mode, autofocus, exposure, and stabilization as a package.

### SL/GS — Slalom & Giant Slalom
**Use when:** You're 3-8m from the gates, racers weaving through.
- Short bursts (8 frames per gate passage)
- Fast AF re-acquisition (racer disappears behind gate panel, reappears 0.3s later)
- Shutter 1/1000 - 1/2000s
- Snow EV +1.5

### DH/SG — Speed Events (Downhill, Super-G)
**Use when:** Racers at 90-150 km/h, typically 10-30m away.
- Continuous high-speed burst
- Maximum stabilization (OIS + EIS) — essential at telephoto
- Shutter 1/2000 - 1/4000s
- Snow EV +2.0

### PAN — Panning
**Use when:** You want motion blur on the background, sharp racer.
- Slow shutter 1/125 - 1/250s
- Stabilization locked to horizontal axis (lets you pan freely)
- Continuous burst during pan gesture

### FIN — Finish Area
**Use when:** Shooting celebrations, emotions, podium.
- Face and eye priority AF
- Portrait-biased
- Softer exposure for skin tones

### WIDE — Atmosphere / Course Overview
**Use when:** Wide shots of the course, mountain, crowds.
- Landscape orientation, HDR enabled
- Single-shot mode
- Full dynamic range

### TRAIN — Training Analysis
**Use when:** Coaching. Priority is usable video over artistic quality.
- Video-first: 4K at 120fps (Dolby Vision)
- Wide framing to capture full body and gate context
- Audio recording for sync and annotation
- Auto-clip detection enabled

---

## Zoom

### Native Zoom Levels

| Zoom | Lens | Focal Length | Quality |
|------|------|-------------|---------|
| **1x** | Main wide | 23mm | Excellent |
| **5x** | Telephoto (native) | 70mm | Excellent (200MP) |
| **10x** | Telephoto + Hasselblad teleconverter | 230mm | Excellent (optical) |
| **13.2x** | 200MP crop (lossless) | ~305mm equiv | Very good |
| **13.2-20x** | Enhanced digital | — | Good (SR processing) |
| **20x+** | Digital zoom | — | Usable with SR |

### Using the Hasselblad Teleconverter

1. Attach the magnetic protective case to your phone
2. Align the teleconverter with the Hasselblad logo — it snaps on magnetically
3. GateShot auto-detects the teleconverter and switches to tele profile
4. You now have 10x optical zoom (230mm)
5. The status bar shows **TELE** when detected

**Tip:** At speed events (DH/SG), the teleconverter is essential. At slalom distance (3-8m), you often don't need it — the native 5x (70mm) is enough.

### Super-Resolution Enhancement

GateShot automatically enhances zoom quality beyond the optical limit:

- **5-10x with teleconverter:** Lens deconvolution corrects softness from the teleconverter optics
- **10-13.2x:** Multi-frame noise reduction (the 200MP sensor's tiny 0.5µm pixels are noisy — stacking multiple frames reduces noise by ~3x)
- **13.2-20x:** Full super-resolution pipeline — multi-frame fusion + AI upscale
- **20x+:** AI neural upscale (best available, but diminishing returns)

This processing happens automatically in the background after capture.

---

## Snow Exposure

Every camera underexposes snow — it meters white snow as gray. GateShot fixes this automatically.

### How It Works

The app continuously analyzes each frame for snow coverage and adjusts exposure:

| Snow Coverage | EV Compensation | Scene |
|--------------|----------------|-------|
| >70% | +2.0 EV | Open slope, speed event venue |
| 50-70% | +1.5 EV | Mixed snow and trees |
| 30-50% | +1.0 EV | Partial snow |
| 10-30% | +0.5 EV | Finish area with crowds |
| <10% | 0 EV | Indoor, no snow |

The current EV compensation is shown in the bottom bar (e.g., **+1.5 EV**).

### Flat Light Mode

On overcast days, the mountain becomes white-on-white. GateShot detects flat light conditions and boosts viewfinder contrast so you can actually see the racer against the snow. The bottom bar shows **FLAT** when detected.

---

## Gate-Zone Trigger

The signature feature. Place trigger zones on gates — GateShot auto-fires a burst when a racer enters the zone.

### Setting Up Zones

1. **Long-press** on the viewfinder where a gate is
2. A blue ellipse appears — this is a trigger zone
3. Repeat for up to 5 gates
4. The zones are now **armed** — GateShot will auto-fire when motion enters any zone
5. **Double-tap** anywhere to clear all zones

### How It Works

The app detects motion in each zone by comparing consecutive frames. When a racer enters a zone (fast-moving pixels appear), it fires `ShutterPressed` — which triggers both the burst capture and the pre-capture buffer flush.

**Result:** Perfectly timed shots at every gate passage, automatically. You don't need to press the shutter.

### Tips

- Place zones slightly ahead of the gate — capture the racer at the gate, not after
- Use 2-3 zones for a consistent comparison across all starters
- The zones have a 500ms cooldown between triggers to avoid double-firing

---

## Racer Tracking (AF Lock)

Tap the **[AF]** button to enable racer tracking. The AF system locks onto the fastest-moving subject and follows them through gates.

### How It Works

- **Speed discrimination:** A racer at 60 km/h moves 20x faster across the frame than a gate judge walking. GateShot locks onto the fast one.
- **Distractor rejection:** Officials, course workers, and coaches are classified as "slow" and ignored.
- **Occlusion hold:** When the racer goes behind a gate panel, GateShot predicts where they'll re-emerge and holds the AF there. It does NOT snap to a nearby official.

### Visual Indicators

| Bracket Color | Meaning |
|--------------|---------|
| **Green** | Locked on racer — AF is following them |
| **Orange** | Racer is temporarily hidden (behind gate) — holding predicted position |
| **Gray** | Scanning — looking for a fast-moving subject |

### When to Use

- **Video recording:** Essential. Keeps the racer sharp throughout the run.
- **Burst at specific gates:** Combine with gate-zone trigger for the best results.
- **Speed events (DH/SG):** The racer crosses the frame fast — tracking keeps AF on them.

### When to Disable

- **Course overview / atmosphere shots:** You want landscape focus, not subject tracking.
- **Finish area with crowds:** Multiple people moving — tracking might jump between subjects.

---

## Audio Trigger

For solo shooting during training when you can't watch the start and shoot simultaneously.

### Setup

1. Go to trigger settings (future UI) or use the endpoint: `trigger/audio/enable`
2. GateShot starts listening for the start gate beep (800-3500 Hz tone)
3. When the beep is detected, the shutter fires automatically

### Tips

- Works best within ~50m of the start gate
- Adjust sensitivity if it triggers on cowbells or crowd noise
- 2-second cooldown between triggers to avoid false positives

---

## Pre-Capture Buffer

GateShot continuously buffers the last 1.5 seconds of frames in memory. When you press the shutter, the saved sequence includes frames from **before** you pressed.

**Why this matters:** Human reaction time is ~200-300ms. By the time you see the perfect moment and press the button, it's already gone. The pre-capture buffer means you never miss it.

This is always on — no setup needed.

---

## Video Recording

### Start Recording

- Tap the **red circle** button in the viewfinder
- The button turns red with a white square (stop icon)
- The viewfinder shows a recording indicator

### Stop Recording

- Tap the button again
- The clip is saved and available in Gallery and Replay

### Frame Rates

| Mode | Resolution | FPS | Use Case |
|------|-----------|-----|----------|
| Standard | 4K | 30 | General shooting |
| High frame rate | 4K | 60 | Good slow-motion |
| Dolby Vision | 4K | 120 | Training analysis (default in TRAIN preset) |
| Slow-motion | 1080p | 240 | Technique detail |
| Super slow-motion | 720p | 480 | Edge angle, ski flex analysis |

---

## Session Organization

GateShot organizes all your media automatically:

```
Event Name (e.g., "Kitzbühel WC")
  └── Date (2026-01-25)
       └── Discipline (GS)
            └── Run 1
                 ├── Bib 07 — 12 photos, 1 clip
                 ├── Bib 14 — 8 photos
                 └── ...
            └── Run 2
                 └── ...
```

Sessions are created automatically. Runs are segmented by timing or manual markers.

---

## Gallery

Swipe to the **Gallery** tab to browse your media.

### Filtering

- **All** — everything in the session
- **Starred** — only items you've rated
- **Video** — only video clips

### Actions (per item)

- **Star** — tap the star icon to rate (toggle)
- **Share** — one-tap share to messaging apps
- **Delete** — remove the item

### Quick Review Between Runs

In a race, there's ~30 seconds between starters. Swipe to Gallery, review the last burst, star the keepers, swipe back to Shoot. The app auto-returns to shooting mode after 5 seconds of inactivity.

---

## Coach Mode

Tap the **[Coach]** button in the status bar to switch to **Shoot + Coach** mode. Two additional screens appear in the bottom navigation:

- **Replay** — video playback and comparison
- **Annotate** — draw on frames and record voice-over

### Instant Replay

1. Record a video during a run
2. Switch to **Replay** tab
3. The last clip is auto-loaded
4. Use the transport controls:
   - **Play/Pause** (large blue button)
   - **Frame forward/backward** (skip icons)
   - **Rewind/Forward 5s**
   - **Speed presets:** 0.25x, 0.5x, 1x, 2x
5. Scrub the timeline for frame-accurate seeking

### Run Overlay (Layered Comparison)

This is the key coaching tool. Layer multiple runs on top of each other to show the racer exactly where they need improvement.

#### Step 1: Capture Course Reference (once per session)

Before training starts:

1. Switch to Coach mode
2. Use endpoint `coach/overlay/reference/start`
3. Slowly pan the camera left-to-right across the blank course (no racers)
4. Use endpoint `coach/overlay/reference/stop`
5. GateShot stitches a panorama and auto-detects all gates

**Why:** This reference allows the app to correct for perspective differences between runs — even if you move between runs, the overlay stays aligned.

#### Step 2: Add Runs as Layers

1. After filming runs, go to Replay
2. Add each run as a layer with its gate passage timestamps
3. The first layer becomes the reference (fully opaque)
4. Subsequent layers are semi-transparent overlays

#### Step 3: Choose Overlay Mode

| Mode | What it shows | Best for |
|------|-------------|----------|
| **Ghost** | Semi-transparent reference on top | Seeing body position differences |
| **Difference** | Pixel-level diff (bright = divergence) | Instantly spotting where lines differ |
| **Trail** | Colored trajectory lines on freeze-frame | Comparing racing lines |
| **Wipe** | Drag a split line between two runs | Side-by-side body position at same gate |

#### Step 4: Navigate by Gate

Use **next/prev gate** to jump both runs to the same gate passage. Even if one run is 0.3s faster to gate 5, both videos show gate 5 at the same moment — so you see the **line difference**, not the time difference.

#### Perspective Correction

If you filmed run 1 from position A and run 3 from position B (5m to the right), the overlay automatically warps run 3's perspective to match the reference panorama. The gates serve as anchor points for registration.

### Manual Split Timing

No electronic timing? Tap the screen at each gate passage.

1. Start a timing run
2. As each racer passes a gate, tap `coach/timing/split/record`
3. The app records the timestamp
4. After multiple runs, compare splits: "Run 3 was 0.4s slower at gate 12 — here's why" (linked to video)

### Voice-Over Annotation

Record audio commentary pinned to specific moments in a clip:

1. In the Annotate screen, play the clip to the moment you want to comment on
2. Tap **Record Voice-Over**
3. Speak: "Watch your inside hand here... now see how your hips open up..."
4. Tap **Stop Voice-Over**
5. The audio is pinned to that timeline position — playable by the athlete later

### Telestrator (Draw on Frame)

Draw on a paused video frame to highlight technique:

1. Pause the video at the moment you want to annotate
2. Select a tool: **Freehand**, **Line**, **Arrow**, **Circle**
3. Select a color: Red, Yellow, Blue, Green (high-visibility on snow)
4. Draw on the frame
5. Tap **Save** to export the annotated frame as an image
6. Tap **Undo** to remove the last stroke, **Clear** to start over

---

## Quick Share

Share media instantly from the Gallery:

### Share Presets

| Preset | Resolution | Quality | Use |
|--------|-----------|---------|-----|
| **Coach** | 1080p | Moderate compression | Quick share to the team WhatsApp |
| **Press** | Full resolution | Maximum quality | Send to media / federation |
| **Social** | 1080p + watermark | Compressed | Instagram, social media |

### Watermark

Configure a text watermark (photographer name, event, date) that's applied to Social preset exports.

---

## Cold Weather Tips

### Battery

- GateShot monitors your battery temperature in real-time (shown in status bar)
- **12°** = normal. **5°** = warning (yellow). **0°** = critical (red)
- Below 0°C: battery capacity drops 2-3x. Keep the phone in your jacket pocket between runs.
- The 7000+ mAh battery and 80W fast charging help — charge during lunch break

### Glove Operation

- All buttons are minimum 14mm (48dp) — usable with thin ski gloves
- **Volume Up = Shutter** — the most important button, accessible without touching the screen
- **Volume Down = Next Preset** — cycle presets without looking
- Swipe gestures for primary navigation

### Screen Visibility

- Status bar has high-contrast elements visible in direct sunlight on snow
- The dark viewfinder surround reduces glare
- Recording indicator is visible from 1m away (for monopod use)

---

## Hardware Setup

### Phone: Oppo Find X9 Pro

| Spec | Value |
|------|-------|
| Camera (telephoto) | **200MP**, 1/1.56" sensor, f/2.1, 70mm, 5x optical, OIS |
| Camera (main) | 50MP, 1/2.8", f/1.5, 23mm, OIS |
| Camera (ultra-wide) | 50MP, 16mm |
| Video | 4K@120fps Dolby Vision, 1080p@240fps, 720p@480fps |
| Processor | MediaTek Dimensity 9500 |
| RAM | 16GB |
| Storage | 512GB |
| Battery | 7000+ mAh, 80W fast charging |
| Durability | IP68 (rain/snow resistant) |

### Hasselblad Teleconverter Kit

| Spec | Value |
|------|-------|
| Magnification | 3.28x (70mm → 230mm) |
| Zoom result | 10x optical |
| Optics | 13 elements / 3 groups, 3 ED, 9-layer AR coating |
| Weight | 180g |
| Mounting | Magnetic (aligns to case logo) |
| Case | Aramid fiber + PC, 23g |

### Recommended Accessories

- **Monopod** — essential for speed events at telephoto range
- **USB-C external storage** — for multi-day events (512GB fills in ~3 days at 200MP)
- **80W charger** — full charge during lunch break
- **Thin capacitive gloves** — for touchscreen use in cold

---

## Troubleshooting

### Camera won't open
- Check that Camera permission is granted in Settings > Apps > GateShot > Permissions
- Close any other camera apps (stock camera, video call apps)
- Restart the app

### Tracking keeps losing the racer
- Make sure the racer is the fastest-moving object in the frame
- If there's a ski school with many racers simultaneously, tracking may jump — use gate-zone trigger instead
- Try increasing the AF region size in tracking config

### Teleconverter not detected
- Ensure the magnetic case is properly attached
- Re-seat the teleconverter (align with Hasselblad logo)
- Update the Oppo system to the latest OTA (required for "Hasselblad Telephoto" mode)

### Photos are too dark on snow
- Check that Snow Exposure is enabled (bottom bar should show EV value)
- If manually overridden, reset the preset (Volume Down to cycle, or tap the active preset)

### Battery draining fast in cold
- Keep the phone warm between runs (jacket pocket)
- Use Quick-Hibernate: the app reduces background processing between runs
- Disable features you're not using (audio trigger, tracking)

---

## Keyboard Shortcuts (Volume Buttons)

| Button | Action |
|--------|--------|
| **Volume Up** | Shutter — fires burst capture |
| **Volume Down** | Cycle to next discipline preset |

These work even when the screen is hard to see in bright sunlight or when wearing gloves.

---

*GateShot v0.1 — Built for ski racing, from the training slope to the World Cup.*
