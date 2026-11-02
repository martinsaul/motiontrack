package io.saul.motiontrack.fusion

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** How the current estimate is being produced. */
enum class FusionMode { WAITING_FOR_GPS, FUSED, DEAD_RECKONING, STATIONARY }

/** One instant of fused state, in SI units. Times are seconds since the session started. */
data class Sample(
    val t: Double,
    val distanceTraveled: Double,
    val distanceFromOrigin: Double,
    val speed: Double,
    /** Raw GPS Doppler speed if a fix arrived since the previous sample, else NaN. */
    val gpsSpeed: Double,
    /** Acceleration along the direction of travel (+ = speeding up). */
    val accelLong: Double,
    /** Acceleration across the direction of travel (+ = toward the left). */
    val accelLat: Double,
)

data class Status(
    val mode: FusionMode,
    val gpsAccuracy: Double,
    val satellitesUsed: Int,
    val satellitesVisible: Int,
    /** Barometric height change since origin, or NaN without a barometer. */
    val elevationChange: Double,
    val peakSpeed: Double,
    val biasE: Double,
    val biasN: Double,
)

/**
 * Loosely-coupled GPS/IMU fusion in a local East-North plane anchored at the first good fix.
 *
 *  - World-frame linear acceleration (from the accelerometer, rotated by the rotation-vector sensor)
 *    drives two [AxisKalman] filters at IMU rate.
 *  - GPS position and Doppler velocity correct them at fix rate, weighted by their reported accuracy.
 *  - A zero-velocity detector (quiet accelerometer + quiet gyroscope + slow GPS) clamps velocity to
 *    zero when the phone is at rest, which stops IMU drift and GPS wander from inflating distance.
 *  - The barometer gives a much smoother height change than GPS altitude.
 *
 * Not thread-safe; the caller serialises access.
 */
class FusionEngine {
    private val east = AxisKalman()
    private val north = AxisKalman()

    private var startNanos = -1L
    private var lastImuNanos = -1L
    private var lastGpsNanos = -1L

    private var originLat = 0.0
    private var originLon = 0.0
    private var cosLat0 = 1.0
    private var hasOrigin = false

    var distanceTraveled = 0.0; private set
    private var peakSpeed = 0.0

    // Display-smoothed, bias-corrected world acceleration.
    private var smoothAE = 0.0
    private var smoothAN = 0.0
    private var headingE = 0.0
    private var headingN = 1.0

    // Stationary detection: running window of linear-accel magnitude and latest gyro rate.
    private val accelWindow = DoubleArray(STILL_WINDOW)
    private var accelWindowCount = 0
    private var accelWindowIdx = 0
    private var gyroRate = Double.NaN
    private var lastGpsSpeed = Double.NaN
    private var pendingGpsSpeed = Double.NaN
    var stationary = false; private set

    private var gpsAccuracy = Double.NaN
    private var satsUsed = 0
    private var satsVisible = 0
    private var baroOrigin = Double.NaN
    private var baroAltitude = Double.NaN

    fun reset() {
        startNanos = -1L; lastImuNanos = -1L; lastGpsNanos = -1L
        hasOrigin = false
        distanceTraveled = 0.0; peakSpeed = 0.0
        smoothAE = 0.0; smoothAN = 0.0
        accelWindowCount = 0; accelWindowIdx = 0
        lastGpsSpeed = Double.NaN; pendingGpsSpeed = Double.NaN
        stationary = false
        baroOrigin = Double.NaN
    }

    private fun mark(nanos: Long) {
        if (startNanos < 0) startNanos = nanos
    }

