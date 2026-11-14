# Create Convoy backend (local only)

Current milestone status and optional `operationId` contracts:
[final review](milestone-3-final-review.md) and
[operation recovery](convoy-operation-recovery.md). Slice-only statements and
validation records below are historical.

Current backend also includes [Leave & End](leave-end-backend.md).
Invitation issuance and joining are implemented locally; see
[Invite & Join Convoy](invite-join-backend.md). The Create-only scope and security
review below are historical records of the preceding slice.

Implemented: authenticated `createConvoy({name})`, atomic metadata/embedded creator
membership/private pointer, and deny-by-default exact-document read rules.
No invitation, join, leave, end, location, or Android UI functionality is included.

Use Node 22 (`.nvmrc`) and Java 21. From the repository root on Windows:

```powershell
npm.cmd ci
npm.cmd --prefix functions ci
npm.cmd run test:emulators
# Interactive local development:
npm.cmd run emulators
```

Both scripts explicitly select `demo-trailo`; they do not deploy. The CLI is a local
pinned dev dependency. All three emulators must run together. Never point an
interactive Admin SDK process at a real project. The tests refuse to start without
the demo project and emulator environment variables. Ports: Auth 9099, Firestore
8080, Functions 5001, loopback only. No credentials or real Firebase options needed.

The name is trimmed and NFC-normalized, 1–80 Unicode code points, with control and
format characters rejected. Only the `name` field is accepted. Caller UID comes
from callable authentication, never the request body. The callable returns
`convoyId`, normalized `name`, `status`, `expiresAt` (epoch milliseconds), and
`maxMembers`. IDs use 256 cryptographically random bits. Stored identity fields
are `creatorId` and `leaderId`, initially identical. Members form one authoritative
map keyed by UID, with server-generated `joinedAt`; `createdAt` is also a server
timestamp. No nickname or invitation fields are written in this slice.

`CONVOY_SESSION_HOURS` is an integer function parameter, default 24, accepted range
1–168. Expiry uses trusted server time and is checked again on transaction retries.
A live pointer and active, unexpired target reject creation with `already-exists`.
Expired pointers, missing targets, and ended/expired targets can be atomically
replaced. Malformed pointer expiry/ID fails closed. Expired records remain stored
but cannot be read through convoy rules. Owner pointers remain readable so clients
can discover expiry. TTL is eventual cleanup, not authorization; no TTL policy is
provisioned here. The clock is injectable only in the internal testable core.

The maximum roster size is 20, stored as server-owned `maxMembers`; creation adds
exactly one member and clients cannot change it. Future join logic must enforce
capacity transactionally against the authoritative map. This slice cannot add
additional members. App Check enforcement, per-user abuse throttling, TTL policies,
region selection and quotas require a separate review before public deployment.
Callable authentication alone does not prevent anonymous-account abuse.

Tests exercise real callable HTTP requests with Auth emulator tokens and direct
unprivileged Firestore SDK requests for rules. Internal core tests inject time and
a transaction failure after staging writes. Emulator checks do not validate deployed
IAM, App Check, physical TTL scheduling, or Android rendering.

## Validation record (2026-11-12)

- Node 22.23.5, npm 10.9.9, Firebase CLI 15.36.0 (local), Java 21.0.2.
- `npm.cmd run build`: passed (TypeScript compilation).
- `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`:
  BUILD SUCCESSFUL, 56 tasks (1 executed, 55 up-to-date). Existing cached JVM
  reports: 43 tests, zero failures/errors/skips. No Android sources changed.
- Dependency install/audit: Functions tree 209 packages, zero reported
  vulnerabilities. Root development/test tree 688 packages, 19 advisories
  (14 moderate, 5 high), including Firebase web SDK/rules-test gRPC dependencies
  and CLI transitive dependencies. Do not use `npm audit fix --force`: proposed
  remediations include incompatible downgrades. Reassess before public deployment.
- First emulator attempt failed before tests: default temp storage reported
  ENOSPC during Firestore download. Retry uses local ignored `.firebase/tmp`.

In restricted Windows environments, set workspace-local tooling paths before
running the scripts:

```powershell
New-Item -ItemType Directory -Force .firebase/tmp | Out-Null
$env:TEMP = "$PWD/.firebase/tmp"
$env:TMP = $env:TEMP
$env:FIREBASE_EMULATORS_PATH = "$PWD/.firebase/emulators"
$env:XDG_CONFIG_HOME = "$PWD/.firebase/config"
$env:CI = 'true'
npm.cmd run test:emulators
```

