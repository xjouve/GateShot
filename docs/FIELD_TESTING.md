# GateShot — Field Testing Protocol
## Test Cases for On-Snow Validation

---

## Testing Environment Requirements

- **Location:** Any ski slope with gates set (club training preferred — more runs, fewer restrictions)
- **Hardware:** Oppo Find X9 Pro + Hasselblad Teleconverter Kit + monopod recommended
- **Conditions to test across:** Bright sun, overcast/flat light, sun/shadow transitions, late afternoon
- **Temperature range:** Aim to test below 0°C at least once for cold weather validation
- **People needed:** At least 1 racer + 1 person operating the phone. Ideally 3-5 racers for session management testing.

---

## TEST SUITE 1 — Core Capture (Shoot Mode)

### T1.1 — First Launch & Onboarding
**Goal:** Verify onboarding flow and camera initialization.
1. Fresh install — open GateShot for the first time
2. Verify onboarding pager appears (7 pages)
3. Swipe through all pages, verify text is readable in outdoor light
4. Tap "Get Started"
5. Grant Camera + Microphone permissions
6. Verify viewfinder appears with live camera preview
7. Verify status bar shows battery level and temperature
8. Close and reopen app — verify onboarding does NOT appear again

**Pass criteria:** Camera live within 2s of permission grant. Onboarding shown once only.

---

### T1.2 — Discipline Presets
**Goal:** Verify each preset configures the camera correctly.
1. Start in SL/GS preset (default)
2. Cycle through all 6 presets using Volume Down
3. For each preset, verify:
   - Preset label changes on left side of viewfinder
   - Take a photo — verify it saves (shot count increments)
   - Check the resulting image exposure (qualitative: does SL/GS look different from Finish?)
4. Return to SL/GS preset

**Pass criteria:** All 6 presets selectable. Each produces visibly different exposure/settings.

---

### T1.3 — Snow Exposure Compensation
**Goal:** Verify auto-EV adjustment on snow.

**Test A — Open slope (>70% snow):**
1. Point camera at a wide snow field, no racer
2. Verify bottom bar shows EV indicator (should be +1.5 to +2.0)
3. Take a photo
4. Compare with stock camera app photo of same scene
5. GateShot photo should have white snow, not gray

**Test B — Mixed scene (trees + snow):**
1. Frame a scene with ~50% trees, ~50% snow
2. Verify EV shows +1.0 to +1.5
3. Take a photo — snow should be white, trees properly exposed

**Test C — Finish area (crowds + banners):**
1. Point at the finish area with people and banners
2. Verify EV drops to +0.5 or lower
3. Take a photo — skin tones should be natural

**Test D — Flat light:**
1. On an overcast day, point at the slope
2. Verify "FLAT" indicator appears in bottom bar
3. Check viewfinder contrast — can you see terrain features better than stock camera?

**Pass criteria:** EV adjusts automatically. Snow is white in photos, not gray. Flat light detected on overcast days.

---

### T1.4 — Zoom Levels & Hasselblad Teleconverter
**Goal:** Verify zoom quality across the full range.

**Test A — Native zoom:**
1. Start at 1x (23mm wide)
2. Pinch to zoom to 5x — verify smooth transition
3. Take a photo at 5x — should be excellent quality (native 200MP telephoto)
4. Continue zooming to 10x, 13x, 20x
5. Take a photo at each level
6. Compare sharpness: 5x should be best, 13x good, 20x softer but usable

**Test B — Teleconverter:**
1. Attach the Hasselblad teleconverter to the magnetic case
2. Verify "TELE" indicator appears in status bar
3. Zoom to 10x — this is now optical (230mm)
4. Take a photo at 10x with teleconverter
5. Compare with 10x photo without teleconverter — teleconverter should be noticeably sharper
6. Remove teleconverter — verify "TELE" disappears

**Pass criteria:** Teleconverter auto-detected. 10x with teleconverter visibly sharper than 10x without.

---

### T1.5 — Shutter & Burst Capture
**Goal:** Verify photo capture, burst, and pre-capture buffer.

**Test A — Single photo:**
1. Press the white shutter button — verify shot count increments
2. Press Volume Up — verify same behavior (shutter fires)
3. Check Gallery — photos should appear

