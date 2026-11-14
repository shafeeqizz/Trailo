# Trailo

Trailo is an Android-first, real-time convoy navigation app for friends traveling
in separate vehicles. The goal is a shared map where participants can see each
other and follow a selected leader's actual movement without a fixed destination.
Public Android release comes first; iOS is a longer-term direction.

**Current status:** Milestone 2 is implemented and validated on an API 35 Google
Play emulator with a simulated moving route. It includes a Maps Compose home
screen, opt-in precise/approximate foreground location, continuous driving
tracking, directional marker, accuracy circle, camera-follow controls, stale
retention/recovery, and recoverable location/Play services states. Map tiles require a
restricted Maps API key; keyless builds show setup guidance. Convoy sharing,
authentication, routes, and navigation are not implemented.

Location has two foreground-only modes: battery-efficient map browsing and a
manually enabled active driving test (high accuracy, requested 1000 ms interval,
500 ms minimum, 0 m displacement). These are tunable requests, not guaranteed
GPS frequencies. Driving follows the moving marker automatically; panning pauses
camera follow and Recenter resumes it. Tracking never depends on repeated
single-location requests. The marker interpolates between measured fixes only.
Temporary loss retains the last position with a stale warning (10 seconds in
driving, 60 seconds in browsing). Fresh fixes recover automatically. Backgrounding
cancels updates; permission loss/downgrade clears positions. Nothing persists to disk.

The driving map uses a blue course arrow when GPS heading is reliable, otherwise
a neutral location dot. Tap **North up** / **Heading up** to switch camera orientation.
Driving follow looks ahead along travel (below-centre vehicle in heading-up); panning
or zooming pauses following, and **Recenter on me** restores it. Stale positions
turn gray and hold still. Recovery after a prolonged gap snaps to the new measured
position rather than animating an unobserved journey. See
[driving map experience](docs/driving-map-experience.md) for heading rules and validation.

## Technology

| Area | Choice |
| --- | --- |
| Language / UI | Kotlin 2.2.21, Compose BOM 2025.10.01, Material 3 |
| Architecture | MVVM, feature-oriented presentation, domain/data layers as needed |
| Build | Gradle Kotlin DSL, wrapper 8.13, Android Gradle Plugin 8.13.2 |
| Android | Minimum API 26 (Android 8.0), compile/target API 36 |
| Testing | JUnit 4 JVM behavior tests, Android instrumented smoke tests, Android lint |
| Mapping | Google Maps SDK via Maps Compose 6.7.1, fused location 21.3.0 |
| Convoy backend | Authenticated Firebase callables, Firestore rules/transactions, Node 22; local Emulator Suite only |
| Android convoy lobby | Anonymous Auth, session listeners and Functions SDK from Firebase BoM 34.19.0 |

Milestone 3 implements Create, Invite, Join, Leave and End with an emulator-only
Android lobby and bounded recovery for uncertain callable outcomes. Release convoy
access is disabled. No Firebase services or rules have been deployed. Live location
sharing, other vehicle markers, routing and Follow Leader remain future work.
See [lobby setup](docs/android-convoy-lobby.md),
[operation recovery](docs/convoy-operation-recovery.md), and
[final review](docs/milestone-3-final-review.md).
The foundation does not need DI, networking, or navigation frameworks.

## Development

1. Install stable Android Studio supporting AGP 8.13 (Otter 2025.2.1 or newer), or
   Android SDK command-line tools. Use JDK 17 or 21 for Gradle; bytecode targets 17.
2. Install Android SDK Platform 36 and Build Tools 35.0.0 and accept SDK licenses.
   Set `ANDROID_HOME` or let Android Studio write ignored `local.properties`
   containing `sdk.dir`.
3. Firebase demo options are supplied explicitly; no `google-services.json` or
   production credentials are needed. Use Node 22 and Java 21 to run `npm ci`,
   `npm --prefix functions ci`, then `npm run emulators` for the lobby. See
   [Firebase setup](docs/external-setup.md#firebase).
4. Open the repository root and sync Gradle. Use the checked-in wrapper; Windows
   users can substitute `gradlew.bat`.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

Outputs: `app/build/outputs/apk/debug/`,
`app/build/reports/tests/testDebugUnitTest/`, and
`app/build/reports/lint-results-debug.html`.

Run `app` on an API 26+ device/emulator in Android Studio, or use
`./gradlew :app:installDebug` with an attached device. Configure a restricted Maps
key using `secrets.properties.example`; see [external setup](docs/external-setup.md).
Keyless builds work with a setup message. Check permissions, recenter, dark mode,
large fonts, rotation, and
system insets. Device checks are separate from JVM tests and lint.
Instrumented tests require a running emulator/device and can uninstall the app;
run `./gradlew :app:installDebug` after them when testing manually.

The milestone suite covers 43 JVM behaviors and two Android smoke checks.
Recorded emulator checks include a moving route with turns, pan/double-tap zoom,
Recenter, stationary fixes, GPS loss/recovery, lifecycle cancellation, and light/dark
layouts. Native-bearing reliability, road GPS noise, and battery use still need
physical-device validation. This is local tracking, not turn-by-turn navigation.

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
      domain/location/  # Plain Kotlin position/state and repository contract
      data/location/    # Cancellable foreground fused-location implementation
      ui/home/          # Map route, stateless screen, ViewModel, immutable UI state
      ui/theme/         # Shared Compose theme
    res/                # Strings, platform themes, launcher artwork
  src/test/             # JVM behavior tests
  src/androidTest/      # Activity recreation and foreground-permission smoke tests
gradle/                 # Version catalog and wrapper
docs/external-setup.md  # Maps key setup, emulator checks, future service boundaries
AGENTS.md               # Contributor and agent conventions
```

Keep one module initially. Add `domain/` for plain Kotlin models/use cases/repository
contracts and `data/` for SDK-backed implementations as features need them. UI
should depend on abstractions, not Firebase/Maps clients. Extract modules only for
concrete ownership, reuse, or build-isolation needs. The home ViewModel uses
StateFlow with lifecycle-aware collection; an Android location repository owns
foreground-only SDK callbacks. There is no iOS scaffold or shared-code architecture yet.

## Roadmap

1. **Foundation:** Compose shell, MVVM boundary, tests, conventions, secure setup docs.
2. **Map/local location (implemented; API 35 emulator validated):** restricted key,
   consent/permission UX, continuous foreground driving, directional marker,
   north-up/heading-up camera following, stale recovery, lifecycle handling, tests.
   Physical-device quality and battery validation remain before public release.
3. **Identity/sessions (implemented locally):** anonymous Auth, all five session
   actions, Android lobby, operation recovery, expiry and emulator-tested rules.
4. **Live convoy:** session-scoped Realtime Database updates, leader selection,
   stale/offline states, battery-aware updates, sharing controls. Firestore is
   already used for convoy metadata; validate future sharing authority boundaries.
5. **Movement following:** leader history and navigation experiments; evaluate
   backend Routes API use where routing is useful.
6. **Release hardening:** privacy/retention, background-location evaluation, abuse
   prevention, accessibility, privacy-preserving diagnostics, signing, Play tracks.
   Evaluate iOS after Android behavior stabilizes.

See [external setup](docs/external-setup.md) before integrating Google services.
Follow [AGENTS.md](AGENTS.md); use small feature branches/PRs and never push to main.

See [foreground driving validation](docs/driving-validation.md) for API 35 moving-route evidence and device-testing limits.
