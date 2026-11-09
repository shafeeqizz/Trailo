# Maps and foreground location setup

Milestone 2 uses Maps Compose 6.7.1 (which brings the Maps SDK for Android)
and Google Play services fused location 21.3.0. Firebase, convoy membership,
friend tracking, Routes, and navigation are not implemented.

## Local key and Google Cloud

1. Confirm application ID `com.trailo.app` before registering production services.
2. In Google Cloud, use a development project with billing enabled. Enable only
   **Maps SDK for Android** for this feature; configure quotas and budget alerts.
3. Create an API key with **Android application** restrictions: package
   `com.trailo.app` and the debug signing certificate SHA-1. Obtain your actual
   fingerprint with `.\gradlew.bat :app:signingReport` (the debug certificate is
   created by a debug build). Do not copy a teammate's fingerprint. Register
   release/Play signing fingerprints separately using a separate production key.
   The project uses Android's default debug signing configuration; it does not
   configure or bundle a private release keystore or signing passwords.
4. Add an **API restriction** allowing only Maps SDK for Android.
5. Copy `secrets.properties.example` to `secrets.properties` in the repository
   root and replace `YOUR_MAPS_API_KEY`. Keep `local.properties` for the SDK path.
6. Rebuild. The app build script reads Java Properties directly, supplies the
   manifest placeholder, and generates only a boolean configuration flag. This
   small local configuration avoids adding a secret-management plugin. Never
   print the property or commit the real file. Java Properties syntax applies.

A missing/blank/example key still builds. The app shows setup instructions and
skips creating the map. A nonempty but invalid/restricted key cannot be validated
locally: tiles will not load until Cloud configuration is corrected. The SDK does
not expose an authentication-failure callback. Check Cloud API/package/SHA-1
restrictions if tiles stay blank. Keys packaged in an APK are extractable;
restrictions are essential. Keep generated APKs, merged manifests, and build
outputs out of Git. The app does not log coordinates, tokens, or keys.

Official references:
- https://developers.google.com/maps/documentation/android-sdk/start
- https://github.com/googlemaps/android-maps-compose
- https://developer.android.com/develop/sensors-and-location/location/permissions/runtime

## Foreground location behavior

Location is requested only when **Enable location** is tapped. Fine and coarse
permissions are requested together; approximate-only access is fully supported.
Denial leaves the map usable. App settings is always available for permanent
denial. Returning from settings rechecks permission and Play services. Device
location settings changes are observed while visible; disabled services stop
location requests and show a settings action. Missing/outdated Play services
shows a recovery action and avoids constructing the map.

The activity starts one continuous fused subscription on resume and cancels it
on stop. No background location permission, foreground service, polling loop,
or repeated single-location requests are used. The Maps My Location source stays
disabled, avoiding a second source of location updates.

The default **Map browsing** policy requests balanced power, a 10-second interval,
5-second minimum interval, and 10-meter displacement. **Start driving test**
switches to **Active driving**: HIGH_ACCURACY, requested interval 1000 ms,
minimum interval 500 ms, zero displacement, and zero initial cached age.
`LocationTrackingPolicy` contains configurable starting values; sensor, OS,
Play services, and approximate permission can deliver slower or batched fixes.
Both modes use permission-level granularity and never upgrade coarse permission.
Active driving is an explicit foreground-only testing mode, not route navigation.
It consumes more power than browsing; actual battery use needs device profiling.

Newer valid measurements always update the marker. Follow Camera starts with
driving mode; a map gesture pauses camera following without stopping location.
Recenter resumes following immediately and future fixes continue centering the
camera. Recenter does not request a single fix. Marker motion interpolates only
between received endpoints, for at most 800 ms. Identical stationary coordinates
do not animate. Stale fixes and recovery after a prolonged gap snap to the last
reported point instead of animating unobserved travel. No dead reckoning occurs.

The compact map view uses a blue directional arrow for reliable GPS course and a
neutral dot before course is known. **North up** / **Heading up** switches camera
orientation. Look-ahead follows travel direction without changing measured marker
coordinates. Pan and zoom both pause following; Recenter preserves the user's
zoom. Uncertain/approximate fixes do not interpolate a precise-looking journey.
See [driving map experience](driving-map-experience.md) for heading thresholds,
camera policies, regression tests and actual emulator observations.

A marker becomes stale after 10 seconds without a fresh fix in driving mode,
or 60 seconds in browsing mode; availability failure can mark it stale earlier.
GPS loss, disabled services, and backgrounding preserve the in-memory last fix.
A newer valid fix automatically restores live tracking. Permission revocation
or a precise-to-approximate downgrade clears retained coordinates. Process death
loses in-memory state; no coordinates are persisted. Nonfinite/invalid fixes and
older or equal timestamps are rejected before they can affect marker, camera,
or freshness timers. The accuracy circle describes the original measurement,
not possible movement since then. Google services process data under their terms.

