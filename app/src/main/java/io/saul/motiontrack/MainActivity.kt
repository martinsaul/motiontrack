package io.saul.motiontrack

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.saul.motiontrack.fusion.FusionMode
import io.saul.motiontrack.fusion.Sample
import io.saul.motiontrack.ui.ChartGutter
import io.saul.motiontrack.ui.Series
import io.saul.motiontrack.ui.TimeChart
import io.saul.motiontrack.ui.Viz
import io.saul.motiontrack.ui.YScale
import io.saul.motiontrack.ui.formatClock
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

class MainActivity : ComponentActivity() {
    private val vm: MotionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Viz.surface, surface = Viz.surface, primary = Viz.blue)) {
                MotionScreen(vm)
            }
        }
    }
}

/** Display-unit conversions. Distance picks its unit from the largest value so the axis stays coherent. */
private class UnitSet(units: Units, maxDistanceM: Double) {
    val speedFactor = if (units == Units.METRIC) 3.6 else 2.236936
    val speedUnit = if (units == Units.METRIC) "km/h" else "mph"
    val distFactor: Double
    val distUnit: String

    init {
        if (units == Units.METRIC) {
            if (maxDistanceM >= 2000) { distFactor = 0.001; distUnit = "km" } else { distFactor = 1.0; distUnit = "m" }
        } else {
            if (maxDistanceM >= 804.672) { distFactor = 1 / 1609.344; distUnit = "mi" } else { distFactor = 3.28084; distUnit = "ft" }
        }
    }
}

private fun fmt(v: Double): String =
    if (abs(v) >= 100 || v == floor(v)) "%.0f".format(v) else "%.1f".format(v)

private fun fmt2(v: Double): String = if (abs(v) >= 10) "%.1f".format(v) else "%.2f".format(v)

