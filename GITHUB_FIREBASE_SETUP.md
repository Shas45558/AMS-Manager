# AMS Manager — GitHub and Firebase setup

## App identity
- App name: AMS Manager
- Android application ID / namespace: `com.ams.mgr`

## Important Firebase requirement
The included `app/google-services.json` was created for the previous package ID (`com.scl.mgr`). It is not a valid final configuration for `com.ams.mgr`. In Firebase Console, open or create a Firebase project you own, add an Android app with package name `com.ams.mgr`, download its new `google-services.json`, and replace `app/google-services.json` with that downloaded file. Do not simply edit the package name inside the old JSON; the Firebase app ID and OAuth configuration must match the registered app.

Enable **Authentication → Sign-in method → Email/Password** in Firebase Console. Email/password login also needs app code changes; this project currently contains Google sign-in based authentication, so do not treat email/password login as implemented yet.

## GitHub repository
Create a new empty repository named `AMS-Manager` under your GitHub account, then upload/push the contents of this folder. Keep `.gitignore` in place and do not commit keystores, passwords, access tokens, or service-account private keys.

## GitHub Actions release signing secrets
If using the included release workflow, add these under **Repository → Settings → Secrets and variables → Actions → New repository secret**:
- `RELEASE_STORE_FILE` — path to the keystore file available to the workflow (the workflow must decode/provision it if stored as a secret)
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

Do not create fake values for these. Generate a keystore locally and store its binary securely as a base64 secret if your workflow supports that, or use a protected GitHub Actions environment. Never commit the keystore.

## SHA-1 / SHA-256 fingerprints
SHA-1 and SHA-256 are certificate fingerprints, not passwords/secrets. Generate them from the actual signing certificate. For local debug builds, run `./gradlew signingReport` from Android Studio/Gradle; for release builds, use the release keystore. Add the relevant fingerprints in Firebase Console → Project settings → Your apps → Android app. A new signing key changes the release fingerprints.

## Before release
- Replace the old Firebase JSON with the newly downloaded file for `com.ams.mgr`.
- Implement and test Firebase email/password registration, sign-in, sign-out, and error handling.
- Review Firestore rules and Cloud Functions before deploying.
- Build and test the app in GitHub Actions; this source archive originally lacked the Gradle wrapper JAR, so CI may need to bootstrap it.
