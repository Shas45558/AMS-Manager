const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { initializeApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");
const bcrypt = require("bcryptjs");

initializeApp();
const auth = getAuth();
const db = getFirestore();

const PROJECT_SCHOOL_ID = "school-manager-b0578";
const ADMIN_EMAIL = "shas45558@gmail.com";

async function requireAdmin(request) {
  if (!request.auth || request.auth.token.email !== ADMIN_EMAIL ||
      request.auth.token.email_verified !== true) {
    throw new HttpsError("permission-denied", "Only the authorized administrator can do this.");
  }
}

function normalizeUserId(value) {
  return String(value || "").trim().toLowerCase();
}

// Called by an authenticated administrator to create or reset a teacher account.
// Password is bcrypt-hashed on the trusted server and never returned/stored as plaintext.
exports.adminCreateTeacher = onCall({ enforceAppCheck: false }, async (request) => {
  await requireAdmin(request);
  const userId = normalizeUserId(request.data?.userId);
  const password = String(request.data?.password || "");
  if (!/^[a-z0-9._-]{3,40}$/.test(userId)) {
    throw new HttpsError("invalid-argument", "User ID must be 3-40 characters (letters, numbers, dot, underscore or hyphen).");
  }
  if (password.length < 10 || password.length > 128) {
    throw new HttpsError("invalid-argument", "Teacher password must be 10-128 characters.");
  }

  const docRef = db.collection("teacherCredentials").doc(userId);
  const passwordHash = await bcrypt.hash(password, 12);
  await docRef.set({
    userId,
    passwordHash,
    role: "teacher",
    schoolId: PROJECT_SCHOOL_ID,
    enabled: true,
    updatedAt: FieldValue.serverTimestamp()
  }, { merge: true });

  return { ok: true, userId };
});

// Teacher ID/password are checked only on the trusted server.
// A Firebase custom token is issued only after credential verification.
exports.teacherLogin = onCall({ enforceAppCheck: false }, async (request) => {
  const userId = normalizeUserId(request.data?.userId);
  const password = String(request.data?.password || "");
  if (!userId || !password || userId.length > 40 || password.length > 128) {
    throw new HttpsError("invalid-argument", "Enter a valid User ID and password.");
  }
  const snap = await db.collection("teacherCredentials").doc(userId).get();
  if (!snap.exists) throw new HttpsError("unauthenticated", "Invalid User ID or password.");
  const record = snap.data();
  if (record.enabled !== true || record.role !== "teacher" ||
      !(await bcrypt.compare(password, record.passwordHash))) {
    throw new HttpsError("unauthenticated", "Invalid User ID or password.");
  }

  // Stable UID per teacher; claims authorize only the intended school's data.
  const uid = `teacher_${userId}`;
  try {
    await auth.getUser(uid);
  } catch (error) {
    if (error.code === "auth/user-not-found") {
      await auth.createUser({ uid, displayName: userId });
    } else {
      throw error;
    }
  }
  await auth.setCustomUserClaims(uid, { role: "teacher", schoolId: PROJECT_SCHOOL_ID });
  const token = await auth.createCustomToken(uid, { role: "teacher", schoolId: PROJECT_SCHOOL_ID });
  return { token };
});
