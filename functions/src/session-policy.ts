import { Firestore, Timestamp, Transaction } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";

export const MAX_MEMBERS = 20;

export function requireUser(uid: string | undefined): string {
  if (!uid) throw new HttpsError("unauthenticated", "Sign in to continue.");
  return uid;
}

export function onlyField(data: unknown, field: string): unknown {
  if (!data || typeof data !== "object" || Array.isArray(data) ||
      Object.keys(data).some(key => key !== field && key !== "operationId") || !Object.hasOwn(data, field)) {
    throw new HttpsError("invalid-argument", "Invalid request fields.");
  }
  return (data as Record<string, unknown>)[field];
}

// Pointers are server-owned discovery records. All mutation handlers share this
// check and serialize on the pointer, including when its document does not exist.
export async function activePointer(db: Firestore, tx: Transaction, uid: string, now: number) {
  const pointer = await tx.get(db.collection("userSessions").doc(uid));
  if (!pointer.exists) return undefined;
  const expiry = pointer.get("expiresAt");
  if (!(expiry instanceof Timestamp)) {
    throw new HttpsError("failed-precondition", "Invalid session pointer.");
  }
  if (expiry.toMillis() <= now) return undefined;
  const id = pointer.get("convoyId");
  if (typeof id !== "string" || !/^[a-zA-Z0-9_-]{1,128}$/.test(id)) {
    throw new HttpsError("failed-precondition", "Invalid session pointer.");
  }
  const convoy = await tx.get(db.collection("convoys").doc(id));
  if (!convoy.exists || convoy.get("status") !== "active") return undefined;
  const convoyExpiry = convoy.get("expiresAt");
  if (!(convoyExpiry instanceof Timestamp)) {
    throw new HttpsError("failed-precondition", "Invalid convoy expiry.");
  }
  return convoyExpiry.toMillis() > now ? id : undefined;
}
