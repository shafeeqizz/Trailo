# Android convoy lobby (local development)

The latest [bounded operation recovery](convoy-operation-recovery.md) supersedes
the historical stuck-Task/restart limitation below. Recovery now uses durable
operation IDs, transactional server fences and independent authorized reads.

This slice adds anonymous authentication, server-authorized session observation,
all five backend actions, and a basic Compose lobby. It does not share location,
render other vehicles, provide navigation, or deploy Firebase resources.

## Isolation and setup

Debug builds create only the named `convoy-demo` Firebase app with explicit demo
options for `demo-trailo`. The demo API-key placeholder is not a production key.
Auth, Firestore and Functions are configured before use with Android emulator host
`10.0.2.2` and ports 9099, 8080 and 5001. No production endpoint fallback or runtime
project selector exists. Automatic `FirebaseInitProvider` initialization is removed.
Existing ignored Firebase configuration and Maps configuration are preserved.
The Google Services plugin remains available but is not applied in this local-only
slice; the client does not require or compile `google-services.json`. The convoy
implementation does not read resource-based Firebase options. Re-enabling a real
project's resource generation requires a separate production integration review.

Release builds have `CONVOY_EMULATORS=false`, construct a disabled repository, and
cannot initialize the convoy gateway. Debug cleartext policy permits only local
emulator hosts; the main/release cleartext policy remains disabled. There are no
new location permissions or changes to location tracking policy.

From the repository root, with Node 22, Java 21 and Android SDK configured:

```powershell
npm.cmd ci
npm.cmd --prefix functions ci
npm.cmd run emulators
```

Keep that process running. In Android Studio, start an API 26+ Android emulator
and run the debug app. Use **Convoy lobby** on the map. Firebase emulators must be
started again after a host reboot. Their data is ephemeral unless separately
exported/imported; restarting emulators can invalidate an existing device identity.
The Android client reuses its persisted anonymous identity rather than signing in
on every resume. Clear app data only for deliberate test resets; that loses the
anonymous identity and any creator access. Session expiry remains the recovery
fallback. Account linking/recovery is not implemented.

This host configuration supports the standard Android emulator. Physical-device
hosts, production builds and production authentication are intentionally not
supported by this slice. Do not substitute a real project to make a test pass.

## Architecture and behavior

`domain/convoy` contains plain Kotlin models, errors, repository contract and strict
invitation/link parsing. `data/firebase` provides the SDK gateway and repository.
The narrow gateway permits JVM tests of repository policy without Android/Firebase.
Presentation lives under `ui/convoy` with immutable StateFlow state and callbacks.
The Activity constructs dependencies; composables do not call Firebase SDKs.

The lobby is a full-screen dialog over the existing map composition. Home's
foreground location subscription remains independent. The lobby listener starts
only while its route is STARTED and detaches on stop/disposal. It authenticates,
listens to the caller's private pointer, and switches to an exact convoy listener.
Auth loss, denied reads, expired sessions, pointer changes and cached snapshots
clear private presentation. Firestore uses memory-only caching; cached documents
are not treated as current membership. Metadata events allow reconnection recovery;
transient authentication/observation failures are retried while the lobby is visible.
Membership is re-read on every route restart and after mutations. Local expiry
timers hide stale UI; trusted server time/rules remain the authorization authority.

Create and Join accept user input. Join accepts a Base32 secret or a pasted
`trailo://convoy/join#SECRET` link. Only that exact scheme, host and path are accepted;
these are not verified app links and are not registered as external intent filters.
Invitation input is masked with keyboard suggestions disabled. The client invokes
`createConvoy`, `createConvoyInvitation`, `joinConvoy`, `leaveConvoy` and `endConvoy`
with only their documented fields. It never directly writes membership or pointers.
Server rules and handlers enforce authorization independently of UI controls.

The active lobby shows name, active status, roster count and participant numbers
with You/Creator/Leader labels. Nicknames and identity recovery are not implemented.
Only the creator sees invitation issuance and End; regular members see Leave.
End requires a confirmation dialog. Issuance rotates/revokes older invitations.
Secrets are held only in transient ViewModel memory, cleared on expiry, stop,
session loss/switch and dismissal, and shared only after an explicit share action.
They are not stored in saved state, preferences, analytics or application logs.
Sharing intentionally passes the bearer secret to the Android chooser/recipient.

Loading, empty, active, error and confirmed-command states are distinct. The app
never optimistically changes membership based on a button click. Requests have a
15-second client wait budget; failures/timeouts do not show success and trigger a
session refresh. A timed-out/cancelled callable may still have committed on the
server; do not blindly repeat it. Invalid/expired/revoked invitations share the
backend's generic failure, while full, rate-limited, already-in-session and offline
outcomes have separate messages. Already downloaded information cannot be erased
by server rules; OS/network detection and listener delivery are not instantaneous.

## Validation and outstanding checks

Run from the root:

```powershell
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew.bat :app:connectedDebugAndroidTest
npm.cmd run test:emulators
git diff --check
```

