# TICKET-021: In-app telephoto capture (2026-09-24)

The user requires GateShot to record video itself so it can autofocus on a ski racer. The previous Oppo Camera handoff was rejected. GateShot's Home now opens a landscape in-app Camera2 viewfinder and records 1080p30 H.264 with AAC microphone audio into `GateShot/videos`; Replay opens after Stop. No broad phone media permission or phone media picker is requested from this workflow.

The Find X9 Pro exposes camera 0 (logical rear camera) to ordinary apps. At zoom ratio 3.03 the Camera2 capture result reported active physical ID `4`, the periscope sensor behind the Hasselblad accessory. Camera 6 remains system-only. The capture screen uses the full landscape height for a 16:9 viewfinder and hides bottom navigation. It allows either landscape grip and calculates preview and video rotation from sensor orientation, display rotation, and the accessory's 180-degree optical inversion. A test with display rotation 1 showed an upright preview and recorded frame; the opposite landscape grip needs an on-device user check.

On the installed build, a tap in the preview set `android.control.afRegions` and capture results reported MediaTek `trackingafMode=1` and the selected `trackingafRegion`. This proves the request reaches the HAL, not that it follows a moving racer correctly; that needs a course test.

The first direct camera-4 test recorded a valid MP4 but capture metadata showed OIS `OFF`. The revised camera-0 telephoto path recorded a valid MP4 and, **during recording**, reported `android.control.videoStabilizationMode=ON`, `android.lens.opticalStabilizationMode=ON`, and `com.oplus.video.stabilization.mode=2`. These flags prove the requested stabilization modes were accepted. They do not prove the resulting handheld telephoto video is acceptably steady. Earlier real clips showed AOSP EIS is much weaker than Oppo's native privileged camera-6 pipeline, and the user has reported that stabilization still appears off. A handheld A/B clip is required before calling the stabilizer fixed.

The 2026-09-24 ADB smoke test opened camera 0, confirmed physical ID 4, recorded and saved a 13-second clip with H.264 video and AAC audio, then opened it in Replay. The sample was static, so it cannot measure shake or tracking performance. The temporary test clips were removed from the phone after verification.

## Paused on 2026-09-24 at user request

The user reported two regressions after in-app capture was installed: the landscape viewfinder was a thin strip, then the image appeared upside down. The thin strip came from the portrait-style recording controls plus the always-visible bottom navigation. The current working tree hides bottom navigation on the Capture route and makes the preview a large 16:9 panel using the full available landscape height, with Back and Record controls in the side margins. An ADB screenshot of the installed build confirmed the larger panel.

For orientation, the current working tree uses `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` and computes the preview rotation and MP4 orientation hint as `(sensorOrientation - displayRotationDegrees + 180 + 360) % 360`. The final `180` represents the teleconverter's optical inversion. AF tap coordinates are inverted only when this correction is 180 degrees. `:app:assembleDebug --offline --console=plain --quiet` passed, and the updated debug APK was installed on the connected Find X9 Pro. With display rotation 1, the preview looked upright in an ADB screenshot. A prior sample video at rotation 1 was upright when decoded. The user has **not yet confirmed** whether the current build is upright in their hand, especially in the opposite landscape grip. An asynchronous clarification was asked about whether the live preview, saved video, or entire screen was upside down; the user instead asked to pause.

Resume by first checking the user's answer about which surface is inverted. Then test both landscape grips on the phone, comparing the live view with a saved frame containing an obvious top/bottom reference. Do not assert the orientation is fixed from the rotation-1 ADB screenshot alone. Also test a moving racer for tracking AF and a handheld telephoto clip for stabilization; camera metadata confirms the requests are ON but cannot establish image quality. The current debug APK is `app/build/outputs/apk/debug/app-debug.apk`; all implementation changes remain uncommitted. Temporary test recordings and screenshots created during this work were removed.

## 2026-09-28: portrait capture, orientation regression

The user confirmed that recording will be done in **portrait**, and that all tests must be done in portrait. In landscape, the 2026-09-24 build showed the live view upside down. That was a regression of the pre-pivot fix. The formula `(sensor - display + 180) % 360` replaced the proven CameraX approach, which was the normal portrait transform plus an extra 180 degrees for the periscope on both preview (`PreviewView.rotation = 180`) and recording (`targetRotation` offset by 2). The capture screen is now locked to `SCREEN_ORIENTATION_PORTRAIT` with a 9:16 viewfinder. The TextureView gets a fixed `rotation = 180`, and the MP4 orientation hint is `(sensorOrientation + 180) % 360`. Tap-to-focus maps portrait (x, y) to sensor (1 - y, x). The build is installed; the user has not yet confirmed that the live view and saved clip are upright.

User confirmed on 2026-09-28 that the portrait live view and replay are upright. The user also reported that tap-to-focus had no visible effect and that stabilization looked off.

