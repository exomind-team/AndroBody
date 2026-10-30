# AndroBody contributor rules

- Read [README](README.md), [architecture](docs/ARCHITECTURE.md) and [roadmap](docs/ROADMAP.md) before changing product behavior.
- Keep Android's theoretical capability surface open. Record potential, current feasibility, actual attempts and observed results separately; static inspection alone cannot prove a capability is blocked or successful.
- Route tools through the shared capability policy and Android permission checks. A model request or permission grant is not evidence that a system action completed.
- Preserve session identity, cancellation state and unresolved receipts across failures. Do not silently replay an action whose result is unknown.
- Keep logs useful for diagnosis while excluding credentials, prompts, notification bodies, audio and file contents by default.
- Distinguish source review, host checks, emulator checks, physical-device checks and human experience. Mark unrun checks as `NOT_RUN`.
- Do not commit credentials, signing keys, device backups, private configuration, raw user logs or generated APKs.
- Physical Android testing and system permission changes require the maintainer's direction. Do not grant permissions through ADB.

## Build entry points

The Android app module is `android/native-app` and uses the checked-in Gradle wrapper.

```powershell
android\gradlew.bat -p android -PtestBuildType=verification :app:assembleAndrobodyVerification
android\gradlew.bat -p android -PtestBuildType=verification :app:assembleLegacyMigrationVerification
```

The verification variants use a debug certificate and separate application IDs. They are not production releases.
