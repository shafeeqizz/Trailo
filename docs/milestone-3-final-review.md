# Milestone 3 final code and security review

Date: 2026-11-14. Scope: the complete `feat/convoy-sessions` change against
`main` (`1cc2803`), including previously untracked source, tests, rules, manifests,
version catalogs, both npm lockfiles and implementation documentation.

## Decision

Suitable for review as an **emulator-only Milestone 3 pull request**. No blocking
authorization, transaction or data-isolation defect was identified in this scoped
review. This is not approval for Firebase deployment or a public Android release.
The production gaps below remain open; passing emulators is not proof of runtime
IAM, App Check, quotas, deployed rules or real-device safety.

Implemented: authenticated Create/Invite/Join/Leave/End, private session observation,
the Android lobby, and bounded recovery with server operation receipts/cancellation
fences. No sharing of locations, friend markers, routing or Follow Leader.

## Findings and guarantees checked

- Callable middleware verifies authentication; handlers derive UID from auth and
  reject forged/extra policy fields. Creator-only issuance/End, participant-only
  self-departure, separate immutable creator/leader fields and capacity 20 remain
  server enforced. The embedded roster is authoritative; member count is derived.
- Firestore denies by default. Only current members get active/unexpired convoy
  documents; only owners get their pointer/operation receipts. No client lists or
  writes are granted. Invitation digests and rate limits remain server-only.
- Admission serializes on the caller pointer and target convoy. Joins cannot
  overfill the final slot. End/Join/Leave/rotation conflicts retry transactionally;
  cleanup compares pointer convoy IDs, preserving newer sessions. Failed
  transactions leave neither partial membership nor partial receipts.
- Each identified mutation reads/creates its operation receipt in the same
  transaction as the mutation. Cancellation and commitment contend on that same
  document. A cancelled late request cannot commit; committed IDs replay without
  re-execution, including after End/Leave. Invitation replay never returns a secret.
- The client never automatically retries a write. It saves ID/kind before dispatch,
  bounds waits independently of the SDK, and restores authorized session reads.
  An uncertain or missing receipt cannot enable writes until recovery fences or
  confirms the operation. Lifecycle and process restart retain the safety journal.
- Server time/request.time enforce expiry/status without relying on TTL or client
  clocks. Expired/ended convoy reads/admissions/issuance are denied. Leave can clean
  only a matching own stale pointer; creator End can finalize an expired convoy.
- Receipts are owner-private historical operation metadata, not authorization for
  an active session. They may outlive a convoy. UI membership always comes from
  current server-confirmed reads, not a historical replay result.
- UI loading, offline, unresolved, restored, role-specific and confirmation states
  were inspected. Unresolved state provides a read-only recovery action and map
  navigation while disabling conflicting writes. Secrets are transient and not
  journalled, logged or saved in instance state. Sharing is explicit.
- Debug options fix `demo-trailo`, region `us-central1`, and emulator hosts.
  Release convoy access is disabled. Firebase auto-init/resource generation is
  disabled for this slice; builds do not require real Firebase client config.
  Main cleartext policy stays disabled; debug allows only local emulator hosts.
  The launcher filters inherited service credentials and accepts no project override.

The pointer/roster invariant assumes privileged actors do not corrupt/delete live
pointers. Clients cannot do so. Malformed privileged records fail closed rather
than searching every convoy. Revocation cannot erase data already downloaded.

## Corrections made during final review

- Replaced stale README/setup instructions requiring a real `google-services.json`
  and calling the implemented lobby/backend future work. Added current contract
  links while retaining clearly labelled historical slice results.
- Normalized two older backend documents to valid UTF-8, preserving their text.
- Made short-deadline fault tests self-contained: unauthenticated handler warm-up,
  authorized initial session reads, and cleanup only of dedicated named test users.
  The first combined device run started cold with a 2-second fault deadline and
  recorded 2 passes/3 failures (unresolved setup state), not a security bypass.
  The fixture now separates startup from response-fault injection; final recheck
  results are recorded below. Normal app deadlines were not shortened or relaxed.

## Dependency security

Separate fresh root and Functions `npm audit --json --cache .npm-cache` runs:

| Tree | Critical | High | Moderate | Low/info | Affected packages |
| --- | ---: | ---: | ---: | ---: | ---: |
| Root development/test | 0 | 5 | 14 | 0 | 19 |
| Functions runtime/development | 0 | 0 | 0 | 0 | 0 |

