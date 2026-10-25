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
