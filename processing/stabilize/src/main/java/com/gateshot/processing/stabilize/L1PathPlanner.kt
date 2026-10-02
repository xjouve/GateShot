package com.gateshot.processing.stabilize

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Live L1-optimal camera path (Grundmann, Kwatra & Essa 2011, the YouTube
 * stabilizer) over a sliding look-ahead window, one axis.
 *
 * The virtual camera path P minimises w1|P'| + w2|P''| + w3|P'''| (L1 norms), so
 * it is made of genuinely still segments, constant-speed pans and smooth
 * transitions -- a tripod when the camera is held still, a clean pan when it
 * follows a racer, with no hold/pan switching. Each frame, the window
 * [i, i + lookahead] is planned with the last three committed path values fixed
 * (continuity), P_i is committed, and the window slides. Constraint: P stays
 * within +/- margin of the camera path C (the crop the output can move in).
 *
 * Solved with ADMM (x = P; z = D x; y = box-projected x); the linear step uses a
 * cached Cholesky factor of (D^T D + I) per window size. Validated against an LP
 * solution of the same problem on real gyro paths (L1PathPlannerTest).
 */
private const val ALPHA = 1.7
/** Proximity tie-breaker weight (vs w1 = 10): picks the most centred of equally optimal paths. */
private const val EPS = 0.01