Run Android instrumented tests while the Firebase emulator launcher is running;
run the backend suite separately because it clears Firestore fixtures. The Android
SDK integration tests use independent named demo apps for two authenticated users.
They cover creation, invitation rotation/rejection, joining, repository restoration,
leaving, ending, cached-data hiding and unreachable functions. Compose tests cover
empty lobby and creator/member controls. JVM tests cover contracts, caching,
revocation, expiry, restoration, cancellation and failure/no-false-success behavior.

Device tests, screenshots, light/dark mode, large-font/rotation/edge-to-edge checks
and map/self-location regression must be reported from actual execution. A repository
recreation test is not proof of OS process-death behavior. Production IAM, App Check,
broader abuse controls, TTL configuration and previous dependency findings remain
outside this local integration slice. No deployment is authorized by these checks.

Validation encountered a full C: drive and memory/cache-lock constraints. Temporary
Gradle and AVD copies under ignored `.firebase/` permit work on D: without removing
originals. Detailed storage migration inventories/copy verification and machine-
specific configuration instructions stay in ignored local reports, not Git.

## Android connection investigation (2026-11-14)

The debug gateway targets `demo-trailo`, `us-central1`, and `10.0.2.2` ports
9099/8080/5001 for Auth/Firestore/Functions. Anonymous authentication and a
server-confirmed empty `userSessions` read worked, but callable requests failed
locally: Functions 22.1.1 invokes its transitive Instance ID dependency, which
validates API-key syntax even when the callable uses the emulator. The former
human-readable demo key caused an `IllegalArgumentException`, mapped to the
generic unconfirmed-request message. The placeholder now has valid syntax but
is still fabricated; no production configuration is loaded.

`ConvoyConnectionIntegrationTest` reproduces the failure before the fix and
passes afterward. It checks anonymous Auth, the private empty-pointer listener,
project selection, and authenticated backend rejection of an empty convoy name
without creating a convoy. The existing two-user create/rotate/revoke/join/
restore/leave/end device test also passed after warming the local functions.
Its first run timed out: unauthenticated startup probes measured about 22 seconds
for cold join/leave handlers, exceeding the client's 15-second wait budget.
Do not automatically replay timed-out writes; inspect/refresh the pointer first.

Before the checked Android retry, the running emulator contained zero convoys
and zero pointers. The checked retry with the old key also left zero records.
The original request's arrival cannot be proven from the retained logs; the
reproduced SDK failure explains why affected calls never send the HTTP request.

Remaining limitations: a previous action error stays visible until a subsequent
action or route reset even after an empty session is confirmed. Firestore emitted
transient reconnect/HTTP2 ping warnings, but the device restoration test passed.
The legacy Instance ID/Installations dependency also attempts background
registration and logs `FIS_AUTH_ERROR` with the fabricated configuration. This
does not authorize production resources, but means these SDKs do not provide
strict all-network emulator isolation. Eliminating that optional legacy traffic
requires a separately reviewed SDK/transport change; do not replace the fake
configuration with real credentials to silence it.

Validation for this investigation: debug and test APKs built; 63 JVM tests passed
with zero failures/errors; the new device regression failed once before the fix
and passed afterward; the two-user device workflow failed once on a cold timeout
then passed warmed (3.129 seconds). Fresh lint passed with 15 existing update
warnings after correcting the ignored local SDK property's escaping and rerunning
lint analysis (the previous result had been cached). `git diff --check` passed.
The final manual lobby read showed no active convoy and no error banner; both
isolated workflow convoys were ended. Full backend, UI-configuration and OS
process-death suites were not rerun in this targeted investigation.

## Reliability follow-up (2026-11-14)

This section supersedes the earlier 15-second callable policy. Anonymous sign-in
still has a 15-second wait, but each mutation now has a 60-second SDK deadline
and a 65-second coroutine safety bound. Local Functions lazily starts a separate
Node handler process on first invocation; measured cold join/leave startup was
about 22 seconds, while subsequent executions were fast. The deadline accommodates
that startup without unlimited waits. No backend timeout or production security
policy was relaxed.

Deadline, unavailable, internal and unknown callable outcomes are uncertain,
not proof that the write failed. The gateway retains the outstanding Task when
the caller stops waiting or is cancelled. A new mutation is rejected until a
read-only reconciliation waits for that Task (at most 65 seconds per refresh)
and then reloads the authenticated user's private pointer and active convoy.
Lifecycle restart uses this same gate. Cancellation never cancels the underlying
Firebase request or automatically resends it. The gateway stores no request
arguments or secrets on disk.

Presentation distinguishes running, unknown, reconciled, confirmed and rejected
commands. Create/Join require a server-confirmed empty session; other mutations
require a confirmed active session and the correct role. Loading, offline,
unconfirmed cached reads and pending reconciliation disable mutations both in
the ViewModel and the screen. Refresh only repeats reads/waits. A reconciled
session does not claim the earlier command succeeded. Only a completed callable
response shows the command-confirmed message. There is no automatic write retry.
Server transactions remain the final protection against concurrent duplicate
active convoys; this is not a general exactly-once request-receipt protocol.