**Test B — Burst with moving racer:**
1. Set SL/GS preset
2. Racer makes a run through gates
3. Press shutter as racer passes a gate
4. Verify multiple frames captured (burst mode)
5. Check Gallery — should see a sequence of images, some from BEFORE the shutter press (pre-capture buffer)

**Test C — Glove operation:**
1. Put on thin ski gloves
2. Tap shutter button — verify it responds (48dp minimum target)
3. Press Volume Up with gloves — verify shutter fires
4. Cycle preset with Volume Down — verify it works
5. Pinch to zoom — verify gesture recognized through gloves

**Pass criteria:** Photos captured on both touch and volume button. Pre-buffer captures frames before press. All controls usable with gloves.

---

### T1.6 — Gate-Zone Trigger
**Goal:** Verify auto-capture when racer enters a trigger zone.

**Setup:** Position phone on monopod pointing at 2-3 visible gates.

1. Long-press on a gate in the viewfinder — verify blue ellipse appears
2. Long-press on a second gate — verify second zone appears
3. Wait for a racer to pass through the first gate
4. Verify the shutter auto-fires (shot count increments without touching the phone)
5. Verify the same happens at the second gate
6. Double-tap to clear zones — verify zones disappear
7. Verify no more auto-captures happen

**Important checks:**
- Does the trigger fire at the right moment (racer at the gate, not 1 second late)?
- Does it fire on the racer, not on a gate judge walking past the zone?
- Does the 500ms cooldown prevent double-firing?

**Pass criteria:** Auto-capture fires when racer enters zone. Does not fire on slow-moving officials.

---

### T1.7 — Racer Tracking (AF Lock)
**Goal:** Verify AF tracks the racer and ignores distractors.

1. Tap [AF] button — verify tracking enabled (button changes color)
2. Point camera at the course with a gate judge standing near a gate
3. Wait for a racer to enter the frame at speed
4. Verify green tracking brackets appear on the racer (NOT on the gate judge)
5. Follow the racer through 3-4 gates — verify brackets stay on the racer
6. When the racer goes behind a gate panel:
   - Verify brackets turn orange ("HOLDING")
   - Verify brackets do NOT snap to the gate judge
   - Verify brackets return to green when racer re-emerges
7. Take a photo during tracking — verify the racer is in focus, not the background
8. Tap [AF] again to disable

**Edge cases to test:**
- Two racers visible at the same time (rare but happens in training) — does it lock on the faster one?
- Gate judge walks across the course while racer is occluded — does tracker hold or switch?
- Racer stops at the bottom — does tracker eventually release (timeout)?

**Pass criteria:** Tracker locks on racer, not officials. Holds through occlusion. Bracket colors correct.

---

### T1.8 — Video Recording
**Goal:** Verify video capture at various frame rates.

**Test A — Standard recording:**
1. Set TRAIN preset
2. Tap red record button — verify recording starts (button shows stop icon)
3. Record a 30-second run
4. Tap stop — verify recording stops
5. Check Gallery — video should appear and play back

**Test B — Frame rate quality:**
1. Record 10s at 4K@30fps — play back, check quality
2. Record 10s at 4K@120fps (TRAIN preset default) — play back
3. The 120fps video should show smooth slow-motion when played at 0.25x in Replay

**Test C — Audio:**
1. Record a video with audio enabled
2. Play back — verify you hear ambient sound (wind, announcements, start beep)

**Pass criteria:** Videos record and play back at configured frame rate. Audio captured.

---

### T1.9 — Audio Trigger
**Goal:** Verify start beep detection auto-triggers capture.

**Setup:** Position within 30-50m of the start gate. Enable audio trigger.

1. Enable audio trigger via Settings (or endpoint)
2. Wait for the start gate beep
3. Verify the shutter fires automatically when the beep sounds
4. Verify it does NOT fire on:
   - Cowbells
   - Coach shouting
   - Wind noise
5. Verify 2-second cooldown between triggers

**Pass criteria:** Fires on start beep. Does not fire on ambient noise. At least 80% detection rate.

---

## TEST SUITE 2 — Session Management

### T2.1 — Session Organization
**Goal:** Verify media is organized by session/run.

1. Create a session: event="Club Training", discipline="GS"
2. Take 5 photos during run 1
3. Mark run 1 as ended
4. Start run 2
5. Take 3 photos during run 2
6. Verify Gallery shows photos organized by run
7. End session

