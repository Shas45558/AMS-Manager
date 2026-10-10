# AMS Manager Firebase Sync testing

1. Confirm Firebase project `ams-manager-b1434` has Firestore created and the `firebase/firestore.rules` rules deployed.
2. Confirm Google sign-in is enabled and `shas45558@gmail.com` is verified.
3. Build and install the APK signed with the configured release key.
4. Sign in as the administrator; open the Sync screen and tap **Sync School Records**.
5. With no records, expect a successful sync with zero counts. Add one disposable student, sync, then inspect Firestore at `schools/ams-manager-b1434/students/{localRoomId}`.
6. On a second test install, sync and confirm the student appears. Test attendance and exam records as well.
7. Test with a non-authorized Google account; Firestore access should be denied.

Limitations: merge-only sync; no cloud deletion propagation, conflict resolution, or automatic background sync. Sync requires internet and Firestore rules deployed. Never store teacher passwords or password hashes in school data collections.
