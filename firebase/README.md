# Firebase setup — AMS Manager

Project: `school-manager-b0578`  
Android package: `com.ams.mgr`  
Admin allowlist: `shas45558@gmail.com`

## Important current status
This folder adds a **secure backend starting point and restrictive Firestore rules**. It does NOT by itself complete Android Firebase login or Room↔Firestore synchronization. The Android source still contains Google Drive synchronization code; do not treat this ZIP as a finished Firebase migration/release.

## Empty cloud database choice
The app's existing on-device Room database is not erased by these files. The Firebase cloud can start empty. No local records are uploaded automatically by this starter package.

## Deploy backend and rules
1. Install Node.js 20 and Firebase CLI on a trusted computer.
2. From this `firebase/` folder, run `firebase login`.
3. Confirm `firebase use school-manager-b0578`.
4. Run `npm --prefix functions install`.
5. Deploy with `firebase deploy --only firestore:rules,functions`.

Cloud Functions deployment may require the Firebase project's billing plan (Blaze). Do not deploy if you do not intend to enable billing.
Do not put service-account JSON/private keys in the Android app or GitHub source.

## Admin Google authentication prerequisite
Configured the supplied Web OAuth client ID in `app/google-services.json` under `other_platform_oauth_client`: `632798008209-3aoq8i0auppniis706rirti9iuuebog2.apps.googleusercontent.com`. The `oauth_client` list for Android remains empty because the Android OAuth client is a separate client. Confirm in Google Cloud that an Android OAuth client exists for package `com.ams.mgr` and the release SHA-1/SHA-256 fingerprints. Do not paste private keys or passwords into chat.

## Rules and schema
The rules deny access by default. App data must live under:
`schools/school-manager-b0578/...`
Teacher credential documents live in `teacherCredentials/{userId}` and are explicitly inaccessible to clients. The Cloud Functions use the Admin SDK to manage them.

Before production, additionally enable Firebase App Check, configure rate limiting/abuse protection for teacher login, test rules with the emulator, and add more granular teacher permissions if required.
