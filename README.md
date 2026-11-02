# MotionTrack

**Speed, acceleration, and distance from your phone, graphed live.** MotionTrack combines GPS with
your phone's motion sensors. The result reacts faster than GPS alone and stays steadier than the
accelerometer alone.

## Features

- **Fused speed.** GPS is accurate but updates about once a second. The accelerometer updates
  100 times a second but drifts. A Kalman filter combines the two, so you get fast response
  without the drift.
- **Acceleration in the direction you travel.** Acceleration is split into **along** (speeding up
  or braking) and **lateral** (cornering), whichever way the phone is mounted.
- **Two kinds of distance.** **Traveled** is the length of the path you took. **From origin** is the
  straight-line distance back to where you pressed Start.
- **Keeps going when GPS drops out.** In a tunnel or between tall buildings, the motion sensors
  carry the estimate until GPS returns.
- **No phantom distance at rest.** When the motion sensors and GPS agree the phone is still,
  speed is held at zero, so GPS jitter doesn't add distance while you're parked.
- **Elevation change.** Phones with a barometer show a smooth height change since the start.
- **Live graphs.** Three stacked graphs share one time axis: distance, speed, and acceleration. Drag
  across them to read exact values at any moment.
- **Private.** MotionTrack has no accounts, no analytics, no ads, and no internet permission. Your
  location never leaves the phone.

## Install

Build it yourself (see [docs/BUILDING.md](docs/BUILDING.md)), or download the debug APK from the
artifacts of the latest successful [CI run](../../actions). It needs Android 8.0 (API 26) or
newer and a phone with GPS and an accelerometer.

## Using it

1. Open MotionTrack and press **Start**. Allow precise location when asked.
2. Wait for a GPS fix. The first fix sets the origin, so the first few seconds outdoors with a
   clear sky give the best start.
3. Move. The large number is your current speed. The line on the right shows what the estimate
   is based on:

   | Status | Meaning |
   |---|---|
   | **Waiting for GPS fix** | No GPS fix good enough (within 25 m) to set the origin yet. |
   | **GPS + IMU fused** | Normal operation: GPS corrects the motion-sensor estimate. |
   | **GPS lost: IMU only** | No GPS fix for 3 s. The estimate runs on motion sensors alone and slowly drifts. |
   | **Stationary** | The phone is at rest, so speed is held at zero. |

4. Use the time window chips to show the last **30 s**, **2 m**, **10 m**, or **All** of the session.
5. Press and drag across the graphs to show a crosshair. Every legend then shows the value at
   that moment. Let go to return to live values.
6. Tap **Metric** or **Imperial** to switch units. Acceleration always shows in m/s².
7. Press **Stop** to end. Pressing **Restart** clears the graphs and sets a new origin.

The screen stays on while tracking.

### Tips for good readings

- **Mount the phone firmly.** A phone sliding around a cup holder adds acceleration that isn't
  the vehicle's. A dashboard or windscreen mount works best.
- **Keep it away from magnets and large metal surfaces where you can.** The compass aligns the
  motion sensors with the GPS directions. A distorted compass mostly affects the lateral
  acceleration reading.
- **Give it a few seconds of driving.** The filter learns the accelerometer's offset (from a
  tilted mount, for example) within the first acceleration or two.

## How it works

The short version:

1. The **rotation-vector sensor** (accelerometer, gyroscope, and compass fused by Android)
   gives the phone's orientation. MotionTrack uses it to rotate each acceleration reading into
   east, north, and up directions.
2. For each horizontal direction, a Kalman filter tracks **position, velocity, and accelerometer
   offset**. Each accelerometer reading moves the estimate forward. Each GPS fix corrects it,
   weighted by the accuracy the GPS reports.
3. A **stationary detector** checks for a quiet accelerometer, a quiet gyroscope, and a slow GPS
   reading. When all three agree, it tells the filter that velocity is zero.

See [docs/HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md) for the full model and its tuning, and
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the code layout.

## Sensors used

| Sensor | Required | Used for |
|---|---|---|
| GPS / GNSS | Yes | Position, speed, heading, satellite count |
| Linear acceleration (or accelerometer) | Yes | Acceleration between GPS fixes |
| Rotation vector | Strongly recommended | Lining acceleration up with east/north |
| Gyroscope | No | Stationary detection |
| Barometer | No | Elevation change |

If the phone has no linear acceleration sensor, MotionTrack removes gravity itself from the raw
accelerometer. If it has no rotation-vector sensor, it uses the game rotation vector, which has
no compass. Fused speed then leans more on GPS.

## Permissions

| Permission | Why |
|---|---|
| `ACCESS_FINE_LOCATION` | GPS position and speed. |
| `ACCESS_COARSE_LOCATION` | Requested together with fine location, as Android requires. |
| `HIGH_SAMPLING_RATE_SENSORS` | Reading motion sensors at 100 Hz on Android 12+. |

MotionTrack has no internet permission and stores nothing: a session lives in memory until you
press Restart or close the app.

## Limitations

- **The app must be on screen.** Tracking stops when the app goes to the background.
- **The first GPS fix matters.** "From origin" is measured from the first fix within 25 m. A poor
  first fix shifts every distance-from-origin value by the same amount.
- **Long GPS outages drift.** The motion sensors bridge seconds to tens of seconds well. Over
  minutes without GPS, speed and distance errors grow.
- **Vertical motion isn't included.** Speed and distances are horizontal. Elevation is shown
  separately.

## Contributing

Bug reports and pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

Copyright (C) 2026 Martin Saul

MotionTrack is free software: you can redistribute it and/or modify it under the terms of the
[GNU Affero General Public License v3.0](LICENSE) as published by the Free Software Foundation,
either version 3 of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the license
for details.
