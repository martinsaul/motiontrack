package io.saul.motiontrack

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import io.saul.motiontrack.fusion.FusionEngine
import io.saul.motiontrack.fusion.Sample
import io.saul.motiontrack.fusion.Status
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Debug-build demo: a scripted drive with realistic sensor noise, fed through the real
 * [FusionEngine] in real time. Lets you see the app work without moving (emulator, screenshots).
 *
 * The route: pull away, cruise, slow for a 90 degree left turn, accelerate, brake hard to a stop,
 * wait, pull away again and take a right-hand bend. GPS arrives at 1 Hz with correlated position
 * error; the accelerometer carries vibration and a constant offset the filter has to learn.
 */
class SimulatedDrive : MotionSource {
    override val sensorSummary = listOf("Simulated drive")

    private val engine = FusionEngine()
    private val lock = Any()
    private var thread: HandlerThread? = null
    private val rnd = Random(42)

    private var t0 = 0L
    private var lastNanos = 0L
    private var nextGps = 0.0
    private var east = 0.0
    private var north = 0.0
    private var heading = 0.0 // bearing, radians clockwise from north
    private var prevV = 0.0
    private var gpsErrE = 0.0
    private var gpsErrN = 0.0

    override fun start() {
        if (thread != null) return
        val t = HandlerThread("simulated-drive").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        synchronized(lock) { engine.reset() }
        t0 = SystemClock.elapsedRealtimeNanos()
        lastNanos = t0
        nextGps = 0.0; east = 0.0; north = 0.0; heading = 0.0; prevV = 0.0
        h.post(object : Runnable {
            override fun run() {
                step(SystemClock.elapsedRealtimeNanos())
                h.postDelayed(this, 10)
            }
        })
    }

    override fun stop() {
        thread?.quitSafely()
        thread = null
    }

    override fun sample(): Pair<Sample, Status> {
        val now = SystemClock.elapsedRealtimeNanos()
        return synchronized(lock) { engine.sample(now) to engine.status(now) }
    }

    private fun step(nanos: Long) {
        val dt = (nanos - lastNanos) / 1e9
        lastNanos = nanos
        if (dt <= 0) return
        val t = (nanos - t0) / 1e9

        val v = speed(t)
        val w = if (v > 1) yawRate(t) else 0.0
        heading += w * dt
        east += v * sin(heading) * dt
        north += v * cos(heading) * dt
        val aFwd = (v - prevV) / dt
        prevV = v
        val aLeft = -v * w

        val moving = v > 0.3
        val vib = if (moving) 0.35 else 0.01
        val aE = aFwd * sin(heading) - aLeft * cos(heading) + ACCEL_OFFSET_E + rnd.nextGaussian() * vib
        val aN = aFwd * cos(heading) + aLeft * sin(heading) + ACCEL_OFFSET_N + rnd.nextGaussian() * vib
        val aU = rnd.nextGaussian() * vib
        val gyroNoise = if (moving) 0.02 else 0.002

        synchronized(lock) {
            engine.onGyro(nanos, rnd.nextGaussian() * gyroNoise, rnd.nextGaussian() * gyroNoise, -w + rnd.nextGaussian() * gyroNoise)
            engine.onAccel(nanos, aE, aN, aU)
            engine.onPressureAltitude(nanos, ALT0 + 7 * smooth((t - 60) / 30) + rnd.nextGaussian() * 0.05)
            if (t >= nextGps) {
                nextGps += 1.0
                // GPS error wanders slowly rather than jumping independently each fix.
                gpsErrE = 0.8 * gpsErrE + rnd.nextGaussian() * 1.2
                gpsErrN = 0.8 * gpsErrN + rnd.nextGaussian() * 1.2
                val k = FusionEngine.EARTH_RADIUS * PI / 180
                val gpsSpeed = (v + rnd.nextGaussian() * 0.12).coerceAtLeast(0.0)
                engine.onSatellites(used = 11, visible = 23)
                engine.onGps(
                    nanos,
                    lat = LAT0 + (north + gpsErrN) / k,
                    lon = LON0 + (east + gpsErrE) / (k * cos(LAT0 * PI / 180)),
                    accuracy = 4.0 + rnd.nextDouble() * 2,
                    speed = gpsSpeed,
                    bearingDeg = if (gpsSpeed >= 0.5) Math.toDegrees(heading) + rnd.nextGaussian() else Double.NaN,
                    speedAccuracy = 0.3,
                )
            }
        }
    }

    private companion object {
        const val LAT0 = 45.4215
        const val LON0 = -75.6972
        const val ALT0 = 70.0
        const val ACCEL_OFFSET_E = 0.12
        const val ACCEL_OFFSET_N = -0.18

        fun smooth(x: Double): Double = 0.5 - 0.5 * cos(PI * x.coerceIn(0.0, 1.0))

        // (start s, end s, from m/s, to m/s)
        val SPEED = arrayOf(
            doubleArrayOf(0.0, 6.0, 0.0, 0.0),
            doubleArrayOf(6.0, 18.0, 0.0, 21.0),
            doubleArrayOf(18.0, 34.0, 21.0, 21.0),
            doubleArrayOf(34.0, 41.0, 21.0, 9.0),
            doubleArrayOf(41.0, 51.0, 9.0, 9.0),
            doubleArrayOf(51.0, 61.0, 9.0, 24.0),
            doubleArrayOf(61.0, 74.0, 24.0, 24.0),
            doubleArrayOf(74.0, 82.0, 24.0, 0.0),
            doubleArrayOf(82.0, 92.0, 0.0, 0.0),
            doubleArrayOf(92.0, 101.0, 0.0, 15.0),
        )

        fun speed(t: Double): Double {
            for (s in SPEED) if (t >= s[0] && t < s[1]) return s[2] + (s[3] - s[2]) * smooth((t - s[0]) / (s[1] - s[0]))
            return 15.0
        }

        /** Radians per second, + = turning right. Eased so lateral acceleration builds and fades. */
        fun yawRate(t: Double): Double = when {
            t in 41.0..51.0 -> -(PI / 2) / (20 / PI) * sin(PI * (t - 41.0) / 10.0) // 90 deg left
            t in 106.0..118.0 -> (PI / 3) / (24 / PI) * sin(PI * (t - 106.0) / 12.0) // 60 deg right
            else -> 0.0
        }
    }
}
