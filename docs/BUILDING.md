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

## Trying it without moving

Emulator motion sensors stay still, but you can feed it GPS fixes from the emulator console to
watch the graphs and the GPS side of the filter:

```bash
# longitude latitude altitude satellites speed(knots)
adb emu geo fix -75.0000 45.0000 70 8 0
adb emu geo fix -75.0000 45.0002 70 8 39
```

Use the unit tests in `fusion/` to exercise the accelerometer side. They simulate drives with
noisy GPS and IMU input.
