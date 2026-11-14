# Milestone 3: private convoy session design

Status: all five mutations, the Android lobby and bounded operation recovery are
implemented locally. See [final review](milestone-3-final-review.md) for the current
decision and checks. The original proposal and historical slice results are
retained below. Nothing has been deployed. Prepared from merged
Milestone 2 at `1cc2803` on `feat/convoy-sessions`. Maps and foreground self-location
remain independent of authentication and convoy connectivity.

## Architecture and initial product defaults

Keep one Android `app` module. Add plain Kotlin authentication/convoy models and
repository contracts under `domain/`, SDK implementations under `data/firebase/`,
and `ui/convoy/` screens and a ViewModel with lifecycle-aware StateFlow collection.
Constructor injection remains sufficient. Firebase SDK calls do not enter
composables or the existing location repository.

Use anonymous Firebase Authentication, Firestore document listeners for authorized
reads, and TypeScript callable Cloud Functions for all convoy mutations. A
`functions/` deployment directory is a real backend boundary, not another Android
module. Admin SDK credentials stay in the managed server runtime; no service-account
key is distributed to the app or checked into Git.

Proposed, configurable MVP defaults:

- One active convoy per anonymous UID, at most 20 simultaneous members.
- Session lifetime: 24 hours. Invitation lifetime: 30 minutes, capped by session expiry.
- Creator is the fixed leader; leader transfer and removing other participants are
  outside this slice. Participants leave; the creator explicitly ends the convoy.
- Optional unverified nicknames identify members. No emails, phone numbers,
  coordinates, location history, routes, or friend-marker data are stored.

The bounded roster permits atomic changes and whole-document cleanup. These are
proposed product limits, not Firebase limits. A substantially larger roster would
justify revisiting the membership layout.

## Firestore model

The Android lobby and [bounded operation recovery](convoy-operation-recovery.md)
are implemented locally. Identified mutations now also write owner-readable,
server-only-writable receipts at `userOperations/{uid}/requests/{operationId}`.
The recovery callable atomically confirms commitment or fences cancellation; it
does not depend on completion of the original Android SDK Task. Receipt retention
and legacy-client compatibility are documented in that recovery contract.

| Document | Fields | Client access |
| --- | --- | --- |
| `convoys/{randomId}` | `name`, `creatorId`, `leaderId`, `status`, `createdAt`, `expiresAt`, `inviteVersion`, `members: {uid: {nickname, joinedAt}}`; `endedAt` when ended | Exact-document reads by current members of an active, unexpired convoy; no collection listing or writes |
| `userSessions/{uid}` | `convoyId`, `expiresAt` | Owner-only exact-document reads; no client writes or listing |
| `invitations/{sha256(normalizedCode)}` | `convoyId`, `version`, `createdAt`, `expiresAt` | No client reads, queries, or writes |
| `rateLimits/{serverDefinedKey}` | Bounded request counters and expiry | Server only |
| `userOperations/{uid}/requests/{operationId}` | Kind, committed/cancelled status, minimal result and timestamp | Owner exact-document get; no client writes/listing; no secret or invitation hash |

Generate random convoy IDs on the server. Keep membership in one authoritative
roster; `userSessions` is only a discovery pointer, never proof of authorization.
Do not trust a caller-supplied UID, role, leader, expiry, or member map.

## Invitations and atomic mutations

Generate each invitation from 128 cryptographically random server-side bits,
encoded as a copyable Base32 code (26 characters). Define strict case/grouping
normalization and reject malformed input before lookup. Store only its SHA-256
digest as the lookup key. Never log raw codes, auth tokens, or request bodies.
An invitation is a bearer capability: recipients can forward it; anonymous auth
does not establish a verified real-world friendship.

Authenticated callable operations:

1. `createConvoy(name)`: validate input, inspect the caller's active
   pointer, then atomically create the convoy, creator membership and
   pointer. Concurrent duplicate creates cannot create two active convoys for a UID.
