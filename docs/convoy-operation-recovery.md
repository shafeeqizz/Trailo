# Bounded convoy operation recovery

Implemented locally on `feat/convoy-sessions`; nothing deployed.

## Root cause and replacement

The previous coroutine deadline returned after 65 seconds, but retained the SDK
Task as the mutation gate. Every refresh waited on that same Task again before
reading Firestore. A truncated HTTP body could leave the Task unsettled, so the
deadline did not release the gate.

The gateway now retains durable operation metadata, not the SDK Task. Each
mutation receives a random 32-hex-character `operationId`. Before dispatch, the
client synchronously saves the ID and kind in private, backup-disabled app
preferences, scoped to the named Firebase app and authenticated UID. Persistence
failure prevents dispatch. Names, invitation secrets, arguments and tokens are
never stored in this journal. A late callback cannot clear a newer operation.

The SDK deadline remains 60 seconds; the independent application wait is at most
65 seconds. Tests use a shorter emulator-only override. Cancellation does not
cancel the server mutation. Navigation/lifecycle stop detach the UI; the journal
survives process death and is checked before another mutation.

## Server fence and contracts

All five existing callable contracts accept an optional `operationId`; the
Android gateway always supplies it. Legacy calls without IDs retain prior behavior.
IDs are scoped to the authenticated UID and operation kind, and must never be
reused for new input or a new action.

Each identified mutation reads/creates its receipt in the same transaction as
convoy, invitation and pointer writes:

`userOperations/{uid}/requests/{operationId}`

Fields are `kind`, `status`, `createdAt`, and minimal committed `result` metadata.
Committed IDs replay the historical result without repeating the mutation.
Invitation receipts contain only convoy/version metadata and an unavailable-secret
flag, never plaintext secrets or invitation hashes.

The new authenticated callable `resolveConvoyOperation` accepts exactly
`{operationId, kind}`. Its transaction either finds a committed receipt or creates
an immutable `cancelled` tombstone. Recovery and the original mutation serialize
on that document: if cancellation wins, delayed delivery cannot mutate afterward;
if the original wins, recovery confirms commitment. No caller-supplied UID or
authorization assertion is accepted. New cancellation records are limited to 20
per UID per anchored minute; reading existing receipts is idempotent.

Rules permit only owner exact-document gets of receipts. Writes, lists and
cross-user reads are denied. Invitation hashes/rate-limit data remain server-only.
Convoy reads still require active, unexpired membership. A receipt grants no
membership and cannot authorize access to an ended convoy.

Receipts/tombstones currently have **no TTL**: deleting them permits an old ID to
execute again. Production retention/compaction must first add a bounded replay
window rejecting old IDs. Do not add TTL deletion alone. This is a storage/abuse
policy limitation; correctness does not depend on timely TTL cleanup.

## Client recovery

Recovery first reads the receipt with Firestore `Source.SERVER` (10-second bound).
An existing receipt avoids another Functions call. If absent, a new SDK Task
requests the idempotent fence (30-second application bound), followed by another
authorized receipt read. It never waits on/replays the old Task. A lost recovery
reply is safe because the receipt is authoritative and another check is idempotent.

The ViewModel also bounds the entire recovery attempt to 45 seconds. It then
observes current session reads independently, even if recovery remains unresolved.
Only a resolved receipt/fence AND server-confirmed current session enable writes.
A missing receipt alone never proves noncommitment.

| Operation | Committed receipt | Cancelled receipt |
| --- | --- | --- |
| Create | Read current session; never recreate from this ID | Original cannot create later; refresh then allow an explicit new create |
| Join | Read current pointer/membership; never replay join | Original cannot join later; user can re-enter a secret for a new attempt |
| Invite | Secret unrecoverable; authorized creator can explicitly replace/revoke it | No invitation issued by this ID; allow fresh issuance |
| Leave | Read current pointer; never clear a newer session locally | Original cannot remove membership later; allow a fresh authorized leave |
| End | Read current pointer; ended convoy remains inaccessible | Original cannot end later; allow a fresh creator-authorized end |

Receipts describe historical commitment; another device may have changed the
session afterward. UI membership always comes from current authorized reads.
Replaying committed Create after End cannot create again; replaying Join after
Leave cannot rejoin. An invitation secret is never reconstructed or persisted.

When offline, denied, throttled or unable to confirm the fence, the UI explicitly
shows **unresolved**, keeps conflicting writes disabled, and offers **Check pending
request** plus **Back to map**. Refresh checks/resolves the existing ID and reloads
session state, never dispatching the original action. Reconnection, route re-entry
and process restart recover without discarding the safety journal. No restart is
required merely because the original SDK Task stays incomplete.

## Local tests and fixtures

`node scripts/convoy-fault-proxy.cjs` exposes localhost-only demo proxies:

