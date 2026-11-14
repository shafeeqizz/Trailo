import { createHash, randomBytes } from "node:crypto";
import { FieldValue, Firestore, Timestamp, getFirestore } from "firebase-admin/firestore";
import { defineInt } from "firebase-functions/params";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { activePointer, MAX_MEMBERS, onlyField, requireUser } from "./session-policy";
import { readOperation, completeOperation } from "./operations";

const invitationMinutes = defineInt("CONVOY_INVITATION_MINUTES", { default: 30 });
const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
const invalidInvitation = () => new HttpsError("failed-precondition", "Invitation is invalid or unavailable.");
const digest = (value: string) => createHash("sha256").update(value).digest("hex");

function newSecret(): string {
  let bits = 0;
  let value = 0;
  let result = "";
  for (const byte of randomBytes(16)) {
    value = (value << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      bits -= 5;
      result += alphabet[(value >>> bits) & 31];
    }
    value &= (1 << bits) - 1;
  }
  if (bits) result += alphabet[(value << (5 - bits)) & 31];
  return result;
}

function normalizeSecret(raw: unknown): string {
  if (typeof raw !== "string" || raw.length > 64) throw invalidInvitation();
  const text = raw.trim().toUpperCase();
  // Either plain or exactly 5-5-5-5-6 groups. Last symbol has two zero padding bits.
  if (!/^(?:[A-Z2-7]{26}|(?:[A-Z2-7]{5}-){4}[A-Z2-7]{6})$/.test(text)) {
    throw invalidInvitation();
  }
  const secret = text.replaceAll("-", "");
  if (!/^[A-Z2-7]{25}[AEIMQUY4]$/.test(secret)) throw invalidInvitation();
  return secret;
}

// A separate transaction deliberately persists attempts when redemption fails.
// Fixed, first-attempt-anchored 60-second window; one bounded document per UID/op.
export async function consumeAttempt(db: Firestore, uid: string, operation: "join" | "invite",
  now: () => number = Date.now) {
  const ref = db.collection("rateLimits").doc(`${operation}_${digest(uid)}`);
  await db.runTransaction(async tx => {
    const snapshot = await tx.get(ref);
    const time = now();
    const start = snapshot.get("windowStart");
    const count = snapshot.get("count");
    const inWindow = snapshot.exists && start instanceof Timestamp && time < start.toMillis() + 60_000;
    if (inWindow && (!Number.isSafeInteger(count) || count < 0)) {
      throw new HttpsError("resource-exhausted", "Please try again later.");
    }
    if (inWindow && count >= 10) throw new HttpsError("resource-exhausted", "Please try again later.");
    const windowStart = inWindow ? start : Timestamp.fromMillis(time);
    tx.set(ref, { windowStart, count: inWindow ? count + 1 : 1,
      expiresAt: Timestamp.fromMillis(windowStart.toMillis() + 60_000) });
  });
}

export async function issueInvitation(db: Firestore, caller: string | undefined, data: unknown,
  minutes = 30, now: () => number = Date.now) {
  const uid = requireUser(caller);
  await consumeAttempt(db, uid, "invite", now);
  const convoyId = onlyField(data, "convoyId");
  if (typeof convoyId !== "string" || !/^[a-f0-9]{64}$/.test(convoyId)) {
    throw new HttpsError("invalid-argument", "Invalid convoy ID.");
  }
  if (!Number.isInteger(minutes) || minutes < 1 || minutes > 1440) {
    throw new HttpsError("internal", "Invalid invitation configuration.");
  }
  const secret = newSecret();
  const convoyRef = db.collection("convoys").doc(convoyId);
  const inviteRef = db.collection("invitations").doc(digest(secret));
  return db.runTransaction(async tx => {
    const op = await readOperation(db, tx, uid, data, "createConvoyInvitation");
    if (op.replay) return op.replay;
    const snapshot = await tx.get(convoyRef);
    const convoy = snapshot.data();
    const time = now();
    if (!convoy || convoy.creatorId !== uid || !Object.hasOwn(convoy.members ?? {}, uid) ||
        convoy.status !== "active" || !(convoy.expiresAt instanceof Timestamp) ||
        convoy.expiresAt.toMillis() <= time) {
      throw new HttpsError("permission-denied", "Cannot issue an invitation.");
    }
    // Local Create Convoy records from the previous slice have no version yet.
    const previous = convoy.inviteVersion ?? 0;
    if (!Number.isSafeInteger(previous) || previous < 0 || previous >= Number.MAX_SAFE_INTEGER) {
      throw new HttpsError("failed-precondition", "Cannot issue an invitation.");
    }
    const version = previous + 1;
    const expiresAt = Timestamp.fromMillis(Math.min(time + minutes * 60_000, convoy.expiresAt.toMillis()));
    tx.update(convoyRef, { inviteVersion: version });
    tx.create(inviteRef, { convoyId, version, createdAt: FieldValue.serverTimestamp(), expiresAt });
    // A replay/recovery must never disclose or persist the bearer secret.
    completeOperation(tx, op, "createConvoyInvitation", {convoyId, version, invitationUnavailable: true});
    // Only returned after commit; neither plaintext nor a reversible encoding is stored.
    return { secret, expiresAt: expiresAt.toMillis() };
  });
}