2. `joinConvoy(secret)`: in a transaction read the invitation, convoy and
   caller pointer. Check server time, active status, invitation version, capacity
   and existing membership. Add only the authenticated caller and its pointer.
   Duplicate joins do not duplicate members; concurrent joins cannot lose updates
   or exceed capacity. Serialize concurrent leave/end/rotation against this same doc.
3. `leaveConvoy(convoyId)`: remove only the caller and its matching pointer in one
   transaction. Repeating a successful leave is safe. The creator uses End instead
   so a convoy cannot be left with an orphaned leader.
4. `endConvoy(convoyId)`: creator-only transaction sets ended status/time, makes
   the convoy eligible for deletion, and clears matching pointers for its bounded
   roster. All invitations become unusable because the convoy is no longer active.
5. `createConvoyInvitation(convoyId)`: creator-only rotation increments `inviteVersion`
   and stores a newly generated digest. Older codes stop working immediately.

The raw code is returned only when issued and held transiently for sharing. If a
create/issue response is lost, reload the user's pointer rather than creating a
duplicate convoy; the creator can explicitly issue a replacement code. Do not
persist plaintext invitation codes just to make retries redisplay them.

Mutations require connectivity and are not queued as offline Firestore writes.
Map network/unavailable, unauthenticated, already-in-session, full and invalid or
expired invitation outcomes to domain errors. Invalid, ended, revoked and expired
codes use a shared user-facing failure without exposing another convoy's details.
Membership removal is not a ban: a departed user could rejoin with a currently
valid invitation. Rotation/end revokes invitation access.

Callable auth validation is necessary but insufficient: every handler must check
its authorization policy and input schema before Admin SDK writes. The Admin SDK
bypasses client security rules. Add server-side per-UID operation limits and bounded
input/work; evaluate and configure App Check enforcement before public deployment.
Per-UID throttling alone does not stop an attacker creating new anonymous accounts.

## Proposed deny-by-default rules

Illustrative policy, to be implemented and emulator-tested before deployment:

```text
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    function signedIn() {
      return request.auth != null;
    }
    match /convoys/{convoyId} {
      allow get: if signedIn()
        && resource.data.status == 'active'
        && request.time < resource.data.expiresAt
        && request.auth.uid in resource.data.members;
      allow list, create, update, delete: if false;
    }
    match /userSessions/{uid} {
      allow get: if signedIn() && request.auth.uid == uid;
      allow list, create, update, delete: if false;
    }
    match /{document=**} {
      allow read, write: if false;
    }
  }
}
```

Knowing a convoy ID or invitation digest does not grant document access. The
catch-all denies invitation and rate-limit access and every unspecified path.
There are no client leader/membership writes, including self-adding or creator
direct writes. Legitimate writes go through checked callable transactions.

## Expiry, revocation and retention

Enforce expiry immediately with trusted function time and Firestore `request.time`;
never wait for the Android clock or physical deletion. Configure Firestore TTL on
`expiresAt` for convoys, invitations, user pointers and expiring rate-limit records.
TTL deletion is asynchronous (typically within 24 hours), not an authorization
mechanism. An end operation sets the convoy's deletion eligibility immediately;
remaining invitation records stay inaccessible and expire on their own schedule.

Membership is embedded so deleting a convoy deletes its roster; no convoy-member
subcollection needs cleanup. Operation receipts are separate per-UID subcollections
and need the explicit retention/deletion design documented in the recovery contract.
Pointer cleanup must not erase a newer convoy pointer; transactions compare its
convoy ID, and a replacement pointer receives the new session's expiry. Expired
pointers do not prevent the server creating/joining a new active convoy.

Use memory-only Firestore caching for this private feature. Cached data is not
proof of current membership. Clear convoy UI/listeners on auth loss, permission
denial or session switch; hide private cached results while awaiting server
confirmation. Rules cannot retroactively erase information already read, and
offline clients cannot promise instantaneous revocation awareness.

