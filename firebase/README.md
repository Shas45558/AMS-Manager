# Firebase setup — AMS Manager (Spark-compatible teacher accounts)

Project: `ams-manager-b1434`  
Android package: `com.ams.mgr`  
Only administrator: `shas45558@gmail.com`

## Teacher account design
- Admin signs in using Google with the allowlisted administrator account.
- The app creates Firebase Authentication email/password accounts using a secondary Firebase app instance so creating a teacher does not sign out the admin. The teacher User ID is represented internally as `<userId>@ams-manager.invalid`.
- The signed-in admin writes `teacherAccounts/{uid}`. Firestore rules allow only the verified admin email to create/update/delete these profiles.
- Teachers sign in with their User ID and password. Firestore rules authorize their school data only if their UID has an enabled teacher profile for this project.
- No Cloud Functions or Firebase Admin SDK are used by this path. Do not put service-account credentials in the app.

## Setup on Firebase Console
1. In Authentication → Sign-in method, enable Email/Password and Google.
2. In Firestore Database → Rules, publish the rules in `firestore.rules`.
3. Keep the only admin as `shas45558@gmail.com`; do not add any other admin.
4. Rebuild and install the updated app.
5. Sign in as admin and create a new test teacher with a 10+ character password. Sign out, then sign in using the test teacher User ID and password.
6. Test Sync with non-sensitive sample data before production.

## Important limitations
- This is client-side account creation. The app UI restricts creation to the allowlisted admin, and Firestore rules prevent other users from creating teacher profiles or granting themselves teacher access. Firebase Authentication may still permit unrelated email/password accounts to be created by clients; those accounts cannot access school data without an admin-created teacher profile.
- Existing accounts created by the old Cloud Functions/custom-token flow will not sign in with the new alias format. Recreate teacher accounts through the updated admin screen.
- Do not deploy `firebase/functions` for this Spark-compatible design. Cloud Functions require Blaze.
- Verify the build and test Firestore rules before distributing the APK.
