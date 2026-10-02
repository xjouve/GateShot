# GateShot — Session Handoff (2026-09-08, capture section 2026-09-28)

## ▶ RESUME HERE (2026-09-28): in-app capture + own stabilizer (UNCOMMITTED)

**Direction change since 2026-09-08:** GateShot records video itself again, through the Camera2 periscope path and its own stabilizer; there is no handoff to the Oppo camera app. The full dated history, with every measurement, is in `docs/tickets/021-in-app-telephoto-capture.md`.

**All of this work is uncommitted on `main`** (see `git status`: `ui/capture/`, `ui/home/`, the new `processing/stabilize` classes and tests, the icon, and ticket 021). Commit it to a branch before experimenting further.

### What exists and works (verified on device CPH2791, serial 3B15C6001PS00000)
- **Capture** (`app/.../ui/capture/`: `TeleCapture`, `StabRenderer`, `OpticalStage`, `GyroStabilizer`, `CaptureScreen`):
  - Portrait only. The periscope image needs a 180° flip; the GL renderer handles it, with MP4 hint 90.
  - Zoom chips on camera 0:

    | Chip | HAL zoom | Crop |
    |---|---|---|
    | 10x | 3.03 | 1.25 |
    | 20x | 3.636 | 2.0 |
    | 30x | 5.454 | 2.0 |

    A HAL zoom below 3.03 switches to the MAIN lens (physical camera 2), so it must never go lower.
  - Pipeline: 4K OES stream → GL affine warp → viewfinder, plus a 1080p H.264 recording at 16 Mbps.
  - Encoder timestamps must be converted from BOOTTIME to MONOTONIC.
  - Tap-to-focus: AF/AE region, AF trigger cancel+start, unlock after FOCUSED_LOCKED. The user is not sure AF actually works; this is unverified with a moving subject.
- **Stabilizer:**
  - Gyro at 397 Hz (needs `HIGH_SAMPLING_RATE_SENSORS`), sync +6 ms, f = 35362 px/rad at 3840.
  - Axes (portrait): image x = +f·Δθ(gyro Y), y = +f·Δθ(gyro X).
  - Per-column rolling-shutter term: readout 7 ms across the source at zoom 3.636, scaled 1/zoom.
  - Optical residual: `GlobalShiftEstimator` (Lucas–Kanade, 256² centre, 4 threads).
  - At 20x/30x, recording uses a live **L1-optimal path** (Grundmann 2011):
    - `L1PathPlanner`, an exact bounded simplex matching the LP reference to 0.0 px, ~27 ms per solve on the phone;
    - 1 s look-ahead;
    - a self-centred 36-frame ring of 1620×2880 (~670 MB).
  - No wait after Stop. This is a hard user requirement: a post-recording wait is "unusable on the slope".
- **Logo:** `Image_big_cat_v1.png` → `res/drawable-nodpi/ic_launcher_logo.png` (the app icon, user-approved) and `watermark_logo.png` (drawn bottom-right on recordings).
- **Debug props** (always reset them to 0):
  - `adb shell setprop debug.gateshot.synth 1` injects a known sub-pixel wobble; score it with `build/qa/stab_m4/synth/synth.py`.
  - `debug.gateshot.ois 1` requests OIS. It is proven to have NO effect for third-party apps.
- **Per-recording logs:** `GateShot/stablogs/<run>_gyro.csv` and `<run>_frames.csv` on the phone.

### Honest status: the user judges it clearly worse than the Hasselblad native stabilizer
The last six-clip A/B is in `build/qa/stab_m8/ab9/`. Position wobble in px, as side / up:

| Clip | Band | GateShot | Native |
|---|---|---|---|
| 20x still | 0.2–1 Hz | 2.48 / 5.08 | 3.93 / 7.69 |
| 20x still | 1–3 Hz | 0.19 / 0.35 | 0.15 / 0.36 |
| 20x still | 3–14 Hz | 0.11 / 0.17 | 0.24 / 0.24 |
| 20x pan | 1–3 Hz, up | 14.3 | 0.45 |
| 20x pan | 3–14 Hz, up | 10.5 | 0.15 |
| 10x still | 0.2–1 Hz, side | 24.7 | 3.4 |

