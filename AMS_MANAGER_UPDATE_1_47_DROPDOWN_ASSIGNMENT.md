# AMS Manager 1.47 — Dropdown class/section assignment

- Replaced free-text class and section assignment fields in Authorization with dropdown selectors.
- Class dropdown stores the existing expected `assignedClass` string (`"6"`, not `"Class 6"`).
- Section dropdown stores the existing expected `assignedSection` string (`"A"`).
- Save Assignment updates `teacherAccounts/{uid}` fields `assignedClass` and `assignedSection`, matching the teacher login and sync code.

The selectors are shown on each teacher card. Create a teacher and tap Refresh if the list is empty. This ZIP has not been built with Android Gradle or tested against live Firebase in this environment.