- **Stabilization:** camera 0 lists video stabilization modes `[0 1 2]`. The code requested mode ON (1), which Android applies to the recording stream only, not the preview. It now prefers `PREVIEW_STABILIZATION` (2), which stabilizes preview and recording identically. On device, capture results report `videoStab=2 ois=1 physical=4`. The HAL accepts it; the visible result still needs a handheld user test.
- **Autofocus:** the camera supports one AF region and one AE region (`maxRegions [1 0 1]`). A tap previously only changed the region in CONTINUOUS_VIDEO mode. It now also sets the AE region and sends AF_TRIGGER CANCEL then START. On device, the trigger took the camera to FOCUSED_LOCKED (afState 4), which would freeze focus distance on an approaching racer. The capture callback therefore sends CANCEL once the lock is reached. It then returns to PASSIVE_FOCUSED (afState 2) with the tapped region kept. The Y coordinate accounts for the 16:9 centre crop of the active array. A yellow ring marks the tap. `TeleCapture` logs HAL results once a second (`adb logcat -s TeleCapture`).

## 2026-09-28: in-app gyro stabilizer, milestone 1

AOSP preview stabilization (mode 2) was accepted by the HAL but still looked unstabilized in the user's hand. GateShot now stabilizes itself. The July CameraX live-EIS (`LiveStabilizer`, `OnlineCalibrator`, CameraX effect/StreamSharing) is deliberately **not** reused. It failed live because CameraX bypassed the effect at tele, and the online calibration guessed signs.

- **Pipeline:** Camera2 camera 0 at zoom 3.03 streams 3840x2160 into one GL-owned SurfaceTexture (`StabRenderer`). Each frame is warped once and drawn to the TextureView viewfinder and, while recording, to MediaRecorder's 1920x1080 input surface with orientation hint 90. Preview and recording therefore show the same stabilized frame. HAL EIS and OIS requests are OFF so they cannot move the image behind the gyro model.
- **Model (`GyroStabilizer`):** each frame is corrected at its own `SENSOR_TIMESTAMP` + 8 ms, a sync offset fitted on the June clip. Rear-camera physics in the upright portrait image gives image x = +f·Δθ(gyro Y) and y = +f·Δθ(gyro X). The June clip confirms both axes and signs, with R² 0.99 after checking that OpenCV `phaseCorrelate` reports positive for content moving right. f = 35362 px/rad on the 3840 side. Roll is not corrected; at tele its visible effect is about 18x smaller than yaw or pitch.
- **Path filter:** a causal, critically damped 0.7 Hz filter with velocity feed-forward, so a steady pan following a racer is tracked without lag. The margin comes from a fixed 1.2x crop, which matches the Oppo teleconverter video framing measured the same day (`build/qa/framing_compare.png`). Replaying the June handheld gyro and frames through the filter removed 77% (x) and 69% (y) of high-frequency shake. The side-to-side axis is limited by the margin: 10 px left at 1.2x against 5.5 px with an unlimited margin. Soft-knee variants did not help.
- **On device:** 30 fps at 4K on physical camera 4, `videoStab=0 ois=0`, and gyro samples arrive about 70 ms before frames are drawn, so no extrapolation is needed. Camera timestamps are BOOTTIME and are shifted to MONOTONIC for the encoder; without this the MP4 had A/V timestamp gaps. Recorded MP4s are upright.
- **Tools:**
  - A Stab ON/OFF button toggles only the correction (same crop), for A/B tests.
  - Each recording writes `GateShot/stablogs/<run>_gyro.csv` and `<run>_frames.csv`.
  - `build/qa/stab_m1/analyze.py <mp4> <frames.csv>` aligns the log to the video frames by timestamp, then reports shake in the output against the shake reconstructed without correction. A wrong sign would show up as the output being worse.
- **Not yet verified:** handheld quality. The phone was only tested lying still.
- **Next (milestone 2):** about 0.5 s of lookahead smoothing for the recording, a rolling-shutter per-row warp, and the optical-residual second stage.

The user reported on 2026-09-28 that the milestone 1 stabilizer "looks better than before". For a like-for-like test against native, the capture screen now has **10x / 20x / 30x** chips, matching the Oppo teleconverter labels, where 10x is the base framing. The zoom is done by the camera (`CONTROL_ZOOM_RATIO` 3.03 / 6.06 / 9.09) rather than by enlarging the 4K frame in GL. The stabilizer focal length scales by the same factor (`GyroStabilizer.zoomFactor`), so the correction stays exact. The crop margin, measured in angle, shrinks proportionally, so 30x will hit the margin more often. The Oppo camera has the same physics. On device, each level reports its zoom ratio on physical camera 4 at 30 fps (`build/qa/zoom_levels.png`).

### 2026-09-28: first handheld A/B (garden, windy), `build/qa/stab_m1/ab1/`

The user judged milestone 1 "better than before but not near native". The GateShot clip ran 10x → 20x → 30x during one recording; the analysis must segment by zoom, which is inferred from `offx/dthetay`. Phase correlation over foliage in wind was unreliable, so feature tracking with RANSAC is used instead (`feat.py`, `whatif.py`, `bands.py`).