**Pass criteria:** Photos correctly associated with runs. Run numbering sequential.

---

### T2.2 — Quick Review Between Runs
**Goal:** Verify the 20-40 second review workflow between starters.

1. Take a burst during a racer's run
2. As the next racer is in the start gate (~30s window):
   - Swipe to Gallery
   - Find the last burst
   - Star the best frame
   - Swipe back to Shoot
3. Verify total time < 15 seconds
4. Verify app returns to shooting mode ready

**Pass criteria:** Gallery → review → star → back to shooting in under 15s.

---

## TEST SUITE 3 — Coach Mode

### T3.1 — Mode Toggle
**Goal:** Verify Shoot/Coach mode switching.

1. Verify bottom nav shows only: Shoot, Gallery, Settings (3 tabs)
2. Tap [Coach] toggle in status bar
3. Verify Replay and Annotate tabs appear (now 5 tabs)
4. Toggle back to Shoot mode
5. Verify Replay and Annotate tabs disappear

**Pass criteria:** Coach-only tabs appear/disappear correctly with toggle.

---

### T3.2 — Instant Replay
**Goal:** Verify video replay immediately after a run.

1. Switch to Coach mode
2. Record a 20-second video of a run
3. Stop recording
4. Switch to Replay tab
5. Verify the clip auto-loads
6. Play at 1x — verify smooth playback
7. Set speed to 0.25x — verify smooth slow-motion
8. Use frame-forward/backward buttons — verify frame-accurate stepping
9. Scrub the timeline — verify responsive seeking

**Pass criteria:** Clip loads within 2s of stopping recording. Slow-motion smooth. Frame stepping works.

---

### T3.3 — Run Overlay with Reference Panorama
**Goal:** Verify perspective-corrected run comparison.

**Setup:** 3+ training runs on the same course.

**Step 1 — Capture reference:**
1. Before any racers run, start reference capture
2. Slowly pan left-to-right across the entire course
3. Stop capture
4. Verify "reference captured" confirmation

**Step 2 — Record runs:**
1. Record run 1 from position A (e.g., 5m left of center)
2. Record run 2 from the same position
3. Record run 3 from position B (e.g., 5m right of center — intentionally different!)

**Step 3 — Overlay comparison:**
1. Add run 1 as reference layer (fully opaque)
2. Add run 2 as overlay layer (60% opacity)
3. Switch to GHOST mode — verify semi-transparent overlay
4. Navigate gate-by-gate — verify both videos show the same gate at the same time
5. Add run 3 (filmed from different position) — verify perspective correction
   aligns it with runs 1 and 2 despite the camera angle difference

**Step 4 — Other overlay modes:**
1. Switch to DIFFERENCE mode — verify bright areas where lines diverge
2. Switch to WIPE mode — drag the wipe line left/right
3. Check timing deltas — verify ms differences per gate

**Pass criteria:** Overlay aligns runs at each gate. Run from different position is perspective-corrected. All 4 modes functional.

---

### T3.4 — Manual Split Timing
**Goal:** Verify tap-to-split timing.

1. Start a timing run
2. As racer passes gate 1 — tap split
3. As racer passes gate 2 — tap split
4. Continue for 5-6 gates
5. Repeat for a second racer
6. Compare splits — verify deltas are shown (e.g., "+0.3s at gate 4")
7. Sync timing with video — verify splits overlay on the correct video frames

**Pass criteria:** Splits recorded within ~0.1s accuracy. Deltas calculated correctly.

---

### T3.5 — Voice-Over Annotation
**Goal:** Verify audio annotation on video clips.

1. In Annotate screen, load a clip
2. Play to a specific moment (e.g., racer at gate 5)
3. Tap "Record Voice-Over"
4. Speak: "Watch the inside hand drop here"
5. Tap stop
6. Play back the clip — verify the voice-over plays at the correct timeline position
7. Add a second voice-over at a different position
8. Play through — verify both play at their pinned positions

**Pass criteria:** Voice-over pinned to correct timeline positions. Multiple annotations supported.

---

### T3.6 — Telestrator Drawing
**Goal:** Verify drawing tools on paused video frames.

1. In Annotate screen, pause a video on a frame showing the racer at a gate
2. Select FREEHAND tool — draw a circle around the inside hand
3. Select ARROW tool — draw an arrow pointing to the ski edge
4. Select CIRCLE tool — circle the knee
5. Change color to yellow — draw another arrow
6. Tap Undo — verify last stroke removed
7. Tap Save — verify annotated frame exported as image
8. Tap Clear — verify all strokes removed