Final emulator run: `npm.cmd run test:emulators` (with the local path environment
above) passed: **12 tests, 12 passed, 0 failed, 0 cancelled, 0 skipped**, in
27.870 seconds. Includes callable/Auth integration, 5 concurrent callable creates
with exactly one winner, invalid input, duplicate rejection, clock-controlled
expiry, rollback, stale/malformed pointers and unprivileged security-rule checks.
The preceding run failed at test loading due to a package import path; corrected
before this successful run. All emulators shut down after the suite.
`git diff --check` passed. No device/UI checks were run (no Android UI changes).
No deployment, TTL provisioning, production access, commit or push occurred.


## Security review (2026-11-13)

**Decision: suitable for the next emulator-only development slice; not approved
for public deployment.** No exploitable authorization or transaction defect was
identified in the implemented Create Convoy flow. This is a scoped code/emulator
review, not proof that production IAM, identity settings or deployed rules are safe.
No dependencies, source behavior, Firebase resources or Android UI were changed by
this review. Existing uncommitted work remains intact.

### Dependency audit

Executed independently from the root:

```powershell
npm.cmd audit --json --cache .npm-cache
npm.cmd --prefix functions audit --json --cache .npm-cache
npm.cmd outdated --json --cache .npm-cache
npm.cmd --prefix functions outdated --json --cache .npm-cache
```

The root audit exits 1 because findings exist; Functions exits 0. Results:

| Dependency tree | Critical | High | Moderate | Low | Info | Total affected packages |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Root development/test | 0 | 5 | 14 | 0 | 0 | 19 |
| Functions, including development dependencies | 0 | 0 | 0 | 0 | 0 | 0 |

These are npm's affected-package counts, including inherited/metavulnerability
reports, not 19 independent exploits. A low-severity gRPC disclosure advisory is
included under the package's higher overall severity. Installed affected packages:

| Severity | Package | Installed version | Dependency path / cause |
| --- | --- | --- | --- |
| High | `firebase` (direct dev) | 12.19.0 | Firestore Node client inherits vulnerable gRPC |
| High | `@firebase/rules-unit-testing` (direct dev) | 5.0.2 | Peer `firebase` inherits findings |
| High | `@firebase/firestore` | 4.17.2 | `firebase` -> gRPC |
| High | `@firebase/firestore-compat` | 0.4.14 | `firebase` -> Firestore |
| High | `@grpc/grpc-js` | 1.9.16 | Firestore Node client |
| Moderate | `firebase-tools` (direct dev) | 15.36.0 | CLI inherits packages below |
| Moderate | `@google-cloud/pubsub` | 5.3.1 | CLI -> OpenTelemetry / google-gax |
| Moderate | `@opentelemetry/core` | 1.30.1 | Pub/Sub dependency |
| Moderate | `archiver` | 7.0.1 | CLI -> archiver-utils / zip-stream |
| Moderate | `archiver-utils` | 5.0.2 | archiver / zip-stream -> glob |
| Moderate | `exegesis` | 4.3.0 | CLI -> glob |
| Moderate | `gaxios` | 6.7.1, 7.1.3 | CLI/auth library -> uuid or rimraf |
| Moderate | `gcp-metadata` | 8.1.4 | CLI -> Pub/Sub -> google-gax -> auth library -> gaxios |
| Moderate | `glob` | 10.5.0 | CLI, archiver-utils, exegesis, rimraf |
| Moderate | `google-gax` | 5.0.8 | Pub/Sub -> rimraf |
| Moderate | `minimatch` | 9.0.9 | glob dependency |
| Moderate | `rimraf` | 5.0.10 | google-gax / nested gaxios -> glob |
| Moderate | `uuid` | 9.0.1 | CLI -> gaxios |
| Moderate | `zip-stream` | 6.0.1 | archiver -> archiver-utils |

All affected packages above are root **development dependencies**. They do not
enter the Functions deployment tree or the Android Gradle dependency graph.
Functions runtime roots are `firebase-admin@14.5.1` and
`firebase-functions@7.4.0`; its installed gRPC is 1.14.7, outside the reported
vulnerable range. Zero audit findings does not establish absence of vulnerabilities.

Reachability assessment (inferred from dependency paths and usage, not exploit tests):

- gRPC 1.9.16: unauthorized certificate handling in certain server configurations
  (high), and handler error disclosure (low), fixed in 1.13.6. Our rules tests use
  a client against loopback plaintext emulators; no custom gRPC server or certificate
  authorization is implemented. Presence is real, but the advisory's server-side
  preconditions are not apparent in this test flow.
