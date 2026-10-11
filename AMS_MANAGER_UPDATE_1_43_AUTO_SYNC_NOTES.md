# AMS Manager 1.43 — Automatic Firebase Sync

## Changes in this source update
- Adds AndroidX WorkManager with a connected-network constraint.
- Enqueues a durable one-time sync when the app starts, after successful admin/teacher login, and after local student create/update/delete, attendance changes, monthly exam marks saves, and exam marks saves.
- WorkManager retains jobs while offline and runs them when connectivity is available. Temporary failures are retried; authorization/rules failures stop and require user action. Manual Sync remains available.
- Keeps student deletion tombstones in Room until a successful sync has propagated them to Firestore. Existing sync applies cloud tombstones before uploading local rows and removes associated attendance/monthly/exam records so stale devices do not recreate the student.
- Teacher profile create/edit/enable/disable/delete actions write directly to Firestore, so they remain online operations rather than offline-queued local data. Firebase Authentication accounts for other users are not deleted by deleting a teacher profile.
- Keeps package/application ID `com.ams.mgr`, Firebase project `ams-manager-b1434`, admin allowlist `shas45558@gmail.com`, and the existing GitHub Actions environment-variable signing setup. No keystore or secret is included in this source archive. No Cloud Functions are required for this sync feature.

## Important limitations
- Android may defer background work due to battery optimization; WorkManager is durable but not instantaneous.
- The current sync model is last-upload-wins for a record edited on multiple devices. Avoid simultaneous edits to the same record.
- Teacher profiles are written directly to Firestore and require connectivity for create/edit/enable/disable/delete.
- Build status: source-level inspection and archive checks only. This environment does not have the Gradle wrapper JAR (`./gradlew` reports it is missing), so a successful Android build has not been verified here. Run the existing GitHub Actions “Build Release APK” workflow to compile and sign with the repository's existing secrets.
