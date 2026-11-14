import { randomBytes } from "node:crypto";
import { initializeApp } from "firebase-admin/app";
import { FieldValue, Firestore, Timestamp, getFirestore } from "firebase-admin/firestore";
import { defineInt } from "firebase-functions/params";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { activePointer, MAX_MEMBERS } from "./session-policy";
import { readOperation, completeOperation } from "./operations";
export { resolveConvoyOperation } from "./operations";

initializeApp();
const sessionHours = defineInt("CONVOY_SESSION_HOURS", { default: 24 });
export { MAX_MEMBERS } from "./session-policy";
export { createConvoyInvitation, joinConvoy } from "./invitations";
export { leaveConvoy, endConvoy } from "./lifecycle";

// Exported core permits deterministic clock/failure tests; never accepts caller policy fields.
export async function createSession(db: Firestore, uid: string | undefined, data: unknown,
  hours = 24, now: () => number = Date.now) {
  if (!uid) throw new HttpsError("unauthenticated", "Sign in to create a convoy.");
  if (!data || typeof data !== "object" || Array.isArray(data) ||
      Object.keys(data).some(key => key !== "name" && key !== "operationId")) {
    throw new HttpsError("invalid-argument", "Supply only a convoy name.");
  }
  const raw = (data as {name?: unknown}).name;
  if (typeof raw !== "string" || raw.length > 320) {
    throw new HttpsError("invalid-argument", "Invalid convoy name.");
  }
  const name = raw.trim().normalize("NFC");
  if ([...name].length < 1 || [...name].length > 80 || /[\p{Cc}\p{Cf}]/u.test(name)) {
    throw new HttpsError("invalid-argument", "Use 1 to 80 visible characters.");
  }
  if (!Number.isInteger(hours) || hours < 1 || hours > 168) {
    throw new HttpsError("internal", "Invalid session configuration.");
  }
  const convoy = db.collection("convoys").doc(randomBytes(32).toString("hex"));
  const pointer = db.collection("userSessions").doc(uid);
  return db.runTransaction(async tx => {
    const op = await readOperation(db, tx, uid, data, "createConvoy");
    if (op.replay) return op.replay;
    const currentTime = now();
    if (await activePointer(db, tx, uid, currentTime)) {
      throw new HttpsError("already-exists", "You already have an active convoy.");
    }
    const expiresAt = Timestamp.fromMillis(currentTime + hours * 60 * 60 * 1000);
    tx.create(convoy, { name, creatorId: uid, leaderId: uid, status: "active",
      createdAt: FieldValue.serverTimestamp(), expiresAt, maxMembers: MAX_MEMBERS, inviteVersion: 0,
      members: { [uid]: { joinedAt: FieldValue.serverTimestamp() } } });
    tx.set(pointer, { convoyId: convoy.id, expiresAt });
    const result = { convoyId: convoy.id, name, status: "active", expiresAt: expiresAt.toMillis(), maxMembers: MAX_MEMBERS };
    completeOperation(tx, op, "createConvoy", result);
    return result;
  });
}

export const createConvoy = onCall({ region: "us-central1", maxInstances: 10 }, request =>
  createSession(getFirestore(), request.auth?.uid, request.data, sessionHours.value()));