- **Shake removed, reconstructed raw vs output:** 60–68% at 10x and 20x, 49–63% at 30x (the margin clamps at 30x). No axis or sign error.
- **Same metric on both clips, % of frame:** fast jitter GateShot 0.11 (10x) / 0.15 (20x) vs Hasselblad 0.03. Low-frequency sway GateShot 2.1–3.4 (side axis) vs Hasselblad 0.2–0.4. The Hasselblad filmed the same wind, so plant motion is not the explanation.
- **The residual is not predictable from the gyro:** single-lag R² is below 0.1, and a multi-lag filter fits in-sample but fails cross-validation across zoom segments. A gyro gain or sync tweak cannot fix it. Simulated lookahead smoothing did not lower jitter, and roll is negligible at about 0.2 px.
- **Open question:** why gyro explains raw motion with R² 0.99 on the June camera-6 raw clip but much less here. Suspects are hardware OIS or HAL processing still active on camera 0 despite the OFF requests; camera 0 advertises OIS modes `[0]` only, so apps cannot control it. The decisive test is a Stab OFF handheld clip of a rigid, distant scene with gyro log: if gyro fails to explain the raw motion, the camera path is not rigid.

### 2026-09-28: correction to the A/B analysis, and the Stab OFF test (`build/qa/stab_m1/ab2/`)

**Analysis bug:** OpenCV `VideoCapture` auto-rotates frames using the MP4 display matrix, so the frames were already upright. The first scripts applied the stored-landscape to upright mapping a second time, which swapped the axes. The "60–68% removed" and "wrong gyro axis" results above are artifacts of that. The jitter comparison with the Hasselblad (`bands.py`) did not depend on axes and stands. `redo.py` is the corrected analysis.

- **Stab OFF, 20x, stone wall:** gyro explains raw image motion with R² 0.986 (x) and 0.994 (y). Axes and signs match the model. Scale is within 2% (41,783 and 43,470 against 42,437 px/rad predicted), and sync is +6 ms (the model uses +8). The camera path is rigid; there is no hidden OIS.
- **Rolling shutter:** strip timing drifts only 2–3 ms across the frame, and every strip fits at R² 0.99. Rolling shutter is minor.
- **Clip 1 at 20x, corrected axes:** raw 8.3/7.0 px goes to 1.64/1.70 px of HF jitter (76–80% removed). At 30x the side axis gets only 26% because of margin clamps.
- **Gyro-only floor:** even an ideal tripod lock from the gyro leaves 1.27/1.41 px. No filter or lookahead change can beat it. The likely cause is hand translation, which the gyro cannot see: at 20x (~2° FOV), 1 mm at 10 m is about 4 px.
- **Optical second stage, simulated:** live with one frame of delay it makes the result worse (1.85–2.17 px). On the recording with a lookahead buffer it reaches 0.29–0.53 px (0.27 s) and 0.13–0.28 px (0.53 s), against about 0.3 px for the Hasselblad. This carries the same teaching-to-the-test caveat as June.
- **Plan (milestone 2):** keep the viewfinder gyro-only. Give the recording a buffer of about 0.5 s of gyro-warped frames on the GPU, measure the leftover motion by phase correlation on a small downscaled copy, and encode each frame with the smoothed optical correction.

## 2026-09-28: milestone 2, optical second stage on the recording

- **Crop budget:** the final crop stays 1.2x. The gyro stage may use up to 1.15x (`GyroStabilizer.gyroCropZoom`). The remaining ~2% per side is for the optical stage.
- **`OpticalStage` (GL thread):**
  - While recording, each gyro-warped frame is rendered into a 16-slot RGBA ring at 1128x2004. The centre 768x768 is downsampled with a 3x3 box and luma to 256x256 and read back.
  - A 3-thread pool estimates the leftover shift between consecutive frames.
  - A frame is encoded 14 frames later, shifted onto its path smoothed with a Gaussian (σ = 5 frames, ±12). Stab OFF zeroes this stage too.
  - The frame log gains `optx, opty` (ring fractions).
- **`GlobalShiftEstimator` (processing/stabilize):** coarse-to-fine Lucas–Kanade with Huber weights, seeded by an exhaustive integer search at 64x64. Unit tests pin the sign (content right/down = positive), sub-pixel accuracy within 0.05 px, 11 px shifts, and robustness to a patch moving on its own (a racer).
  - The existing `PhaseCorrelator` was rejected: it read 3 px as 2.71 and 1.5 as 1.21 on broadband texture.
- **On device (still phone):** 30 fps is held and the MP4 is intact. The estimate takes ~50 ms per pair on one core, so it runs on 3 threads; 1 of 245 measurements was dropped. CPU cost is about 1.5 cores while recording; heat on the slope is to be watched.
- **Not yet verified:** handheld quality against the Hasselblad.