- **20x pan is broken:** the up correction jumps 20–40 px per frame. The self-centred buffer's centre (the planner's tentative path) lurches and does not cancel, because the image does not follow the gyro exactly (yaw gain ≈ 0.8, measurement noise).
- **10x sway is structural:** the margin is small, and the periscope has no wider field than 10x.
- **Key new finding:** GateShot frames are about half as sharp as native. Laplacian variance at 1080 width:

  | Clip | GateShot | Native |
  |---|---|---|
  | 10x | 98 | 201 |
  | 20x still | 17.5 | 31.3 |
  | 20x pan | 15.6 | 26.8 |

  The motion metrics are blind to this. Suspects:
  - two bilinear resamples (camera → ring → output);
  - 1080p H.264 at 16 Mbps, where native records 4K HEVC 10-bit;
  - no exposure-time cap while recording (motion blur, "shimmer");
  - possible AF hunting.

### Next plan (recommended; the user has not yet said go)
1. **Image quality first.** Verify each item with the sharpness metric against the native clips BEFORE asking the user to film:
   - 4K high-bitrate HEVC output;
   - a single sharper resample (bicubic/Lanczos), with no double bilinear;
   - an exposure-time cap while recording;
   - focus lock after tap.
2. **Replace live L1 + self-centring with Gyroflow-style velocity-dampened smoothing:** strong smoothing when still, weaker in pans, no window discontinuities. Gyroflow is GPL-3: reuse its ideas, not its code.
3. **Later:**
   - an ~10-band rolling-shutter mesh (Karpenko 2011);
   - on-device re-calibration of readout and sync (rate cross-correlation);
   - focus-breathing correction (Google Pixel fused stabilization, 2017).
- **Alternative route to native quality:** Oppo's CameraUnit SDK (`/product/framework/com.oplus.camera.unit.sdk.jar`) reaches the native super-EIS but is gated by Oppo app authorization. **Do not attempt to bypass it.** The only legitimate route is applying to Oppo's CameraUnit / open-capability program.

### Research survey 2026-09-28 (top ideas)
- Karpenko et al. 2011 (Stanford): gyro stabilization with rolling-shutter correction and cross-correlation sync.
- Google "Fused Video Stabilization" 2017: gyro + OIS + focus-breathing correction.
- Gyroflow: velocity-dampened smoothing and a rolling-shutter tool (GPL-3).
- Deep Online Fused Video Stabilization (WACV 2022; no code).
- Kalman/IMM online smoothing.
- Stabilization patents (US11818465, US9204048): cap the exposure time, because blur in stabilized video shimmers.
- OpenCamera-Sensors (GPL-3): synchronized IMU logging.
- The accelerometer is useless for hand translation; image-based translation measurement is the right tool.

### Method (use it, don't re-learn it)
- Judge by ALL bands (0.2–1, 1–3, 3–14 Hz; `build/qa/stab_m7/ab8/bandall.py`) plus sharpness. A single HF "jitter" figure misled us once, and the user caught the overclaim.
- Work offline and test live only at the end:
  1. Simulate on logged clips.
  2. Run the pixel bench (`build/qa/stab_m5/ois/bench*.py`, `build/qa/stab_m6/pan/design*.py`) on raw Stab-OFF clips with their gyro logs.
  3. Pin each Kotlin port to the Python reference with a replay unit test.
  4. Verify GPU paths on device with the synth known-signal test.
- Gotchas:
  - OpenCV auto-rotates phone MP4s: assert `frame.shape`.
  - GLSL `mediump` is fp16 on Mali: always use `highp`.
  - Gradle serves stale test results: use `--rerun --no-build-cache`.
  - Git Bash heredocs corrupt backslashes: write scripts to files.
  - Git Bash adb pulls need `export MSYS_NO_PATHCONV=1`.
  - A "Camera disabled (3)" error after reinstall or a USB drop: restart the app cleanly.
- Before each live test, check the installed APK is the fresh build.

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

## Tier-1 hardening pass (2026-09-07)

Three parallel tracks, merged on `main`, built, tested and verified on the
phone (release build included):

- **Release engineering** — `versionName 1.0.0`, `versionCode` = git commit
  count, `isShrinkResources`, release signing from env / `local.properties`
  (`GATESHOT_KEYSTORE`, `GATESHOT_KEYSTORE_PASSWORD`, `GATESHOT_KEY_ALIAS`,
  `GATESHOT_KEY_PASSWORD`; falls back to the debug keystore so
  `assembleRelease` always installs). ProGuard rules rewritten for the real
  code (dead capture-era keeps removed; TFLite + kotlinx.serialization kept;
  the endpoint registry keys on a string field, not class names, so no
  blanket keep). Adaptive launcher icon (mipmap-anydpi-v26 + monochrome),
  backup / data-extraction rules that exclude `GateShot/videos`, permissions
  trimmed to RECORD_AUDIO + BLUETOOTH(≤30)/BLUETOOTH_CONNECT, predictive back
  enabled, edge-to-edge with dark system bars (`SystemBarStyle.dark` in
  `MainActivity`), `lint {}` block. **The R8-minified release APK was
  installed and exercised on the device: Library, Replay playback, Coach —
  no crashes, no `EndpointRegistry` failures.** No debug applicationId
  suffix on purpose: it would orphan the clips stored under `com.gateshot`.
