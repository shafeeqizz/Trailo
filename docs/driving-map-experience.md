# Driving map presentation

This refines local foreground tracking only. No routing, multiplayer, compass
subscription, background service, or additional location requests are introduced.

## Behavior

- A blue arrow displays accepted course over ground. Before a trustworthy course
  exists, a neutral blue dot avoids implying a direction. Stationary fixes retain
  the last trusted course. Stale artwork is gray and labeled as last known.
- Native GPS course needs precise permission, horizontal accuracy at most 30 m,
  bearing accuracy at most 20 degrees, and speed minus reported speed uncertainty
  at least 2 m/s. These conservative starting thresholds need real-device tuning.
- If course metadata is missing/unreliable, consecutive precise GPS fixes can
  supply course only when 0.5–5 seconds apart, separated by at least 10 m and the
  sum of their accuracy radii, with implied speed 2–65 m/s. Reported stationary or
  uncertain speed vetoes this fallback. Approximate permission never supplies heading.
- Marker interpolation is bounded to incoming measurements and at most 800 ms.
  Approximate or horizontally uncertain (over 30 m) fixes snap rather than imply
  an accurate path through intermediate coordinates.
  Rotation takes the shortest angular path. No extrapolation or dead reckoning occurs.
- North-up keeps geographic north at the top. Heading-up rotates to accepted course
  and falls back to north before course exists. Driving follow offsets the camera
  target along travel so the vehicle sits below centre in heading-up and opposite
  the direction of travel in north-up; the offset scales with zoom, density and
  viewport and is capped at 1 km. It never changes the measured marker position.
- Camera animations run once per changed target/mode, not once per rendered frame.
  A pan or zoom cancels following, not tracking. Orientation changes preserve this
  pause; Recenter resumes it. Stationary identical targets cause no repeated animation.
- GPS loss holds the marker. A prolonged gap cancels interpolation and recovery
  snaps to the new measurement, avoiding a false journey through the missing period.
  The old heading is discarded on recovery unless a new reliable course exists.

Location metadata is provided by Android's [Location API](https://developer.android.com/reference/android/location/Location).
Camera position/bearing use the Maps [CameraUpdateFactory API](https://developers.google.com/android/reference/com/google/android/gms/maps/CameraUpdateFactory).

## Validation

Validation on emulator-5554 (API 35) exercised a synthetic east/north/west route,
with additional route runs after camera corrections. Observed results:

| Check | Observation |
| --- | --- |
| Continuous tracking and heading | New fixes updated the marker without refresh/restart. Accepted course and heading-follow camera changed approximately 90°, 0°, 270° across the three legs. |
| Marker/camera smoothing | Successive recorded frames showed bounded movement and changing map position. No frame-rate or physical GPS latency claim is made. |
| Look-ahead | Vehicle stayed below centre in heading-up. Viewport density correction was visually verified. North-up retained a 0° camera and a west-pointing arrow, with space ahead to the west. |
| Pan | Follow became false while fresh fixes and marker updates continued. Recenter restored following. |
| Zoom | A fast ADB double-tap changed zoom 17 to 18 and paused follow. Recenter resumed at zoom 18. Pinch zoom was not separately injected. |
| Stationary | Eight callbacks in ten seconds; zero marker position setter calls and zero camera animation calls. Median callback interval 1000 ms, with some 1998 ms gaps. |
| GPS loss | A disabled temporary test GPS provider produced a gray retained arrow and stale warning. The final guard retained the camera target even when the banner resized the viewport (camera-to-marker distance remained about 78.6 m at zoom 18). |
| Recovery | Removing the test provider and sending new moving fixes restored fresh status, blue artwork, accepted course, and following without restart/Recenter. |
| Layout | Light/dark tiles and marker artwork inspected; 180% font size in landscape retained visible controls, map, and system insets. Display settings restored. |

The emulator reported near-north native course with 30° uncertainty even during
east/west travel; attempted NMEA injection did not resolve that inconsistency.
The app rejects that uncertainty and used its GPS-displacement fallback in these
route tests. Reliable native-bearing selection is JVM-tested, but needs a physical
device check. Debugger snapshots briefly suspend the app; visual recordings and
targeted stationary counters are diagnostic evidence, not performance benchmarks.

Validation commands:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest
```

43 JVM tests and two Android smoke tests passed; lint reported zero errors and
13 dependency-version warnings. The waiting-label typography suggestion was
resolved using an XML character reference. Regression tests cover uncertain course, angle
wraparound, camera modes/gestures/recenter, interrupted zoom, display density,
uncertain-fix interpolation, and stale banner resizing. Existing lifecycle,
ordering, permission, and tracking-policy tests remain in the suite.

These checks cannot establish real-road GPS quality, physical-device battery use,
or production navigation quality. This map has no route snapping or turn guidance.

The final installed build repeated moving fixes, stale retention, recovery, and
background/foreground cancellation. GNSS stopped on backgrounding and in browsing;
the emulator was left in browsing mode. Temporary GPS providers, shell app-ops,
debugger port forwards, night mode, font size, and rotation were restored.