### 2026-09-28: milestone 2 A/B, stone wall at 10x and 20x (`build/qa/stab_m2/ab3/`)

The user's verdict: "the native stabilizer outperforms yours by miles". The measurements agree (feature tracking, px at 1080-wide output):

| Clip | Fast jitter x / y | Sway x / y |
|---|---|---|
| GateShot 10x | 1.3–1.7 / 1.5–1.8 | 16–18 |
| Hasselblad 10x | 0.13 / 0.08 | 3.1 / 4.3 |
| GateShot 20x | 4.2 / 3.2 | 38 / 25 |
| Hasselblad 20x | 0.19 / 0.08 | 1.7 / 1.3 |

Findings:
- **Stage breakdown (`stages.py`):**
  - Gyro stage removes 60–70% of jitter but little sway: at 20x, raw sway 44 → 41. It follows hand drift by design.
  - Optical stage: 2.1 → 1.7 at 10x, and nothing at 20x (3.95 → 4.22).
- **Optical stage under-corrects:** its path correlates 0.55–0.87 with the ideal correction, at a gain of only 0.31–0.61. The direction is right; the measurement under-reads.
- **Gyro floor on the raw 20x wall clip (`floor.py`):** 1.25 / 1.87 px (85–88% of HF explained). The residual barely depends on rotation speed, so it is not a timing error. It is non-rotational motion, most likely hand translation, which the Hasselblad removes.
- **Gyroscope:** 397 Hz with no gaps.
- **Zoom design flaw:** at 20x and 30x the camera zooms (`CONTROL_ZOOM_RATIO` 6.06 / 9.09), so the stabilizer margin is only 7% of an already-narrow field. Hand drift at 20x exceeds it; a hold-mode simulation hits the margin 18–20 times. Fix: keep the camera at 3.03 and crop 20x in GL from the 4K stream (1:1 source pixels at 1080p), leaving about 25% margin per side.
- **Hold mode:** a lower-cutoff filter without velocity feed-forward cuts 10x sway by 20–40% at unchanged jitter.
- **OIS:** camera 0 exposes no OIS control (`availableOpticalStabilization [0]`, no OIS data mode, no vendor OIS request key). The Hasselblad pipeline's hardware OIS and privileged EIS stay out of reach (see ticket 020).

## 2026-09-28: milestone 3 (user chose option A: fix the three causes)

1. **Zoom via crop.** 20x and 30x now use `CONTROL_ZOOM_RATIO` 3.636 and 5.454 plus a 2.0 GL crop; 10x is unchanged (3.03 with a 1.2 crop). Source pixels stay 1:1 at 1080p. The 20x margin grows from 85x150 to 517x918 output px. `GyroStabilizer.setView(cameraFactor, crop)` sets both; the gyro crop is always crop x 1.15/1.2. On device: 3.03 / 3.636 / 5.454 on physical camera 4.
2. **Hold filter.** `PathFilter` now holds at 0.1 Hz with no feed-forward and fades to the 0.7 Hz feed-forward follower between 0.5 and 1.5°/s of smoothed rate. It stiffens by (1 + 8e⁴) near the margin. Simulated on the ab3 logs (`hold2.py`, `hold10.py`):
   - Still: 20x sway 41 → 4.2 px, 10x 20 → 9.7 px.
   - 3–8°/s pans are followed at 1.4 px jitter with no clamps at 20x.
   - At 10x, 8°/s pans still clamp because of the small margin, as before.
3. **Optical stage.**
   - `GlobalShiftRealFramesTest` checks the estimator against OpenCV on 20 real 20x pairs: gain 0.99, RMS error 0.09 px. The estimator is not the cause of the under-correction.
   - Suspected cause: dropped or skipped measurements, which count as zero motion. Workers raised from 3 to 4 and the queue from 6 to 12, and gradients are now cached per level. On device: 0 dropped out of 187.
   - The frame log gains `measx, measy` (empty when missing), so the next A/B can check the gain directly.
4. **Sync offset:** 8 → 6 ms (camera 0 fits).

### 2026-09-28: milestone 3 A/B, wall at 10x/20x still and a 20x pan (`build/qa/stab_m3/ab4/`)

| Clip | Jitter x / y | Sway x / y |
|---|---|---|
| GateShot 20x still | 1.01 / 1.34 | 9.9 / 5.2 |
| Hasselblad 20x still | 0.72 / 0.36 | 2.2 / 3.8 |
| GateShot 10x still | 0.77 / 1.46 | 11 / 17 |
| Hasselblad 10x still | 0.67 / 0.44 | 1.9 / 1.7 |
| GateShot 20x pan | **9.94** / 2.53 | – |
| Hasselblad 20x pan | 0.48 / 0.34 | – |