| Port | Fault |
| --- | --- |
| 5002 | Completed reply delayed 22 seconds |
| 5003 | Completed success reply replaced with unavailable/503 |
| 5004 | Mutation reply truncated after upstream completes; SDK Task may stay unsettled |
| 5005 | Mutation held until `/release`; recovery passes through |

Only the five demo mutation endpoints and recovery endpoint are forwarded to
local port 5001. Bodies/headers/secrets are never logged. The held test fences
cancellation, then releases the old authenticated request to prove it cannot
commit. Stop proxies before the offline test expecting port 5002 to be closed.

`ConvoyStuckTaskIntegrationTest` exercises all five truncated operations, two-user
membership afterward, cancellation before late delivery, repeated attempt blocking
and a TaskCompletionSource that never completes. The last test verifies the wait
returns without completing/cancelling the underlying Firebase Task.

Short-deadline tests first warm the handlers with unauthenticated requests that
must return 401, establish an authorized initial Firestore session read, and
reconcile/clean only their dedicated named test identities. This separates cold
startup from intentional response faults and makes fixture retries safe. The
ordinary app still uses the documented 60/65-second callable limits.

Run `ConvoyRestartIntegrationTest#cSeedUnresolvedMutation`, force-stop the app,
then run `#dRecoverUnresolvedAfterProcessRestart` separately. It verifies durable
metadata, the same anonymous UID (test-only hash), authorized restoration and End.
JVM tests cover recovery's safety bound, unresolved/offline states, repeated attempts
and lifecycle re-entry; Compose tests cover visible membership and safe controls.

## Remaining boundaries

- No confirmed receipt/fence and no authorized session means unresolved, never
  permission to repeat writes. Offline recovery necessarily waits for connectivity.
- Lost invitation secrets require an explicit version-rotating replacement.
- Legacy callers without IDs do not get replay protection. Receipt retention and
  broader anonymous-account abuse policy require production review.
- The Firebase Installations background traffic limitation is unchanged. Explicit
  Auth/Firestore/Functions operations remain on the local demo emulators. No real
  credentials, production security changes, Maps/location edits or deployment.

## Validation recorded for this change

- `npm run build`: passed, Node 22.
- `npm run test:emulators`: **67 passed, 0 failed/skipped/cancelled**, 169.687
  seconds. Includes the original 56 tests and 11 receipt/fence/security cases:
  replay after End/Leave, cancellation for all five operations, create/cancel race,
  no invitation secret/hash in receipts, owner-only rules, atomic rollback,
  recovery-call authentication/schema checks and cancellation abuse limits.
- `gradlew.bat -Pkotlin.incremental=false :app:assembleDebug
  :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --offline
  --console=plain`: **passed**, final run 54 seconds, 84 tasks (16 executed,
  68 up-to-date). **70 JVM tests, zero failures/errors/skips**. Lint: **0 errors,
  17 warnings**: 15 existing dependency-update notices and two UseKtx suggestions.
  Native synchronous preference commits intentionally check the Boolean disk-write
  result before dispatch/clearing; the suggested convenience extension returns Unit.
- `ConvoyStuckTaskIntegrationTest`: **3/3 passed**, 15.313 seconds. This includes
  the never-completing Task adapter, all-five-operation truncated replies plus
  subsequent two-user activity, and not-committed/late-delivery cancellation.
- `ConvoyReliabilityIntegrationTest`: **2/2 passed**, 24.133 seconds, covering the
  22-second delayed reply and committed mutation with lost success reply.
- Unresolved restart seed: **1/1 passed**, 2.386 seconds; actual target-process
  force-stop; restore/clean in a separate invocation: **1/1 passed**, 1.562 seconds.
- Connection + normal two-user/offline integration + Compose UI classes:
  **8/8 passed**, 39.014 seconds, after stopping the fault proxies.
- Compose class repeated in **dark mode, landscape, 1.5x font scale**:
  **5/5 passed**, 33.828 seconds. Original emulator settings (light, portrait,
  font scale 1.0, auto-rotation disabled) restored. Baseline tests used light/
  portrait. These are automated rendering/control checks, not a full manual visual
  audit; an optional UI hierarchy/screenshot attempt returned a null root.
- Final targeted device runs therefore executed **20 passing tests across 15
  distinct cases**. Truncated tests use the same gateway path with a 2-second
  application bound to avoid repeating 65-second waits; the app default remains
  65 seconds. No operation outcome is inferred from elapsed time.
- `git diff --check`: passed, existing line-ending warnings only. Pre-test local
  Auth/Firestore state exported/restored; zero active test convoys left afterward.
  Fault proxies stopped, ordinary local demo backend left running. Logs, exports,
  local configuration and build artifacts remain ignored. No deploy, commit/push,
  environment migration, live-location sharing or Follow Leader work occurred.
