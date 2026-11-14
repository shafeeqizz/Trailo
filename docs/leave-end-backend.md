# Leave & End Convoy backend (local only)

Current Android integration and optional `operationId` contracts:
[final review](milestone-3-final-review.md) and
[operation recovery](convoy-operation-recovery.md). Slice-only scope and
validation statements below are historical.

Current lifecycle callables: `leaveConvoy({convoyId})` and
`endConvoy({convoyId})`, alongside Create, invitation issuance/rotation and Join.
No Android convoy UI, live locations, routing or Follow Leader is included.
Nothing is deployed. Use the Node 22 demo-only emulator setup documented in
[Invite & Join](invite-join-backend.md).

## Leave contract

Authentication is mandatory. The only accepted input field is a 64-character
lowercase hexadecimal `convoyId`; caller identity never comes from input.
A transaction reads the convoy and caller pointer before any writes. If the convoy
is active and unexpired, it removes only the authenticated caller's roster key.
The creator cannot leave an active unexpired convoy (`failed-precondition`); they
must explicitly end it. A missing creator pointer does not bypass this restriction.
Creator/leader fields and every remaining member record stay unchanged.

The authoritative member count is `Object.keys(members).length`, as in existing
admission checks. There is no separately stored `memberCount` to drift. Whole-map
updates are protected by the convoy transaction read and preserve literal UID keys,
including dots. No other member UID can be supplied to the API.

Success is always `{left: true}`. A repeated request or a caller who is not in the
roster is a harmless no-op on the convoy, with the same response. This deliberately
does not reveal whether an unrelated convoy/member exists. The handler can delete
only the caller's own pointer and only when its current `convoyId` matches the
requested convoy. It never deletes a newer/different pointer. Thus a nonmember
cannot remove someone else, and delayed retries after switching sessions are safe.
An existing inconsistent matching pointer can be cleared without using that pointer
as proof of membership. Missing, ended and expired convoys also permit this self-only
pointer cleanup; their rosters are never changed. Malformed active expiry/rosters
fail closed. Active roster removal is authorized by membership, not the pointer.

Leaving revokes subsequent server reads through the existing membership rule. It
is not a ban: the caller can rejoin using a valid current invitation. Concurrent
leave/join requests from the same caller may serialize in either order; the final
roster and pointer agree. There is no implicit invitation rotation on Leave.

## End contract

Only the authenticated `creatorId` can end a convoy. A different `leaderId` does
not acquire creator authority. Missing targets and noncreator callers share
`permission-denied: Cannot end this convoy.` No roster or invite metadata is returned.

One transaction reads the convoy and at most 21 unique pointers (the bounded
20-member roster plus creator if absent due to privileged corruption). All reads
precede writes. It changes active status to `ended`, records server-generated
`endedAt`, sets trusted-server `expiresAt` to now for TTL eligibility, increments
`inviteVersion`, and deletes each pointer only if it still references this convoy.
The final roster, `creatorId` and separate `leaderId` are preserved. No invitation
collection scan or plaintext secret handling occurs.

Status is authoritative termination: Join and invitation issuance require active,
unexpired status; normal convoy reads require it too. The version increment also
revokes every prior invitation. Existing invitation hash records remain private
and can expire asynchronously. Leave after End only cleans a matching caller
pointer and cannot mutate the frozen roster. An expired but still present convoy
can be finalized by its creator for cleanup; expiry already denies access/admission.

A repeated creator End returns `{ended: true}`, without changing timestamps,
version, expiry or roster again. It can clean any still-matching roster pointers,
while preserving newer ones. After physical TTL deletion, creator identity can no
longer be verified; End returns the same generic permission denial as a missing
convoy. This retry is safe (no mutation), but cannot promise a success response
after deletion. No retained tombstone/extra personal-data collection is introduced.

## Concurrency, expiry and future sharing