- **Still clips:** a large improvement over the milestone 2 run (20x jitter 4.2/3.2 → 1.0/1.3, sway 38/25 → 10/5). This round's Hasselblad clips were also shakier (0.7 against 0.2).
- **Pan failure:** in the gyro stage, the 0.7 Hz rate estimate treated the changing speed of a hand pan as intent (gyro-only jitter 9.4 against 11.8 raw). The optical stage also rejected shifts over 15 px, leaving 263 of 569 frames unmeasured.
- **Tuning on the real pan (`tune.py`):**
  - Rate estimate at 0.1 Hz: pan jitter 9.0 → 2.6, still clips unchanged or better.
  - Look-ahead Gaussian (σ = 8 frames within ±12) on the recording: pan jitter → 0.6 px (Hasselblad 0.48), and still clips improve further. It needs up to 123 px of extra margin at 20x.
- **Implemented:**
  - `PathFilter` rate estimate at 0.1 Hz.
  - `OpticalStage`: SIGMA 8, shift rejection raised to 60 px, ring enlarged to 1380x2454 (about 217 MB of GPU memory).
  - At 20x/30x the ring holds source/(crop/1.278), leaving about 14% look-ahead margin per side; 10x is unchanged at about 2%.
- **On device at 20x:** 30 fps held, allocation OK, 0 dropped, 3 rejected, MP4 intact.

### 2026-09-28: offline tuning to beat the Hasselblad (no new recordings)

The user asked for the largest possible improvement before the next live test, with the goal of beating the Hasselblad stabilizer. `build/qa/stab_m3/ab4/e2e.py` replays the whole recording pipeline on the three real clips (20x still, 10x still, 20x pan). It uses the raw motion reconstructed from saved video plus logs, including non-rotational motion, adds measurement noise of 0.27 px, and applies the real margins.

- **Sway diagnosis (`diag.py`):** most sway is rotational, so the gyro hold was too loose. Non-rotational drift is about 4 px at 20x and 6–11 px at 10x.
- **Architecture:** a "recording follows, look-ahead holds" split was tried and rejected (`arch.py`): the ring margin is far smaller than the gyro margin, so it saturated. Kept instead:
  - Separate gyro paths from the same gyro: the viewfinder holds firmly (0.05 Hz) and the recording ring holds more loosely (0.1 Hz).
  - The recording look-ahead stage (σ = 8, ±12 frames) is followed by a hold on the smoothed path. Pan detection uses the centred slope over the window (0.15–0.35°/s), the hold moves with a pan through feed-forward, catches up over 0.15 s, releases progressively from 30% of the margin, and holds at 0.05 Hz when still.
- **Simulated with the chosen settings** (`combo5.py`, "stiff .3 rel 1 gyro .1"), against the Hasselblad's measured numbers:

| Clip | Jitter x / y | Sway x / y | Hasselblad jitter / sway |
|---|---|---|---|
| 20x still | 0.29 / 0.26 | 5.6 / 2.0 | 0.72/0.36, 2.2/3.8 |
| 10x still | 0.28 / 0.27 | 10.9 / 7.5 | 0.67/0.44, 1.9/1.7 |
| 20x pan | 0.87 / 0.30 | – | 0.48/0.34 |

  The simulated jitter beats the Hasselblad everywhere except pan x. Sway still loses at 10x (the 1.2 crop margin) and on the 20x x axis.
- **Code:**
  - `RecordingPathPlanner` (processing/stabilize) holds the planner maths. `RecordingPathPlannerReplayTest` feeds it the simulation's real-clip inputs and it matches the Python output to 0.0000 px on all 6 axis tracks.
  - `OpticalStage` uses it. `GyroStabilizer.Correction` now carries separate ring offsets (`forRing()`), and the frame log records the ring offsets.
- **Pending:** a probe of whether the periscope stays active at `CONTROL_ZOOM_RATIO` below 3.03, which would give 10x a real margin. The phone disconnected before the probe build could be installed; the source was reverted to the real mapping and the APK rebuilt.
- **Probe result:** at `CONTROL_ZOOM_RATIO` 2.88 / 2.73 / 2.58 the logical camera uses **physical camera 2** (main), not the periscope. 3.03 is the floor. `cameraFactor` is now clamped to ≥ 1.
- **10x crop 1.2 → 1.25** (framing 4% tighter than the Oppo 10x). Simulated 10x sway goes from 10.9/7.6 to 5.2/5.0, and jitter from 0.29/0.30 to 0.27/0.28 (`crop10.py`); 1.3 and 1.4 were worse. The `gyroCropZoom` branch now splits at crop 1.5.
- **Final build on device:** 10x/20x/30x run on physical camera 4 (3.03 / 3.636 / 5.454). A 10x recording held 30 fps with 0 dropped measurements and a clean MP4. Ready for the user's final live A/B.

### 2026-09-28: final A/B worse than simulated; shader precision bug found (`build/qa/stab_m4/`)

The user reported that the milestone 3 build looked worse than before, with the image "moving by itself, mostly up and down, without correlation to the hand". Measured (`ab5/`):

