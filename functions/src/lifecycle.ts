import { FieldValue, Firestore, Timestamp, getFirestore } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { MAX_MEMBERS, onlyField, requireUser } from "./session-policy";
import { readOperation, completeOperation } from "./operations";

function convoyIdFrom(data: unknown): string {
  const id = onlyField(data, "convoyId");
  if (typeof id !== "string" || !/^[a-f0-9]{64}$/.test(id)) {
    throw new HttpsError("invalid-argument", "Invalid convoy ID.");
  }
  return id;
}

function rosterFrom(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value) ||
      Object.keys(value).length > MAX_MEMBERS) {
    throw new HttpsError("failed-precondition", "Invalid session membership.");
  }
  return value as Record<string, unknown>;
}

export async function leaveSession(db: Firestore, caller: string | undefined, data: unknown,
  now: () => number = Date.now) {
  const uid = requireUser(caller);
  const convoyId = convoyIdFrom(data);
  const ref = db.collection("convoys").doc(convoyId);
  const pointerRef = db.collection("userSessions").doc(uid);
  return db.runTransaction(async tx => {
    const op = await readOperation(db, tx, uid, data, "leaveConvoy");
    if (op.replay) return op.replay;
    const snapshot = await tx.get(ref);
    const pointer = await tx.get(pointerRef);
    const convoy = snapshot.data();
    if (convoy?.status === "active") {
      if (!(convoy.expiresAt instanceof Timestamp)) {
        throw new HttpsError("failed-precondition", "Invalid convoy expiry.");
      }
      if (convoy.expiresAt.toMillis() > now()) {
        if (convoy.creatorId === uid) {
          throw new HttpsError("failed-precondition", "The creator must end the convoy.");
        }
        const members = rosterFrom(convoy.members);
        if (Object.hasOwn(members, uid)) {
          const remaining = { ...members };
          delete remaining[uid];
          tx.update(ref, { members: remaining });
        }
      }
    }
    // A nonmember, an already-left caller, and a missing/inactive convoy have the
    // same no-op result. Only the caller's own matching discovery pointer is cleared.
    if (pointer.exists && pointer.get("convoyId") === convoyId) tx.delete(pointerRef);
    completeOperation(tx, op, "leaveConvoy", {left: true, convoyId});
    return { left: true };
  });
}

export async function endSession(db: Firestore, caller: string | undefined, data: unknown,
  now: () => number = Date.now) {
  const uid = requireUser(caller);
  const convoyId = convoyIdFrom(data);
  const ref = db.collection("convoys").doc(convoyId);
  return db.runTransaction(async tx => {
    const op = await readOperation(db, tx, uid, data, "endConvoy");
    if (op.replay) return op.replay;
    const convoy = (await tx.get(ref)).data();
    if (!convoy || convoy.creatorId !== uid) {
      throw new HttpsError("permission-denied", "Cannot end this convoy.");
    }
    if (convoy.status !== "active" && convoy.status !== "ended") {
      throw new HttpsError("failed-precondition", "Invalid session status.");
    }
    const members = rosterFrom(convoy.members);
    // Include the creator even if privileged corruption removed their roster entry.
    const uids = [...new Set([...Object.keys(members), uid])];
    const pointers = await tx.getAll(...uids.map(id => db.collection("userSessions").doc(id)));
    if (convoy.status === "active") {
      const version = convoy.inviteVersion ?? 0;
      if (!Number.isSafeInteger(version) || version < 0 || version >= Number.MAX_SAFE_INTEGER) {
        throw new HttpsError("failed-precondition", "Invalid invitation version.");
      }
      // Status is authoritative revocation; version also revokes every prior code.
      tx.update(ref, { status: "ended", endedAt: FieldValue.serverTimestamp(),
        expiresAt: Timestamp.fromMillis(now()), inviteVersion: version + 1 });
    }
    for (const pointer of pointers) {
      if (pointer.exists && pointer.get("convoyId") === convoyId) tx.delete(pointer.ref);
    }
    // Keep the final roster and immutable creator/leader fields. No later handler
    // may mutate this roster: joining/issuance require active status, leave no-ops.
    completeOperation(tx, op, "endConvoy", {ended: true, convoyId});
    return { ended: true };
  });
}

export const leaveConvoy = onCall({ region: "us-central1", maxInstances: 10 }, request =>
  leaveSession(getFirestore(), request.auth?.uid, request.data));

export const endConvoy = onCall({ region: "us-central1", maxInstances: 10 }, request =>
  endSession(getFirestore(), request.auth?.uid, request.data));
