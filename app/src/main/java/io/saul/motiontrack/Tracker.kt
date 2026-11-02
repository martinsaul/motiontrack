package io.saul.motiontrack

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import io.saul.motiontrack.fusion.FusionEngine
import io.saul.motiontrack.fusion.Sample
import io.saul.motiontrack.fusion.Status

/**
 * Owns the sensor + GNSS subscriptions and feeds them into a [FusionEngine] on a dedicated thread.
 * All engine access goes through [lock].
 */
class Tracker(context: Context) {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val location = context.getSystemService(LocationManager::class.java)

    private val linearAccel = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val rawAccel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    // Magnetometer-referenced orientation so IMU axes line up with GPS east/north.
    private val rotation = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val pressure = sensors.getDefaultSensor(Sensor.TYPE_PRESSURE)

    val sensorSummary: List<String> = buildList {
        add(if (linearAccel != null) "Linear accel" else "Accel (gravity removed in-app)")
        if (rotation != null) add(if (rotation.type == Sensor.TYPE_ROTATION_VECTOR) "Rotation vector" else "Game rotation")
        if (gyro != null) add("Gyroscope")
        if (pressure != null) add("Barometer")
        add("GNSS")
    }

    private val engine = FusionEngine()
    private val lock = Any()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private val rot = FloatArray(9).also { it[0] = 1f; it[4] = 1f; it[8] = 1f }
    private var hasRotation = false

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rot, e.values)
                    hasRotation = true
                }
                Sensor.TYPE_LINEAR_ACCELERATION -> feedAccel(e.timestamp, e.values, 0f)
                Sensor.TYPE_ACCELEROMETER -> feedAccel(e.timestamp, e.values, SensorManager.GRAVITY_EARTH)
                Sensor.TYPE_GYROSCOPE -> synchronized(lock) {
                    engine.onGyro(e.timestamp, e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble())
                }
                Sensor.TYPE_PRESSURE -> synchronized(lock) {
                    val alt = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, e.values[0])
                    engine.onPressureAltitude(e.timestamp, alt.toDouble())
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    /** Rotate device-frame acceleration into East-North-Up and hand it to the engine. */
    private fun feedAccel(nanos: Long, v: FloatArray, gravity: Float) {
        if (!hasRotation) return
        val x = v[0]; val y = v[1]; val z = v[2]
        val e = rot[0] * x + rot[1] * y + rot[2] * z
        val n = rot[3] * x + rot[4] * y + rot[5] * z
        val u = rot[6] * x + rot[7] * y + rot[8] * z - gravity
        synchronized(lock) { engine.onAccel(nanos, e.toDouble(), n.toDouble(), u.toDouble()) }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            val speedAcc = if (Build.VERSION.SDK_INT >= 26 && l.hasSpeedAccuracy()) l.speedAccuracyMetersPerSecond.toDouble() else Double.NaN
            // Bearing is noise below walking pace.
            val bearing = if (l.hasBearing() && l.hasSpeed() && l.speed >= 0.5f) l.bearing.toDouble() else Double.NaN
            synchronized(lock) {
                engine.onGps(
                    nanos = l.elapsedRealtimeNanos,
                    lat = l.latitude, lon = l.longitude,
                    accuracy = if (l.hasAccuracy()) l.accuracy.toDouble() else 50.0,
                    speed = if (l.hasSpeed()) l.speed.toDouble() else Double.NaN,
                    bearingDeg = bearing,
                    speedAccuracy = speedAcc,
                )
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            synchronized(lock) { engine.onSatellites(used, status.satelliteCount) }
        }
    }

    @SuppressLint("MissingPermission") // caller checks ACCESS_FINE_LOCATION first
    fun start() {
        if (thread != null) return
        val t = HandlerThread("motion-sensors").also { it.start() }
        val h = Handler(t.looper)
        thread = t; handler = h

        synchronized(lock) { engine.reset() }
        val periodUs = 10_000 // 100 Hz
        rotation?.let { sensors.registerListener(sensorListener, it, periodUs, h) }
        if (linearAccel != null) sensors.registerListener(sensorListener, linearAccel, periodUs, h)
        else rawAccel?.let { sensors.registerListener(sensorListener, it, periodUs, h) }
        gyro?.let { sensors.registerListener(sensorListener, it, periodUs, h) }
        pressure?.let { sensors.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI, h) }

        location.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, locationListener, t.looper)
        location.registerGnssStatusCallback(gnssCallback, h)
    }

    fun stop() {
        sensors.unregisterListener(sensorListener)
        location.removeUpdates(locationListener)
        location.unregisterGnssStatusCallback(gnssCallback)
        thread?.quitSafely()
        thread = null; handler = null
        hasRotation = false
    }

    val running get() = thread != null

    fun sample(): Pair<Sample, Status> {
        val now = SystemClock.elapsedRealtimeNanos()
        return synchronized(lock) { engine.sample(now) to engine.status(now) }
    }
}