    /** World-frame (East, North, Up) linear acceleration in m/s^2, gravity already removed. */
    fun onAccel(nanos: Long, aE: Double, aN: Double, aU: Double) {
        mark(nanos)
        val mag = sqrt(aE * aE + aN * aN + aU * aU)
        accelWindow[accelWindowIdx] = mag
        accelWindowIdx = (accelWindowIdx + 1) % STILL_WINDOW
        if (accelWindowCount < STILL_WINDOW) accelWindowCount++

        val prev = lastImuNanos
        lastImuNanos = nanos
        if (prev < 0) return
        val dt = (nanos - prev) / 1e9
        if (dt <= 0 || dt > 0.5) return // sensor hiccup; skip rather than integrate a huge step

        // Display smoothing (~0.25 s time constant) of bias-corrected acceleration.
        val alpha = 1 - exp(-dt / 0.25)
        smoothAE += alpha * ((aE - east.b) - smoothAE)
        smoothAN += alpha * ((aN - north.b) - smoothAN)

        if (!hasOrigin) return

        east.predict(aE, dt)
        north.predict(aN, dt)

        stationary = detectStationary(nanos)
        if (stationary) {
            east.updateVelocity(0.0, ZUPT_VAR)
            north.updateVelocity(0.0, ZUPT_VAR)
        }

        val speed = speed()
        if (speed > 1.0) {
            headingE = east.v / speed
            headingN = north.v / speed
        }
        if (!stationary && speed > MIN_MOVING_SPEED) distanceTraveled += speed * dt
        if (speed > peakSpeed) peakSpeed = speed
    }

    fun onGyro(@Suppress("UNUSED_PARAMETER") nanos: Long, wx: Double, wy: Double, wz: Double) {
        val w = sqrt(wx * wx + wy * wy + wz * wz)
        gyroRate = if (gyroRate.isNaN()) w else gyroRate + 0.2 * (w - gyroRate)
    }

    fun onPressureAltitude(@Suppress("UNUSED_PARAMETER") nanos: Long, altitude: Double) {
        baroAltitude = if (baroAltitude.isNaN()) altitude else baroAltitude + 0.1 * (altitude - baroAltitude)
        if (baroOrigin.isNaN() && hasOrigin) baroOrigin = baroAltitude
    }

    fun onSatellites(used: Int, visible: Int) {
        satsUsed = used; satsVisible = visible
    }

    /**
     * A GNSS fix. [speed]/[bearingDeg]/[speedAccuracy] are NaN when the fix lacks them.
     * [accuracy] is the reported 68% horizontal radius in metres.
     */
    fun onGps(
        nanos: Long, lat: Double, lon: Double, accuracy: Double,
        speed: Double, bearingDeg: Double, speedAccuracy: Double,
    ) {
        mark(nanos)
        gpsAccuracy = accuracy
        if (!hasOrigin) {
            if (accuracy > ORIGIN_MAX_ACCURACY) return
            originLat = lat; originLon = lon
            cosLat0 = cos(lat * PI / 180)
            hasOrigin = true
            val (ve, vn) = gpsVelocity(speed, bearingDeg)
            val vVar = velVariance(speedAccuracy)
            east.reset(0.0, ve, accuracy * accuracy, vVar)
            north.reset(0.0, vn, accuracy * accuracy, vVar)
            if (!baroAltitude.isNaN()) baroOrigin = baroAltitude
            lastGpsNanos = nanos
            return
        }
        lastGpsNanos = nanos

        if (!speed.isNaN()) {
            lastGpsSpeed = speed
            pendingGpsSpeed = speed
        }

        if (accuracy <= POSITION_MAX_ACCURACY) {
            val (pe, pn) = toLocal(lat, lon)
            val r = accuracy * accuracy
            east.updatePosition(pe, r)
            north.updatePosition(pn, r)
        }
        if (!speed.isNaN()) {
            val (ve, vn) = gpsVelocity(speed, bearingDeg)
            // Without a usable bearing only the magnitude is known; a slow fix still says "near zero".
            if (!bearingDeg.isNaN() || speed < 0.5) {
                val r = velVariance(speedAccuracy) + if (bearingDeg.isNaN()) speed * speed else 0.0
                east.updateVelocity(ve, r)
                north.updateVelocity(vn, r)
            }
        }
    }

    fun speed() = if (hasOrigin) hypot(east.v, north.v) else 0.0