- **Robustness** — silent `catch {}` sites in timing/annotation/pose/exporter
  now log or propagate; long analyses check cancellation and release codecs
  in `finally`; import checks free space and cleans partial copies; `.gates`
  parsing tolerates malformed lines (`GateSidecar`); a failed muxer stop marks
  the export failed. JVM tests 24 → 64 (`EndpointRegistryTest`,
  `SessionFeatureModuleTest`, `TimingFeatureModuleTest`, `GateSidecarTest`,
  `EnhancedExporterMathTest`).
- **UI/UX** — all user-facing strings in `strings.xml` (4 → 250 entries;
  the only literals left are the generated PDF report text in
  `MainViewModel`), contentDescriptions on meaningful icons and toggle
  semantics on toggles, ≥48dp touch targets on primary controls, explicit
  loading / empty / error states, an app-wide snackbar channel on
  `MainViewModel` for failures that used to be swallowed, confirmation
  dialogs before deletes.

New load-bearing facts:

10. **The Find X9 Pro is 360dp wide** (1080px at 3.0×), not 411dp. A header
    row of six ≥48dp targets cannot share a line with a title; the Replay
    header stacks the title above the strip for that reason.
11. `git` on `K:\TEMP` needs `safe.directory` (no ownership on that
    filesystem); Git Bash mangles `/sdcard/...` paths unless
    `MSYS_NO_PATHCONV=1` is set before `adb shell screencap`.
12. Leftover worktrees from this pass live at `K:\TEMP\claude\wt-{release,ui,robust}`
    (branches `tier1-*`, fully merged) — safe to `git worktree remove`.

## AI racer analysis (2026-09-08)

Two-stage feature on the Coach → Analysis tab ("AI Coach" card), device-verified
on the slalom clip `4601.mp4`:

1. **On-device technique tracking** (`coaching/pose/TechniqueAnalyzer`, free,
   ~60 s for a 20 s clip): decodes frames at 1920 px every 200 ms, localizes
   the racer by pan-compensated motion saliency (global pan from row/column
   luma-projection correlation, aligned |diff|, blur, mean+2σ threshold,
   integral-image window search with a distance penalty to the previous
   position), crops 3× the racer height around the seed and runs MoveNet on
   the crop (retries ×1.5 / ×0.7; targeted 192/320 px fallback search every 5th
   untracked sample). Strict validity (overall conf ≥ 0.45, hips+knees ≥ 0.3,
   an ankle ≥ 0.3, height ≥ 24 px, person-like aspect). Metrics are nullable
   and computed in PIXEL space only from confident joints: knee/hip angles,
   torso lean, shoulder tilt (side-swap corrected, wrapped to ±90°), stance
   ratio, hands-forward. Aggregates, per-gate segments (from the `.gates`
   sidecar), heuristic flags (UPRIGHT, STRAIGHT_LEGS_AT_GATE, HANDS_BACK,
   SHOULDER_TILT, NARROW_STANCE, LOW_TRACKING) and 6–8 key frames. Sidecar:
   `<clip>.technique.json`. Pure math lives in `TechniqueMath.kt` (63 tests).
2. **Claude coaching report** (`coaching/aicoach/AiCoachClient`, Anthropic
   Java SDK 2.61.0, model `claude-opus-5`, ~4 K output tokens, cost shown on
   the button, typically < $0.15): sends the cropped key frames + one
   full-frame context image + the compact technique JSON + run context and
   requests a JSON-schema structured report (summary, 1–10 score,
   strengths/corrections with priority and optional timestamp/gate, drills,
   confidence note). Sidecar `<clip>.aicoach.json`; share as text. API key in
   Settings → AI Coach (`ApiKeyStore`, `gateshot_config` prefs).

Measured on `4601.mp4` (racer filmed from below, 20–140 px tall): 39 % of
samples tracked, all in the second half where the racer is ≥ ~80 px; the card
shows the LOW_TRACKING warning prominently. Realistic expectation: tracking
works when the racer is roughly ≥ 80 px tall at 1920 px decode; for distant
racers the vision report on cropped key frames is the useful part. A
`initialHint` parameter exists on `analyze()` for a future tap-to-select-racer
UI.

New load-bearing facts:

