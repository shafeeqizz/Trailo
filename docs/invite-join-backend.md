# Invite & Join Convoy backend (local only)

Current Android integration, optional `operationId` contracts and validation:
[final review](milestone-3-final-review.md) and
[operation recovery](convoy-operation-recovery.md). Slice-only statements below
are historical; all five mutations and the lobby are now implemented locally.

Current lifecycle additions are documented in [Leave & End](leave-end-backend.md).
The slice scope and validation below describe the preceding Invite/Join milestone.

Implemented callables: `createConvoyInvitation({convoyId})` and
`joinConvoy({secret})`. Create Convoy remains available. No leave, end, Android UI,
location sharing or leader-following functionality is implemented or deployed.

## Invitation issuance and revocation

Only the authenticated creator, who must still be in the active, unexpired convoy
roster, can issue invitations. A normal member cannot issue one. The ID must be
exactly 64 lowercase hexadecimal characters. Missing, unrelated and inactive IDs
share `permission-denied` without revealing a roster or convoy metadata.

The server draws 16 random bytes (128 bits) with `crypto.randomBytes`, encodes them
as 26 RFC 4648 Base32 characters without padding, and stores only the SHA-256 digest
as the `invitations/{digest}` document ID. Stored fields are `convoyId`, `version`,
server-generated `createdAt`, and `expiresAt`. No plaintext/encoded secret is stored
elsewhere. Only the authorized issuance response contains `{secret, expiresAt}`;
expiry is epoch milliseconds. No application logging or analytics was added.

`CONVOY_INVITATION_MINUTES` is an integer function parameter, default 30, permitted
range 1–1440. Expiry is capped by the convoy's expiry. New convoys start at
`inviteVersion: 0`. Older local records without a version can issue their first
invitation as version 1. Each issuance atomically increments the convoy's version
and creates one invitation, revoking every earlier version immediately. Old hash
records remain unreadable and unusable until cleanup. No separate revoke callable
is included. Issuance is explicit; Create Convoy does not issue an invitation.

If issuance commits but the response is lost, its plaintext cannot be recovered.
The creator can issue a replacement, which invalidates the previous version.
Concurrent issuances serialize; only the highest committed version remains valid.
An invitation is reusable by multiple members until expiry, rotation or session
invalidation; it is not a one-time redemption token. It is a bearer capability
that recipients can forward. App identity is not verified personhood.

## Redemption and session invariants

Only `{secret}` is accepted; user ID and policy fields always come from the server.
Accept either 26 plain Base32 characters or exact 5-5-5-5-6 groups separated by
hyphens. Outer whitespace and ASCII letter case normalize; internal whitespace,
other grouping and noncanonical final padding bits are rejected. Inputs are length
bounded before normalization. There are no nicknames in this slice.

The server hashes the normalized code. One Firestore transaction reads the
invitation, target convoy and caller pointer (and its target when still live).
It validates invitation/session expiry against trusted server time, active status,
and matching invitation version, then atomically updates the authoritative roster
and private pointer. It rechecks expiry after potentially waiting for pointer reads.
No transaction callback sends responses or performs an external side effect.

All mutation handlers share the active-pointer check. Joining while in another
live convoy returns `already-exists`. Create-versus-join and cross-convoy joins
serialize through the same pointer. All admissions read/write the convoy, so the
final slot cannot be admitted twice. The admission limit is the server constant
20, never a client value or mutable capacity request.

An existing member can redeem the current valid invitation again without a new
member or changed `joinedAt`, even when full. A missing/stale pointer can be repaired
if it does not point to another live convoy. A live matching pointer with no roster
membership fails closed. Dots in UIDs are literal map keys, not field-path syntax.
Pointer uniqueness still depends on server-owned pointer/roster consistency;
privileged corruption/removal of unrelated active pointers is not repaired by
searching all convoys. Future leave/end operations must preserve this invariant.

Success returns only `{convoyId, expiresAt}`. Unknown, malformed, expired, revoked,
missing-target, ended and expired-session invitations share `failed-precondition`
and `Invitation is invalid or unavailable.` No invitation-derived errors include
secret, digest, convoy ID or backend diagnostics. Bad request schemas use
`invalid-argument`; unauthenticated calls use `unauthenticated`. Only after a valid
invitation are caller session/capacity errors returned. Unexpected failures are
masked as `INTERNAL` by callable middleware; SDK server diagnostics must stay private.

## Abuse controls and rules

