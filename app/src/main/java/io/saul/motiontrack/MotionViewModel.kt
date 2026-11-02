package io.saul.motiontrack

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.saul.motiontrack.fusion.Sample
import io.saul.motiontrack.fusion.Status
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class Units { METRIC, IMPERIAL }

enum class TimeWindow(val label: String, val seconds: Double) {
    S30("30s", 30.0), M2("2m", 120.0), M10("10m", 600.0), ALL("All", Double.POSITIVE_INFINITY)
}

class MotionViewModel(app: Application) : AndroidViewModel(app) {
    private val tracker = Tracker(app)
    private var source: MotionSource = tracker

    /** Debug builds only: run the scripted [SimulatedDrive] instead of real sensors. */
    var demo = false

    /** Graph history; mutated and read on the main thread only. [revision] signals redraws. */
    val history = ArrayDeque<Sample>()
    var revision by mutableIntStateOf(0); private set
    var latest by mutableStateOf<Sample?>(null); private set
    var status by mutableStateOf<Status?>(null); private set
    var running by mutableStateOf(false); private set

    var units by mutableStateOf(Units.METRIC)
    var window by mutableStateOf(TimeWindow.M2)

    private var loop: Job? = null

    fun start() {
        if (running) return
        history.clear()
        source = if (demo) SimulatedDrive() else tracker
        source.start()
        running = true
        loop = viewModelScope.launch {
            while (isActive) {
                val (s, st) = source.sample()
                history.addLast(s)
                if (history.size > MAX_SAMPLES) history.removeFirst()
                latest = s
                status = st
                revision++
                delay(SAMPLE_PERIOD_MS)
            }
        }
    }

    fun stop() {
        loop?.cancel(); loop = null
        source.stop()
        running = false
    }

    override fun onCleared() {
        stop()
    }

    companion object {
        const val SAMPLE_PERIOD_MS = 100L
        const val MAX_SAMPLES = 4 * 60 * 60 * 10 // 4 h at 10 Hz
    }
}