**Pass criteria:** All 4 drawing tools work. Colors apply. Undo/clear functional. Save produces an image.

---

## TEST SUITE 4 — Environmental Stress Tests

### T4.1 — Cold Weather Operation
**Goal:** Verify app behavior below 0°C.

1. Operate in temperatures at or below 0°C for 30+ minutes
2. Monitor battery temperature in status bar
3. Verify cold warnings appear (yellow at 5°C, red at 0°C)
4. Verify touchscreen remains responsive
5. Verify Volume Up shutter works (backup when screen unresponsive)
6. Monitor battery drain rate — compare warm vs cold

**Pass criteria:** App functional at -5°C or below. Warnings display. Volume buttons work.

---

### T4.2 — Bright Sunlight Visibility
**Goal:** Verify viewfinder and UI readable in direct sunlight on snow.

1. Stand on a south-facing slope in direct midday sun
2. Check viewfinder — can you see the live preview?
3. Check preset labels — readable?
4. Check status bar (battery, temp, EV) — readable?
5. Check tracking brackets — visible against snow?
6. Check zone overlay ellipses — visible?

**Pass criteria:** All UI elements readable in direct sun. No critical information hidden.

---

### T4.3 — Extended Session (Battery & Storage)
**Goal:** Verify stability over a full training session.

1. Start a fresh session at the beginning of training
2. Shoot continuously for 90-120 minutes (mix of photos and video)
3. Monitor:
   - Battery level at 30-minute intervals
   - Storage remaining
   - App stability (any crashes? freezes? OOM?)
   - Frame processing performance (does the app slow down over time?)
4. After 90 minutes, generate a session report

**Pass criteria:** No crashes over 90 minutes. Battery lasts the session. Storage estimates accurate.

---

### T4.4 — Monopod Shooting (High Position)
**Goal:** Verify usability with phone on a monopod above the head.

1. Mount phone on monopod at ~2.5m height
2. Set gate-zone trigger on 2 gates
3. Enable racer tracking
4. Let 5 racers pass without touching the phone
5. Verify:
   - Gate-zone trigger fires for each racer
   - Tracking locks on racer (check via review after)
   - Recording indicator visible from ground (~1m away)
   - Photos properly exposed and sharp

**Pass criteria:** Fully autonomous shooting from monopod. No interaction needed per racer.

---

## TEST SUITE 5 — Performance & Edge Cases

### T5.1 — Rapid Sequential Runs
**Goal:** Verify performance when racers come every 20-30 seconds.

1. Set up for a race with 20-30 second intervals between starters
2. Shoot each racer (burst + gate-zone trigger)
3. Verify no missed triggers between racers
4. Verify shot count accurately tracks all captures
5. Verify Gallery correctly separates racers

**Pass criteria:** No missed racers. No lag between runs. Media correctly organized.

---

### T5.2 — Quick Share Under Time Pressure
**Goal:** Verify sharing a photo to a coach's WhatsApp in under 10 seconds.

1. Take a photo of a racer at a key gate
2. Open Gallery
3. Tap Share on the photo
4. Select "Coach" preset (1080p, fast)
5. Share to WhatsApp
6. Measure total time from capture to send

**Pass criteria:** Under 10 seconds. Photo arrives in acceptable quality.

---

### T5.3 — Multiple Concurrent Features
**Goal:** Verify stability with everything enabled simultaneously.

