# Firebase migration status — AMS Manager

## Completed in this source package
- Removed Google Drive sync manager, Drive OAuth scope requests, Drive-specific background workers/scheduler, Drive backup manager, and OkHttp Drive API dependency.
- Kept the existing Room database and did not clear or upload local records.
- Admin sign-in now exchanges Google Sign-In ID token for Firebase Authentication credentials and checks the allowlisted email `shas45558@gmail.com` in the app.
- Teacher sign-in calls the trusted `teacherLogin` Firebase Callable Function and uses the returned Firebase custom token.
- Admin teacher creation calls the trusted `adminCreateTeacher` Firebase Callable Function; server-side code hashes passwords with bcrypt.
- Added Firebase Functions and coroutine Task-await dependencies. Web OAuth client ID is configured for Google ID-token requests.
- Firestore rules and Cloud Functions starter files are in `../firebase/`.

## Not yet complete / do not treat as production-ready
- Student, attendance, monthly-exam and exam-mark bidirectional Room↔Firestore synchronization is **not implemented yet**. The Sync page currently reports Firebase sign-in status and does not claim to sync records.
- Google Cloud still needs an Android OAuth client matching package `com.ams.mgr` and the release signing SHA-1; the Web client ID is not a replacement for that Android client.
- Deploy the Cloud Functions and Firestore rules before testing teacher account creation/login.
- Harden callable functions with App Check, rate limiting and production review before release.
- This source package could not be built here because the Gradle wrapper JAR is absent; the repository's GitHub Actions workflow bootstraps the wrapper before building. Run CI and test login flows before installing.

## Data safety
The cloud database is intended to start empty. Existing local Room records are left on-device and are not automatically uploaded or deleted by this migration step.


## Firebase project configuration updated
- Replaced `app/google-services.json` with the configuration supplied for Firebase project `ams-manager-b1434` and Android package `com.ams.mgr`.
- Updated the app’s Google Sign-In Web OAuth client ID to match the supplied Firebase configuration.
- Updated Firebase CLI/backend project references to `ams-manager-b1434`.
- The supplied configuration lists Android certificate SHA-1 `7ea3ca9b4bd61249c2f70ec58a2c5b691d1801bd`; confirm this matches the signing certificate used by the APK you install.
- This configuration update does not itself enable Email/Password provider or create teacher accounts. Admin login currently uses Google Sign-In; teacher login uses the callable `teacherLogin` backend.

## Room ↔ Firestore sync implementation (2026-10-10)
The Android Sync screen now attempts a merge-only synchronization for `students`, `attendance`, `monthlyExams`, and `examMarks` under `schools/ams-manager-b1434/`. It uploads current local rows using deterministic Firestore document IDs and merges cloud rows into Room. It does not propagate deletions and does not sync teacher credential hashes. The signed-in admin must be `shas45558@gmail.com` with a verified email; teacher sync requires the expected custom claims and Firestore rules.

Before relying on this in production, run a successful Android build and test with a disposable record. Current merge strategy does not implement conflict resolution or deletion tombstones; avoid editing the same record on multiple devices simultaneously. Deploy the `firebase/firestore.rules` rules before attempting sync. Cloud Functions are not required for the record sync itself, but remain required for teacher account operations.


## Spark-compatible teacher authentication update
The teacher account flow has been switched from callable Cloud Functions/custom tokens to Firebase email/password authentication with a secondary Firebase app. Teacher roles are stored in `teacherAccounts/{uid}` and protected by Firestore rules so only the verified administrator email can create or modify role records. Deploy `firebase/firestore.rules`; do not deploy Cloud Functions for this design. Existing callable-function-created teacher accounts need to be recreated in the new format.