| Clip | Jitter x / y | Sway x / y | Hasselblad jitter / sway |
|---|---|---|---|
| 20x still | 1.38 / 1.56 | 9.9 / 20.2 | 1.26/0.59, 1.9/1.7 |
| 10x still | 1.05 / 1.17 | 3.8 / 6.9 | 1.21/0.71, 5.3/7.3 |
| 20x pan | 5.64 / 2.56 | – | 0.56/0.47 |

- **Cause:** the app's optical measurements had 1.9–2.1 px error with gains of 0.35–0.84 (`check.py`). The estimator itself matched OpenCV on frames from this clip (gain 1.02, error 0.08).
- **Root cause:** every fragment shader used `precision mediump float`. On Mali that is fp16, which snaps texture coordinates of a 4K texture to 1–2 texel steps. The camera warp therefore moved in whole-pixel jumps, and the measurement images were distorted. The planner's image hold then integrated the biased, noisy measurements into a drift, which is the "moves by itself" the user saw.
- **Known-signal test:** a debug hook (`adb shell setprop debug.gateshot.synth 1`) adds a timestamp-driven wobble (0.6 px at 2.3 Hz in x, 0.8 px at 3.1 Hz in y) on a phone lying still (`synth/synth.py`). Measured against all applied offsets:

| Build | Measurement error | Saved-video jitter |
|---|---|---|
| mediump | 0.28–0.36 px (corr 0.84–0.91) | 0.16 / 0.21 px |
| highp | **0.08–0.11 px** (corr 0.99) | **0.07 / 0.07 px** |

  16-bit luma readback made no measurable difference; it is kept as harmless.
- **Fixes:**
  - All fragment shaders use highp, guarded by `GL_FRAGMENT_PRECISION_HIGH`.
  - The long-term image hold in `RecordingPathPlanner` is disabled (`holdEnabled = false`): an integrated image path can drift, and snow slopes will have little texture. Holding is left to the gyro. The recording keeps the ±12-frame look-ahead smoothing.
  - The recording's gyro hold stays at 0.1 Hz for pans; the viewfinder stays at 0.05 Hz.
- **Simulated, look-ahead only, measurement noise 0.3:** 20x still 0.27/0.26 jitter, sway 11/4; 10x still 0.28/0.28, sway 12/7; 20x pan 0.67/0.29.
- **Also found:** on a still phone, gyro noise alone moves the gyro-warped picture by ~0.5 px/frame at 20x. The recording removes it; the viewfinder shows it.

### 2026-09-28: the user chose option A (continue R&D); pixel test bench, rolling shutter, OIS probe

- **Confirmed-build A/B (`stab_m4/ab6/`):** the phone ran the exact latest APK (md5-identical). GateShot 20x still 0.96/0.76, 10x 1.32/1.14, 20x pan 4.86/1.33; Hasselblad 0.10/0.10, 0.82/0.34, 0.33/0.27.
- **The app's measurement is correct** (`center.py`): it matches the video's centre square to 0.24–0.31 px (corr 0.97–0.99). But centre and whole-frame motion differ (corr 0.35 in x), so the residual motion is not rigid.
- **Hypotheses ruled out:**
  - Roll: gyro Z explains 8% of the apparent rotation.
  - Frame drops: none in video or log.
  - Measurement fault: see above.
- **Deformation, per-frame at the frame edge (`deform.py`):** GateShot rotation 1.2 / x-stretch 1.0 / y-stretch 0.15 / skew 1.1–1.2 px against the Hasselblad's 0.6–0.8 / 0.3–0.4 / 0.03–0.08 / 0.6–0.8. The x-stretch plus skew is the rolling-shutter signature: the sensor reads the portrait image column by column across its width.
- **OIS probe:** `adb shell setprop debug.gateshot.ois 1` requests LENS_OPTICAL_STABILIZATION_MODE_ON; the HAL echoes ois=1. On a handheld Stab OFF 20x clip, image motion still follows the gyro 1:1 in every band (0.3–14 Hz gain 0.86–1.00, corr 0.94–1.00). **Hardware OIS is not available** to a third-party app on camera 0.
- **Pixel test bench** (`stab_m5/ois/bench*.py`): the stabilizer is applied offline, with cv2.remap, to the real pixels of the raw Stab OFF 20x clip and scored with the Hasselblad metrics. It reproduces the phone: the app design without RS correction gives 0.90/1.20 against 0.96/0.76 measured. `BenchReplayTest` confirms the Kotlin estimator on bench frames matches OpenCV (gain 0.995, 0.12 px).

| Bench configuration | Jitter x / y (px) |
|---|---|
| Raw | 12.3 / 11.9 |
| Gyro stage | 1.59 / 3.25 |
| + RS correction, T = +3.5 ms across the 20x view | 1.48 / 2.90 (skew 0.70 → 0.35, stretch 0.96 → 0.20) |
| + optical stage, centre at 256 (app design) | **0.30 / 0.20** |

  Whole-frame or larger regions at 256 give the same result. Full-resolution whole-frame tracking reached 0.10 / 0.09, but that is largely the scorer's own measure. Sway stays at 10 / 4 px against the Hasselblad's 1–2.