Root direct affected development dependencies are `firebase`,
`@firebase/rules-unit-testing` and `firebase-tools`. High inherited paths include
Firestore/compat and `@grpc/grpc-js`. Moderate paths include Pub/Sub,
OpenTelemetry, archiver/zip-stream, exegesis, glob/minimatch/rimraf and
gaxios/gcp-metadata/uuid. These do not enter the Functions deployment tree or
Android Gradle artifacts. Counts include inherited package reports, not 19
independent exploits. Detailed paths/advisories and compatibility analysis remain
in [the Create backend review](create-convoy-backend.md#dependency-audit).

Root audit exits 1 for findings; Functions exits 0. No `audit fix`, forced fixes,
major upgrades/downgrades or global overrides were applied. Audit proposes unsafe
major downgrades for several parent trees. Prefer compatible upstream parent/patch
updates, then audit and test the resolved tree. `fixAvailable: true` does not prove
every pinned parent range is compatible. Keep tooling loopback-only and do not
process untrusted trees/patterns as though development dependencies were harmless.
Zero Functions npm findings is not a claim about all Android SDK vulnerabilities.

## Outstanding production-readiness issues

1. Review/resolve or explicitly accept dependency risks, including the Android
   BoM/Kotlin compatibility pin. Do not suppress compiler/artifact/TLS checks.
2. Configure and verify App Check, real auth/provider policy, runtime IAM,
   separate environments/registrations, regions, API restrictions, quotas,
   monitoring and broader abuse controls. Per-UID limits are bypassable through
   anonymous account creation; Create/Leave/End and existing receipt reads still
   incur work without broader request limits.
3. Define bounded replay/retention and account-deletion policy before deleting
   receipts/tombstones. They currently have no TTL; deleting a fence alone permits
   an old operation to execute again. Receipt subcollections need explicit cleanup.
   No physical TTL policy has been provisioned or verified for session data.
4. Legacy callers omitting IDs get only the older session invariant, not receipt
   replay protection. Coordinate server/client rollout and never reuse an ID for
   new input. Anonymous identity loss can lose creator access; linking/recovery is
   not implemented. Device clocks/timers are presentation aids, not authorization.
5. Strict all-network Firebase isolation remains incomplete: the transitive
   Instance ID/Installations path can reach Google and fail with fabricated config.
   Explicit convoy operations stay local. Do not add real credentials to silence
   this. Review an SDK/transport fix or external egress policy separately.
6. Physical-device networking, production identity/revocation behavior, runtime
   resource/battery profiling and exhaustive visual/fault testing remain unverified.
   No location-sharing schema, permissions or deployment is included.

## Final validation

Fresh checks from the repository root:

| Check | Final result |
| --- | --- |
| `npm run build` | Passed, Node 22.23.5 |
| `npm run test:emulators` | 67/67 passed, 0 failed/skipped/cancelled, 170.878 seconds |
| Debug APK + test APK + JVM + lint Gradle tasks | Passed; final full build 1m 12s, 84 tasks (14 executed, 70 up-to-date) |
| JVM XML reports | 70 tests, 0 failures/errors/skips; an explicit test-task rerun also passed |
| Lint | 0 errors, 17 warnings: 15 dependency notices, 2 intentional synchronous-preference UseKtx suggestions |
| Stuck-task + delayed/lost-response device classes | 5/5 passed, 39.800 seconds, after fixing fixture readiness |
| Unresolved seed, force-stop, restore/clean | 1/1 + 1/1 passed, 61.906 / 2.676 seconds including fixture warm-up |
| Connection + normal two-user/offline + UI device classes | 8/8 passed, 38.246 seconds |
| Dark/landscape/1.5x-text Compose recheck | 5/5 passed, 42.921 seconds; original settings restored |
| Existing home recreation/foreground-permission regression | 2/2 passed, 7.210 seconds |
| `git diff --check` and staged equivalent | Passed after trimming trailing blank lines in newly staged files |
| Staged publication scan | 49 files; zero credential patterns, forbidden paths/artifacts, machine paths, invalid UTF-8 or unsafe lockfile resolved URLs |

Gradle commands used `-Pkotlin.incremental=false` with
`:app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest
:app:lintDebug --offline --console=plain`. A separate invocation explicitly reran
the JVM test, main lint analysis and lint report tasks. Fixture-only compilation
and lint passed after the test-readiness correction. Audits ran separately as
documented above; no updates were forced.

Device commands used `adb shell am instrument -w -r -e class <class-or-method>
com.trailo.app.test/androidx.test.runner.AndroidJUnitRunner`. Fault proxies were
stopped before the offline test; restart seed and restore ran in separate processes.
The earlier 2/5 combined device result is retained as a failed setup run, not
represented as green. No production identities or backend resources were tested.
Final targeted runs executed **20 passing device tests across 15 distinct cases**
on the API 35 emulator. Test-only 2-second deadlines isolate stuck/truncated replies;
the ordinary app retains 60/65-second limits. Fault proxies were stopped afterward.
The existing home regression adds two passing cases: **22 passing executions across
17 distinct cases overall**. Zero active emulator convoys remained after cleanup.

## Publication hygiene

Only source, tests, deny-by-default rules, lockfiles and documentation are included.
Local Firebase client configs, environment files, signing material, SDK paths,
exports/logs, dependency trees, generated Functions JS, Gradle caches and APKs
remain ignored. Staged names/content and the diff are checked before commit.
The demo key and synthetic test values are fabricated, not production credentials.
No Firebase deployment is authorized or performed; the pull request must not be merged.