Both issuance and redemption have separate server-enforced per-UID budgets:
**10 authenticated attempts per 60 seconds**, anchored to the first attempt in the
window. A dedicated transaction persists `rateLimits/{operation}_{sha256(uid)}`
with `windowStart`, `count` and `expiresAt`. It runs before schema/secret validation,
so failed attempts count and cannot roll back the budget by failing a redemption.
Counts saturate at 10; concurrent attempts serialize. At the exact window boundary,
the next request resets the counter. Rate-limited callers receive only
`resource-exhausted: Please try again later.` A valid but full convoy instead returns
`resource-exhausted: Convoy is full.` Clients can distinguish these fixed messages.

There is one bounded record per operation/UID; expired counters can be reused even
without TTL cleanup. Configure TTL on `expiresAt` separately before deployment.
The budget can permit bursts across window boundaries and is not a global/IP limit.
An attacker can create new anonymous UIDs; App Check, broader abuse controls,
quotas/monitoring and a production assessment remain required. Create Convoy itself
still has no request throttle, apart from rejecting an existing active session.

Firestore rules are unchanged and deny by default: only current members can get
an active/unexpired convoy; users can get only their own session pointer. Lists
and every direct client write are denied. Invitation hashes and rate-limit records
have no client read or write grant, even for creator/members. Admin handlers
independently enforce authorization because Admin writes bypass these rules.
Expiry blocks server reads/redemption without waiting for physical TTL deletion;
previously downloaded information cannot be erased by rules.

## Local setup, validation and remaining risks

Use Node 22 and Java 21, then from the repository root:

```powershell
npm.cmd ci
npm.cmd --prefix functions ci
npm.cmd run build
npm.cmd run test:emulators
# Optional interactive development:
npm.cmd run emulators
```

The demo-only launcher `scripts/firebase-emulators.cjs` fixes `demo-trailo` and
Auth/Firestore/Functions, accepts no project/endpoint overrides, and passes only
OS executable-discovery variables plus local tooling settings to the CLI. Inherited
ADC paths, auth tokens, service endpoints, application secrets and function parameters
are not passed. It places cache/config/temp data inside ignored `.firebase/`.
Local Functions environment files are still loaded by Firebase; keep them local,
free of production credentials, and never share CLI debug logs. All service hosts
are loopback; tests assert the exact Auth and Firestore emulator endpoints.
Manual CLI/Admin commands bypass this launcher and are not hard-guarded.

No package versions or lockfiles changed. Separate fresh audits on 2026-11-13:
root `npm.cmd audit --json --cache .npm-cache` reports **19 affected packages
(5 high, 14 moderate; 0 critical/low/info)** and exits 1; Functions
`npm.cmd --prefix functions audit --json --cache .npm-cache` reports **0** and exits 0.
These are the previously reviewed local tooling/test vulnerabilities. Their paths,
reachability and compatibility constraints remain in `create-convoy-backend.md`.
No forced fixes or major upgrades were applied. Zero findings does not prove absence
of vulnerabilities. Public deployment remains pending dependency-risk resolution
or explicit acceptance, App Check review, runtime IAM, TTL, quotas and monitoring.

Tests use Auth emulator tokens for callable flows, unprivileged Firestore SDKs for
rules, and the same handler core with injected clocks/failures for deterministic
expiry and rollback. Rollback injection throws after staging mutation writes, while
preserving the separate attempt charge; it does not simulate every network/commit
failure. Fixtures mark convoys ended/expired; no End function exists.

Validation on 2026-11-13:

- `npm.cmd run build`: passed (also rebuilt by each emulator command).
- `npm.cmd run test:emulators`: **34 tests, 34 passed, 0 failed, 0 skipped,
  0 cancelled**, 79.463 seconds. The preceding expanded run passed 30/30;
  final run includes the late-expiry guard and four additional invariant/race tests.
  Actual callable/Auth tests and Firestore security-rule tests run in this suite.
- `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`:
  initial attempt failed before build because its default cache lock directory
  could not be created. Retry using the existing local Gradle cache passed:
  **BUILD SUCCESSFUL**, 56 tasks, 1 executed and 55 up-to-date. Cached JVM XML
  reports contain **43 tests, 0 failures, 0 errors, 0 skipped**. No Android source
  or UI changes; device/rendering checks were not run.
- Separate root and Functions audits completed with results recorded above.
- `git diff --check`: passed. Reviewed untracked source/tooling files and ignore
  coverage; dependency trees, generated JS, environment files, emulator downloads,
  logs and local tooling state remain ignored. Existing unrelated changes preserved.

Ready for the next emulator-only development slice. Public deployment is not
approved by these results: production identity/IAM/App Check, network fault
behavior, physical TTL cleanup and real-device behavior remain unverified.
No resources were deployed; no commit or push was performed.