export async function redeemInvitation(db: Firestore, caller: string | undefined, data: unknown,
  now: () => number = Date.now) {
  const uid = requireUser(caller);
  await consumeAttempt(db, uid, "join", now);
  const secret = normalizeSecret(onlyField(data, "secret"));
  const inviteRef = db.collection("invitations").doc(digest(secret));
  const pointerRef = db.collection("userSessions").doc(uid);
  return db.runTransaction(async tx => {
    const op = await readOperation(db, tx, uid, data, "joinConvoy");
    if (op.replay) return op.replay;
    const invitation = (await tx.get(inviteRef)).data();
    const time = now();
    if (!invitation || !(invitation.expiresAt instanceof Timestamp) ||
        invitation.expiresAt.toMillis() <= time ||
        typeof invitation.convoyId !== "string" || !/^[a-f0-9]{64}$/.test(invitation.convoyId)) {
      throw invalidInvitation();
    }
    const convoyRef = db.collection("convoys").doc(invitation.convoyId);
    const convoy = (await tx.get(convoyRef)).data();
    if (!convoy || convoy.status !== "active" || !(convoy.expiresAt instanceof Timestamp) ||
        convoy.expiresAt.toMillis() <= time || !Number.isSafeInteger(invitation.version) ||
        invitation.version < 1 || invitation.version !== convoy.inviteVersion) {
      throw invalidInvitation();
    }
    const current = await activePointer(db, tx, uid, time);
    if (current && current !== convoyRef.id) {
      throw new HttpsError("already-exists", "You already have an active convoy.");
    }
    if (!convoy.members || typeof convoy.members !== "object" || Array.isArray(convoy.members)) {
      throw invalidInvitation();
    }
    // Pointer reads may wait for locks; check expiry again immediately before writes.
    const commitCheckTime = now();
    if (invitation.expiresAt.toMillis() <= commitCheckTime || convoy.expiresAt.toMillis() <= commitCheckTime ||
        Object.keys(convoy.members).length > MAX_MEMBERS) throw invalidInvitation();
    const existingMember = Object.hasOwn(convoy.members, uid);
    // Fail closed on a live pointer without its authoritative membership.
    if (current === convoyRef.id && !existingMember) {
      throw new HttpsError("failed-precondition", "Invalid session membership.");
    }
    if (!existingMember && Object.keys(convoy.members).length >= MAX_MEMBERS) {
      throw new HttpsError("resource-exhausted", "Convoy is full.");
    }
    if (!existingMember) {
      // Whole-map replacement is safe because the convoy was read in this transaction;
      // it also avoids interpreting dots in a Firebase UID as field path separators.
      tx.update(convoyRef, { members: { ...convoy.members, [uid]: { joinedAt: FieldValue.serverTimestamp() } } });
    }
    tx.set(pointerRef, { convoyId: convoyRef.id, expiresAt: convoy.expiresAt });
    const result = { convoyId: convoyRef.id, expiresAt: convoy.expiresAt.toMillis() };
    completeOperation(tx, op, "joinConvoy", result);
    return result;
  });
}

export const createConvoyInvitation = onCall({ region: "us-central1", maxInstances: 10 }, request =>
  issueInvitation(getFirestore(), request.auth?.uid, request.data, invitationMinutes.value()));

export const joinConvoy = onCall({ region: "us-central1", maxInstances: 10 }, request =>
  redeemInvitation(getFirestore(), request.auth?.uid, request.data));