Regression coverage includes virtual-time delayed responses, a committed write
with a lost response, pending reconciliation, manual retry gating, and reconnect
without replay. `ConvoyReliabilityIntegrationTest` additionally exercises the SDK
through `scripts/convoy-fault-proxy.cjs`, which forwards only the local demo
create endpoint and never logs bodies or headers. Start it with
`node scripts/convoy-fault-proxy.cjs`. Port 5002 delays replies by 22 seconds;
port 5003 discards completed success replies and replaces them with a 503.
The test methods are independent of execution order.
The tests restore the normal Functions endpoint and end their own test convoys.
Stop the proxy before the offline test that expects port 5002 to be closed.

An additional truncated-body probe returned a ProtocolException inside the SDK
and left its Task unsettled. The application kept the result unknown and blocked
mutations through its bounded recovery waits, but that device probe failed after
196.112 seconds, including repeated cleanup waits. This is a remaining SDK edge
case, not a successful recovery claim: restart the app process to discard a stuck
in-memory Task, then obtain the authorized session pointer before taking another
action. The isolated committed probe convoy was ended through the local backend.
No blanket replay, unlimited timeout or security-rule bypass was added to hide it.

### Firebase network isolation findings

Explicit Auth, Firestore and callable endpoints remain `10.0.2.2:9099/8080/5001`,
project `demo-trailo`, region `us-central1`; release convoy access remains disabled.
The local test proxy is used only by instrumented tests. Production Firebase
resource configuration and credentials are not loaded. Maps/self-location are
independent and were not changed by this follow-up.

The transitive Functions 22.1.1 -> Instance ID -> Installations path is not fully
emulated. Installed bytecode calls `getToken()`, which starts a background sync;
the Installations transport targets `firebaseinstallations.googleapis.com`.
Runtime log inspection found HTTP 400 / `API_KEY_INVALID` responses followed by
`FIS_AUTH_ERROR`, confirming Google received and rejected those synthetic
registration attempts. Only error categories/counts were reported, never tokens
or configuration values. Upstream implementations:
[Functions context provider](https://github.com/firebase/firebase-android-sdk/blob/main/firebase-functions/src/main/java/com/google/firebase/functions/FirebaseContextProvider.kt),
[Installations transport](https://github.com/firebase/firebase-android-sdk/blob/main/firebase-installations/src/main/java/com/google/firebase/installations/remote/FirebaseInstallationServiceClient.java).

The current dependency path has no supported Installations emulator switch.
Disabling auto-initialization alone does not prevent the explicit legacy token
lookup from scheduling sync. Real credentials must not be introduced to silence
the error. Strict all-network isolation would require a separately reviewed SDK
fix/transport replacement or external egress restriction; it is not achieved by
`useEmulator()` on the three convoy clients. No SDK internals were replaced with
reflection or global HTTPS interception, and production security was unchanged.

### Follow-up validation results

- `npm run build`: passed using Node 22.
- `npm run test:emulators`: 56/56 passed, zero failures/skips/cancellations,
  142.755 seconds, including rules, expiry, rollback and duplicate/concurrent create.
- `gradlew.bat -Pkotlin.incremental=false :app:assembleDebug
  :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --offline
  --console=plain`: passed in 1m 19s, 84 tasks (27 executed, 57 up-to-date).
  JVM: 67 passed, zero failures/errors/skips. Lint: zero errors, 15 existing
  dependency-update warnings. Initial incremental compilation could not resolve
  unchanged top-level map functions in tests; full Kotlin compilation passed
  without changing those functions. Subsequent fixture-only APK/lint build passed
  in 32 seconds, 61 tasks (7 executed, 54 up-to-date).
- Cold backend plus delayed-reply device test: 1/1 passed, 51.831 seconds including
  cold End cleanup. Final independent delay/lost-reply class: 2/2 passed in
  24.084 seconds. It restored the committed session and verified duplicate Create
  returned already-active rather than adding a second convoy.
- Connection, two-user workflow and four lobby UI cases passed. The initial
  combined seven-case run had 6 passes and one offline-test cleanup failure:
  cleanup tried another mutation before reconciling the uncertain call. The
  fixture now reconciles first; that specific test passed on recheck in 3.926
  seconds. Thus all nine distinct targeted device cases have passing results;
  this is not a claim that the initial seven-case run was fully green.
- The separate truncated-body experiment remains a failed recovery probe as
  described above. No large-font/dark-mode/rotation matrix or OS process-death
  suite was rerun. These checks do not establish production readiness.
- `git diff --check`: passed (existing line-ending warnings only). Existing
  uncommitted work was preserved. No deployment, commit, push, migration, location
  sharing or Follow Leader implementation occurred.

The pre-test Auth/Firestore state was exported under ignored `.firebase/` before
the backend suite and restored for device testing. The fault proxies were stopped
after validation; the local demo backend remains running. Export files, logs,
machine configuration and generated APKs remain ignored.
