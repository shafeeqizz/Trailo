# Trailo

Trailo is an Android-first, real-time convoy navigation app for friends traveling
in separate vehicles. The goal is a shared map where participants can see each
other and follow a selected leader's actual movement without a fixed destination.
Public Android release comes first; iOS is a longer-term direction.

**Current status:** a Compose welcome screen, light/dark themes, ViewModel-owned
overview state, and JVM tests. Maps, navigation, authentication, and location
sharing are not implemented. The app requests no location or network access and
uses no external services.

## Technology

| Area | Choice |
| --- | --- |
| Language / UI | Kotlin 2.2.21, Compose BOM 2025.10.01, Material 3 |
| Architecture | MVVM, feature-oriented presentation, domain/data layers as needed |
| Build | Gradle Kotlin DSL, wrapper 8.13, Android Gradle Plugin 8.13.2 |
| Android | Minimum API 26 (Android 8.0), compile/target API 36 |
| Testing | JUnit 4 local JVM tests, Android lint |
| Planned mapping / routing | Google Maps SDK for Android, Google Routes API |
| Planned backend | Firebase Authentication, Realtime Database, Cloud Firestore |

Maps/Firebase dependencies are absent until their features are implemented.
The foundation does not need DI, networking, or navigation frameworks.

## Development

1. Install stable Android Studio supporting AGP 8.13 (Otter 2025.2.1 or newer), or
   Android SDK command-line tools. Use JDK 17 or 21 for Gradle; bytecode targets 17.
2. Install Android SDK Platform 36 and Build Tools 35.0.0 and accept SDK licenses.
   Set `ANDROID_HOME` or let Android Studio write ignored `local.properties`
   containing `sdk.dir`.
3. Open the repository root and sync Gradle. Use the checked-in wrapper; Windows
   users can substitute `gradlew.bat`.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Outputs: `app/build/outputs/apk/debug/`,
`app/build/reports/tests/testDebugUnitTest/`, and
`app/build/reports/lint-results-debug.html`.

Run `app` on an API 26+ device/emulator in Android Studio, or use
`./gradlew :app:installDebug` with an attached device. No Google credentials are
needed. Check overview expansion/collapse, dark mode, large fonts, rotation, and
system insets. Device checks are separate from JVM tests and lint.

Release builds enable R8 with Android's default optimized rules. Release signing,
Play deployment, and production backends are unconfigured. `com.trailo.app` is
provisional: confirm ownership/final ID before Firebase registration or publishing.

## Structure

```text
app/
  build.gradle.kts
  src/main/
    AndroidManifest.xml
    java/com/trailo/app/
      MainActivity.kt
      ui/home/          # Route, stateless screen, ViewModel, immutable UI state
      ui/theme/         # Shared Compose theme
    res/                # Strings, platform themes, launcher artwork
  src/test/             # JVM behavior tests
gradle/                 # Version catalog and wrapper
docs/external-setup.md  # Future Google services setup and security boundaries
AGENTS.md               # Contributor and agent conventions
```

Keep one module initially. Add `domain/` for plain Kotlin models/use cases/repository
contracts and `data/` for SDK-backed implementations as features need them. UI
should depend on abstractions, not Firebase/Maps clients. Extract modules only for
concrete ownership, reuse, or build-isolation needs. The current ViewModel uses
synchronous Compose state; use StateFlow with lifecycle-aware collection for
asynchronous work. There is no iOS scaffold or shared-code architecture yet.

## Roadmap

1. **Foundation:** Compose shell, MVVM boundary, tests, conventions, secure setup docs.
2. **Map/local location:** restricted key, consent/permission UX, foreground location,
   lifecycle handling, device tests.
3. **Identity/sessions:** Firebase Authentication, create/join, membership security,
   expiry, emulator-tested database rules.
4. **Live convoy:** session-scoped Realtime Database updates, leader selection,
   stale/offline states, battery-aware updates, sharing controls. Firestore is
   planned for durable metadata; validate authority boundaries before integration.
5. **Movement following:** leader history and navigation experiments; evaluate
   backend Routes API use where routing is useful.
6. **Release hardening:** privacy/retention, background-location evaluation, abuse
   prevention, accessibility, privacy-preserving diagnostics, signing, Play tracks.
   Evaluate iOS after Android behavior stabilizes.

See [external setup](docs/external-setup.md) before integrating Google services.
Follow [AGENTS.md](AGENTS.md); use small feature branches/PRs and never push to main.
