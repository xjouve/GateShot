# GateShot — Session Handoff (2026-09-08)

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
4. **AI analysis follow-ups:** tap-to-select-racer seed (wire `initialHint`),
   a skeleton overlay of tracked samples in Replay, and a real-key end-to-end
   run to tune the coaching prompt on actual reports.
5. Cosmetics/cleanup: optional `MainViewModel` → `AnalysisViewModel` rename;
   Library still lists old camera-era test clips on the device (user can
   delete in-app); TRAIL overlay mode is still a ghost fallback.

## Testing technique that worked (for the next session)

Drive the app over adb and read screenshots:
`adb shell input tap X Y`, `adb shell screencap -p /sdcard/s.png` + `adb pull`
(PowerShell `>` corrupts binary stdout — always screencap-to-file + pull).
Screen is 1080×2354. Check `adb logcat -d | Select-String EndpointRegistry`
after exercising features — silent failures land there now.