@Composable
fun MotionScreen(vm: MotionViewModel) {
    val ctx = LocalView.current.context
    val view = LocalView.current
    LaunchedEffect(vm.running) { view.keepScreenOn = vm.running }

    var permissionDenied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true) { permissionDenied = false; vm.start() }
        else permissionDenied = true
    }
    fun startOrAsk() {
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (granted) vm.start()
        else launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    val latest = vm.latest
    val status = vm.status
    val units = UnitSet(vm.units, max(latest?.distanceTraveled ?: 0.0, latest?.distanceFromOrigin ?: 0.0))

    Column(Modifier.fillMaxSize().background(Viz.surface).safeDrawingPadding().padding(horizontal = 12.dp)) {
        // Hero: current speed, plus mode + signal quality. Mode is named in text, never colour alone.
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
            Text(
                latest?.let { "%.0f".format(it.speed * units.speedFactor) } ?: "--",
                color = Viz.textPrimary, fontSize = 64.sp, fontWeight = FontWeight.Bold, lineHeight = 64.sp,
            )
            Text(
                units.speedUnit, color = Viz.textSecondary, fontSize = 18.sp,
                modifier = Modifier.padding(start = 6.dp, bottom = 10.dp),
            )
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 8.dp)) {
                Text(
                    when {
                        !vm.running -> "Stopped"
                        status == null -> "Starting"
                        else -> when (status.mode) {
                            FusionMode.WAITING_FOR_GPS -> "Waiting for GPS fix"
                            FusionMode.FUSED -> "GPS + IMU fused"
                            FusionMode.DEAD_RECKONING -> "GPS lost: IMU only"
                            FusionMode.STATIONARY -> "Stationary"
                        }
                    },
                    color = Viz.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                )
                if (status != null && vm.running) {
                    val acc = if (status.gpsAccuracy.isNaN()) "--" else "%.0f m".format(status.gpsAccuracy)
                    Text(
                        "GPS +/-$acc  sats ${status.satellitesUsed}/${status.satellitesVisible}",
                        color = Viz.textSecondary, fontSize = 12.sp,
                    )
                }
                if (latest != null) Text("t ${formatClock(latest.t)}", color = Viz.textSecondary, fontSize = 12.sp)
            }
        }

        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("Traveled", latest?.let { fmt(it.distanceTraveled * units.distFactor) + " " + units.distUnit })
            Stat("From origin", latest?.let { fmt(it.distanceFromOrigin * units.distFactor) + " " + units.distUnit })
            Stat("Accel", latest?.let { fmt2(it.accelLong) + " m/s²" })
            Stat("Peak", status?.let { "%.0f".format(it.peakSpeed * units.speedFactor) })
            Stat("Elev Δ", status?.elevationChange?.takeIf { !it.isNaN() }?.let {
                if (vm.units == Units.METRIC) "%.1f m".format(it) else "%.0f ft".format(it * 3.28084)
            })
        }

        Charts(vm, units, Modifier.weight(1f))

        if (permissionDenied) {
            Text(
                "Location permission is required for GPS speed and position.",
                color = Viz.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
            )
        }

        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { if (vm.running) vm.stop() else startOrAsk() },
                colors = ButtonDefaults.buttonColors(containerColor = if (vm.running) Viz.grid else Viz.blue, contentColor = Viz.textPrimary),
                shape = RoundedCornerShape(8.dp),
            ) { Text(if (vm.running) "Stop" else if (vm.history.isEmpty()) "Start" else "Restart") }
            TextButton(onClick = { vm.units = if (vm.units == Units.METRIC) Units.IMPERIAL else Units.METRIC }) {
                Text(if (vm.units == Units.METRIC) "Metric" else "Imperial", color = Viz.textSecondary)
            }
            Spacer(Modifier.weight(1f))
            TimeWindow.entries.forEach { w ->
                FilterChip(
                    selected = vm.window == w,
                    onClick = { vm.window = w },
                    label = { Text(w.label, fontSize = 11.sp, maxLines = 1) },
                    modifier = Modifier.padding(start = 4.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        labelColor = Viz.textSecondary,
                        selectedContainerColor = Viz.grid,
                        selectedLabelColor = Viz.textPrimary,
                    ),
                )
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String?) {
    Column {
        Text(label, color = Viz.textSecondary, fontSize = 11.sp)
        Text(value ?: "--", color = Viz.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Three stacked panels on one shared time axis. Press and drag anywhere over them to scrub: a
 * crosshair follows the finger across all panels and every legend shows the value at that instant.
 */
@Composable
private fun Charts(vm: MotionViewModel, units: UnitSet, modifier: Modifier) {
    val history = vm.history
    val rev = vm.revision
    val tEnd = history.lastOrNull()?.t ?: 0.0
    val firstT = history.firstOrNull()?.t ?: 0.0
    val win = vm.window.seconds
    // Fill left-to-right until the window is full, then scroll.
    val tStart = if (win.isInfinite()) firstT else max(firstT, tEnd - win)
    val tEndShown = if (win.isInfinite()) max(tEnd, firstT + 10) else tStart + win

    // Scrub position as a fraction of the plot width, so the crosshair stays under the finger as data scrolls.
    var scrubFrac by remember { mutableStateOf<Double?>(null) }
    val gutterPx = with(LocalDensity.current) { ChartGutter.toPx() }
    val scrubT = scrubFrac?.let { tStart + it * (tEndShown - tStart) }

    val df = units.distFactor
    val sf = units.speedFactor
    val distance = listOf(
        Series("Traveled", Viz.blue, { s: Sample -> s.distanceTraveled * df }),
        Series("From origin", Viz.orange, { s: Sample -> s.distanceFromOrigin * df }),
    )
    val speed = listOf(
        Series("Fused", Viz.aqua, { s: Sample -> s.speed * sf }),
        Series("GPS", Viz.yellow, { s: Sample -> s.gpsSpeed * sf }, dots = true),
    )
    val accel = listOf(
        Series("Along", Viz.magenta, Sample::accelLong),
        Series("Lateral", Viz.green, Sample::accelLat),
    )

    val currentWidth = rememberUpdatedState(gutterPx)
    Column(
        modifier.fillMaxWidth().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                fun frac(x: Float) = ((x - currentWidth.value) / (size.width - currentWidth.value)).toDouble().coerceIn(0.0, 1.0)
                scrubFrac = frac(down.position.x)
                while (true) {
                    val ev = awaitPointerEvent()
                    val c = ev.changes.firstOrNull() ?: break
                    if (!c.pressed) break
                    scrubFrac = frac(c.position.x)
                    c.consume()
                }
                scrubFrac = null
            }
        },
    ) {
        val common = Modifier.fillMaxWidth()
        TimeChart(
            "Distance", units.distUnit, distance, history, rev, tStart, tEndShown, scrubT,
            YScale.ZERO_BASED, minSpan = 10 * df, format = ::fmt, showTimeAxis = false,
            modifier = common.weight(1f),
        )
        Spacer(Modifier.height(8.dp))
        TimeChart(
            "Speed", units.speedUnit, speed, history, rev, tStart, tEndShown, scrubT,
            YScale.ZERO_BASED, minSpan = 10.0, format = ::fmt, showTimeAxis = false,
            modifier = common.weight(1f),
        )
        Spacer(Modifier.height(8.dp))
        TimeChart(
            "Acceleration", "m/s²", accel, history, rev, tStart, tEndShown, scrubT,
            YScale.SYMMETRIC, minSpan = 2.0, format = ::fmt2, showTimeAxis = true,
            modifier = common.weight(1.15f),
        )
    }
    if (history.isEmpty()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("Press Start, then move. Drag across the graphs to inspect values.", color = Viz.textMuted, fontSize = 12.sp)
        }
    }
}