    fun distanceFromOrigin() = if (hasOrigin) hypot(east.p, north.p) else 0.0

    fun mode(nanos: Long): FusionMode = when {
        !hasOrigin -> FusionMode.WAITING_FOR_GPS
        stationary -> FusionMode.STATIONARY
        nanos - lastGpsNanos > GPS_STALE_NANOS -> FusionMode.DEAD_RECKONING
        else -> FusionMode.FUSED
    }

    /** Snapshot for the graph. Consumes the "new GPS speed" marker. */
    fun sample(nanos: Long): Sample {
        val t = if (startNanos < 0) 0.0 else (nanos - startNanos) / 1e9
        val gps = pendingGpsSpeed
        pendingGpsSpeed = Double.NaN
        val along = smoothAE * headingE + smoothAN * headingN
        val left = -smoothAE * headingN + smoothAN * headingE
        return Sample(
            t = t,
            distanceTraveled = distanceTraveled,
            distanceFromOrigin = distanceFromOrigin(),
            speed = speed(),
            gpsSpeed = gps,
            accelLong = along,
            accelLat = left,
        )
    }

    fun status(nanos: Long) = Status(
        mode = mode(nanos),
        gpsAccuracy = gpsAccuracy,
        satellitesUsed = satsUsed,
        satellitesVisible = satsVisible,
        elevationChange = if (baroOrigin.isNaN()) Double.NaN else baroAltitude - baroOrigin,
        peakSpeed = peakSpeed,
        biasE = east.b,
        biasN = north.b,
    )

    private fun detectStationary(nanos: Long): Boolean {
        if (accelWindowCount < STILL_WINDOW) return false
        var mean = 0.0
        for (m in accelWindow) mean += m
        mean /= STILL_WINDOW
        var varSum = 0.0
        for (m in accelWindow) varSum += (m - mean) * (m - mean)
        val accelQuiet = varSum / STILL_WINDOW < STILL_ACCEL_VAR && mean < STILL_ACCEL_MEAN
        val gyroQuiet = gyroRate.isNaN() || gyroRate < STILL_GYRO
        // A car cruising on glass-smooth road can look quiet; only trust it if GPS agrees or is absent.
        val gpsFresh = nanos - lastGpsNanos < GPS_STALE_NANOS
        val gpsSlow = !gpsFresh || lastGpsSpeed.isNaN() || lastGpsSpeed < STILL_GPS_SPEED
        return accelQuiet && gyroQuiet && gpsSlow
    }

    private fun toLocal(lat: Double, lon: Double): Pair<Double, Double> {
        val k = EARTH_RADIUS * PI / 180
        return (lon - originLon) * k * cosLat0 to (lat - originLat) * k
    }

    private fun gpsVelocity(speed: Double, bearingDeg: Double): Pair<Double, Double> {
        if (speed.isNaN() || bearingDeg.isNaN()) return 0.0 to 0.0
        val b = bearingDeg * PI / 180
        return speed * sin(b) to speed * cos(b)
    }

    private fun velVariance(speedAccuracy: Double): Double {
        val s = if (speedAccuracy.isNaN() || speedAccuracy <= 0) 1.0 else max(speedAccuracy, 0.1)
        return s * s
    }

    companion object {
        const val EARTH_RADIUS = 6_371_008.8
        const val ORIGIN_MAX_ACCURACY = 25.0
        const val POSITION_MAX_ACCURACY = 60.0
        const val GPS_STALE_NANOS = 3_000_000_000L
        const val STILL_WINDOW = 100 // ~1 s at SENSOR_DELAY_GAME-ish rates
        const val STILL_ACCEL_VAR = 0.004
        const val STILL_ACCEL_MEAN = 0.25
        const val STILL_GYRO = 0.04
        const val STILL_GPS_SPEED = 1.0
        const val ZUPT_VAR = 0.02 * 0.02
        const val MIN_MOVING_SPEED = 0.3
    }
}