13. **The SDK typed structured-output path crashes on Android.**
    `outputConfig(Class)` builds the schema with jsonschema-generator, which
    calls `Method.getAnnotatedReturnType()` — absent on ART →
    `NoSuchMethodError`. Use the manual path
    (`OutputConfig.builder().format(JsonOutputFormat.builder().schema(...))`
    with `Schema.builder().putAdditionalProperty(key, JsonValue.from(map))`)
    and parse the text block with kotlinx. The client wraps every `Throwable`
    into `AiCoachException`.
14. R8 needs keep/dontwarn rules for `com.anthropic.**`, Jackson, OkHttp/Okio,
    `com.github.victools.**` and `java.lang.reflect.AnnotatedType` (see
    `app/proguard-rules.pro`). The SDK adds ~23 MB to the release APK
    (30 → 53 MB).
15. Compose: a `Row` of chips carrying full sentences squeezes the 2nd+ chip to
    zero width (letter-wrapped, enormous height) — stack them in a `Column`.
16. Testing the AI path without a real key: enter any string in Settings; the
    request goes out and comes back as the AUTH error state (verified).
    A **real** key is now stored on the dev phone (2026-09-08), so the AI
    path runs for real — every report costs money. To go back to testing the
    error path, overwrite the key in Settings with any junk string.

## AI coaching: first real-key report (2026-09-08)

The end-to-end paid path has now been exercised on the device for the first
time, on `4601.mp4` (SL training clip, camera at the finish looking up the
hill, racer skiing toward the lens).

| | |
|---|---|
| Model returned | `claude-opus-5` (matches the pinned constant) |
| Latency | ~60–90 s, UI shows "Asking Claude…" with a Cancel button |
| Cost shown on the button | ~$0,09 |
| Verdict | overallScore 5/10 |
| Content | 3 strengths, 7 corrections, 6 drills |

**It works, and the report quality is good.** Every item carries a
`timestampMs` and cites the metric behind it. The three priority-1
corrections were: hands low and trailing the hips, stance too upright at the
ankle, and a narrow foot platform leaving nothing to angulate against.

**Calibration was the standout.** The model down-weighted its own confidence
off the 40 % tracking rather than overclaiming, noticed a coach blocking the
frame at 12,4 s, and declined to treat the discipline as confirmed. The
`confidenceNote` field is doing real work — keep it in the schema.

**Defect found: `gateIndex` was null on 6 of 7 corrections.** Gate
correlation is dead weight until gates are tagged. This is also a hard
prerequisite for the reference-band work below, because a mean over a whole
run is close to meaningless for comparison — you need metrics at turn phases.

**Reading a report:** don't OCR screenshots. The result is persisted next to
the clip as `<clip>.aicoach.json` (`summary`, `overallScore`, `strengths`,
`corrections`, `drills`, `confidenceNote`, `model`, `createdAtMs`). Pull that
file. Printed `?` mojibake on em-dashes is only the Windows console codepage;
the file is valid UTF-8.

**Installing / replacing the key on the dev phone:** the store is plain
SharedPreferences, file `gateshot_config`, key `anthropic_api_key`. Recipe
that worked: `adb shell am force-stop com.gateshot` FIRST (a running app
flushes in-memory prefs over your write), then push a small `sh` script to
`/data/local/tmp` and run it via `adb shell run-as com.gateshot sh …`, doing a
`sed -i` substitution on that one XML value so the other 44 settings survive.
Quoting a sed script straight through `adb shell` from git bash is a nesting
trap; the pushed-script route avoids it. Validate a key cheaply without
spending anything: `GET https://api.anthropic.com/v1/models` with `x-api-key`
and `anthropic-version: 2023-06-01`. That also confirms the pinned model still
exists on the account. One spurious 503 was seen; retry before concluding
auth failure.

## Where the AI coaching goes next (design decisions, 2026-09-08)

Worked through with the user this session. Recorded here because none of it is
derivable from the code.

**1. The binding constraint is the pixel budget on the racer, not the prompt.**
On this clip the racer was ~94 px tall, so a thigh spans ~25 px and a 3 px
joint error is already several degrees of knee angle. Cropping tighter lets
the pose net spend its full input resolution on the racer and removes the
distractors that caused 8 search fallbacks — but it cannot create detail the
sensor never captured. Ceiling: cropping moves you from unusable to
*directional*, not to precise joint angles.

**2. Therefore capture comes first.** Filming side-on from mid-course, panning
with the telephoto, puts the racer several hundred px tall; the phone already
has the glass. An **in-app framing guide shown before recording** is worth
more than any post-processing. Second: replace motion-saliency localization
with a **person detector + tracker** that holds the racer across frames
(fixes the temporal dropouts too).

