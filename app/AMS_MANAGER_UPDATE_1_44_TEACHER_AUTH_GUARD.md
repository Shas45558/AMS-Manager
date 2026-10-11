# AMS Manager 1.44 — Remembered Teacher Authorization Guard

This update adds a startup authorization check for remembered teacher sessions and server-only profile checks before Firebase sync.

- Online startup: a remembered teacher must have a live Firebase Authentication session and an enabled `teacherAccounts/{uid}` profile with role `teacher` and school ID `ams-manager-b1434`. Missing/disabled profiles revoke the remembered session and clear the local Room database.
- Offline startup: a remembered teacher may enter offline mode as requested, but this does not count as authorization. Firebase sync checks the profile from the server and does not sync from cached profile data.
- When a background sync detects a deleted/disabled teacher profile (or an invalid Firebase account), it clears local school data, signs out that teacher, removes Remember me, and signals the open app to return to login.
- Admin behavior and existing release signing configuration are preserved. Firebase Spark/no Cloud Functions remains supported.

Important limitations: a phone cannot learn that a profile was deleted while it is offline. Local data is cleared only after the phone can contact Firebase and confirm revocation. This archive has been checked for ZIP integrity and source-level consistency, but it has not been compiled in Android/Gradle in this environment; test the GitHub Actions build and the offline/deletion scenarios before distributing the APK.
