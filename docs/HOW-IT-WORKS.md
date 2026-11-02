# How MotionTrack works

MotionTrack is a loosely coupled GNSS/INS filter. "Loosely coupled" means it uses the position and
velocity the phone's GPS chip already computed, not raw satellite measurements. All of the fusion
logic is in [`fusion/`](../app/src/main/java/io/saul/motiontrack/fusion) and has no Android
dependencies, so it is unit-tested on the JVM.

## 1. Coordinate frame

The **origin** is the first GPS fix with a reported accuracy of 25 m or better. Every later fix is
converted to metres east and north of the origin with an equirectangular projection:

```
east  = (lon - lon0) * R * cos(lat0) * pi/180
north = (lat - lat0) * R * pi/180          (R = 6 371 008.8 m)
```

At the scale of a drive or a run (tens of kilometres), this is far more precise than phone GPS.

## 2. Acceleration in world axes

Android's **rotation vector** sensor gives the phone's orientation relative to east, north, and up.
MotionTrack turns it into a rotation matrix and applies it to each **linear acceleration** sample
(acceleration with gravity removed):

```
a_world = R(rotation vector) * a_device
```

Rotating each sample means the phone can sit at any angle. If the device has no linear
acceleration sensor, MotionTrack rotates the raw accelerometer and subtracts g from the up axis.

## 3. Kalman filter

MotionTrack runs one independent filter per horizontal axis (east and north). Each has three states:

```
x = [ p, v, b ]   position (m), velocity (m/s), accelerometer offset (m/s^2)
```

### Predict (every IMU sample, about 100 Hz)

The measured acceleration `a` is a control input:

```
p <- p + v*dt + 1/2 (a - b) dt^2
v <- v + (a - b) dt
b <- b

F = | 1  dt  -dt^2/2 |        Q = G G^T sigma_a^2 + diag(0, 0, sigma_b^2 dt)
    | 0  1   -dt     |        G = [dt^2/2, dt, 0]^T
    | 0  0    1      |
```

- `sigma_a = 0.6 m/s^2` covers accelerometer noise, vibration, and small orientation errors.
- `sigma_b = 0.02 m/s^2/sqrt(s)` lets the offset estimate wander slowly. The offset is limited to
  +/-1.5 m/s^2.

### Correct (each GPS fix, about 1 Hz)

Each correction is a scalar update on one state:

| Measurement | State | Variance |
|---|---|---|
| GPS position | `p` | `accuracy^2`, the reported 68 % horizontal radius. Skipped if accuracy is worse than 60 m. |
| GPS velocity | `v` | `speedAccuracy^2` (default 1 m/s if the fix doesn't report it, floor 0.1 m/s) |

GPS velocity comes from the Doppler speed and bearing:
`v_east = speed * sin(bearing)` and `v_north = speed * cos(bearing)`. Below 0.5 m/s the bearing is
noise, so MotionTrack ignores it. A slow fix is then treated as "velocity near zero", with its
variance increased by `speed^2`.

Doppler speed is the most accurate thing the GPS reports (often better than 0.2 m/s), so it
anchors velocity. The accelerometer fills the gap between fixes. Over time, the persistent
difference between the two shows up as the offset `b`. That difference comes from a tilted mount,
sensor bias, or a small orientation error.

## 4. Zero-velocity updates

When the phone is at rest, GPS still wanders by a few metres. Integrating that wander would add
phantom distance. MotionTrack treats the phone as **stationary** when all of these hold:

- the linear acceleration magnitude has a variance below 0.004 (m/s^2)^2 and a mean below
  0.25 m/s^2 over the last 100 samples (about 1 s)
- the smoothed gyroscope rate is below 0.04 rad/s (skipped on phones without a gyroscope)
- the last GPS speed is below 1 m/s, or GPS has been silent for over 3 s

The GPS condition matters. A car cruising on a very smooth road can look as quiet as a parked
car, so the IMU alone doesn't get to declare a stop.

While stationary, MotionTrack applies a velocity measurement of 0 with a standard deviation of
0.02 m/s on each sample, and stops adding distance.

## 5. Derived values

| Value | Definition |
|---|---|
| Speed | `sqrt(v_east^2 + v_north^2)` |
| Distance traveled | Sum of `speed * dt` over every IMU step, while not stationary and above 0.3 m/s |
| Distance from origin | `sqrt(p_east^2 + p_north^2)` |
| Along acceleration | Offset-corrected world acceleration, smoothed with a 0.25 s time constant, projected on the direction of travel |
| Lateral acceleration | The same, projected on the left-pointing normal (+ = toward the left) |
| Elevation change | Barometric altitude (standard atmosphere), smoothed, relative to the value when the origin was set |

The direction of travel is the filter's velocity direction. Below 1 m/s, MotionTrack keeps the last
direction so the along/lateral split doesn't flicker.

## 6. Modes

| Mode | Condition |
|---|---|
| Waiting for GPS fix | No origin yet |
| Stationary | The zero-velocity detector is on |
| GPS lost: IMU only | No GPS fix for more than 3 s |
| GPS + IMU fused | Otherwise |

## 7. Known simplifications

- The east and north filters are independent. Orientation error couples the two axes, but the
  offset state absorbs most of that.
- GPS fixes are applied when they arrive, with no compensation for their latency (typically
  50-200 ms).
- The compass sets the heading of the rotation vector. If it is disturbed, for example near a
  car's metalwork, the acceleration direction rotates. GPS velocity updates keep speed right, but
  lateral acceleration suffers most. Learning a compass-to-GPS heading offset is a possible
  improvement.
