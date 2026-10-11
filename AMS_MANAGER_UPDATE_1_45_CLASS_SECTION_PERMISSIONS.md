# AMS Manager 1.45 — Teacher Class & Section Permissions

## Included changes
- Admin can assign each teacher one class (6–10) and section (A/B) from Teacher Accounts.
- New teacher profiles start unassigned; an admin must save an assignment before teacher sync is allowed.
- Teacher student list, dashboard counts, attendance, monthly exam, and exam-result screens are scoped to the assigned class and section.
- Teacher add/edit forms lock class and section to the assignment. Opening a student outside the assignment is denied in the UI.
- Firebase sync reads the assignment from the Firestore server, uploads only the assigned class/section, and queries related attendance/results by assigned student IDs.
- Firestore rules are updated to enforce assigned class/section for student documents and records linked to students.
- Student deletion tombstones now retain class and section so teacher deletions can be scoped; Room database migration 6→7 is included.
- Existing package `com.ams.mgr`, Firebase project configuration, and release signing setup are preserved.

## Required deployment order
1. Review `firebase/firestore.rules` and deploy these rules to the `ams-manager-b1434` Firebase project before installing the APK. The app UI alone is not a security boundary.
2. Build the release APK in GitHub Actions.
3. On the admin device, open Teacher Accounts and save a class and section assignment for each teacher.
4. Test with a teacher account: only the assigned class/section should appear; add/edit/delete a student there; verify another class is inaccessible; verify results and sync.

## Verification status
The ZIP archive is integrity-checked. A full Android Gradle build and live Firebase Rules simulation were not available in this environment. Do not distribute the APK until GitHub Actions builds successfully and the listed device tests pass.
