# AMS Manager Update 1.47.7 — Safe roster synchronization

## Changes
- Fetches the current Firestore student roster after applying queued local student additions/edits and deletions.
- Adds a Room-backed pending student upsert queue. Student additions and edits are queued before auto-sync starts, so newly added offline students are uploaded before cloud reconciliation can remove stale local copies.
- Removes the previous bulk-upload of every local student, which could resurrect a student deleted on another device.
- Reconciles local student records against the cloud roster for the admin and the teacher's assigned class/section.
- Keeps pending additions queued if they are outside the teacher's current assignment or a Firestore upload fails.
- Adds Room migration 7→8 for the pending-upsert queue.
- Does not use or create a Firestore deletedStudents collection.

## Deployment/testing
1. Replace the repository files with this project and build the APK with GitHub Actions.
2. Install the update on the admin and all teacher devices.
3. Publish the existing `firebase/firestore.rules` rules if they are not already deployed.
4. Test online and offline: add a student, sync, delete from admin, sync teacher, and then sync again on both devices. Confirm deleted students do not return and new students survive sync.

## Compatibility note
Students added after installing this version are queued for upload before reconciliation. A student that was added on an older version but never reached Firestore is indistinguishable from an old stale local copy; review any such unsynced legacy entries before the first sync after upgrading.
