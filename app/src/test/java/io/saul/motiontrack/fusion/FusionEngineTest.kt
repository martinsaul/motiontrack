package io.saul.motiontrack.fusion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.random.Random

class FusionEngineTest {
    private val lat0 = 45.0
    private val lon0 = -75.0
    private val mPerDegLat = FusionEngine.EARTH_RADIUS * PI / 180

    private fun lat(north: Double) = lat0 + north / mPerDegLat

    /** Drive north with a constant acceleration profile, IMU at 100 Hz, GPS at 1 Hz. */
    private fun simulate(
        engine: FusionEngine,
        seconds: Double,
        accel: (Double) -> Double,
        gpsUntil: Double = Double.POSITIVE_INFINITY,
        accelBias: Double = 0.0,
        rnd: Random = Random(1),
        gpsNoise: Double = 3.0,
    ): Pair<Double, Double> {
        var pos = 0.0; var vel = 0.0
        val dt = 0.01
        var t = 0.0
        var nextGps = 0.0
        while (t < seconds) {
            val nanos = (t * 1e9).toLong()
            val a = accel(t)
            engine.onGyro(nanos, 0.0, 0.0, if (vel > 0.1) 0.1 else 0.0)
            val vib = if (vel > 0.1) rnd.nextDouble(-0.3, 0.3) else rnd.nextDouble(-0.01, 0.01)
            engine.onAccel(nanos, rnd.nextDouble(-0.05, 0.05), a + accelBias + vib, 0.0)
            if (t >= nextGps && t < gpsUntil) {
                engine.onGps(
                    nanos, lat(pos + rnd.nextDouble(-gpsNoise, gpsNoise)), lon0, 5.0,
                    vel + rnd.nextDouble(-0.2, 0.2), if (vel >= 0.5) 0.0 else Double.NaN, 0.3,
                )
                nextGps += 1.0
            }
            pos += vel * dt + 0.5 * a * dt * dt
            vel += a * dt
            t += dt
        }
        return pos to vel
    }

    @Test
    fun tracksConstantAccelerationRun() {
        val e = FusionEngine()
        // 10 s at 2 m/s^2, then 20 s cruise.
        val (pos, vel) = simulate(e, 30.0, { t -> if (t in 1.0..11.0) 2.0 else 0.0 })
        assertEquals(vel, e.speed(), 0.5)
        assertEquals(pos, e.distanceFromOrigin(), 5.0)
        assertEquals(pos, e.distanceTraveled, 0.05 * pos)
    }

    @Test
    fun bridgesShortGpsOutageWithImu() {
        val e = FusionEngine()
        // GPS drops at 15 s; vehicle keeps accelerating at 1 m/s^2 from 12 s on.
        val (_, vel) = simulate(e, 20.0, { t -> if (t in 2.0..8.0 || t > 12.0) 1.0 else 0.0 }, gpsUntil = 15.0)
        assertEquals(vel, e.speed(), 1.0)
    }

    @Test
    fun learnsAccelerometerBias() {
        val e = FusionEngine()
        simulate(e, 60.0, { t -> if (t in 1.0..6.0) 2.0 else 0.0 }, accelBias = 0.3)
        val st = e.status(60_000_000_000L)
        assertEquals(0.3, st.biasN, 0.12)
    }

    @Test
    fun stationaryPhoneAccumulatesNoDistance() {
        val e = FusionEngine()
        simulate(e, 60.0, { 0.0 }, gpsNoise = 8.0)
        assertTrue("traveled ${e.distanceTraveled}", e.distanceTraveled < 1.0)
        assertTrue(e.stationary)
        assertTrue(abs(e.speed()) < 0.1)
    }
}
