package com.gateshot.processing.stabilize

import java.io.File

/**
 * Orchestrates the gyro + optical-residual hybrid stabilizer on a recorded clip
 * (port of `build/qa/camspike/hybrid.py`):
 *   pass 1a (optical measure) -> calibrate -> stage-1 gyro path
 *   -> pass 1b (residual on gyro-corrected frames) -> combine -> pass 2 warp+encode.
 *
 * Falls back to optical-only smoothing if the gyro/optical correlation is poor
 * (e.g. missing or mis-clocked gyro), rather than emit a worse-than-input clip.
 */
internal class StabilizerPipeline {

    data class Result(
        val outputPath: String,
        val frames: Int,
        val syncOffsetMs: Float,
        val r2x: Float,
        val r2y: Float,
        val fellBackToOptical: Boolean
    )

    private val analyzeN = 512

    fun stabilize(
        clipPath: String,
        gyroCsvPath: String,
        sigma: Float,
        cropFrac: Float,
        resSigma: Float,
        progress: (Float) -> Unit
    ): Result {
        val source = VideoFrameSource(clipPath)
        val meta = source.readMeta()
        val w = meta.width.toFloat()
        val h = meta.height.toFloat()
        val correlator = PhaseCorrelator(analyzeN)

        // ── Pass 1a: measure optical inter-frame motion ──────────────────────
        val mdxL = ArrayList<Double>(1024)
        val mdyL = ArrayList<Double>(1024)
        var prev: FloatArray? = null
        val ptsNs = source.forEachLuma(analyzeN) { luma, _ ->
            val p = prev
            if (p != null) {
                val s = correlator.correlate(p, luma)
                mdxL.add(s[0].toDouble() * w / analyzeN)
                mdyL.add(s[1].toDouble() * h / analyzeN)
            }
            prev = luma
        }
        progress(0.2f)

        val n = ptsNs.size
        if (n < 4) {
            // Too short to stabilize meaningfully — copy through unchanged.
            return Result(clipPath, n, 0f, 0f, 0f, fellBackToOptical = true)
        }
        val ft = DoubleArray(n) { ptsNs[it].toDouble() }
        val mdx = mdxL.toDoubleArray()
        val mdy = mdyL.toDoubleArray()

        // ── Calibrate gyro -> pixels + sync offset ───────────────────────────
        val gyroFile = File(gyroCsvPath)
        val haveGyro = gyroFile.exists()
        val calib = if (haveGyro) {
            Calibrator(GyroTrack.fromCsv(gyroFile)).calibrate(ft, mdx, mdy)
        } else null

        val poorFit = calib == null || (calib.r2x < 0.5 && calib.r2y < 0.5)

        val angleRad: FloatArray
        val txNorm: FloatArray
        val tyNorm: FloatArray
        val fellBack: Boolean
        val r2x: Float
        val r2y: Float
        val syncMs: Float

        if (!poorFit) {
            val gyro = GyroTrack.fromCsv(gyroFile)
            val cal = calib!!
            val fa = Calibrator(gyro).frameAngles(ft, cal.offsetNs)
            val px = FloatArray(n) { (cal.sx * fa[it][cal.axX]).toFloat() }
            val py = FloatArray(n) { (cal.sy * fa[it][cal.axY]).toFloat() }
            val roll = FloatArray(n) { fa[it][2].toFloat() }
            val gcx = sub(PathSmoother.gaussian1d(px, sigma), px)
            val gcy = sub(PathSmoother.gaussian1d(py, sigma), py)
            val gcr = sub(PathSmoother.gaussian1d(roll, sigma), roll)
            progress(0.3f)

            // ── Pass 1b: residual on gyro-corrected frames ───────────────────
            val (rcx, rcy) = measureResidual(source, w, h, gcx, gcy, gcr, resSigma, correlator)
            progress(0.45f)

            angleRad = gcr
            txNorm = FloatArray(n) { (gcx[it] + rcx[it]) / w }
            tyNorm = FloatArray(n) { (gcy[it] + rcy[it]) / h }
            fellBack = false
            r2x = cal.r2x.toFloat(); r2y = cal.r2y.toFloat()
            syncMs = (cal.offsetNs / 1e6).toFloat()
        } else {
            // Optical-only fallback: smooth the cumulative optical path.
            val ox = cumsum(mdx, n)
            val oy = cumsum(mdy, n)
            val cx = sub(PathSmoother.gaussian1d(ox, sigma), ox)
            val cy = sub(PathSmoother.gaussian1d(oy, sigma), oy)
            angleRad = FloatArray(n)
            txNorm = FloatArray(n) { cx[it] / w }
            tyNorm = FloatArray(n) { cy[it] / h }
            fellBack = true
            r2x = (calib?.r2x ?: 0.0).toFloat(); r2y = (calib?.r2y ?: 0.0).toFloat()
            syncMs = ((calib?.offsetNs ?: 0.0) / 1e6).toFloat()
            progress(0.45f)
        }

        // ── Pass 2: warp + encode ────────────────────────────────────────────
        val out = File(clipPath).let { File(it.parentFile, "${it.nameWithoutExtension}_stab.mp4") }
        val frames = VideoTranscoder().transcode(
            clipPath, out.absolutePath, meta, cropFrac, angleRad, txNorm, tyNorm, progress
        )
        progress(1f)
        return Result(out.absolutePath, frames, syncMs, r2x, r2y, fellBack)
    }

    /** Pass 1b: warp each frame by the stage-1 correction (at analysis scale),
     *  re-measure residual inter-frame motion, return smoothed residual (full-res px). */
    private fun measureResidual(
        source: VideoFrameSource,
        w: Float, h: Float,
        gcx: FloatArray, gcy: FloatArray, gcr: FloatArray,
        resSigma: Float,
        correlator: PhaseCorrelator
    ): Pair<FloatArray, FloatArray> {
        val scl = analyzeN / w
        val center = analyzeN / 2f
        val rdx = ArrayList<Double>(gcx.size)
        val rdy = ArrayList<Double>(gcx.size)
        var prevW: FloatArray? = null
        source.forEachLuma(analyzeN) { luma, idx ->
            val i = idx.coerceIn(0, gcx.size - 1)
            val warped = LumaWarp.affine(luma, analyzeN, center, center, gcr[i], gcx[i] * scl, gcy[i] * scl)
            val p = prevW
            if (p != null) {
                val s = correlator.correlate(p, warped)
                rdx.add(s[0].toDouble() * w / analyzeN)
                rdy.add(s[1].toDouble() * h / analyzeN)
            }
            prevW = warped
        }
        val n = gcx.size
        val rx = cumsum(rdx.toDoubleArray(), n)
        val ry = cumsum(rdy.toDoubleArray(), n)
        val rcx = sub(PathSmoother.gaussian1d(rx, resSigma), rx)
        val rcy = sub(PathSmoother.gaussian1d(ry, resSigma), ry)
        return rcx to rcy
    }

    /** Cumulative sum with a leading 0, clamped/padded to length [n]. */
    private fun cumsum(d: DoubleArray, n: Int): FloatArray {
        val out = FloatArray(n)
        var acc = 0.0
        for (i in 0 until n) {
            if (i > 0 && i - 1 < d.size) acc += d[i - 1]
            out[i] = acc.toFloat()
        }
        return out
    }

    private fun sub(a: FloatArray, b: FloatArray): FloatArray =
        FloatArray(a.size) { a[it] - b[it] }
}