- **Implemented:** gyro-driven rolling-shutter correction folded into the affine warp. Column X is shifted by (rsX, rsY) × X, with rs = f × gyro rate × readout; readout is 7.0 ms across the source at zoom 3.636, scaled as 1/zoom (`READOUT_S_AT_BASE_ZOOM`). It applies to the viewfinder and the recording. 20x is calibrated; the 10x/30x scaling is physics-based and not yet measured.
- **Next:** the user's live A/B of this build (20x still, 10x still, 20x pan, each with the Hasselblad). Check skew and stretch with `deform.py` to validate the 10x readout scaling.

### 2026-09-28: A/B of the RS build (`stab_m5/ab7/`), and correcting an overclaim

**Measured:**

| Clip | GateShot jitter x / y | Hasselblad jitter x / y |
|---|---|---|
| 10x still | 0.18 / 0.52 | 0.19 / 0.19 |
| 20x still | 0.53 / 0.62 | 0.51 / 0.23 |
| 20x pan | 3.22 / 4.74 | 0.53 / 0.21 |

Edge deformation (skew / stretch) is now GateShot 0.18–0.19 / 0.14 against the Hasselblad's 0.68–0.76 / 0.47–0.49, so the RS correction works, including the 10x scaling.

**Overclaim corrected:** I told the user that side-to-side jitter "equals the Hasselblad". The user was right that it does not. The HF metric (σ = 3 frames) ignores slow wobble, which dominates what the eye sees. Side-to-side position wobble by band (`bandcmp.py`):

| Clip | 0.2–1 Hz | 1–3 Hz | 3–14 Hz |
|---|---|---|---|
| GateShot 10x | **14.45** | 0.34 | ≈ equal |
| Hasselblad 10x | 0.73 | 0.13 | |
| GateShot 20x | **5.01** | 0.40 | ≈ equal |
| Hasselblad 20x | 1.38 | 0.28 | |

**From now on, report all bands.**

**Cause of the 20x slow wobble:** the recording gyro path carried the hold offset from aiming into the recording (61–65% of the margin at Record) and then drifted back. The Python replica started from the app's logged state reproduces the app exactly (5.85 px). Reset at Record: **1.16 px** (the Hasselblad has 1.38). Fix: `GyroStabilizer.restartRecordingPath()` is called when recording starts.

**10x is a structural limit:** with the reset, 0.2–1 Hz side wobble is 9.0 px, and even a 1.40 crop leaves 5.4 side / 9.3 up against the Hasselblad's 0.73. The periscope is only available to apps from zoom 3.03, the 10x field itself, so there is no spare margin. The Oppo camera uses privileged camera 6.

**20x pan (open):** during the pan the image measurement error is 4.5–7.8 px per frame (motion blur at 20–40 px per frame), and image motion is only 0.83x the gyro prediction on the side axis, with 6–7 px unexplained. Blending towards the gyro prediction did not help (`blend.py`). The next step is a raw Stab OFF 20x pan clip for the pixel bench.

### 2026-09-28: live L1-optimal camera path (20x/30x), logo

