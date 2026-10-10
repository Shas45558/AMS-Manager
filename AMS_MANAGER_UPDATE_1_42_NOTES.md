# AMS Manager Update 1.42 — source review

## Teacher Authorization
- Admin list still reads teacher profiles from `teacherAccounts`.
- Added a confirmation-protected Delete teacher profile action. This removes the Firestore profile, not the Firebase Authentication user or historical school data. The missing profile blocks teacher authorization under the deployed rules.

## Student deletion sync
- Student deletion records a local tombstone.
- Sync uploads/applies tombstones before uploading local rows, removes the student cloud document and associated attendance/monthly exam/exam mark documents, and removes stale local copies on other devices. Tombstones remain in Firestore so stale devices cannot resurrect deleted students.
- Existing records should be backed up before first sync with this version. Deletion sync is destructive by design for a student intentionally deleted on one device.

## Exam / Monthly Exam Firebase review
- Exam & Results saves marks into Room locally (`repository.saveExamMarks`).
- Monthly Exam saves marks into Room locally (`repository.saveMonthlyExams`).
- `FirebaseSchoolSync.sync()` uploads all Room `exam_marks` rows to `schools/ams-manager-b1434/examMarks` and all `monthly_exams` rows to `schools/ams-manager-b1434/monthlyExams`, then downloads cloud rows. Thus marks do reach Firebase when the user taps Sync and it succeeds; they do not auto-upload immediately when saved.
- Sync page wording updated to explain this.

## Compatibility / verification
- Package/application ID remains `com.ams.mgr`; Firebase project remains `ams-manager-b1434`; admin remains `shas45558@gmail.com`; version is 1.42 (versionCode 19).
- Room migration 5->6 adds a pending-deletion table.
- Source patch only; successful CI/Android build has not been verified in this environment. Run GitHub Actions and test on a backup/test student before production use.
