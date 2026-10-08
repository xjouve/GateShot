# GateShot — Session Handoff (capture section last updated 2026-10-06)

## ▶ RESUME HERE (2026-10-07): stabilizer validated, sharpness in progress

**Direction since 2026-09-28:** GateShot records video itself, through the Camera2 periscope path and its own stabilizer; there is no handoff to the Oppo camera app. The full dated history up to 2026-09-28 is in `docs/tickets/021-in-app-telephoto-capture.md`; everything since is in the dated sections below (newest facts first within 2026-10-06).

**State at the end of 2026-10-06**
- Branch `inapp-capture-stabilizer`, local only (not pushed), clean tree. Commits of the day: `840060c` (stabilizer + viewfinder), `7da5566`, `c61e61c` (debug props for ISP modes), `1f64226` (docs).
- The phone (CPH2791) runs `build/qa/stab_m9/sh2/sweep.apk` = `c61e61c`. Debug props are reset. `adb logcat -G 8M` was set (not persistent across a reboot).
- **Stabilizer: validated by the user** ("it looks good to me now"). Recording: level with native on a 20x pan, far steadier framing than native on a 20x still. Viewfinder: one frame late, held on the image path.
- **Sharpness: measured, nothing built yet.** Native is ~2x cleaner (detail/noise) in every band at dusk and sharpens a different band than the ISP does for us; the camera's own quality modes give no free gain.

**Tomorrow, in this order**
1. **Daylight pair, phone fixed, 20x, same scene:** 10 s GateShot (default modes), 10 s native. Score with `build/qa/stab_m9/sh2/nat.py` (detail / noise per band, tone-matched). It decides whether multi-frame averaging is worth building: tonight's result is at ISO ~1000.
2. **Offline prototype** on `sh2/ex_nx.mp4` + `sh2/native.mp4`: N-frame average of aligned frames, then a filter that raises 0.25-0.5 cycles/px (~1.6-1.9x in amplitude at the top) and lowers 0.125-0.25; score with the same split. Target: detail/noise at or above native's in each band, detail ratio ~1.
3. **GLES pass** only after 2: sharpen inside the existing 36 Lanczos taps of the encode shader (advisers); averaging needs per-pixel rejection for the moving racer.
4. **Shared estimate** for viewfinder and recording: while recording the viewfinder estimate is late on up to 65% of frames. It also frees the fast cores for 3.

**Working rules the user set on 2026-10-06**
- Put design decisions to both advisers (Fable and GPT Astra: `consult` with `advisor='all'`), then decide.
- Do not ask for more native clips than needed: 23 native teleconverter clips are on the PC (`build/qa/stab_m1`..`m9`, `camspike`, measured in `stab_m9/nat_all/survey.txt`), plus `g2/native_still.mp4` and `sh2/native.mp4`.
- Never drive the phone (`input tap`) without the user's go-ahead; a touch-idle check is not proof it is on a desk. When the phone is on its side, the home screen is in landscape (Record button tap `1114 576`), in portrait `540 990`; the capture screen is always portrait.

### 2026-10-06 update: two outside reviews, measurement gate and seeded search (UNCOMMITTED, installed on the phone)
- **Reviews.** Fable (read the repo and logs, scripts in `build/qa/stab_m9/fable/`) and GPT Astra (read-only) were each asked for an independent plan. Both put the image measurement first and keep the live L1 planner. Both reject 60 fps for now, a Gyroflow-style planner, and a rolling-shutter mesh.
- **What the pan roughness is** (`fable/check.py`, `check4.py`; confirmed by `g1/gate_replay.py`): a random walk of the fused path, not the planner. Steps of 0.5 px on measured frames plus 4.5 px on the 4% without a measurement reproduce the measured 0.63 px (3-14 Hz). The frames without a measurement were not blur:
  - the gated ones were correct measurements (0.0-1.4 px from truth); the 4 px gate rejected them because the gyro prediction is itself 2-3 px rms off at any speed;
  - the rejected ones were steps of 63-103 px, thrown away by the `abs(dx) < 60` bound in `OpticalStage`.
- **Changes made today:**
  - `GATE_PX` 4 -> 10. Replay on five logged 20x clips (`g1/gate_replay.py`, `gate_replay.txt`): no additional wrong measurement accepted on any clip; path error on `u7` side 1.03 / 0.63 -> 0.48 / 0.38 (1-3 / 3-14 Hz), up 0.85 / 0.55 -> 0.64 / 0.36; `u5` and `u4` improve by 20-30%, `u6` by 10-25% except side 1-3 Hz (unchanged). No gate at all lets 92 and 198 px errors through.
  - `GlobalShiftEstimator.estimate(prev, cur, seedDx, seedDy)` searches around the gyro prediction (scaled by the running gain); the 60 px bound is now on the distance from the prediction. New test on 36 fast-pan pairs made from a real 20x frame (`g1/pairs.py`, shifts 45-105 ring px with motion blur): worst error 0.035 px at 256. Those pairs are synthetic (one frame shifted), so this shows reach, not accuracy under real blur.
  - Unit tests pass; the build runs at 30-31 fps on the phone, estimate 73 ms. **Not verified on a real pan.**
