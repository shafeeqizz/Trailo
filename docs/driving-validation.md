# Foreground driving test evidence

Local validation on 2026-11-09 used emulator-5554, Android API 35, Google Play
services, and an Android-restricted development Maps key. No backend, routes,
background permission, or foreground service was added.

## Implementation boundaries

- `LocationTrackingPolicy` selects balanced browsing or continuous high-accuracy
  driving. The driving starting request is 1000 ms interval, 500 ms minimum,
  zero displacement. Those values do not guarantee delivery frequency.
- A single callback-flow subscription exists while foregrounded. Switching modes
  cancels the previous subscription; backgrounding stops GPS acquisition.
- No single-location acquisition API or polling loop drives tracking.
- The ViewModel rejects invalid, duplicate, and out-of-order fixes. A newer fix
  updates the marker and, when enabled, the camera target.
- Gestures pause camera following without stopping location. Recenter restores
  following. Marker interpolation lasts at most 800 ms between received endpoints;
  prolonged loss holds the last measurement, and recovery snaps to fresh data.

## Observed emulator behavior

| Check | Evidence |
| --- | --- |
| Moving route | A 12-point ADB route produced 11 distinct positions at the ViewModel, with 35 location events during the observation window. No restart or refresh was needed. Additional longer routes also ran. |
| Callback cadence | Median approximately 1080 ms; some gaps were around 2 seconds. |
| Injection-to-ViewModel latency | Median 171 ms; observed range 121–1191 ms across 11 matched points. Includes host/ADB and targeted debugger overhead, not physical GPS latency or final pixel latency. |
| Follow Camera | Camera followed the moving marker. A real pan set follow to false while marker updates continued. The paused camera was over 500 m from the moving marker; Recenter restored follow and settled within about 1 m of the marker. Display-state distances, not GPS accuracy claims. |
| Stationary | Six fresh events in 10 seconds, zero marker position setter calls, and zero camera animation calls. |
| GPS-only loss | A disabled temporary GPS test provider, with the master location switch still on, produced stale state while preserving marker and recenter. |
| Recovery | Removing the temporary provider and injecting a new point restored fresh tracking automatically, without a restart or Recenter. |
| Lifecycle | GNSS stopped after backgrounding; the app had no active subscription. Foregrounding resumed driving and fresh updates. |
| Mode switch | GNSS stopped in browsing and restarted when driving was selected again. |
| Approximate permission | Fine was temporarily revoked while coarse remained granted. The app received/displayed approximate location. Coarse delivery could become stale; fine permission was restored. |
| Animation | An unprofiled moving-route recording shows marker movement with a paused camera, then following after Recenter. |

A broad method-entry profiler initially distorted UI timing; those measurements
were discarded. The clean sample used one targeted ViewModel breakpoint.
Stationary checks used targeted marker/camera breakpoints. Screenshots,
recordings, coordinate-free timing data, and probes stayed outside Git.
Temporary mock-provider and shell app-op changes were restored.

## Repeatable checks and limits

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest
.\gradlew.bat :app:installDebug
```

Choose **Start driving test**, then play a moving emulator route. Pan while the
route continues: only camera follow should pause. Tap **Recenter on me** and
continue the route. Hold a stationary point and verify no visual drift.
Simulate provider loss and restore it without restarting Trailo.
Background/foreground the app and switch back to browsing to verify cancellation.

JVM tests cover policies, lifecycle ownership, timestamp ordering, freshness,
follow/pause/recenter behavior, and interpolation. Android smoke tests cover
activity recreation and the foreground-only manifest permission boundary.

The continuous-tracking baseline passed 31 JVM tests, two Android smoke tests,
debug assembly and lint (zero errors, 13 dependency-version warnings). That APK was installed
and exercised with another six-point route. Settled light and dark map rendering
were visually checked; large-font landscape controls were also inspected.
The emulator was left in browsing mode with diagnostic settings restored.

The completed directional-marker/camera refinement expands the suite to 43 JVM
tests and retains the two Android smoke checks. See
[driving map experience](driving-map-experience.md) for the final milestone behavior,
validation results and remaining device limitations.

These are emulator observations. Physical-device battery consumption, real-road
GPS noise, urban-canyon reception, thermal behavior, and approximate-location
delivery rates need device testing before navigation-quality claims are made.