1. Enable: racer tracking + gate-zone trigger (3 zones) + audio trigger + TRAIN preset (4K@120fps)
2. Start video recording
3. Racer makes a run
4. During the run, all systems should be active:
   - Tracking follows the racer
   - Gate zones trigger (but we're already recording, so just burst photos)
   - Snow exposure adjusts
   - Audio trigger may fire on beep
5. Stop recording
6. Immediately switch to Replay and play back
7. Check debug log for any errors or performance warnings

**Pass criteria:** No crash. No visible fps drop. All features functional simultaneously.

---

### T5.4 — App Recovery
**Goal:** Verify recovery from interrupts.

**Test A — Phone call during recording:**
1. Start recording a run
2. Have someone call the phone
3. Decline the call
4. Verify recording continues (or resumes gracefully)

**Test B — Notification overlay:**
1. During shooting, receive a notification
2. Verify viewfinder remains active
3. Verify shutter still responds

**Test C — App backgrounding:**
1. Switch to another app briefly
2. Switch back to GateShot
3. Verify camera reopens and all settings are preserved

**Pass criteria:** App recovers from all interrupts without losing data or crashing.

---

## TEST SUITE 6 — Debug & Logging Verification

### T6.1 — Log Export
**Goal:** Verify debug logs capture and export correctly.

1. Perform a 10-minute shooting session with varied features
2. Open Settings → Debug Log
3. Verify log entries appear in real-time
4. Filter by ERROR — verify only errors shown
5. Filter by a specific module (e.g., "tracking") — verify filtering works
6. Tap "Share" — verify share sheet opens with formatted log text
7. Tap "Save" — verify toast confirms file saved
8. Find the saved file on the device and open it — verify readable

**Pass criteria:** Logs captured, filterable, exportable as text. File saved to device storage.

---

### T6.2 — Correlation ID Tracing
**Goal:** Verify a single shutter press can be traced through the pipeline.

1. Enable debug logging
2. Press shutter once
3. Open Debug Log
4. Find the ShutterPressed log entry
5. Note its correlation ID (e.g., "gs-42318")
6. Verify the same correlation ID appears in:
   - Burst module (buffer flush)
   - Session module (media recorded)
   - Burst culling (if applicable)

**Pass criteria:** Single correlation ID traceable across 3+ modules.

---

## Reporting Template

For each test, record:

```
Test ID: T1.3-B
Date: 2026-04-05
Location: Val d'Isère training slope
Conditions: Overcast, -2°C, flat light
Tester: [Name]

Result: PASS / FAIL / PARTIAL

Notes:
- [What worked]
- [What didn't work]
- [Unexpected behavior]
- [Performance observations]

Screenshots/Video:
- [Attach if relevant]

Debug Log:
- [Attach exported log if FAIL]
```

---

## Priority Order for First Field Test

If you only have 2 hours on the slope, test in this order:

1. **T1.1** — First launch (5 min)
2. **T1.3** — Snow exposure (10 min)
3. **T1.5** — Shutter + burst (10 min)
4. **T1.8** — Video recording (10 min)
5. **T1.4** — Zoom + teleconverter (10 min)
6. **T1.6** — Gate-zone trigger (15 min)
7. **T1.7** — Racer tracking (15 min)
8. **T3.2** — Instant replay (10 min)
9. **T3.6** — Telestrator drawing (5 min)
10. **T4.2** — Sunlight visibility (ongoing — observe throughout)

This covers the core capture pipeline, the signature differentiators, and the most-used coaching tool in ~90 minutes.

---

## TEST SUITE 7 — In-House Tests (No Snow Required)

These tests can be performed at home, in the office, or anywhere indoors. They validate all functionality that doesn't depend on snow, racers, or ski slopes.

### T7.1 — App Launch & Permissions
**Goal:** Verify clean install, permissions, and first-run behavior.
1. Uninstall GateShot if previously installed
2. Install the APK
3. Launch — verify onboarding appears
4. Swipe through all 7 pages — verify text, icons, tips are displayed
5. Tap "Get Started"
6. Deny camera permission — verify the app doesn't crash (graceful handling)
7. Reopen — grant both permissions — verify camera preview appears
8. Kill the app and reopen — verify onboarding does NOT appear again
9. Verify status bar shows battery level and temperature (should show room temp ~20-25°C)

**Pass criteria:** Clean lifecycle. No crash on denied permission. Onboarding shown once.

---

### T7.2 — UI Navigation & Layout
**Goal:** Verify all screens are accessible and laid out correctly.
1. Verify bottom nav shows: Shoot, Gallery, Settings (3 tabs in Shoot mode)
2. Tap Gallery — verify Gallery screen loads
3. Tap Settings — verify Settings screen loads with all sections
4. Scroll through Settings — verify: Tracking, Audio Trigger, Pre-Capture Buffer, Snow Exposure, Zoom Enhancement, Export, Session, About sections all present
5. Tap [Coach] toggle — verify Replay and Annotate tabs appear (now 5 tabs)
6. Tap Replay — verify Replay screen loads with transport controls
7. Tap Annotate — verify Annotation screen loads with drawing tools
8. Toggle Coach off — verify Replay and Annotate disappear
9. Rotate phone to landscape — verify UI adapts (no elements cut off)
10. Rotate back to portrait — verify UI restores

**Pass criteria:** All 5 screens accessible. Coach toggle shows/hides correct tabs. No layout breakage.

---

### T7.3 — Camera Preview & Zoom (Indoor)
**Goal:** Verify live camera, zoom, and lens switching.
1. Verify live camera preview is visible (point at any indoor scene)
2. Pinch to zoom from 1x to 5x — verify smooth zoom
3. Continue to 10x, 15x — verify zoom indicator updates correctly
4. Zoom back to 1x — verify smooth
5. Verify zoom shows "1.0x", "5.0x", etc. in the zoom indicator
6. Take a photo at 1x — verify shot count increments
7. Take a photo at 5x — verify shot count increments
8. Take a photo at 10x — compare sharpness with 5x (should be softer but usable)

**With teleconverter (if available):**
9. Attach teleconverter — verify "TELE" indicator appears
10. Zoom to 10x — take photo — compare with 10x without teleconverter
11. Remove teleconverter — verify "TELE" disappears

**Pass criteria:** Smooth zoom. Photos captured at all zoom levels. Teleconverter detected.

---

### T7.4 — Photo Capture & Burst (Indoors)
**Goal:** Verify photo capture works without racers.

**Test A — Single shot:**
1. Point at any static scene
2. Tap shutter — verify photo taken (shot count increments)
3. Press Volume Up — verify photo taken
4. Check Gallery — verify photos appear

**Test B — Burst with moving object:**
1. Have someone walk past the camera at normal speed
2. Press shutter — verify burst fires
3. Check Gallery — verify multiple frames captured
4. Look for frames from BEFORE the shutter press (pre-buffer frames)

**Test C — Moving object for tracking simulation:**
1. Enable racer tracking
2. Have someone walk briskly past the camera (or toss a ball across the frame)
3. Check if tracking brackets appear on the moving object
4. Note: walking speed may be below the racer threshold — tracking may classify as "OFFICIAL". This is correct behavior. To trigger racer lock, you'd need something moving very fast.

**Pass criteria:** Single and burst capture work. Pre-buffer captures earlier frames. Tracking responds to motion.

---

### T7.5 — Video Recording (Indoor)
**Goal:** Verify video start/stop/playback.
1. Tap the red record button — verify recording indicator appears
2. Record 10 seconds of any scene
3. Tap stop — verify recording stops
4. Switch to Gallery — verify video appears
5. Switch to Replay (Coach mode) — verify clip loads
6. Play at 1x — verify smooth playback
7. Set speed to 0.25x — verify slow-motion (smoother if recorded at 120fps)
8. Set speed to 2x — verify fast playback
9. Use frame-forward/backward — verify frame-accurate stepping
10. Scrub the timeline — verify responsive seeking

**Pass criteria:** Record, stop, playback, speed control, and seeking all work.

---

### T7.6 — Preset Cycling
**Goal:** Verify all 6 presets switch correctly.
1. Note the current preset (default: SL/GS)
2. Press Volume Down — verify preset changes to DH/SG (Speed)
3. Press Volume Down — PAN (Panning)
4. Press Volume Down — FIN (Finish)
5. Press Volume Down — WIDE (Atmosphere)
6. Press Volume Down — TRAIN (Training Analysis)
7. Press Volume Down — wraps back to SL/GS
8. Alternatively, tap each preset button on the left side — verify each activates

**Pass criteria:** All 6 presets cycle correctly. Active preset highlighted visually.

---

### T7.7 — Gate-Zone Trigger (Indoor Simulation)
**Goal:** Verify zone placement, visual overlay, and motion detection.
1. Point camera at a doorway or hallway
2. Long-press on the left side of the frame — verify blue zone ellipse appears
3. Long-press on the right side — verify second zone appears
4. Have someone walk through the first zone's area
5. Check if the shutter fires (it may or may not depending on walking speed vs threshold)
6. Double-tap — verify both zones disappear
7. Verify no more auto-captures after zones are cleared

**Pass criteria:** Zones appear on long-press, clear on double-tap. Motion detection triggers on movement through zone.

---

### T7.8 — Annotation Tools (Drawing)
**Goal:** Verify telestrator drawing tools without needing ski footage.
1. Switch to Coach mode
2. Go to Annotate screen
3. Verify the "Paused video frame" placeholder (or load a real clip if available)
4. Select FREEHAND tool — draw a squiggle on the screen
5. Select LINE tool — draw a straight line
6. Select ARROW tool — draw an arrow — verify arrowhead appears
7. Select CIRCLE tool — draw a circle
8. Change color to Yellow — draw another stroke — verify yellow
9. Change to Blue — draw another — verify blue
10. Tap Undo — verify last stroke removed
11. Tap Undo 3 more times — verify strokes removed in reverse order
12. Tap Clear — verify all remaining strokes removed
13. Draw something and tap Save — verify annotated frame export (check toast/file)

**Pass criteria:** All 4 tools draw correctly. 4 colors work. Undo removes in order. Clear removes all. Save exports.

---

### T7.9 — Settings Controls
**Goal:** Verify all settings toggles and sliders respond.
1. Open Settings
2. **Tracking section:**
   - Toggle "Enable AF Tracking" — verify switch moves
   - Slide "Min racer speed" — verify value changes in real-time
   - Slide "AF region size" — verify percentage updates
   - Slide "Occlusion hold time" — verify frames + ms display updates
3. **Audio Trigger section:**
   - Toggle "Enable audio trigger" — verify switch moves
   - Slide "Sensitivity" — verify percentage updates
4. **Pre-Capture Buffer section:**
   - Slide "Buffer duration" — verify seconds and memory estimate update
5. **Snow Exposure section:**
   - Toggle "Auto snow compensation" — verify switch
   - Disable auto — verify "Manual EV bias" slider appears
   - Slide EV — verify value changes
   - Toggle "Auto flat light detection" — verify switch
6. **Zoom Enhancement section:**
   - Toggle "Auto enhance zoom" — verify switch
7. **Export section:**
   - Toggle "Watermark on Social shares" — verify watermark text appears
8. **About section:**
   - Verify version "GateShot v0.1.0" displayed
   - Verify "21 modules" text

**Pass criteria:** All toggles and sliders respond. Values update in real-time. No crashes.

---

### T7.10 — Debug Log Viewer
**Goal:** Verify log capture, filtering, and export.
1. Navigate around the app (switch tabs, take photos, toggle features) for 1 minute
2. Open Settings → scroll to bottom or navigate to Debug Log
3. Verify log entries are appearing (monospace, color-coded)
4. Tap "ERR" filter — verify only errors shown (if any)
5. Tap "WARN" filter — verify only warnings+ shown
6. Tap "ALL" — verify all entries return
7. Tap a module name chip (e.g., "camera") — verify filtered to that module
8. Tap "Share" — verify Android share sheet opens
9. Share to yourself via email/notes — verify the log text is readable
10. Tap "Save" — verify toast "Log saved: gateshot_log_XXXXXXXX.txt"
11. Find the file at `/Android/data/com.gateshot/files/` — open it — verify readable log text
12. Tap "Clear" — verify log entries disappear

**Pass criteria:** Logs captured, filtered, shared, saved to file, and cleared.

---

### T7.11 — Battery & Temperature Monitoring
**Goal:** Verify real sensor readings (no snow needed).
1. Check status bar — verify battery percentage matches system battery level
2. Verify temperature display shows a reasonable room temperature (15-30°C)
3. Use the phone heavily (record video, zoom in/out, enable tracking) for 5 minutes
4. Check temperature again — may have risen slightly
5. Put the phone in a refrigerator for 5 minutes (⚠️ remove before condensation!)
6. Check temperature — verify it dropped
7. If below 5°C — verify yellow warning color in status bar

**Pass criteria:** Battery % matches system. Temperature changes with actual conditions. Warnings at thresholds.

---

### T7.12 — Snow Exposure on White Surface (Simulated)
**Goal:** Test snow exposure without snow using white paper/wall.
1. Point camera at a large white surface (white wall, white paper, white tablecloth)
2. Check EV indicator in bottom bar — should show positive compensation
3. Take a photo — the white surface should appear white, not gray
4. Compare with stock camera app — stock will likely make white appear gray
5. Point at a dark surface (dark desk, black jacket) — verify EV drops to 0
6. Point back at white — verify EV rises again

**Pass criteria:** EV adjusts based on white surface detection. White rendered as white, not gray.

---

### T7.13 — Replay Transport Controls
**Goal:** Verify all replay UI controls without ski footage.
1. Record a 30-second video of anything (walk around the room)
2. Switch to Coach mode → Replay tab
3. Verify clip auto-loads
4. **Play/Pause:** tap play, verify playback. Tap pause, verify stops.
5. **Speed presets:** tap 0.25x — verify slow. Tap 2x — verify fast. Tap 1x — normal.
6. **Frame step:** tap frame-forward — verify advances one frame (~33ms). Tap frame-backward.
7. **Skip:** tap rewind 5s — verify jumps back. Tap forward 5s — verify jumps ahead.
8. **Timeline scrub:** drag the slider — verify video seeks to position.
9. **Split-screen toggle:** tap the split-screen icon — verify layout changes to two panes.
10. **Speed indicator:** verify current speed shown in the speed bar.

**Pass criteria:** All 10 transport controls respond correctly.

---

### T7.14 — Glove Simulation
**Goal:** Verify touch targets are large enough.
1. Wear thick winter gloves (or oven mitts as a worst case)
2. Try to tap the shutter button — verify it responds
3. Try to tap a preset button — verify it responds
4. Try to tap the Coach toggle — verify it responds
5. Try to tap the record button — verify it responds
6. Try to swipe between tabs — verify navigation works
7. Press Volume Up — verify shutter fires (backup when screen is unresponsive)
8. Press Volume Down — verify preset cycles

**Pass criteria:** All primary controls usable with thick gloves. Volume buttons always work as backup.

---

### T7.15 — App Stability & Memory
**Goal:** Verify no crashes or memory leaks during extended indoor use.
1. Use the app continuously for 30 minutes:
   - Take 50+ photos (mix of single and burst)
   - Record 5+ videos (10-30s each)
   - Switch between all 5 screens repeatedly
   - Toggle Coach mode on/off 10 times
   - Enable/disable tracking 5 times
   - Place and clear trigger zones 5 times
   - Open and close Settings 5 times
   - Zoom in/out continuously for 1 minute
2. After 30 minutes:
   - Check that the app is still responsive
   - Check that shot count is accurate
   - Check Gallery — all media present
   - Check Debug Log — look for any ERROR entries
   - Check battery drain — should be reasonable (<15% for 30 min)

**Pass criteria:** Zero crashes. No freezes. Shot count accurate. All media preserved.

---

### T7.16 — Multiple App Lifecycle Events
**Goal:** Verify app handles Android lifecycle correctly.

**Test A — Home button:**
1. Press Home button during shooting
2. Return to app — verify camera resumes, settings preserved

**Test B — Recent apps:**
1. Open recent apps view
2. Switch to another app
3. Switch back to GateShot — verify state restored

**Test C — Screen off/on:**
1. Press power button to turn screen off
2. Turn screen back on, unlock
3. Verify GateShot resumes (camera may need to re-open)

**Test D — Notification during recording:**
1. Start video recording
2. Receive a notification (set a timer or have someone message you)
3. Verify recording continues
4. Dismiss notification — verify viewfinder still active

**Test E — Low memory simulation:**
1. Open several heavy apps (games, Chrome with many tabs)
2. Switch back to GateShot
3. Verify app restores without data loss

**Pass criteria:** App survives all lifecycle events. No data loss. Camera resumes.

---

## In-House Test Priority (First 60 Minutes)

| Order | Test | Time | What it validates |
|-------|------|------|-------------------|
| 1 | T7.1 | 5 min | Install, permissions, onboarding |
| 2 | T7.2 | 5 min | All screens and navigation |
| 3 | T7.3 | 5 min | Camera preview and zoom |
| 4 | T7.4 | 5 min | Photo capture and burst |
| 5 | T7.5 | 5 min | Video recording and playback |
| 6 | T7.6 | 3 min | Preset cycling |
| 7 | T7.9 | 5 min | All settings controls |
| 8 | T7.10 | 5 min | Debug log viewer and export |
| 9 | T7.8 | 5 min | Drawing tools |
| 10 | T7.12 | 5 min | Snow exposure simulation |
| 11 | T7.15 | 10 min | Stability soak (abbreviated) |

This covers all core functionality in ~60 minutes without leaving your desk.

---

*GateShot v0.1 — Field Testing Protocol*