- OpenTelemetry: unbounded W3C baggage memory allocation, fixed in 2.8.0. In the
  CLI's Pub/Sub tooling path; Create Convoy does not use Pub/Sub or this root tree.
- glob/minimatch: cancellation bypass/resource exhaustion and pattern amplification.
  Relevant to local filesystem/tooling work with untrusted patterns or trees, not
  convoy request processing. Do not treat development findings as harmless when
  processing untrusted repositories or exposing emulators beyond loopback.
- uuid: bounds checking in v3/v5/v6 with a supplied buffer, fixed in 11.1.1. The
  convoy ID uses `crypto.randomBytes`, not uuid. Installed gaxios 6.7.1 uses uuid
  v4; the advisory's vulnerable API precondition is not apparent there.

Primary advisory references:
[gRPC certificate handling](https://github.com/advisories/GHSA-m9gg-hp2v-232j),
[gRPC error disclosure](https://github.com/advisories/GHSA-f596-whhp-79r4),
[OpenTelemetry baggage](https://github.com/advisories/GHSA-8988-4f7v-96qf),
[glob cancellation](https://github.com/advisories/GHSA-3g3m-2fjr-6pw4),
[minimatch amplification](https://github.com/advisories/GHSA-g3hv-75wg-m249),
[uuid buffer bounds](https://github.com/advisories/GHSA-w5hq-g745-h8pq).

### Update recommendations

No updates or `npm audit fix` commands were applied. Do not use `--force`.
Direct CLI, rules testing, and Functions dependencies were current according to
`npm outdated`; Firebase 12.19.0 is the newest published 12.x checked, while latest
Firebase is 13.3.0. Exact pins make `wanted` equal `current`; published version
metadata was also checked for Firebase and the gaxios/gcp-metadata paths.

1. No verified non-breaking dependency update was identified that resolves these
   findings under all current parent constraints. `gaxios@7.3.1` is a same-major
   remediation candidate for the nested 7.1.3 branch, but `gcp-metadata@8.1.4` pins
   exactly `7.1.3` (not a caret range), and 8.1.4 is the newest published 8.x checked.
   It therefore needs an upstream parent update or a scoped override that explicitly
   departs from the parent's pin. Evaluate this in a separate dependency change,
   then rerun audit and emulator tests; it will not clear the other CLI paths.
2. No ordinary compatible update was identified that clears the high gRPC finding:
   Firestore pins `~1.9.0`, excluding fixed 1.13.6+. A scoped gRPC override would
   stay within gRPC major 1 but violate the parent's declared range; upstream support
   and the entire rules suite need verification before accepting that workaround.
3. Do not blindly upgrade Firebase to 13: rules-unit-testing 5.0.2 declares peer
   `firebase: ^12.0.0`. A coordinated compatible rules-test release or explicit
   migration is required; SDK/runtime/API changes and the actual resulting gRPC
   tree must be checked. No claim is made that 13 alone fixes all findings.
4. OpenTelemetry 1 -> 2.8+, glob 10 -> 14.0.2+, minimatch 9 -> 11.0.1+, and uuid
   9 -> 11.1.1+ cross dependency major versions/ranges. Do not force global overrides:
   module format, engine and API compatibility may change. Prefer maintained CLI
   releases that update these parents and then verify the lockfile and tests.
5. Audit suggests major downgrades (Firebase 9.14.0, rules tests 2.0.7, CLI 13.11.2).
   These are not safe non-breaking recommendations and would jeopardize the current
   peer/runtime/tooling combination. `fixAvailable: true` on inherited packages is
   not evidence of a compatible fixed release for every path. Monitor upstream fixes.

### Backend and rules assessment

- **Authentication/input:** callable middleware validates the token; the handler
  requires `request.auth.uid`. It accepts only `name`, bounds raw length before
  normalization, rejects empty/oversized/control/format text, and ignores no forged
  role, expiry or membership fields (they cause rejection). Anonymous identities
  were created through the Auth emulator in integration tests. Any valid Firebase
  auth provider is accepted; anonymous-only provider restriction is not implemented
  or required. Anonymous identity is not verified personhood or an abuse defense.
- **Atomicity/concurrency:** every creation reads and writes the same
  `userSessions/{uid}` document in a Firestore transaction. Competing commits retry
  and observe the winner; five simultaneous callable requests produced exactly one
  success and one convoy. Candidate IDs are generated outside the retry callback;
  there are no external side effects inside it. Both documents and embedded
  membership commit together. The injected callback failure after staging both
  writes left neither record. Commit/network-failure injection is not covered.
- **Invariant boundary:** uniqueness assumes server-owned pointers and convoy expiry
  remain consistent. Missing/expired pointers are not backed by a convoy collection
  search, so privileged deletion/corruption of a pointer can permit a second live
  convoy. Client rules prevent such corruption. Future mutations must update the
  pointer atomically and compare the convoy ID before clearing/replacing it.
  Malformed pointer expiry/live ID and malformed active target expiry fail closed.
- **Authorization:** only exact convoy gets by authenticated members of an active,
  unexpired roster are allowed; no collection listing. The pointer is discovery,
  not membership proof. Only the pointer owner can get it, including after expiry.
  No client create/update/delete is granted on any path; overlapping catch-all
  denies do not override grants but introduce none. Tests cover cross-user reads,
  unauthenticated access, list denial, forged leader/member writes, unknown paths,
  invitation denial and removal from the authoritative roster.
- **Expiry:** rules compare `request.time < expiresAt`, independent of TTL deletion
  and client clock. An expired document still present in Firestore was denied to
  its member, and an exact-boundary expired pointer was replaced. Ended documents
  were also denied using fixtures; no End operation was added. Already downloaded
  or offline cached information cannot be erased by rules; future Android code
  needs the documented memory-only cache/listener-clearing policy.
- **Errors/logging:** intentional errors contain generic validation/session messages
  and no IDs, request bodies, credentials or raw database diagnostics. Configuration
  and corrupt-record errors reveal only broad categories to the caller. Installed
  callable middleware replaces non-HttpsError failures with `INTERNAL`; it logs the
  error server-side. No application logging is present. Faulted callable responses,
  invalid/revoked tokens and server log redaction are not directly integration-tested.
  Do not add raw payload/token logging. Firebase CLI debug output can include inherited
  environment values: keep logs local/ignored and use an environment without secrets.
- **Capacity:** creation always writes one roster member and immutable server-owned
  `maxMembers: 20`. No general admission check exists yet because joining is absent.
  Future join transactions must check authoritative roster size; the field alone is
  not a capacity enforcement algorithm.
- **Isolation:** scripts explicitly use `demo-trailo`, all configured service hosts
  are loopback, and tests assert demo project plus emulator environment variables.
  Callable tests use fixed loopback Auth/Functions endpoints; Admin Firestore honors
  the emulator endpoint. No Android Firebase calls or emulator routing exist yet.
  Tests currently check environment-variable presence, not that their values are
  loopback. Manual Admin invocations and direct CLI commands are not hard-guarded;
  inherited credentials or endpoints must be excluded in a future hardened runner.
  Local emulator success does not verify production Anonymous Auth settings, IAM,
  revocation policy, App Check or TTL; none were inspected or changed.

Firebase references: [transaction atomicity/retries](https://firebase.google.com/docs/firestore/manage-data/transactions),
[callable authentication/error handling](https://firebase.google.com/docs/functions/callable),
[demo projects/emulator routing](https://firebase.google.com/docs/emulator-suite/connect_functions).

### Required follow-up and validation

Before public deployment: resolve or explicitly accept applicable dependency risks;
implement server-side abuse limits and assess App Check enforcement; configure TTL,
region, least-privilege runtime IAM, quotas and monitoring; decide revoked-user/token
policy. `maxInstances: 10` bounds scaling, not per-user request abuse. Anonymous-account
creation can bypass per-UID limits. Authenticated duplicate requests still cost reads.

Before expanding mutation behavior: preserve the pointer/roster invariant in shared
transaction policy and test stale-pointer replacement under concurrency, rollback
with an existing pointer, malformed target expiry and invalid credentials. Harden
emulator endpoint assertions/credential isolation before sharing the test runner.
These are targeted coverage/defense improvements, not evidence of a current client
write bypass. Public deployment remains blocked on its separate security review.

Review validation: `npm.cmd run test:emulators`, with workspace-local paths shown
above, rebuilt TypeScript and passed **12/12 tests, 0 failures, 0 skipped**, in
24.285 seconds. This includes actual callable/Auth integration and security rules.
Separate audit commands completed as recorded above. No Android sources/build
configuration changed in this review; Android/device checks were not rerun. The
prior Android result remains historical, not a fresh validation claim.
