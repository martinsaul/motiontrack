# Building MotionTrack

## Requirements

- JDK 17 or newer (CI uses 21)
- Android SDK with platform 36 and build-tools installed
- No other setup: the Gradle wrapper downloads the right Gradle version

Tell Gradle where your SDK is, either with the `ANDROID_HOME` environment variable or with a
`local.properties` file in the project root:

```
sdk.dir=/path/to/android-sdk
```

## Common tasks

```bash
./gradlew testDebugUnitTest   # unit tests
./gradlew lintDebug           # Android lint
./gradlew assembleDebug       # debug APK  -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease     # release APK (unsigned) -> app/build/outputs/apk/release/
```

Install a debug build on a connected device:

```bash
./gradlew installDebug
```

## Signing a release

The release build is minified and **unsigned**, so you sign it with your own key:

```bash
# One-time: create a key
keytool -genkeypair -v -keystore my-release.jks -alias motiontrack \
  -keyalg RSA -keysize 4096 -validity 10000

# Sign and verify
$ANDROID_HOME/build-tools/<version>/zipalign -p 4 \
  app/build/outputs/apk/release/app-release-unsigned.apk motiontrack-aligned.apk
$ANDROID_HOME/build-tools/<version>/apksigner sign --ks my-release.jks \
  --out motiontrack.apk motiontrack-aligned.apk
$ANDROID_HOME/build-tools/<version>/apksigner verify --print-certs motiontrack.apk
```

Android only updates an installed app in place if the new APK is signed with the same key, so
keep your keystore safe.

## Demo drive

Debug builds include a scripted drive, so you can see the app work at a desk or on an emulator:

```bash
adb shell am start -n io.saul.motiontrack/.MainActivity --ez demo true
```

Press **Start**. The status line reads **Demo: ...** while it runs. The drive lasts about two
minutes, then cruises:

- pull away to 75 km/h and cruise
- slow down for a 90 degree left turn
- accelerate to 85 km/h, then brake hard to a stop
- wait about 10 seconds
- pull away and take a 60 degree right-hand bend

The demo generates GPS fixes (1 Hz, with slowly wandering position error), accelerometer readings
with vibration and a constant offset, gyroscope, and barometer, all in real time. They go through
the same `FusionEngine` as real sensors. Release builds ignore the `demo` extra.

The README screenshots come from this mode.

## Testing on real hardware

Nothing replaces a real drive. Mount the phone firmly, start before you set off, and compare the
fused speed with the raw GPS dots and your vehicle's speedometer. Emulator motion sensors are not
a substitute: the emulator smooths injected accelerometer values, and its GPS has no heading.
Use the demo drive or the unit tests in `fusion/` instead.
