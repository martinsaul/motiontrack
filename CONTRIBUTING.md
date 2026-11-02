# Contributing to MotionTrack

Thanks for helping out.

## Reporting a bug

Open an issue with:

- your phone model and Android version
- how the phone was mounted (dashboard mount, pocket, hand, bike bar)
- what you were doing (driving, cycling, walking) and roughly how fast
- what you expected and what happened, ideally with a screenshot of the graphs

## Pull requests

1. Fork the repository and create a branch from `main`.
2. Keep the change focused. Small pull requests get reviewed faster.
3. Before you open the pull request, run:

   ```bash
   ./gradlew testDebugUnitTest lintDebug assembleDebug
   ```

4. Add or update unit tests for any change to `fusion/`. Its code has no Android dependencies, so
   tests run on the JVM with simulated sensor data.
5. If you change filter tuning or the model, update [docs/HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md) to
   match.

## Design principles

- **Open and go.** Press Start and get numbers. Tuning belongs in code, not in settings.
- **No tracking.** No analytics, crash reporters, ads, network access, or third-party SDKs.
- **Few dependencies.** AndroidX and Kotlin coroutines only, unless there's a strong reason.

## License

By contributing, you agree that your contributions are licensed under the
[GNU AGPL v3.0 or later](LICENSE), the same license as the project.
