package io.saul.motiontrack.fusion

/**
 * Three-state Kalman filter for one horizontal axis: position, velocity, accelerometer bias.
 *
 * The accelerometer drives the prediction (a control input, not a measurement), so the filter
 * reacts at IMU rate; GPS position and Doppler velocity correct it at ~1 Hz and pull the bias
 * estimate toward whatever residual tilt / mounting error the IMU carries.
 */
class AxisKalman(
    /** Accelerometer white-noise density (m/s^2). Covers vibration and orientation error. */
    private val accelSigma: Double = 0.6,
    /** Bias random walk (m/s^2 per sqrt(s)). */
    private val biasWalk: Double = 0.02,
) {
    var p = 0.0; private set
    var v = 0.0; private set
    var b = 0.0; private set

    // Covariance, row-major 3x3.
    private val P = DoubleArray(9)

    fun reset(pos: Double, vel: Double, posVar: Double, velVar: Double) {
        p = pos; v = vel; b = 0.0
        P.fill(0.0)
        P[0] = posVar
        P[4] = velVar
        P[8] = 0.3 * 0.3
    }

    val posVariance get() = P[0]
    val velVariance get() = P[4]

    /** Propagate by [dt] seconds with measured world-frame acceleration [a] on this axis. */
    fun predict(a: Double, dt: Double) {
        if (dt <= 0) return
        val ac = a - b
        p += v * dt + 0.5 * ac * dt * dt
        v += ac * dt

        // F = [[1, dt, -dt^2/2], [0, 1, -dt], [0, 0, 1]]
        val h = -0.5 * dt * dt
        val f = DoubleArray(9).also {
            it[0] = 1.0; it[1] = dt; it[2] = h
            it[4] = 1.0; it[5] = -dt
            it[8] = 1.0
        }
        val fp = mul(f, P)
        val fpft = mulT(fp, f)

        // Q = G G^T sigma_a^2 + diag(0, 0, walk^2 dt), with G = [dt^2/2, dt, 0]
        val g0 = 0.5 * dt * dt
        val q = accelSigma * accelSigma
        fpft[0] += g0 * g0 * q
        fpft[1] += g0 * dt * q
        fpft[3] += g0 * dt * q
        fpft[4] += dt * dt * q
        fpft[8] += biasWalk * biasWalk * dt
        fpft.copyInto(P)
    }

    fun updatePosition(z: Double, variance: Double) = scalarUpdate(0, z, variance)

    fun updateVelocity(z: Double, variance: Double) = scalarUpdate(1, z, variance)

    /** Scalar measurement of state component [i] (H = unit row vector). */
    private fun scalarUpdate(i: Int, z: Double, r: Double) {
        val x = doubleArrayOf(p, v, b)
        val s = P[i * 3 + i] + r
        if (s <= 0) return
        val k = DoubleArray(3) { P[it * 3 + i] / s }
        val y = z - x[i]
        for (j in 0..2) x[j] += k[j] * y
        p = x[0]; v = x[1]; b = x[2].coerceIn(-MAX_BIAS, MAX_BIAS)

        // Joseph-free form is fine at these magnitudes: P = (I - K H) P
        val row = DoubleArray(3) { P[i * 3 + it] }
        for (r0 in 0..2) for (c in 0..2) P[r0 * 3 + c] -= k[r0] * row[c]
        // Keep it symmetric against round-off.
        for (r0 in 0..2) for (c in r0 + 1..2) {
            val m = 0.5 * (P[r0 * 3 + c] + P[c * 3 + r0])
            P[r0 * 3 + c] = m; P[c * 3 + r0] = m
        }
    }

    private fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { idx ->
        val r = idx / 3; val c = idx % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    /** a * b^T */
    private fun mulT(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { idx ->
        val r = idx / 3; val c = idx % 3
        a[r * 3] * b[c * 3] + a[r * 3 + 1] * b[c * 3 + 1] + a[r * 3 + 2] * b[c * 3 + 2]
    }

    private companion object {
        const val MAX_BIAS = 1.5
    }
}
