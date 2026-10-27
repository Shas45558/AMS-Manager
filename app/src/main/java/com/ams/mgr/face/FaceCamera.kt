package com.ams.mgr.face

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.graphics.ImageFormat
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/** Small on-device CameraX + ML Kit face pipeline. No network is used. */
class FaceCameraAnalyzer(
    private val onFace: (Bitmap) -> Unit,
    private val onError: (Exception) -> Unit = {}
) : ImageAnalysis.Analyzer {
    private val busy = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.15f)
            .enableTracking()
            .build()
    )

    override fun analyze(imageProxy: ImageProxy) {
        if (closed.get() || !busy.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            busy.set(false); imageProxy.close(); return
        }
        val rotation = imageProxy.imageInfo.rotationDegrees
        val input = InputImage.fromMediaImage(mediaImage, rotation)
        detector.process(input)
            .addOnSuccessListener { faces ->
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                if (face != null) {
                    if (!closed.get()) {
                        runCatching {
                            val bitmap = imageProxyToBitmap(imageProxy, rotation)
                            val cropped = cropFace(bitmap, face.boundingBox)
                            if (cropped != null && !closed.get()) onFace(cropped)
                            else cropped?.recycle()
                            if (bitmap !== cropped) bitmap.recycle()
                        }.onFailure { throwable ->
                            if (!closed.get()) onError(throwable as? Exception ?: Exception(throwable))
                        }
                    }
                }
            }
            .addOnFailureListener { onError(it as? Exception ?: Exception(it)) }
            .addOnCompleteListener {
                busy.set(false)
                imageProxy.close()
                if (closed.get()) detector.close()
            }
    }

    fun close() {
        // Do not close ML Kit while a detector task is still using the native object.
        // The completion callback performs the final close after busy becomes false.
        if (closed.compareAndSet(false, true) && !busy.get()) {
            detector.close()
        }
    }

    private fun cropFace(bitmap: Bitmap, box: Rect): Bitmap? {
        // FaceNet works best when enrollment and attendance use a consistent square crop.
        val side = max(box.width(), box.height())
        val pad = (side * 0.30f).toInt()
        val cropSide = side + pad * 2
        val cx = box.centerX()
        val cy = box.centerY()
        var left = cx - cropSide / 2
        var top = cy - cropSide / 2
        left = left.coerceIn(0, max(0, bitmap.width - cropSide))
        top = top.coerceIn(0, max(0, bitmap.height - cropSide))
        val right = min(bitmap.width, left + cropSide)
        val bottom = min(bitmap.height, top + cropSide)
        if (right <= left || bottom <= top) return null
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    companion object {
        private fun imageProxyToBitmap(proxy: ImageProxy, rotationDegrees: Int): Bitmap {
            val image = proxy.image ?: error("Camera image unavailable")
            val width = image.width
            val height = image.height
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]
            val nv21 = ByteArray(width * height * 3 / 2)
            var offset = 0
            val yBuffer = yPlane.buffer.duplicate()
            for (row in 0 until height) {
                val rowStart = row * yPlane.rowStride
                for (col in 0 until width) nv21[offset++] = yBuffer.get(rowStart + col * yPlane.pixelStride)
            }
            val chromaHeight = height / 2
            val chromaWidth = width / 2
            val uBuffer = uPlane.buffer.duplicate()
            val vBuffer = vPlane.buffer.duplicate()
            for (row in 0 until chromaHeight) {
                val uRow = row * uPlane.rowStride
                val vRow = row * vPlane.rowStride
                for (col in 0 until chromaWidth) {
                    nv21[offset++] = vBuffer.get(vRow + col * vPlane.pixelStride)
                    nv21[offset++] = uBuffer.get(uRow + col * uPlane.pixelStride)
                }
            }
            val jpeg = ByteArrayOutputStream()
            YuvImage(nv21, ImageFormat.NV21, width, height, null).compressToJpeg(Rect(0, 0, width, height), 90, jpeg)
            val decoded = BitmapFactory.decodeByteArray(jpeg.toByteArray(), 0, jpeg.size()) ?: error("Could not decode camera frame")
            if (rotationDegrees == 0) return decoded
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() }
        }
    }
}
