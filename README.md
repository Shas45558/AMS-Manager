# AMS Manager 1.49

## Student promotion and SSC year management
- Added an SSC Students drawer page for Class 10 students.
- Assign an SSC exam year such as SSC-2027 without changing the student's class, section, roll number, permanent database ID, or academic records.
- Admins can view and change SSC years for all Class 10 students; teachers can assign SSC years only for their assigned Class 10 section.
- Repeating a Class 10 student during promotion automatically assigns the target calendar year as their SSC year (for example, 2026 to 2027).
- The admin Students page includes search and class, section, gender, and religion filters.
- Teachers can access Student Promotion for their assigned class and section. Promotion updates preserve the same student ID and section; teacher promotion sync is limited to records originating in their assigned class.
- Added the `sscYear` Room field with migration 9 to 10 and Firebase sync support.

## Install
1. Replace the repository files with this project.
2. Deploy `firebase/firestore.rules` in Firebase Console. The updated rules allow a teacher to update an existing student record from their assigned class while keeping its section, and only write promotion history originating in that assigned class/section.
3. Build using the existing GitHub Actions workflow and install on admin and teacher devices.
4. Test promotion, SSC year assignment/change/removal, student filters, and admin/teacher synchronization with test students before using real records.

Keep a backup of your current project and Firebase data before upgrading.

SSC drawer visibility: once a Class 10 student is assigned an SSC exam year, the student remains in Class 10 and appears in the SSC Students drawer only. Assigned SSC students are excluded from the regular Students drawer and regular student search. The SSC Students drawer continues to show assigned students and their SSC year.