**3. Claude is prompted, not trained.** Do NOT plan to "train the coach" on
reference videos — that is a category error. Two separable things:
the *pose tracker* is a network and fine-tuning it on ski footage would
genuinely help (it has never seen a tucked racer in a helmet with poles at
distance); *Claude* only ever sees what is in the prompt. The buildable
version of the user's idea is to **feed it measured reference numbers**, e.g.
"knee angle at apex 155°, elite band 105–125°" — concrete and defensible
instead of the model reaching for priors.

**4. Reference corpus source: social-media racer clips, not broadcast.**
Racers post runs on Instagram. Those are phone-shot from the side of a hill
and share view geometry with our own capture in a way a World Cup TV feed
never will. Stratify by metadata: camera position, sex, discipline, level.
Store *numbers only*, never redistribute the clips.

**5. Per-metric view validity beats more metadata buckets.** "Behind" and
"front" are buckets on a continuous variable — a camera 10 m left of the fall
line and one straight down it are both "front" and give materially different
apparent torso lean. Our metrics are 2D pixel-space, so admit a sample into
the band only when the view supports *that specific metric*:
- `kneeAngle` — holds up while the leg is near the image plane, collapses
  under foreshortening.
- `stanceRatio`, `handsForward` — most view-sensitive metrics we have, worst
  head-on.
- `shoulderTilt` — reads well from front/behind, poorly from the side.

**6. The band will be biased tight (selection bias).** Racers post their good
runs and cut to the best turns. The reference therefore describes elite
skiing on its best day, already edited, and will make our athlete look worse
than they are. Label it a *best-run distribution* and publish interquartile
ranges, not means. Ship it as a band, never as a target.

**7. Age adjustment must be empirical, not from the literature.** There is
good literature on youth range of motion, strength and growth, but very
little mapping a 14-year-old to an expected knee angle at apex in slalom.
Use the literature for **guardrails on what not to prescribe** (loading,
growth-plate risk); get the age bands from age-group racers on the same
platforms and at the user's own club. Same reasoning applies to elite
references generally: technique is adapted to that athlete's body, skis and
course, and a junior copying Odermatt's angles gets hurt.

**8. Tension to plan around:** good reference footage is filmed close, our
clip was filmed far. The better the reference, the less comparable it is to
what athletes actually shoot. This is *why* item 2 comes before item 4 —
fixing capture is what puts our own footage in the same measurement space as
the reference.

**9. Validation gate before collecting at scale — do this first.** Take
skiers already known to be stronger and weaker, filmed the same way, and check
whether the pipeline ranks them correctly. **If it cannot separate skiers you
can separate by eye, the band is noise and sample size will not rescue it.**
Cheap test; it decides whether the whole reference plan deserves funding.

**10. Strata multiply fast; start with one cell.** 2 sexes × 3 disciplines ×
3 view buckets = 18 cells before level or age, at ~20–30 clean turns each.
Start with the single cell matching footage already in hand: SL, head-on.

**11. Instagram re-encodes and racers post in slow motion.** Angles survive;
anything timing-derived (turn duration, pressure timing) does not. Do not
build reference bands on timing metrics from that source.

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
4. **AI analysis follow-ups** — real-key run is DONE (see "first real-key
   report" above); the prompt no longer needs proving, it needs better input.
   In order: (a) in-app **framing guide before recording** — biggest single
   win, the racer is too few pixels tall to measure precisely; (b) **person
   detector + tracker** to replace motion-saliency localization and hold a
   tight crop across frames; (c) **gate/turn-phase segmentation**, without
   which `gateIndex` stays null and reference bands are meaningless;
   (d) tap-to-select-racer seed (wire `initialHint`); (e) skeleton overlay of
   tracked samples in Replay. Only then (f) the elite **reference-band
   corpus** — see "Where the AI coaching goes next" above, and run the
   discriminative validation gate before collecting at scale.
5. Cosmetics/cleanup: optional `MainViewModel` → `AnalysisViewModel` rename;
   Library still lists old camera-era test clips on the device (user can
   delete in-app); TRAIL overlay mode is still a ghost fallback.

## Testing technique that worked (for the next session)

Drive the app over adb and read screenshots:
`adb shell input tap X Y`, `adb shell screencap -p /sdcard/s.png` + `adb pull`
(PowerShell `>` corrupts binary stdout — always screencap-to-file + pull).
Screen is 1080×2354. Check `adb logcat -d | Select-String EndpointRegistry`
after exercising features — silent failures land there now.
