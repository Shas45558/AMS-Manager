# AMS Manager v1.40 — Firebase migration (in progress)

This package removes Google Drive-based database synchronization and moves sign-in/teacher account actions toward Firebase.

## Included
- Android app source, preserving the local Room database.
- Firebase Authentication Google sign-in for the allowlisted admin email `shas45558@gmail.com`.
- Teacher login and admin-created teacher accounts through Firebase Callable Functions.
- Firestore security rules and Firebase Functions source under `firebase/`.
- Supplied Firebase configuration for Android package `com.ams.mgr` and Web OAuth client ID.

## Important limitations
- **Room↔Firestore synchronization of students, attendance, monthly exams and exam marks is not implemented yet.** The Sync screen currently checks Firebase authentication status only; it does not sync records.
- The cloud database is intended to start empty. Existing Room data remains on-device and is not automatically uploaded or erased.
- Deploy `firebase/functions` and `firebase/firestore.rules` before testing teacher account creation/login. Review App Check, abuse protection and rate limiting before production.
- Google Cloud still needs an Android OAuth client with package `com.ams.mgr` and the correct release SHA-1. The Web OAuth client ID does not replace it.
- A full Android build could not be run in this environment because `gradle-wrapper.jar` is absent. The provided GitHub Actions workflow bootstraps the wrapper; run CI and test on a device before release.

See `FIREBASE_SETUP_STATUS.md` for status and next steps.


## Firebase project
This version is configured for Firebase project `ams-manager-b1434` and Android package `com.ams.mgr`. The `app/google-services.json` file must remain in place. Enable the required Firebase Authentication providers in the Firebase Console and deploy the backend before testing teacher login.
