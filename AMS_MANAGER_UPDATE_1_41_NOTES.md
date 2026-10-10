# AMS Manager update 1.41

## Changes
- Admin Authorization screen now loads and displays teacher accounts from Firestore.
- Admin can edit a teacher's display name and enable/disable that teacher's app access.
- Teachers can see the Sync destination in the navigation drawer.
- App version bumped to 1.41 (versionCode 18).

## Important Spark limitation
The login User ID is mapped to a Firebase Authentication email (`<userId>@ams-manager.invalid`). The Spark-compatible client cannot rename or delete another user's Firebase Authentication account. To change a login ID, create a new account and disable the old profile. Disabling a teacher profile blocks app access through the app's profile check and Firestore rules.

## Build
Source only; APK build has not been verified in this environment. The repository includes a GitHub Actions workflow that can build the APK if the repository and signing secrets are configured.

## Sync
The Sync drawer destination is visible to teachers. Sync still requires internet access, a valid signed-in teacher profile (`role=teacher`, `schoolId=ams-manager-b1434`, `enabled=true`), and the deployed Firestore rules. Sync is manual and merge-only.