class L1PathPlanner(
    private val lookahead: Int = 30,
    private val w1: Double = 10.0,
    private val w2: Double = 1.0,
    private val w3: Double = 100.0,
    private val rho: Double = 1.0,
    private val iterations: Int = 300,
) {
    private val committed = ArrayList<Double>()   // last few committed path values
    private var warm: DoubleArray? = null         // previous window solution, for warm start
    private val factors = HashMap<Int, Array<DoubleArray>>()

    fun reset() { committed.clear(); warm = null }
    /** Diagnostics: simplex iterations of the last / worst window. */
    var lastIterations = 0; private set
    var maxIterations = 0; private set
    var exitOptimal = 0; private set
    var exitUnbounded = 0; private set
    var infeasibleRows = 0; private set

    /**
     * Commit the path for the next frame. [future] holds the camera path C for
     * this frame and the known frames after it (index 0 = this frame).
     */
    fun next(future: DoubleArray, margin: Double): Double =
        nextBounded(future, DoubleArray(future.size) { future[it] - margin }, DoubleArray(future.size) { future[it] + margin })

    /** Last value of the most recent window solution: the planner's current estimate of the
     *  path at the newest known frame (no look-ahead yet). */
    @Volatile var tentative = Double.NaN; private set
    private var lastWin = DoubleArray(0)
    /** The last window's solution for its future frames (tests / diagnostics). */
    fun lastWindow(): DoubleArray = lastWin.copyOf()

    /**
     * As [next], with explicit bounds per future frame (lo <= P <= hi), and [future] = the
     * camera path the tie-breaker keeps P close to.
     */
    fun nextBounded(future: DoubleArray, loF: DoubleArray, hiF: DoubleArray): Double {
        val h = min(3, committed.size)
        val n = h + future.size
        val c = DoubleArray(n)
        val lo = DoubleArray(n)
        val hi = DoubleArray(n)
        for (j in 0 until h) {
            val v = committed[committed.size - h + j]
            c[j] = v; lo[j] = v; hi[j] = v
        }
        for (j in future.indices) {
            c[h + j] = future[j]; lo[h + j] = loF[j]; hi[h + j] = max(hiF[j], loF[j] + 1e-6)
        }
        val p = if (n < 4) DoubleArray(n) { (lo[it] + hi[it]) / 2 } else solve(n, c, lo, hi, h)
        val out = p[h]
        tentative = p[n - 1]
        lastWin = p.copyOfRange(h, n)
        committed.add(out)
        if (committed.size > 8) committed.removeAt(0)
        // Warm start: the next window starts one frame later.
        warm = p.copyOfRange(h + 1, n)
        return out
    }

    /**
     * Exact solve: bounded-variable primal simplex. Variables: p' = P - lo in
     * [0, hi - lo] for the window's future path, and s+/s- >= 0 per difference row
     * with (D P)_k + d_k = s+_k - s-_k (d_k: the fixed history's contribution).
     * Cost w_k (s+ + s-). The all-slack basis (p' = 0) is feasible, so there is no
     * phase 1. Dantzig pricing, Bland's rule after degenerate pivots (anti-cycling).
     */
    private fun solve(n: Int, cAbs: DoubleArray, loAbs: DoubleArray, hiAbs: DoubleArray, h: Int): DoubleArray {
        // Work relative to the window's first value: keeps numbers small (px, not thousands).
        val origin = cAbs[0]
        val lo = DoubleArray(n) { loAbs[it] - origin }
        val hi = DoubleArray(n) { hiAbs[it] - origin }
        val nf = n - h
        val m1 = n - 1; val m2 = n - 2; val m3 = n - 3
        val m = m1 + m2 + m3
        val wts = DoubleArray(m) { k -> if (k < m1) w1 else if (k < m1 + m2) w2 else w3 }
        val dBase = DoubleArray(m); applyD(lo, dBase, n)      // history rows: lo == hi == value
        val unit = DoubleArray(n); val colD = DoubleArray(m)
        // Columns: p' [0,nf) | s+ [nf,nf+m) | s- [nf+m,nf+2m) | d+ [nf+2m,nf+2m+nf) | d- [.., nf+2m+2nf)
        // Rows: m difference rows, then nf proximity rows p'_j - d+_j + d-_j = c_j - lo_j
        // (tie-breaker EPS*|P - C|: among equally optimal paths, keep the one nearest the
        // camera path -- committing to a band edge leaves no room for later frames).
        val nv = nf + 2 * m + 2 * nf
        val rowsN = m + nf
        val t = Array(rowsN) { DoubleArray(nv + 1) }
        for (j in 0 until nf) {
            java.util.Arrays.fill(unit, 0.0); unit[h + j] = 1.0
            applyD(unit, colD, n)
            for (k in 0 until m) t[k][j] = colD[k]
        }
        val cost = DoubleArray(nv)
        for (k in 0 until m) { cost[nf + k] = wts[k]; cost[nf + m + k] = wts[k] }
        for (j in 0 until nf) { cost[nf + 2 * m + j] = EPS; cost[nf + 2 * m + nf + j] = EPS }
        val ub = DoubleArray(nv) { Double.POSITIVE_INFINITY }
        for (j in 0 until nf) ub[j] = hi[h + j] - lo[h + j]
        val basis = IntArray(rowsN)
        val atUpper = BooleanArray(nv)
        val isBasic = BooleanArray(nv)
        // Start at the camera path clipped into [lo, hi]. Per proximity row
        // p'_j - d+_j + d-_j = c_j - lo_j:  inside -> p'_j basic; above the band ->
        // p'_j at its upper bound, d-_j basic; below -> p'_j at 0, d+_j basic (row negated).
        val pBasic = BooleanArray(nf)
        for (j in 0 until nf) {
            val row = t[m + j]
            row[j] = 1.0; row[nf + 2 * m + j] = -1.0; row[nf + 2 * m + nf + j] = 1.0
            val rhs = (cAbs[h + j] - origin) - lo[h + j]
            row[nv] = rhs
            when {
                rhs in 0.0..ub[j] -> { basis[m + j] = j; pBasic[j] = true }
                rhs > ub[j] -> { atUpper[j] = true; basis[m + j] = nf + 2 * m + nf + j }
                else -> { for (q in 0..nv) row[q] = -row[q]; basis[m + j] = nf + 2 * m + j }
            }
        }
        for (k in 0 until m) {
            val row = t[k]
            row[nv] = -dBase[k]
            for (j in 0 until nf) {
                if (!pBasic[j]) continue
                val f = row[j]
                if (f != 0.0) { val pr = t[m + j]; for (q in 0..nv) row[q] -= f * pr[q] }
            }
            row[nf + k] = -1.0; row[nf + m + k] = 1.0
            // Current value of this row's slack given nonbasic p' at their upper bounds.
            var v = row[nv]
            for (j in 0 until nf) if (atUpper[j]) v -= row[j] * ub[j]
            if (v < 0) for (q in 0..nv) row[q] = -row[q]
            basis[k] = if (v >= 0) nf + m + k else nf + k
        }
        for (b in basis) isBasic[b] = true
        val beta = DoubleArray(rowsN)
        fun refreshBeta() {
            for (k in 0 until rowsN) {
                var v = t[k][nv]
                for (j in 0 until nf) if (!isBasic[j] && atUpper[j]) v -= t[k][j] * ub[j]
                beta[k] = v
            }
        }
        refreshBeta()
        // Objective row of reduced costs, kept up to date by the pivots.
        val red = DoubleArray(nv)
        for (j in 0 until nv) {
            if (isBasic[j]) continue
            var z = 0.0
            for (k in 0 until rowsN) { val a = t[k][j]; if (a != 0.0) z += cost[basis[k]] * a }
            red[j] = cost[j] - z
        }
        val eps = 1e-9
        var degenerateRun = 0
        var iter = 0
        while (iter < 20000) {
            iter++
            val bland = degenerateRun > 10
            var enter = -1; var bestScore = 1e-7
            for (j in 0 until nv) {
                if (isBasic[j]) continue
                val score = if (!atUpper[j]) -red[j] else red[j]
                if (score > bestScore) {
                    enter = j; bestScore = score
                    if (bland) break
                }
            }
            if (enter < 0) { exitOptimal++; break }
            val dir = if (atUpper[enter]) -1.0 else 1.0
            var theta = ub[enter]
            var leave = -1; var leaveToUpper = false
            for (k in 0 until rowsN) {
                val a = dir * t[k][enter]
                var lim = Double.POSITIVE_INFINITY; var toUpper = false
                if (a > eps) lim = max(0.0, beta[k]) / a
                else if (a < -eps && ub[basis[k]].isFinite()) { lim = max(0.0, ub[basis[k]] - beta[k]) / -a; toUpper = true }
                if (lim < theta - 1e-12 || (lim <= theta + 1e-12 && leave >= 0 && basis[k] < basis[leave])) {
                    if (lim.isFinite()) { theta = lim; leave = k; leaveToUpper = toUpper }
                }
            }
            if (theta.isInfinite()) { exitUnbounded++; break }
            degenerateRun = if (theta < 1e-9) degenerateRun + 1 else 0
            if (leave < 0) { atUpper[enter] = !atUpper[enter]; refreshBeta(); continue }
            val old = basis[leave]
            isBasic[old] = false; atUpper[old] = leaveToUpper
            basis[leave] = enter; isBasic[enter] = true; atUpper[enter] = false
            val pr = t[leave]; val piv = pr[enter]
            for (j in 0..nv) pr[j] /= piv
            for (k in 0 until rowsN) {
                if (k == leave) continue
                val row = t[k]; val f = row[enter]
                if (f != 0.0) for (j in 0..nv) row[j] -= f * pr[j]
            }
            // Reduced costs: red -= red[enter] * pivot row.
            val re = red[enter]
            for (j in 0 until nv) red[j] -= re * pr[j]
            red[enter] = 0.0
            refreshBeta()
        }
        lastIterations = iter
        maxIterations = max(maxIterations, iter)
        for (k in 0 until rowsN) {
            val u = ub[basis[k]]
            if (beta[k] < -1e-6 || (u.isFinite() && beta[k] > u + 1e-6)) { infeasibleRows++; break }
        }
        val out = DoubleArray(n)
        for (j in 0 until h) out[j] = lo[j] + origin
        for (j in 0 until nf) out[h + j] = lo[h + j] + (if (atUpper[j]) ub[j] else 0.0) + origin
        for (k in 0 until rowsN) if (basis[k] < nf) out[h + basis[k]] = lo[h + basis[k]] + beta[k] + origin
        return out
    }

    private fun denseSolve(a: Array<DoubleArray>, b: DoubleArray, x: DoubleArray, n: Int) {
        // Cholesky (the reduced Hessian is symmetric positive definite).
        val l = Array(n) { DoubleArray(n) }
        for (i in 0 until n) for (j in 0..i) {
            var s = a[i][j]
            for (k in 0 until j) s -= l[i][k] * l[j][k]
            l[i][j] = if (i == j) sqrt(max(s, 1e-14)) else s / l[j][j]
        }
        cholSolve(l, b, x, n)
    }

    /** z = [D1 x; D2 x; D3 x]. */
    private fun applyD(x: DoubleArray, out: DoubleArray, n: Int) {
        var k = 0
        for (i in 0 until n - 1) out[k++] = x[i + 1] - x[i]
        for (i in 0 until n - 2) out[k++] = x[i] - 2 * x[i + 1] + x[i + 2]
        for (i in 0 until n - 3) out[k++] = -x[i] + 3 * x[i + 1] - 3 * x[i + 2] + x[i + 3]
    }

    /** out = D^T z. */
    private fun applyDT(z: DoubleArray, out: DoubleArray, n: Int) {
        java.util.Arrays.fill(out, 0, n, 0.0)
        var k = 0
        for (i in 0 until n - 1) { val a = z[k++]; out[i] -= a; out[i + 1] += a }
        for (i in 0 until n - 2) { val a = z[k++]; out[i] += a; out[i + 1] -= 2 * a; out[i + 2] += a }
        for (i in 0 until n - 3) { val a = z[k++]; out[i] -= a; out[i + 1] += 3 * a; out[i + 2] -= 3 * a; out[i + 3] += a }
    }

    /** Dense Cholesky factor L of (D^T D + I); n <= ~40, computed once per size. */
    private fun cholesky(n: Int): Array<DoubleArray> {
        val a = Array(n) { DoubleArray(n) }
        val e = DoubleArray(n); val col = DoubleArray(n)
        for (j in 0 until n) {
            java.util.Arrays.fill(e, 0.0); e[j] = 1.0
            val dz = DoubleArray((n - 1) + (n - 2) + (n - 3))
            applyD(e, dz, n); applyDT(dz, col, n)
            for (i in 0 until n) a[i][j] = col[i] + if (i == j) 1.0 else 0.0
        }
        val l = Array(n) { DoubleArray(n) }
        for (i in 0 until n) for (j in 0..i) {
            var s = a[i][j]
            for (k in 0 until j) s -= l[i][k] * l[j][k]
            l[i][j] = if (i == j) sqrt(max(s, 1e-12)) else s / l[j][j]
        }
        return l
    }

    private fun cholSolve(l: Array<DoubleArray>, b: DoubleArray, x: DoubleArray, n: Int) {
        val yv = DoubleArray(n)
        for (i in 0 until n) { var s = b[i]; for (k in 0 until i) s -= l[i][k] * yv[k]; yv[i] = s / l[i][i] }
        for (i in n - 1 downTo 0) { var s = yv[i]; for (k in i + 1 until n) s -= l[k][i] * x[k]; x[i] = s / l[i][i] }
    }
}
