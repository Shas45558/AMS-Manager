# AMS Manager — Student Promotion Update

Android application package: `com.ams.mgr`  
Firebase project: `ams-manager-b1434`

## Student promotion

- Keeps the same permanent local student ID and name.
- Updates class, section (unchanged), roll number, and calendar academic year.
- Allows admins to select students and edit roll numbers before confirmation.
- Preserves attendance and exam rows when class/year changes.
- Records promotion history in the local database and synchronizes it to Firestore.
- Supports Promote, Repeat, and Left School outcomes.
- If a target class/section roll number is already occupied, the admin can move the existing active student to **Old Students** and complete the promotion without deleting that student's record.
- In the Student Promotion screen, enable **Show Old Students for later promotion** to find and select archived students later.
- Old Students are excluded from normal active-student lists and remain in the database with the same permanent ID.

## Safe use

1. Back up your Firebase data and the current working project before deployment.
2. Build the APK with the repository's GitHub Actions workflow.
3. Install the same release on admin and teacher devices.
4. Test promotion, repeat, left-school, roll-number conflict handling, old-student restoration/promotion, and Firebase sync with test records first.

## Build notes

The Gradle wrapper JAR is not included in this source archive. Use the configured GitHub Actions workflow to build, and verify the resulting APK on devices before using it with production student records.

Keep `app/google-services.json` and the Firebase rules aligned with the Firebase project. Do not commit signing keys or credentials.