- **Background:** the user asked why not use established or open-source methods. The pipeline already follows Karpenko 2011 (gyro + RS), Gyroflow and the Pixel fused-stabilization structure; the hand-made hold/pan logic was the weak part. Replaced by **L1-optimal camera paths** (Grundmann, Kwatra & Essa 2011, the YouTube stabilizer). Minimise 10|P'| + 1|P''| + 100|P'''|, subject to |P − C| ≤ margin: still segments, constant-speed pans and smooth transitions.
- **Pixel bench** (raw 20x pan + raw 20x still, all bands, `stab_m6/pan/design*.py`):
  - Full-path look-ahead with ±1 s beats ±0.4 s on pans.
  - L1 plus optical beats the Gaussian look-ahead on still sway (13.4 → 3.3 px on the bench's small margin).
  - **Live L1** (receding horizon, 1 s look-ahead, the last 3 committed values fixed) matches whole-clip L1: pan side 1–3 Hz 1.36 / 3–14 Hz 0.11, up 0.41 / 0.10, against the Hasselblad's 3.14 / 0.35 and 0.33 / 0.18. Still side 0.2–1 Hz 2.36 against 1.38.
  - The user rejected a record-then-process design (waiting after Stop is unacceptable on the slope), so it must run live.
- **Solver (`L1PathPlanner`, processing/stabilize):**
  - ADMM and a log-barrier interior-point method were not accurate enough; inexact paths add visible jitter on the bench.
  - Final: **bounded-variable primal simplex**, started at the camera path, with the objective row kept in the tableau, drift-free basic values, Bland anti-cycling, and a proximity tie-breaker 0.01|P − C|. Without the tie-breaker, degenerate windows committed to a band edge (all constants in the band were optimal).
  - `L1PathPlannerTest` matches the HiGHS LP to 0.0 px and 0.0% objective on 4 real tracks. PC time 4–9 ms per axis per frame; **phone 26 ms**, on one thread per axis.
- **App (20x/30x only; 10x unchanged):**
  - The recording ring holds the raw view (RS only). The viewfinder keeps its own gyro hold.
  - Look-ahead is 30 frames; the ring is 36 frames at 1380x2454 (~0.5 GB), allocated and planner-JIT-warmed when the camera screen opens.
  - Correction = (C − P) + optical leftover (measurement − gyro-predicted motion, Gaussian ±12).
  - The GL thread never blocks on a plan unless the ring would overwrite the frame.
- **On device:** 30 fps, 0 missing plans, estimator ~94 ms per pair on 4 threads. The on-phone l1x/l1y equal the Python reference to 0.000 px on a real clip. The frame log adds rawx, rawy, l1x, l1y.
- **Logo:** `Image_big_cat_v1.png` is now the adaptive app icon (white background, monochrome layer removed; the user confirmed it). It is also a watermark burned into recordings: 160 px on the 1080-wide video, bottom right, 85% opacity, verified in a saved clip.
- **Bug fixed:** the watermark fields were declared after the `init` block that used them (Kotlin initialisation order), which gave an NPE when the camera opened.

### 2026-09-28: live L1 A/B, then the self-centred 1.5x buffer (`stab_m7/ab8/`, build in `stab_m8/`)

**User verdict:** "don't see much improvement, the native still beats yours by miles". Measured position wobble (px):

| Clip | GateShot | Hasselblad |
|---|---|---|
| 20x still, 0.2–1 Hz side / up | 11.1 / 20.4 | 1.8 / 4.7 |
| 20x still, 1–3 Hz side / up | 1.05 / 1.41 | 0.10 / 0.17 |
| 20x pan, 1–3 Hz side / up | 3.90 / 2.12 | 2.47 / 0.44 |

- **Sign check (`signcheck.py`):** the corrections are applied correctly; video = raw − correction fits with corr 0.99.
- **Cause:** the camera path wobbled 36/75 px (0.2–1 Hz) during the "still" shot, and the L1 path was confined to ±120/±213 px around the raw camera (buffer 1.278x). It hit those limits on both sides throughout (`margin.py`), so P kept 12/27 px of wobble.
- **Fix (simulated in `selfcenter.py` on these clips):** centre each buffered frame on the planner's latest tentative path, extrapolated two frames and clamped to the source field (±0.95 of the gyro-warp margin). Bound the planner by the whole field intersected with ±90% of the buffer margin around that centre. With a 1.5x buffer (~670 MB):
  - Still clip: P has 0.00 px in every band.
  - Pan: side 1–3 Hz 2.26 (Hasselblad 2.47), up 0.34 (Hasselblad 0.44).
- **Solver:** `L1PathPlanner.nextBounded(c, lo, hi)` gives a feasible start when the camera path lies outside the band (p' at a bound with d± basic). `L1BoundsTest` shows a 0.00000% gap to the LP on 20 asymmetric windows. The symmetric tests are still exact.
- **App:**
  - 20x/30x ring 1620x2880, 36 frames; `gyroCropZoom` = crop/1.5.
  - The ring is drawn with offsets (C − S), and the optical residual is measured relative to S.
  - Final correction = (S − P) + optical; the log's l1x/l1y now hold S − P.
- **On device:** 30 fps, 0 missing plans, planner 27 ms, estimator 66 ms/pair, clean MP4.
- **Oppo CameraUnit SDK (read-only look):** it is on the phone as a shared library (`com.oplus.camera.unit.sdk`). It contains the super-EIS path through Oppo's APS service, but it is gated by Oppo app authorization (an approved-apps list). Circumventing that gate was not pursued. The legitimate route is to apply to Oppo's CameraUnit / open-capability program.

### 2026-09-28 (end of day): PAUSED. See `docs/HANDOFF.md` "RESUME HERE"

- **Self-centred L1 A/B (`stab_m8/ab9`):**
  - 20x still is roughly equal to or better than native in most bands.
  - 20x pan is BROKEN: up-axis jumps of 20–40 px per frame (1–3 Hz 14.3 vs 0.45; 3–14 Hz 10.5 vs 0.15).
  - 10x still sway is 24.7 vs 3.4.
  - The user judges the whole result worse and doubts that AF works.
- **Sharpness (Laplacian variance):** GateShot is about half as sharp as native (10x 98 vs 201, 20x still 17.5 vs 31.3, pan 15.6 vs 26.8).
- **Literature survey:** Karpenko 2011, Google fused stabilization 2017, Gyroflow velocity damping, exposure caps, and others. The list is in HANDOFF.
- **Next:**
  1. Image quality: 4K HEVC, a single sharp resample, an exposure cap, focus lock.
  2. Gyroflow-style velocity-dampened smoothing replacing live L1 + self-centring.
  3. RS mesh and on-device calibration.
