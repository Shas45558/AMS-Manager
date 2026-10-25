# AMS Manager — Offline Face Attendance (initial integration)

## Included in this revision
- Added a local Room `face_templates` table, keyed by the existing local student ID.
- Added `FaceTemplateDao` for observing, saving, reading, counting, and deleting enrolled templates.
- Added database migration 11 -> 12. Existing student and attendance data are preserved by this migration.
- Templates store embedding bytes and model metadata only; this table does not store face photos.

## Not yet complete
- Live camera preview and enrollment UI.
- Face detection, FaceNet/TFLite inference, face alignment, embedding normalization, and matching threshold.
- Automatic attendance recording from a match.
- Firestore template synchronization.

## Why recognition is not enabled yet
The reference repository's source describes a FaceNet TFLite model and an ObjectBox vector database. The AMS Manager archive available in this workspace does not contain those model assets or the reference project's source files. This revision therefore adds the safe local storage foundation only; it does not claim to recognize faces yet. The next required step is to bring in and verify the model asset and implement the camera/inference pipeline, then connect accepted matches to the existing attendance DAO/repository.

## Biometric data note
Face embeddings are sensitive biometric data even when photographs are not stored. Limit access to authorized staff, obtain appropriate consent, and provide a way to delete a student's template.