HomeViewModel owns StateFlow state, permission/environment changes, and camera
requests; the plain Kotlin location contract has no Android/Google types. The
Android repository owns SDK callbacks and cancellation. HomeScreen receives state
and callbacks; TrailoMap is the isolated rendering boundary for future friend
markers. No speculative multiplayer model or empty layer has been added.

## Emulator/device checklist

Use an API 26+ emulator with **Google Play** (preferred) or Google APIs and working
Play services, internet, and device location. Set a valid restricted key, then:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest
.\gradlew.bat :app:installDebug
```

Launch Trailo, enable location, and send coordinates from Emulator Extended
Controls > Location > Single points > Set location. Test:

Run installation after instrumented tests because test cleanup can uninstall the
application. API 35 emulator observations are recorded in
[foreground driving validation](driving-validation.md) and
[driving map experience](driving-map-experience.md); the checklist below also lists
physical-device and alternative-image checks that have not all been completed.

- Precise and approximate (Android 12+) grants; first fix centers the camera.
- Enable Start driving test and play a simulated moving route. Verify marker and camera follow automatically. Pan the map: marker updates must continue while camera following pauses. Recenter must resume automatic following.
- Deny once/repeatedly and use App settings to grant/revoke. Relaunch and rotate.
- Toggle device location off/on while visible; the marker remains stale, then recovers.
- With no fix ever received, verify waiting/unavailable guidance. After a fix,
  interrupt GPS delivery: the marker and recenter action must remain with a stale
  warning after the policy deadline (or earlier on availability failure).
  Restoring fresh measurements must recover automatically without a restart.
- Stationary driving measurements should keep freshness alive without moving
  the marker or camera. Merely stopping route playback is not GPS loss: the
  emulator can continue emitting fresh measurements at the same coordinate.
  Browsing's displacement filter can suppress stationary callbacks.
- Inject a moving sequence while driving: marker and camera must follow. Pan:
  marker tracking must continue while the camera stays paused. Recenter resumes
  following subsequent points. Approximate delivery can be throttled by Android.
- Background/foreground repeatedly; verify no foreground-service notification
  or ongoing app location use after stop. Runtime profiling is needed to measure
  actual battery consumption.
- Missing/outdated Play services on a separate AOSP/test image: recovery UI, no crash.
- Missing key: setup message; invalid key/offline: no authenticated tiles, app remains usable.
- Light/dark mode, large fonts, landscape, rotation, gesture and three-button insets.

JVM tests verify ViewModel behavior, last-fix retention, virtual-time staleness,
permission clearing, timestamp ordering, tracking-policy selection, lifecycle cancellation,
camera follow/pause, marker interpolation, and subscription/timer cancellation via a fake repository.
They do not verify Play services, runtime permission dialogs, Android
callback cleanup, map tiles, accuracy, battery behavior, or rendering. Instrumented
smoke tests cover activity recreation and the manifest permission boundary.

## Google Routes API

Routes is not required to follow a leader's movement. Establish the use case first.
Web-service credentials belong on a trusted backend with client authentication,
API restrictions, quotas, and rate limits. Never ship backend keys or service-account
credentials in an APK. Review Maps Platform usage/storage terms before retaining
route data.

Official guide: https://developers.google.com/maps/documentation/routes

## Firebase

1. Create separate development/production projects. Register Android using the
   confirmed app ID and relevant signing fingerprints.
2. Choose auth providers and database regions deliberately. Proposed roles:
   Authentication for identity, Realtime Database for ephemeral location/presence,
   Firestore for durable metadata. Validate the design before implementation;
   avoid conflicting membership authorities across databases.
3. Only during integration, put downloaded client `google-services.json` in the
   ignored `app/` directory and add Google Services/Firebase BOM dependencies.
   Client config is not authorization. Keeping it local avoids mixing environments.
   Never include Firebase Admin/service-account keys in an Android client.
4. Deploy deny-by-default rules before clients connect. Require authenticated,
   active membership and validate identities, schema, and timestamps. Commit rules
   and emulator tests for unauthorized/cross-session access, expiry, and revoked
   membership with the feature. Never use public test-mode rules. Evaluate App Check.
5. Define sharing consent, retention/deletion, account deletion, expiry, and offline
   behavior. Minimize location history and never log coordinates or auth tokens.

Use isolated emulators with synthetic data for integration tests, never production.
Emulator configuration/tooling will be added with Firebase integration.

Official docs:

- https://firebase.google.com/docs/android/setup
- https://firebase.google.com/docs/emulator-suite
- https://firebase.google.com/docs/rules

## Android and release prerequisites

The current manifest requests internet and foreground fine/coarse location only. Background location and sharing controls remain future work. Background sharing needs separate evaluation of Android
foreground-service restrictions, battery impact, and Play location policies.

Before public release: confirm package ownership, configure secure release signing
and Play App Signing, provide a privacy policy and accurate Data safety disclosures,
verify current target-API requirements, and complete device integration testing.
Keep signing keys/passwords in secure storage, never version control.