Join, Leave, End and invitation rotation all read the same convoy document;
Firestore transactions serialize their changes or retry. If Join commits before
End, the End retry observes its member/pointer and clears it. If End commits first,
Join fails its active-session check. Leave before End removes its own pointer;
End before Leave freezes the roster and Leave becomes cleanup-only. Pointer reads
and comparison prevent cleanup from deleting a concurrently replaced pointer.

Missing/expired pointers and pointers targeting missing/ended/expired convoys remain
replaceable by Create/Join via the shared active-pointer policy. Delayed TTL never
grants access or keeps these sessions active. Privileged corruption of malformed
pointers/rosters still requires repair; it is not a normal lifecycle state. Future
mutations must preserve both the pointer and authoritative roster invariant.

Existing Firestore rules remain deny-by-default: no direct client writes, no
invitation/rate-limit reads, owner-only pointer gets, and member-only active convoy
gets. No rule grants were added. Already downloaded data cannot be erased by rules.
Future live-location storage must independently enforce parent active status,
expiry and membership for reads/writes and define retention; roster retention alone
must never authorize location access. No location schema, permission or code exists
in this slice, so live sharing cannot accidentally continue through these APIs.

No per-user throttle was added to Leave/End, to avoid coupling departure/termination
to invitation-redemption budgets. Repeated unauthorized/no-op requests still incur
reads and require production abuse controls, App Check assessment, quotas and
monitoring. Existing invitation limits and all prior production blockers remain.
No package changes, new dependencies, Firebase configuration, IAM or TTL policies
were introduced. Fresh separate audits on 2026-11-13 report 19 affected root tooling/test packages
(5 high, 14 moderate; 0 critical/low/info) and 0 Functions findings. Commands:
`npm.cmd audit --json --cache .npm-cache` (exit 1 for findings) and
`npm.cmd --prefix functions audit --json --cache .npm-cache` (exit 0).
No forced fixes or dependency updates were applied. See the Create backend review
for affected packages, reachability and compatibility constraints.

## Validation

Commands from the repository root:

```powershell
npm.cmd run build
npm.cmd run test:emulators
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
git diff --check
```

The demo-only launcher fixes `demo-trailo` and Auth/Firestore/Functions loopback
endpoints and filters inherited credentials. Local environment files and CLI logs
remain local and ignored. Tests use real callable requests with Auth emulator
tokens, unprivileged clients for rules, and injected clocks/transaction failures
for deterministic core checks. Failure injection throws after staging all writes;
it does not simulate every network/commit-failure scenario.


Validation results (2026-11-13):

- `npm.cmd run build`: passed; TypeScript also rebuilt in each emulator run.
- `npm.cmd run test:emulators`: final suite **56 tests, 56 passed, 0 failed,
  0 skipped, 0 cancelled**, 140.968 seconds. Earlier expanded run passed 53/53
  before three additional full-capacity/concurrency tests were added.
- Actual callable tests cover Leave/End authentication, self-only departure,
  creator restrictions, creator-only termination and request schema validation.
  Emulator core/rules tests cover repeat requests, preserved/newer pointers,
  counts, revocation, all-20-member cleanup, cross-session isolation, rollback,
  stale/expired recovery, Join/Leave/End/issuance races and denial of client writes.
- `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` with
  the existing local Gradle cache: **BUILD SUCCESSFUL**, 56 tasks, 1 executed,
  55 up-to-date. Cached XML reports contain **43 JVM tests, 0 failures/errors/skips**.
  No Android sources/UI changed; no device/rendering checks were run.
- Both separate dependency audits completed with the findings recorded above.
- `git diff --check`: passed. Source/test syntax and untracked-file/ignore review
  completed; generated JS, environment files, tooling state and logs remain ignored.

Ready for the next emulator-only slice/review. Remaining limitations: physical TTL,
production identity/IAM/App Check, broader abuse controls, real-device behavior and
exhaustive network/commit fault injection are unverified. Previously reviewed root
tooling vulnerabilities remain unresolved. These checks do not authorize production
deployment. No deployment, commit or push occurred; existing unrelated changes
were preserved.