Reuse an existing anonymous identity instead of signing in again on every resume.
It is device/app-data scoped: uninstalling or clearing app data can lose creator
access. Expiry is the MVP fallback; account linking/recovery is later work. Keep
Firebase identity persistence distinct from location data, which remains local
and is never sent to this backend.

## External setup and local findings

Inspected environment: Node 16.14.2, npm 8.5.0, Java 21.0.2. No Firebase CLI command
or `app/google-services.json` was found. Cloud project configuration was not inspected.

Implementation prerequisites:

- Use a supported Node runtime (proposed Node 22) for backend tooling/functions;
  Node 16 does not satisfy the current Firebase CLI engine requirement. Pin the
  CLI, test dependencies and backend SDKs locally with a lockfile. Java 21 is available.
- Run Auth, Firestore and Functions emulators against an isolated `demo-` project.
  Android debug builds opt into emulator endpoints explicitly; release builds must
  never use them. Supply local demo Firebase options without real credentials.
- For a real development project, register `com.trailo.app`, enable Anonymous Auth,
  select the Firestore database region, colocate functions, and supply the ignored
  `app/google-services.json`. Maps key configuration stays separate and unchanged.
- Deploy checked rules/functions and enable TTL policies, API quotas and monitoring.
  Cloud Functions deployment requires the Blaze plan; local emulation does not.
  App Check debug tokens must remain local; production enforcement needs setup.
- Keep Maps usable and builds valid without Firebase configuration; show a convoy
  setup state instead of initializing an unusable Firebase client.

No project provisioning, billing changes, deployment, SDK installation or service
account creation has happened during this proposal phase.

## Implementation slices and validation

1. Implement server policies, callable transactions, deny-by-default rules and
   emulator tests first; verify abuse/error paths before wiring Android mutations.
2. Add Firebase Auth/Firestore/Functions adapters behind domain repositories and
   fake-repository ViewModel tests. Keep auth failure independent of maps/location.
3. Add create/join/current-convoy UI, roster/leader, invite sharing/rotation, Leave
   and End. Collect StateFlow with lifecycle awareness and detach Firestore listeners.
4. Run Android build/JVM/lint/instrumented checks and a moving self-location map
   regression. Prepare the feature PR with exact passed, failed and unrun checks.

Rules tests use `@firebase/rules-unit-testing`: unauthenticated and cross-convoy
reads, collection listing, invitation/pointer privacy, arbitrary writes, forged
leader/member changes, leave/end revocation and expired-but-not-deleted documents.
Seed fixtures with privileged test context, but assert access with unprivileged clients.

Separate callable integration tests must exercise actual Auth emulator tokens and
the Functions/Firestore emulators: create/join/leave/end, caller UID spoofing,
nickname/name validation, malformed/expired/rotated codes, lost-response retries,
concurrent joins at capacity, join versus end/leave/rotation, and stale pointer
replacement. Rules-only tests cannot validate authorization inside Admin handlers.
Test expiry decisions with an injected server clock; physical TTL scheduling is a
separate deployed-service check, not something emulator success should claim.

The original proposal was documentation-only. Current emulator results are recorded in the backend implementation documents linked above.

## Primary references checked for this proposal

- [Anonymous Android authentication](https://firebase.google.com/docs/auth/android/anonymous-auth)
- [Callable auth token handling](https://firebase.google.com/docs/functions/callable)
- [Firestore access rules and server SDK bypass](https://firebase.google.com/docs/firestore/security/rules-conditions)
- [Transactional writes and retries](https://firebase.google.com/docs/firestore/manage-data/transactions)
- [TTL semantics and subcollection limitations](https://firebase.google.com/docs/firestore/ttl)
- [Functions deployment prerequisites](https://firebase.google.com/docs/functions/get-started)
- [Security rule testing](https://firebase.google.com/docs/rules/unit-tests)
- [Firebase CLI Node engine requirement](https://github.com/firebase/firebase-tools/blob/main/package.json)