- **First real 20x pan with this build** (`build/qa/stab_m9/g2/an.py`, 19.9 s, handheld, camera steps median 16 / p95 48 / max 92 ring px per frame; the picture pans on 29% of frames at 12 px/frame, a gentler take than `u7`): **no frame without a measurement, none gated** (`rejected=0 gated=0`, was 10 + 15 on `u7`). Across the pan 0.38 (1-3 Hz) / 0.27 (3-14 Hz), along 0.95 / 0.22, largest frame step across 1.3 px (`u7`: 0.98 / 0.57, 1.94 / 0.59, 8.0). Native same garden on 5 October: 0.63 / 0.22 and 1.67 / 0.20. Fused-path error 0.18-0.34 px in every band; measurement against truth 0.51 px rms per step, none over 3 px. Different takes and a scorer floor of 0.2-0.3 px, so this says "level with native", not "better". An estimate took 97 ms. The 20x still was not recorded (the script's second Record tap did not start a recording).
- **20x handheld still, same scene, GateShot then native** (`build/qa/stab_m9/g2/`: `still_run_1791293705139`, `native_still.mp4`, `pair.jpg`; flower box on a wall; user-recorded, separate takes; px at 1080 width, sliding 10 s windows). GateShot: picture stays within 3.5-4.3 px; bands 0.12-0.40 / 0.07-0.08 / 0.06-0.07 side and 0.20-0.23 / 0.07-0.08 / 0.05-0.06 up (0.2-1 / 1-3 / 3-14 Hz); no unmeasured or gated frame; measurement against truth 0.13 px rms; the hand moved the camera 11.6 px/frame median. Native: picture drifts 48-115 px side and 90-156 px up per 10 s; bands 0.85-1.67 / 0.08-0.09 / 0.04 side and 1.39-3.33 / 0.04-0.10 / 0.03-0.06 up. So GateShot holds the framing far better (native lets it wander slowly), the two are equal at 1-3 Hz, and above 3 Hz both are at the scorer floor. Laplacian variance 89 against 132 (native 4K, brighter exposure; indicative). The hand motion of the native take is not known (no gyro log).
- **Viewfinder rebuilt the same day (`ViewfinderStage.kt`, UNCOMMITTED, installed as `build/qa/stab_m9/g3/vf3.apk`).** User: the recorded file is steadier than native, but while recording the native preview looked far steadier than ours, and "the image still moves by itself".
  - Cause 1, fast shake: the viewfinder was gyro-only, and the gyro mis-predicts each image step by 2-3 px at random (autocorrelation ~0). Rebuilt from logs: 1.15 / 2.3 px at 3-14 Hz against 0.06 in the recording. A measurement that arrives after the frame was shown does not help and makes that band worse (`g2/vfsim.py`); only the displayed frame's own measurement removes it. Fable and Astra agreed (consult of 2026-10-06): show the frame one frame late; 33 ms is worth it, 100 ms is not. The user accepted 33 ms.
  - Cause 2, slow slide: a view held still in gyro terms glides, because image path and gyro path drift apart by 150-230 px in 13 s (`g3/vfsim3.py`). The hold now runs on the measured image path, and stands still while the correction is small against the margin (first as a `lowHz` zone in `PathFilter`, since replaced, see below). Simulated picture movement over a steady 13.5 s at 20x: gyro-only 227 / 206 px, new 4 / 6 px (recording 3 / 3); on the afternoon still 6 / 255 (the hand left the zone on the up axis) (`g3/sweep2.py`). The gyro `PathFilter`s of the recording buffer are unchanged.
  - How: every camera frame is copied 1:1 into one of two textures (2880x3840, 88 MB), its 256 px luma is read back, and `GlobalShiftEstimator.estimateFast` (half-size level only, gyro-seeded; a quarter of the cost, 0.03 px rms from the full estimate on real pairs) runs on its own thread. The previous frame is drawn with offset = image path minus held path; a frame whose estimate is late or gated advances on the gain-scaled gyro step. Only the rolling-shutter terms come from the gyro stage.
  - On the phone (`vf2`/`vf3.apk`): user says stability "looks way better", but after a move "the viewfinder struggles to get to a stable position and keeps moving without my panning". Replay (`g3/settle.py`) shows it: after a re-aim that hold overshoots by up to 745 px and then creeps for more than 10 s. **`PathFilter` is back to its committed form (no `lowHz`); the viewfinder uses a new `ViewHold`** (in `GyroStabilizer.kt`): a spring acts only on the part of the correction beyond 25% of the margin, engaged above 45%, so the view stops there instead of returning to the centre; pan follower as `PathFilter`. Replayed on the same two clips the picture stands within 3 px from about 1 s after the move ends. Installed as `vf4.apk`; **not yet seen by the user**. No unit test pins `ViewHold` to `settle.py` (the app module has no test setup).
  - Late estimates: 46 of 300 frames at default thread priority; with display priority and a 10 ms wait, 0 of 300 when not recording (estimate 17-20 ms) but **91-92 of 300 while recording** (25-29 ms: the four recording workers hold the fast cores). A late frame is placed on the gyro step and lands 2-3 px off. `vf4.apk` reduces the luma 2:1 while decoding it and runs only the gradient refinement at 128 px (`GlobalShiftEstimator.refineFrom`); not measured yet. If it is still late while recording, the next step is one shared estimate for viewfinder and recording. Recording unaffected by the viewfinder stage: 29.6 fps, 1.3% long intervals, as before; a 28.6 s clip holds 0.30 / 0.15 across and 0.57 / 0.21 along (1-3 / 3-14 Hz), no unmeasured frame (`g3/run_1791302456503`).
  - `debug.gateshot.synth` no longer moves the viewfinder (its offset is not taken from the gyro stage). `logcat` buffer raised to 8 MB (`adb logcat -G 8M`): the 256 KB default lost the statistics within minutes.
  - Not done: the viewfinder was never captured from the screen, so its numbers are simulations; no check that the luma copy and the extra blit leave headroom on a warm phone.
- **The 0.8 pan gain** is real image motion, not estimator bias (truth/gyro 0.79, truth/measurement 1.01). It is on the yaw axis only and grows with speed: 0.93-1.0 at 3-8 px/frame, 0.77-0.85 at 20-60, both directions; pitch stays 0.94-1.0 (`fable/check2_out.txt`). The 0.83 "on both axes" of 2026-10-02 came from the older, noisier estimator; the `h1` still clip is 0.98-1.0 in every band. Cause still unknown.
- **The scorer cannot resolve native.** Its floor on a fixed-phone 20x clip is 0.21-0.29 px side, 0.04-0.06 up (114 frames). Native over the six pans on file is 0.33-0.74 (1-3 Hz) and 0.11-0.27 (3-14 Hz) across the pan (`nat_all/survey.txt`, all 23 native teleconverter clips, one tracker). A claim of beating native above 3 Hz needs a better scorer first.
- **Checked:** the 5 October native and GateShot pans show the same magnification to 3.5% (`n1/fov.py`). The phone holds no native teleconverter clip that is not on the PC (4 October clips are main lens, 30 September are the bare 3x; pulled to `nat1004/`, `nat0930/`).
- **OIS data:** `dumpsys media.camera` (`kern/dumpsys_camera.txt`) defines result tags `com.mediatek.3afeature.oisdata` / `oisdatavalidnum` next to the `gyrodata` we receive, but `oisdata` is not delivered in our session and the standard OIS-samples mode is not advertised. Both reviewers: reading result keys is fine; setting undocumented Oppo/MediaTek request tags to wake the vendor EIS is on the wrong side of the "do not bypass" line.
- **Next, in order:** (1) one real 20x pan and one still with this build; (2) estimator noise 0.5 -> ~0.2 px per step (full-resolution sparse patches instead of the 3x-reduced centre square), A/B offline on raw frame pairs first; (3) a full-resolution scorer that prints its fixed-phone floor; (4) bridge a frame without a measurement by registering its two neighbours; (5) manual exposure cap. Astra also flags that `finish()` can wait up to 1 s for measurements before encoding the tail; measure the stop latency.
- `rec.sh` was run today while the phone was in the user's hands (the idle check looked at touch input only). Check the gyro, or ask, before driving the phone.

### 2026-10-06 (evening): stabilizer validated by the user and committed (`840060c`, local, not pushed); sharpness is next
- **User verdict on `vf4.apk`:** "it looks good to me now. I think we can validate the stabilizer and continue to the sharpener". That build is commit `840060c`.
- **Left open in the stabilizer:** while recording, the viewfinder estimate was late on 108, 195 and 16 of 300 frames (estimate 26-36 ms; the recording's estimate went up to 111 ms), so the half-size estimate did not cure it; idle it is 17-18 ms and never late. The fix to try is one shared estimate per frame for viewfinder and recording (the half-size estimate is 0.03 px rms from the full one on real pairs), which also frees the fast cores for a sharpening pass. It changes the recording's measurement, so it needs a recording to confirm.
- **Where native is sharper** (`build/qa/stab_m9/sh1/spec.py`, same scene `g2`, frames registered onto one grid, contrast-normalised power spectrum, ratio native / GateShot per band in cycles per GateShot output px):

  | patch | 0.02-0.06 | 0.06-0.125 | 0.125-0.25 | 0.25-0.375 | 0.375-0.5 |
  |---|---|---|---|---|---|
  | stone wall (in focus) | 0.92 | 0.79 | 0.79 | 2.28 | 2.28 |
  | flowers (in focus) | 0.86 | 0.66 | 0.85 | 2.47 | 2.70 |
  | grass (background) | 0.29 | 0.24 | 0.21 | 0.35 | 0.30 |

  - Native's advantage is confined to the upper half of the 1080p range (about 1.5x in amplitude); below it GateShot carries slightly more. Above GateShot's limit native's 4K holds only 1.6-1.7% of the power of its own 0.25-0.5 band: at 20x its 4K has no detail beyond 1080p. So 4K output is not the route (consistent with the `k4` test).
  - On the grass GateShot carries 3-5x more power than native: noise or texture that native smooths. Not tone-matched, so part of every ratio is the tone curve (native is visibly brighter); `crop_flowers.png` shows native's edge enhancement.
- **Exposure smear is not the cause on a still** (`sh1/smear.py`, Fable's check): per frame, the 0.25-0.5 band power falls only to 0.92-0.94 of the steadiest tenth at the median gyro smear (1.9 px in 5 ms) and to 0.73-0.80 in the worst tenth. A 1.9 px smear should cut it far more, so the image moves less during the exposure than the gyro says: more evidence that the OIS is working on its own. Pans not checked.
- **Sharpening pass, offline** (`sh1/usm.py`, unsharp mask on the GateShot frames): sigma 0.7 px, amount 1.0 brings the 0.25-0.5 band to native's level (ratio 0.95-1.03 and 0.70-0.83) but over-lifts 0.125-0.25 (ratio 0.52-0.56) and multiplies the grass power (ratio 0.14-0.09). So: a narrower kernel than a Gaussian unsharp mask, with coring, and probably noise reduction first.
- **Advisers on sharpness (Fable and Astra, consult of the evening):** find the cause before building; sharpen inside the existing 36 Lanczos taps (detail = Lanczos sum minus a Gaussian-weighted sum of the same taps, cored, no extra fetches) before any multi-frame fusion; fusion only if the band proves noise-limited (with 0.4 px alignment error an N-frame average is itself a blur that cuts 0.375 cycles/px to 0.64, and it ghosts on the racer); measure with tone-matched clips, edge MTF with overshoot, flat-patch noise, and a coherent/incoherent split between two frames of one clip, not Laplacian variance or MTF50 alone.
- **ISP mode sweep done (fixed phone, 20x, dusk: 5 ms, ISO 1010-1210; `build/qa/stab_m9/sh2/sweep.py`, seven 7 s clips; new debug props `debug.gateshot.edge` / `debug.gateshot.nr`, empty = template).** The HAL applies what is requested (result keys echo it); the record template's default is edge FAST, NR FAST. Detail = power common to two frames 5 apart, noise = half the power of their difference:
  - HIGH_QUALITY for either or both: the same as the default within 10% in every band. No free gain.
  - Edge OFF: detail falls to 0.16-0.27 of the default at 0.125-0.375 cycles/px. So the frames we get are already edge-enhanced by the ISP; switching that off to sharpen ourselves starts from far behind.
  - Edge HIGH_QUALITY with NR OFF: +38% detail in the top band at the centre, but noise +13% to +250%; detail/noise equal or worse.
  - In the default clip detail/noise is 110-170 at 0.03-0.125, 17 at 0.125-0.25, 2.1-2.4 at 0.25-0.375 and **0.5-0.6 at 0.375-0.5**: in that light the top band holds more noise than detail (the difference also contains codec noise and any residual jitter, so this is an upper bound on sensor noise). Not measured in daylight.
  - Frame rate 31 fps in every mode. Decision: keep the template's modes.
- **Native on the same fixed scene, same evening** (`sh2/nat.py`; `sh2/native.mp4` is 10 s cut from the user's 109 s native clip; native reduced 2:1, registered onto GateShot's grid, tone-matched per patch by histogram; GateShot = the default clip `ex_nx.mp4`). Ratio native / GateShot, bands 0.03-0.125 | 0.125-0.25 | 0.25-0.375 | 0.375-0.5 cycles per GateShot px, three patches:
  - detail: 0.78-0.81 | 0.29-0.55 | 1.17-2.33 | 2.59-3.47. Native carries less than GateShot in the middle band and 2.6-3.5x more in the top one: a different sharpening shape, not just "more".
  - noise: 0.32-0.46 | 0.25-0.28 | 0.70-0.87 | 1.09-1.50.
  - detail/noise: native is about 2x GateShot in every band (centre: 369 / 31.7 / 5.5 / 1.33 against 181 / 17.3 / 2.1 / 0.56). Even native's top band is barely above its noise in this light.
  - Reading: to equal native here GateShot needs twice the signal-to-noise (what averaging two aligned frames gives on a static background, in power) and then a reshaping of the spectrum (top band up by ~1.6-1.9x in amplitude, middle band down). Without tone matching the detail ratios read 0.4-0.7x lower: tone curve differences are that large, so untuned comparisons are not usable.
  - Dusk only (ISO ~1000). Whether the top band is noise-limited in daylight is not measured; a fixed-phone pair in daylight is needed before deciding on multi-frame fusion.
- **2026-10-08: periscope driver read for gain and HDR, and the two MediaTek noise-reduction keys tested (uncommitted).** Driver (`build/qa/stab_m9/kern/`): analog gain 1-256x in the binned modes and the HAL's ISO 100-19200 is all analog, so native has no gain advantage at dusk; the in-sensor-zoom modes stop at 64x (2x2) and 16x (full-resolution crop), so native cannot use them in low light either. The dual-conversion-gain video modes read at 10.6 us per line (rolling shutter 3.3x ours) and the two-exposure modes alternate frames: neither suits a fast racer at 20x; not pursued. New debug props `debug.gateshot.tnr` / `debug.gateshot.ainr` (0/1, empty = HAL default) set `com.mediatek.nrfeature.3dnrmode` and `com.mediatek.videoainrfeature.videoAinrModes`; the result log prints `tnr=` / `ainr=`. Fixed phone, 20x, daylight wall (5 ms, ISO 740-990; `build/qa/stab_m9/tn1/`: `run.sh`, `sweep.py`, `lag.py`, seven clips): the HAL echoes both keys, 31 fps in every setting, but nothing changes. Flat-patch noise is within 12% of the default for every setting (the two default clips differ by as much), and the lag-1 / lag-5 frame-difference ratio is 0.39-0.45 in all seven, including temporal NR forced off. Not tested at dusk, where the HAL might apply them differently; the scene has too little texture to say anything about detail. Decision: leave both unset.
- **2026-10-08: three-way handheld 20x, native / GateShot / TeleCam Pro** (`build/qa/stab_m9/t3/score.py`, same tracker as `n1/cmp.py`, out px at 1080 width, one take each, garden at ~20 m, daylight; GateShot = today's debug build, side / up). Still hold: range 3 / 5 px against native 25 / 61; 0.2-1 Hz 0.24 / 0.39 against 2.51 / 4.92; 1-3 Hz 0.10 / 0.13 against 0.14 / 0.21; 3-14 Hz 0.10 / 0.08 against 0.08 / 0.06; Laplacian variance 178 against 185. Pan (21 against 24 px/frame): across the pan range 12 against 331, 1-3 Hz 0.31 against 0.89, 3-14 Hz 0.21 against 0.15, largest step 1.7 against 7.2; along the pan 1-3 Hz 1.60 against 3.07, 3-14 Hz 0.27 against 0.28; Laplacian variance while panning 130 against 71 (different frames, indicative). So GateShot now holds position about 10x better than native and is equal or better up to 3 Hz; native keeps a small edge (0.02-0.06 px) above 3 Hz. TeleCam Pro (v1.0.2, open source, built for the Find X9 Ultra) is effectively unstabilized on this phone: 3-14 Hz 9-13 px in its hold clips and 6.5-7.9 px in its pan, frame steps of 50-160 px, framing wider than 20x; the user judged it not a good app. Its route (HAL stabilization from a third-party session) gives nothing here.
- **2026-10-08: daylight fixed pair done, and averaging tested offline** (`build/qa/stab_m9/d1/`: `nat.py`, `hi.py`, `fuse.py`; hedge at 20x, GateShot 5-10 ms ISO 640-1260, native `VID20261008093414`). Ratio native / GateShot on GateShot's grid, three patches, same bands as the dusk pair: detail 1.07-1.11 | 0.79-0.86 | 2.17-2.50 | 3.90-4.35; noise 0.39-0.70 | 0.31-0.48 | 0.65-0.83 | 1.23-1.42; detail/noise centre native 440 / 66 / 15.3 / 3.7 against GateShot 159 / 29 / 4.9 / 1.15. So in daylight native is 2.3-3.3x ahead in every band (dusk: about 2x) with the same spectrum shape (less middle, far more top), and GateShot's top band is at the noise level even in daylight. On its own 4K grid native keeps frame-consistent detail above GateShot's output limit (0.24 cycles per native px; binned sensor limit 0.22): detail/noise 2.5 at 0.25-0.30, 1.7 at 0.30-0.35, 1.1 at 0.35-0.40, below 1 above. Consistent with in-sensor zoom, not proven; small either way. **Averaging N recorded frames does not deliver the expected gain** (`fuse.py`, fixed phone, the best case): noise falls to 0.87-0.93 at N=2, 0.63 at N=4, 0.47-0.58 at N=6 and no further at N=8, against 1/N for independent noise; detail/noise x1.15 / x1.3-1.75 / x1.7-2.1. Sub-pixel alignment makes it slightly worse (frames are already aligned to ~0.1 px). The noise in the file is correlated between neighbouring frames (lag-1 / lag-5 0.4, `tn1/lag.py`); whether that comes from the encoder or from the ISP is not known, and decides whether fusing camera frames before the encoder would do better. Six frames are 200 ms: usable only on a static background, never on the racer. Gotcha: `cv2.phaseCorrelate` with a window multiplies its inputs in place when no padding is needed; pass copies.
- **2026-10-08: noise before and after the encoder** (new debug prop `debug.gateshot.dump <frames>`, up to 120: the uncompressed centre 512 px of that many consecutive ring frames, written to `stablogs/<run>_ring.rgba`; `build/qa/stab_m9/rd1/`: `run.sh`, `acf.py`, `pre.py`; fixed phone, 20x, 31 fps with the dump on). A hedge clip was useless for this: slow picture movement over texture reads as correlated noise (rho at lag 1 0.71 before and 0.77 after the encoder), which also means the hedge averaging figures above include movement. On a blurred blank wall (5 ms, ISO 156, noise sd 0.8 levels): per-pixel noise correlation at lag 1 / 2 / 3 / 5 is 0.30 / 0.21 / 0.14 / 0.05 in the camera frames and 0.50 / 0.26 / 0.14 / 0.02 in the recorded file. So the frames the app receives already carry temporally filtered noise (the ISP), and the encoder adds to it. Averaging N camera frames leaves noise 0.71-0.58 (N=2), 0.58-0.42 (N=3), 0.50-0.34 (N=4), 0.41-0.25 (N=6), coarse to finest band; the same on the recorded file 0.79-0.73, 0.67-0.58, 0.57-0.47, 0.49-0.37. Before the encoder the finest band is close to independent (lag-1 / lag-5 noise 0.84). Reading: fusion has to sit before the encoder, and three to four frames there give x1.7-2.9 detail/noise, about native's lead (2.3-3.3) on a static background. One clip at low ISO; the ISP's temporal filter may be stronger at high ISO. Not measured: native against GateShot on a moving subject, which is what matters for a racer and where neither can average.
- **Next:** (1) the same fixed-phone pair (GateShot default + native, 10 s each) in daylight; (2) offline prototype on `sh2`: N-frame average of aligned frames plus a spectrum reshaping filter, scored with `nat.py`'s split; (3) only then the GLES pass (advisers: inside the existing Lanczos taps; fusion needs rejection on the racer); (4) the shared estimate for viewfinder and recording.

### 2026-10-02 update: sharpness cause found, single-resample recording (UNCOMMITTED on branch `inapp-capture-stabilizer`)
- The 2026-09-28 state is committed as `a90d5c9` on the local branch `inapp-capture-stabilizer` (not pushed).
- **Cause of the ~2x sharpness gap** (`build/qa/stab_m9/sharp.py`, on native frames): one bilinear sub-pixel resample at 1:1 keeps only ~50% of the Laplacian variance, two keep ~33%; bicubic ~85%, Lanczos ~93%. That alone reproduces the measured GateShot/native ratio (0.53–0.62). Codec, exposure and AF are not needed to explain it.
- **Fix built and running on the phone** (not yet A/B-measured against native on the same scene):
  - The ring frame is an exact texel copy of the camera frame: whole-pixel offset, no rolling-shutter warp, `GL_NEAREST`. The ring is sized 1:1 with the source per zoom level (10x 1800x3200, 829 MB; 20x/30x 1620x2880) and re-allocated on a zoom change. `gyroCropZoom` at 10x is now exactly 1.2.
  - The encode pass does the only resample (Lanczos-3, 36 taps) and applies the sub-pixel rest and the rolling shutter there. At 10x the optical measurement is corrected for the rounding (`fixX/fixY`); at 20x/30x the rounding is absorbed into S.
  - Output is HEVC: 4K at 60 Mbps at 10x, 1080p at 30 Mbps at 20x/30x (the output is never smaller than the view in source pixels). Zoom chips are disabled while recording.
  - Verified on device: `stMatrix` is a pure rotation (exact copy holds), 30–31 fps at 20x, clean 1080p HEVC file, picture upright with watermark. **Not verified:** 10x at 4K (frame rate of the 36-tap pass at 4K, memory), the 10x rounding-correction sign (use the synth test), sharpness against native.
- **Same-scene A/B, phone fixed on a hedge** (`build/qa/stab_m9/`: `rec.sh`, `ab.py`, `old_*`/`new_*` clips, `ab_crops.png`; old = commit `a90d5c9`). Laplacian variance at 1080 width, centre: 10x 1653 -> 2531 (+53%), 20x 254 -> 515 (x2.0). 10x at 4K HEVC holds 31 fps (58 Mbps file). The 10x rounding correction has the right sign (measurement noise 0.40 px vs 0.36 px before; a wrong sign would give ~0.9). Still open: the same A/B against the native camera.
- **From the oppo-source kernel repo** (`android_kernel_modules_and_devicetree_oppo_mt6993`, branch `oppo/mt6993_b_16.0.0_find_x9`; this phone is project 24206 "Changjiang"; read for facts only, GPL code not copied; `build/qa/stab_m9/sensor_modes.py` tabulates the modes):
  - The periscope sensor is a Samsung S5KHP5 (16384x12288), driver `cjtele`. Line time 3.25 us in the 4x4-binned modes.
  - The HAL reports `SENSOR_ROLLING_SHUTTER_SKEW` = 7.488 ms at every zoom from 3.03 to 9.0, which is exactly the driver's binned 4096x2304 mode (2304 x 3.25 us). `READOUT_S_AT_BASE_ZOOM` is now 7.488 ms (was 8.4); the pixel bench prefers it (`stab_m5/ois/bench_T.py`: stretch 0.14 vs 0.20, skew 0.30 vs 0.35).
  - **Resolution ceiling:** a third-party app always gets the 4x4-binned mode; the HAL does not switch to the in-sensor-zoom modes (2x2 binned, 8192-wide crop) the driver has. So the 20x view holds only ~1700 real sensor pixels across its long side and 30x ~1140. 10x holds ~3280. No app-side resample can add detail beyond that.
  - The driver has 60 fps and 120 fps binned modes with the same line time, and the camera advertises a [60,60] fps range: 60 fps capture is a real option (half the motion per frame).
  - OIS runs on the sensor-hub coprocessor (its I2C pins are routed to the SCP); the kernel only switches its power. No OIS hall data reaches the app: no OIS sensor in `dumpsys sensorservice`, no OIS keys in the capture results, `availableOpticalStabilization` = [off].
  - Each capture result carries `com.mediatek.3afeature.gyrodata` (82 samples x 24 bytes: int64 boot-time ns + 3 floats, ~400 Hz). Same gyro as SensorManager, but stamped by the HAL: usable to cross-check the +6 ms sync.
  - Debug props added: `debug.gateshot.keys 1` logs stabilization-related result keys once; `debug.gateshot.zoom <ratio>` forces the HAL zoom. Reset both to 0.
- **Whole-frame buffer + L1 at every zoom (later on 2026-10-02, installed on the phone, uncommitted).** User feedback on the single-resample build: "looks better, but the image still makes slight up/downs by itself". Cause (`build/qa/stab_m9/u1/`): at 10x the picture followed the causal hold filter's lagging path (corr 0.98); at 20x the self-centred L1 path drifted. Simulated on those clips' gyro logs (`u1/sim_full.py`), a plain L1 path bounded by the real crop margin stays fixed (0 px) where the old paths moved 40–180 px. Change: `gyroCropZoom` = 1 (the ring holds the whole 2160x3840 frame, 1.19 GB, no self-centring) and `l1Mode` = true at 10x too; the viewfinder hold margin now uses `cropZoom`. Runs at 30–31 fps at 10x (4K) and 20x, 0 missing plans.
- **Handheld result with that build** (`build/qa/stab_m9/h1/`, 12 s clips, out px at 1080 width): 10x 0.2–1 Hz 2.0 side / 3.2 up, 20x 2.5 / 2.0; 1–3 Hz 0.10–0.29; 3–14 Hz 0.05–0.13. The planned path is exactly fixed, but the picture still drifts 16–93 px over 12 s, anti-correlated with the camera path: **the image moves only ~0.83x what the gyro predicts** (g = -0.10 to -0.18, both axes, both zooms). Same 0.83 as the 09-28 pan clips. Most likely near-field parallax (rotation about the body pivot r with the subject at distance d gives gain 1 - r/d; r 0.5 m, d 3 m -> 0.83), which would vanish on a slope (d > 50 m). **Not verified**: needs a clip of a far subject. If it persists far away, estimate the gain online from the optical measurement (sliding regression of measured shift on the gyro prediction, one scalar per axis) and plan with g*C.
- **Correction to the line above:** the user says the subjects were 10–20 m (wall) and 50–60 m (trees), so parallax cannot explain 0.83. Per band, the measured raw image motion against the gyro prediction gives gains of 0.85–1.02 that differ by clip, axis and band (`h1/gain.py`); over a whole clip the gyro path is off the true image path by 34–100 px, the app's measured image path by 12–29 px (`h1/fuse.py`; truth = tracked output + applied correction). Cause of the gyro mismatch still unknown (OIS acting on its own is a candidate; not gyro bias: `h1/bias.py`).
- **Fused path (installed, uncommitted):** `OpticalStage` now plans on gyro path + accumulated (measured image shift − gyro prediction), settled 6 frames late (`settle`, `pathAt`); a frame with no usable measurement advances on the gyro alone. Correction = fused path − L1 path; the separate Gaussian "optical leftover" stage (`RecordingPathPlanner`) is no longer used by the app. Fixed-phone check (`h2/`): the picture moves 0.5–0.9 px over 12 s at 10x and 20x, so the 09-28 fear that accumulating measurements makes the picture drift by itself does not hold with the current estimator. **Handheld not yet tested with this build** (it got dark: 20 ms exposure, ISO 14300). Risk to watch: a racer filling the 768 px centre region could pull the path.
- **2026-10-05: 4:3 camera stream (installed, uncommitted).** User feedback on the fused build, three handheld clips (`build/qa/stab_m9/u2/`): "the screen still moves without me moving". Measured (`u2/an.py`): the picture is fixed for seconds, then glides to a new position: 20x side 445 px in two glides (135 px, then 310 px in 2.5 s), 10x side 32 px, 10x up 45 px in the first 2 s. Cause: **the crop window runs out of margin**; the L1 path then has to follow the hand. The live-L1 simulation on the logged fused path reproduces all three (`u2/sim.py`: 445 / 32 / 45 px).
  - Fix: the camera stream is now 3840x2880 (4:3) instead of 3840x2160. In portrait the extra 720 px are side margin: 10x ±122 -> ±324 out px, 20x ±486 -> ±810. Simulated on the same clips: 10x side 32 -> 0 px, 20x side 445 -> 121 px. `GyroStabilizer.srcAspect` / `viewAspect` replace `aspect`; `OpticalStage.windowX()` is the output window's width fraction; the ring is 2880x3840 x36 (1.59 GB), 31 fps at 10x (4K) and 20x.
  - Verified on device, phone fixed, old vs new build on the same scene (`build/qa/stab_m9/s43/cmp.py`): same framing to scale 0.9998 and under 1 px, so focal length and aspect hold. The HAL reports skew 9.984 ms on this stream (3072 rows x 3.25 us), which the readout term now uses (7.488 ms x 4/3). **Not verified:** handheld result, rolling-shutter quality on the 4:3 stream, 30x.
  - Tried and rejected: 20x as camera zoom 3.03 + app crop 2.4. It would bring the 20x clip to 0 px, but is 10-25% less sharp on the same scene at every analysis scale (`s43/sharp_ms.py`: 39.7 vs 52.6 at 1080 width). The HAL's zoom above 3.03 is better than our Lanczos upscale.
  - **Handheld result with the 4:3 build** (`build/qa/stab_m9/u3/`, two user clips, out px at 1080 width): over the whole clip the picture moves 3.8 side / 2.1 up at 10x (11 s) and 3.5 / 5.6 at 20x (12.5 s). Bands: 10x 0.12 / 0.05 / 0.05 (0.2-1 / 1-3 / 3-14 Hz), 20x 0.45-0.73 / 0.17-0.21 / 0.15-0.17. No clamping. The hand moved less than in `u2` (camera range 164-682 px against 269-1031), so the 20x margin was not stressed. One flaw: a single 5 px jump at 10.57 s of the 20x clip, on a 20 px/frame jerk, where the image measurement was ~5 px off and stayed in the path. Not compared with native on the same scene.
  - **20x pan with the 4:3 build** (`build/qa/stab_m9/u4/`, 22.8 s, ~16-19 px/frame): the up position jumps 33-92 px per frame at 15.3-15.5 s. Cause (`u4/lag.py`): optical measurements 40-90 px away from the gyro prediction (blur in the fast motion; they passed the inlier test) were added into the fused path; video steps correlate -0.81 with that error. Outside the jumps the up axis holds 1.1-1.9 px (1-3 Hz) and 0.5-0.8 px (3-14 Hz); native on the 09-28 pan was 0.45 / 0.15, the 09-28 GateShot build 14.3 / 10.5 (different takes).
  - **Gate added (installed, uncommitted):** `OpticalStage.settle` ignores a measurement that differs from the gyro prediction by more than 4 px + 30% of the predicted step (`GATE_PX`, `GATE_FRACTION`); that frame advances on the gyro alone. On today's clips (`u4/gate.py`) it rejects 0-2 frames of each still clip and 27 of 661 in the pan. The end-of-recording log line prints `gated=`. **Not verified on a pan yet.**
  - **Second 20x pan, gate build** (`build/qa/stab_m9/u5/`, 21.8 s, up to 35 px/frame): the jumps are gone (largest frame step 11.6 px, inside a smooth 370 px glide that follows a real tilt; `gated=18 rejected=17` of 664). Whole clip, out px: up 1.34 (1-3 Hz) / 0.65 (3-14 Hz); side, along the pan, 3.8 / 2.25. The path rides the margin bound on only 5% of frames (`u5/ride.py`), so that is not the cause of the side roughness.
  - **Running gain (installed, uncommitted, not verified on a pan):** in a pan the image moves only 0.77-0.82x the gyro prediction on the pan axis (0.94-0.97 on still clips and on the other axis; cause unknown). A frame without a usable measurement advanced on the raw gyro step is then 6-8 px rms off; scaled by a running gain, 3.4-3.5 (`u5/gain.py`). `OpticalStage.gain()` fits measured against predicted motion over ~1 s per axis (clamped 0.7-1.1, only when the axis moves more than ~7 px/frame rms); it scales the gyro step of unmeasured or gated frames, the unsettled tail in `pathAt`, and the gate's prediction.
  - **Third 20x pan, gain build** (`build/qa/stab_m9/u6/`, 25.8 s, a wilder take with two up tilts): no jumps. `u6/decomp.py` splits the roughness above 1 Hz into planned-path roughness and fused-path error, for this pan and the previous one. On frames with an accepted measurement the picture follows the plan to 0.5-0.7 px per frame. On the 5-8% without one, the error per frame is 14.5 px side without the gain (`u5`) and 6.6 px with it (`u6`); up 3.8 and 5.1. Those frames dominate the path error (side 4.2 -> 2.7 px, up 1.3 -> 1.9; different takes). The planned path itself is 2.1 / 0.9 px (`u5`) and 4.9 / 2.8 px (`u6`) above 1 Hz, which includes intended speed changes. On this warm phone an estimate took 121 ms on average (50-60 ms cold), `rejected=37 gated=13 dropped=3`.
  - **Installed after that, uncommitted, not verified on a pan:** estimator inlier floor 0.4 -> 0.2 (`MIN_INLIERS`; the gyro gate now does the validity check), `SETTLE` 6 -> 10 frames, and a new `inl` column in `_frames.csv`.
  - **Fourth 20x pan, with those changes** (`build/qa/stab_m9/u7/`, 24.4 s, up to 34 px/frame): whole clip, out px: up 0.95 (1-3 Hz) / 0.58 (3-14 Hz), side 2.07 / 0.66; largest up step 8 px; `rejected=10 gated=15` of 732 (4% unmeasured). `u7/decomp.py`: path error above 1 Hz 1.2 side / 1.0 up; frame step 0.5 px on measured frames, 4-5 px on unmeasured ones. No measurement had an inlier fraction under 0.4 in this take, so the lower floor was not exercised. Best pan so far, still above the native 0.45 / 0.15 of the 09-28 pan (different take). In still clips the measured shift and the gyro prediction already differ by 1.6-2 px rms per frame while the picture is steady to 0.05-0.17 px, so the gyro is not accurate enough to replace or smooth the measurement; what is left on measured frames is measurement noise under pan blur (exposure 5 ms in daylight = ~4.5 px of blur at 30 px/frame).
  - **Native 20x pan of the same garden, same morning** (`build/qa/stab_m9/n1/cmp.py`, native `VID20261005103121.mp4` 4K against the `u7` GateShot pan, same tracker, out px at 1080 width, pan section only; separate takes, 19.5 vs 16.4 px/frame):

    | | native | GateShot |
    |---|---|---|
    | across the pan, 1-3 Hz | 0.63 | 0.98 |
    | across the pan, 3-14 Hz | 0.22 | 0.57 |
    | along the pan, 1-3 Hz | 1.67 | 1.94 |
    | along the pan, 3-14 Hz | 0.20 | 0.59 |
    | up/down range over the pan | 257 | 51 |
    | largest frame step across | 6.6 | 8.0 |
    | Laplacian variance while panning | 658 | 407 |

    GateShot holds the height better and is close at 1-3 Hz; native is 2.5-3x steadier above 3 Hz and its frames measure sharper (native records 4K at 20x, GateShot 1080p; the two clips do not show identical content frame for frame, so the sharpness figure is indicative only).
  - **4K output at 20x: tried and reverted the same day** (`build/qa/stab_m9/k4/`; the user asked for it, then asked to switch back if it brought nothing). Two user clips at 20x in 4K: Laplacian variance at 4K analysis 35 against 33 for the 1080p pan enlarged with Lanczos, native 90 (`k4/sharp4k.py`); different content, so indicative. Load went up: an estimate took 132 ms, 3 measurements dropped, one second at 23 fps. `videoSize()` is back to 1080p at 20x/30x and 4K at 10x; that build is installed (10:48). The two 4K clips had much larger hand motion than earlier takes (the "still" one moved 650-880 px per second and hit the side bound), so they say nothing about stability. `rec.sh` must not be run while the user is handling the phone: its taps went to the native camera once.
  - **Kernel repo re-checked 2026-10-05 for how native stabilizes** (file list and four files in `build/qa/stab_m9/kern/`). It holds kernel drivers only; the EIS algorithm is in the closed camera HAL and is not in the repo. What it does show, in `vendor/oplus/kernel/camera/lens/ois/ois_def.h`: the OIS ("MOIS") has Movie / Still / Centering modes, `pantilt` and `anglelimit` settings, takes the AF focus distance, and has a `report_frequency` at which "hall offset data and gyro data" are reported to a client. So the native pipeline can read the lens position; a third-party app cannot. `ois_power.c` only switches OIS power and routes its I2C to the SCP. An OIS that is active and handles pans on its own is a candidate cause of the 0.8 pan gain; **not verified** (whether the OIS is powered during a GateShot session is unknown).
  - The video file of the pan has 684 frames for 695 log rows: 11 frames logged but not in the file (where they are lost is not checked). Align by lag before comparing video and log.
  - Still open: 10x up (45 px at the start of the clip; the up axis has no extra margin, 3840 is the longest stream side), a 26 px slow up move at 12-14 s of the 20x clip that the simulation does not predict, and the pan clip (`run_1791183090574`, not analysed).
  - `L1PathPlannerTest` now skips `kotlin_*.csv` (output of `L1DumpTest` in the same folder; it made the test fail on a fresh run).
- Not re-measured: sharpness of the whole-frame build on a fixed scene (the clips taken while the phone was being picked up are not comparable).
- `build/qa/stab_m9/rec.sh <apk> <tag>` installs a build and records 10x and 20x clips over adb (`HX/HY` = home-button tap for portrait `540 990`, `DUR` = seconds).
- The camera's largest stream is 4096x3072 / 3840x2160; there is no 8K stream. A 3840x2880 (4:3) stream exists and would give 33% more side margin in portrait: a candidate for the 10x sway problem.
- `aeAvailablePriorityModes` has a single entry, so there is no shutter-priority AE; an exposure cap needs manual exposure. The result log now prints exposure, ISO and focus distance once a second.
- `github.com/oppo/CameraUnit` was surveyed (subagent, page summaries only): stale since 2022-11, 2020–2021 devices, rear-main only, still authorization-gated. Not a route.

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
