const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { initializeApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");
const bcrypt = require("bcryptjs");

initializeApp();
const auth = getAuth();
const db = getFirestore();

const PROJECT_SCHOOL_ID = "ams-manager-b1434";
const ADMIN_EMAIL = "shas45558@gmail.com";

async function requireAdmin(request) {
  if (!request.auth || request.auth.token.email !== ADMIN_EMAIL ||
      request.auth.token.email_verified !== true) {
    throw new HttpsError("permission-denied", "Only the authorized administrator can do this.");
  }
}

async function requireAdminOrCoAdmin(request) {
  if (!request.auth) throw new HttpsError("unauthenticated", "Sign in first.");
  if (request.auth.token.email === ADMIN_EMAIL && request.auth.token.email_verified === true) return;
  const profile = await db.collection("adminAccounts").doc(request.auth.uid).get();
  if (!profile.exists || profile.data().role !== "co-admin" ||
      profile.data().schoolId !== PROJECT_SCHOOL_ID || profile.data().enabled !== true) {
    throw new HttpsError("permission-denied", "Only an enabled administrator can do this.");
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


// Permanently removes a teacher or co-admin login using the trusted Admin SDK.
// Co-admin deletion is primary-admin-only; teacher deletion is available to enabled admins.
exports.adminDeleteAccount = onCall({ enforceAppCheck: false }, async (request) => {
  const accountType = String(request.data?.accountType || "");
  if (accountType === "co-admin") await requireAdmin(request);
  else await requireAdminOrCoAdmin(request);
  const uid = String(request.data?.uid || "").trim();
  const userId = normalizeUserId(request.data?.userId);
  if (!uid || uid.length > 128 || !userId || userId.length > 40) {
    throw new HttpsError("invalid-argument", "A valid account UID and User ID are required.");
  }

  if (accountType === "teacher") {
    const profileRef = db.collection("teacherAccounts").doc(uid);
    const profileSnap = await profileRef.get();
    if (profileSnap.exists && profileSnap.data().role !== "teacher") {
      throw new HttpsError("failed-precondition", "The selected profile is not a teacher account.");
    }
    await db.collection("teacherCredentials").doc(userId).delete().catch((error) => {
      if (error.code !== 5 && error.code !== "not-found") throw error;
    });
    await profileRef.delete().catch((error) => {
      if (error.code !== 5 && error.code !== "not-found") throw error;
    });
    await auth.deleteUser(uid).catch((error) => {
      if (error.code !== "auth/user-not-found") throw error;
    });
    // Older teacher login flow may have issued a stable custom-token UID.
    await auth.deleteUser(`teacher_${userId}`).catch((error) => {
      if (error.code !== "auth/user-not-found") throw error;
    });
    return { ok: true, accountType, userId };
  }

  if (accountType === "co-admin") {
    const profileRef = db.collection("adminAccounts").doc(uid);
    const profileSnap = await profileRef.get();
    if (!profileSnap.exists || profileSnap.data().role !== "co-admin" ||
        profileSnap.data().schoolId !== PROJECT_SCHOOL_ID ||
        normalizeUserId(profileSnap.data().userId) !== userId) {
      throw new HttpsError("failed-precondition", "The selected co-admin profile does not match the requested account.");
    }
    await profileRef.delete();
    await auth.deleteUser(uid).catch((error) => {
      if (error.code !== "auth/user-not-found") throw error;
    });
    return { ok: true, accountType, userId };
  }

  throw new HttpsError("invalid-argument", "Unsupported account type.");
});
