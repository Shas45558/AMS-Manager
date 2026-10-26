# AMS Manager offline face attendance — integration stage

Base: `AMS_Manager_Offline_Face_Attendance_Initial.zip` (user reported its release build succeeded).

## Added in this iteration
- TensorFlow Lite runtime dependency.
- Bundled Google ML Kit face-detection dependency (offline detector model dependency).
- CameraX dependencies and camera permission declaration for the future live-camera screen.
- `FaceNetEmbedder.kt`: local TFLite embedding inference for a compatible `[1,160,160,3]` model with 128- or 512-dimensional output.
- `FaceMatcher.kt`: compact float-vector encoding/decoding and cosine-similarity matching against Room `FaceTemplate` rows.

## Required before this is a working feature
- The reference repository's actual `facenet.tflite` asset is NOT included in this ZIP. Add the compatible model at `app/src/main/assets/facenet.tflite` before constructing `FaceNetEmbedder`.
- Confirm the chosen model's preprocessing exactly. This implementation uses RGB floats scaled to [-1,1], which must be checked against the specific model artifact.
- The camera preview, ML Kit face crop/alignment, enrollment UI, and attendance-screen wiring are not yet connected.
- The matching threshold is provisional and must be calibrated with consented real samples. Do not use face matching as the sole attendance evidence until tested.
- Room biometric template table is local-only; Firestore synchronization is not implemented in this iteration.

## Build status
Build could not be run in this environment: the project wrapper script reports that `gradle-wrapper.jar` is missing. This ZIP is therefore source changes, not a verified build or working APK.


## Face workflow implemented
- Student Details now has Register / Replace Face.
- Enrollment captures five ML Kit-detected face crops, creates a normalized 512-D FaceNet template, and stores it locally in Room.
- Attendance now has Face Attendance for a selected class/section/date.
- CameraX + ML Kit detect the largest face; FaceNet matching is performed entirely on-device.
- Matching threshold remains 0.72 and should be calibrated with real school enrollment samples.
- Attendance is written through the existing offline Room/Firebase sync path and duplicate attendance is prevented by the existing student/date unique index.
- Anti-spoof model files are bundled and a wrapper is included, but its 3-class label mapping has not been used to block attendance until the supplied model is validated against known live/spoof samples.


## Firebase face enrollment sync
Face enrollment stores only the numerical FaceNet embedding locally. The embedding is queued and synced to Firestore in `schools/ams-manager-b1434/faceTemplates/{studentId}` when online. Original face photos are not uploaded.


## v7 matching fix
The attendance matcher now keeps the student roster snapshot paired with the face templates loaded from that roster. This prevents a roster refresh during camera scanning from causing a valid high-confidence match to be reported as a missing student record.
