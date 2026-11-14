import { DocumentReference, FieldValue, Firestore, Transaction, Timestamp, getFirestore } from "firebase-admin/firestore";
import { createHash } from "node:crypto";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { requireUser } from "./session-policy";

export const operationKinds = ["createConvoy", "createConvoyInvitation", "joinConvoy", "leaveConvoy", "endConvoy"];

function operationRef(db: Firestore, uid: string, id: unknown) {
  if (typeof id !== "string" || !/^[a-f0-9]{32}$/.test(id)) {
    throw new HttpsError("invalid-argument", "Invalid operation ID.");
  }
  return db.collection("userOperations").doc(uid).collection("requests").doc(id);
}

export async function readOperation(db: Firestore, tx: Transaction, uid: string, data: unknown, kind: string) {
  const id = (data as Record<string, unknown>)?.operationId;
  if (id === undefined) return {};
  const ref = operationRef(db, uid, id);
  const snapshot = await tx.get(ref);
  if (!snapshot.exists) return { ref };
  if (snapshot.get("kind") !== kind || snapshot.get("status") !== "committed") {
    throw new HttpsError("failed-precondition", "Operation is no longer available. Refresh your session.");
  }
  return { ref, replay: snapshot.get("result") as Record<string, unknown> };
}

export function completeOperation(tx: Transaction, op: {ref?: DocumentReference}, kind: string,
  result: Record<string, unknown>) {
  if (op.ref) tx.create(op.ref, {kind, status: "committed", result, createdAt: FieldValue.serverTimestamp()});
}

// A cancellation tombstone and the mutation read/write the same document. Exactly
// one wins: a late/retried mutation cannot commit after recovery has cancelled it.
export async function resolveOperation(db: Firestore, caller: string | undefined, data: unknown) {
  const uid = requireUser(caller);
  if (!data || typeof data !== "object" || Array.isArray(data) ||
      Object.keys(data).length !== 2 || !Object.hasOwn(data, "operationId") || !Object.hasOwn(data, "kind")) {
    throw new HttpsError("invalid-argument", "Invalid recovery request.");
  }
  const {operationId, kind} = data as Record<string, unknown>;
  if (typeof kind !== "string" || !operationKinds.includes(kind)) throw new HttpsError("invalid-argument", "Invalid operation kind.");
  const ref = operationRef(db, uid, operationId);
  return db.runTransaction(async tx => {
    const snapshot = await tx.get(ref);
    if (snapshot.exists) {
      if (snapshot.get("kind") !== kind) throw new HttpsError("failed-precondition", "Operation kind does not match.");
      return {status: snapshot.get("status")};
    }
    // Bound new cancellation records; reading an existing receipt is idempotent.
    const rate = db.collection("rateLimits").doc(`recovery_${createHash("sha256").update(uid).digest("hex")}`);
    const limit = await tx.get(rate);
    const start = limit.get("windowStart");
    const count = limit.get("count");
    const time = Date.now();
    const inWindow = start instanceof Timestamp && time < start.toMillis() + 60_000;
    if (inWindow && (!Number.isSafeInteger(count) || count < 0 || count >= 20)) {
      throw new HttpsError("resource-exhausted", "Please try again later.");
    }
    const windowStart = inWindow ? start : Timestamp.fromMillis(time);
    tx.set(rate, {windowStart, count: inWindow ? count + 1 : 1,
      expiresAt: Timestamp.fromMillis(windowStart.toMillis() + 60_000)});
    tx.create(ref, {kind, status: "cancelled", createdAt: FieldValue.serverTimestamp()});
    return {status: "cancelled"};
  });
}

export const resolveConvoyOperation = onCall({region: "us-central1", maxInstances: 10}, request =>
  resolveOperation(getFirestore(), request.auth?.uid, request.data));
