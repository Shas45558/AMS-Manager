# Face model integration

The project includes the supplied TensorFlow Lite models in `app/src/main/assets/`:

- `facenet_512.bin` — FaceNet embedding model
- `(removed from APK)` — anti-spoof model
- `(removed from APK)` — anti-spoof model

`FaceNetEmbedder` now defaults to `facenet_512.bin`, validates a 160x160x3 input tensor and a 512-dimensional output, and L2-normalizes the embedding.

The anti-spoof models are included but are not yet connected to the attendance camera flow.
