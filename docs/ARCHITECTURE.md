# Architecture

Single module, Kotlin + Jetpack Compose. No dependencies beyond AndroidX and Kotlin coroutines.

```
app/src/main/java/io/saul/motiontrack/
  MainActivity.kt        Screen: hero speed, stats row, charts, controls; permission request
  MotionViewModel.kt     Session lifecycle, 10 Hz sampling loop, graph history (up to 4 h)
  Tracker.kt             Sensor + GNSS subscriptions on a background thread; rotates
                         acceleration into world axes and feeds the engine
  fusion/
    AxisKalman.kt        3-state [position, velocity, offset] Kalman filter for one axis
    FusionEngine.kt      Origin, two AxisKalman filters, zero-velocity detector, derived values
  ui/
    TimeChart.kt         Canvas time-series panel: auto-scaled axes, min/max decimation,
                         scrub crosshair, legend with live values
app/src/test/java/io/saul/motiontrack/fusion/
  FusionEngineTest.kt    Simulated drives: acceleration run, GPS outage, offset learning,
                         stationary phone
```

## Data flow

```
 sensors (100 Hz) ---+
                     +--> Tracker (HandlerThread) --> FusionEngine   [guarded by one lock]
 GNSS fixes (1 Hz) --+                                    |
                                                          | sample() every 100 ms
                                                          v
                              MotionViewModel.history (main thread) --> Compose charts
```

- `Tracker` registers every listener on one `HandlerThread`, so sensor callbacks never block the
  UI. Every engine call is made under a single lock.
- `MotionViewModel` takes a `Sample` and `Status` every 100 ms and appends the sample to an
  `ArrayDeque`. It bumps a `revision` counter that the chart canvases read, so they redraw.
- `FusionEngine` timestamps are `elapsedRealtimeNanos`, the time base shared by `SensorEvent` and
  `Location`.

## Charts

`TimeChart` draws on a `Canvas`. To stay smooth with hours of 10 Hz data:

- it binary-searches the first visible sample
- when there are more samples than pixel columns, it draws the min and max of each column in
  their original order, so short peaks survive any zoom level
- all panels share a fixed left gutter, so one horizontal drag maps to the same instant on
  every panel

Colours come from a palette whose adjacent pairs are checked for colour-blind separation and
contrast against the dark background. Every series is also named in its panel's legend, so
nothing relies on colour alone.
