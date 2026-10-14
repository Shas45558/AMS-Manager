# AMS Manager 1.50

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


## Professional PDF reports
- Added a Reports drawer screen for exam marksheets, attendance reports, student lists, SSC student lists, monthly exam reports, and student report-card style marks summaries.
- PDFs use A4-sized pages, automatic multi-page tables, repeated column headings, school/app logo, report titles, class/section details, generation date, and page numbers. Wide exam tables use landscape orientation.
- Reports are generated from locally available data; sync first to ensure the latest shared records are on the device.


## Release note 1.50
- Added a dedicated Reports drawer destination with professional PDF exports for exam marksheets, attendance summaries, student lists, SSC student lists, monthly exam marks, and student report-card style marks summaries.
- PDFs are generated as A4 tables, paginate automatically, repeat table headers on each page, and include the app logo, AMS Manager heading, report title, class/section or report subtitle, generation date, page number, and signature-ready whitespace. Exam and monthly marksheets use landscape orientation for wide subject columns.
- SSC-assigned students are sourced from the SSC list for the SSC report and are kept out of normal student-list exports. Teacher reports remain limited to the teacher's assigned class and section.
- Reports use data currently stored on the device; sync first for the latest cross-device records.
