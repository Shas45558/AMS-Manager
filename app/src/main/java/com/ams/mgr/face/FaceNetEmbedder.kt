package com.ams.mgr.face

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Offline FaceNet inference. Put a compatible 160x160 RGB FaceNet model at assets/facenet_512.bin. */
class FaceNetEmbedder(context: Context, modelAsset: String = "facenet_512.bin") : Closeable {
    private val interpreter: Interpreter = Interpreter(loadAsset(context, modelAsset), Interpreter.Options().apply { setNumThreads(4) })
    private val outputSize: Int = interpreter.getOutputTensor(0).shape().last()

    init {
        require(interpreter.getInputTensor(0).shape().contentEquals(intArrayOf(1, 160, 160, 3))) {
            "Expected FaceNet input tensor [1,160,160,3]"
        }
        require(outputSize == 512) { "Expected 512-dimensional FaceNet output, got $outputSize" }
    }

    /** Input must already be cropped/aligned to the face. */
    fun embed(face: Bitmap): FloatArray {
        val resized = Bitmap.createScaledBitmap(face, 160, 160, true)
        val input = ByteBuffer.allocateDirect(1 * 160 * 160 * 3 * 4).order(ByteOrder.nativeOrder())
        val pixels = IntArray(160 * 160)
        resized.getPixels(pixels, 0, 160, 0, 0, 160, 160)
        for (p in pixels) {
            // FaceNet models commonly expect RGB values scaled to [-1,1]. Verify against the model's source.
            input.putFloat((((p shr 16) and 0xff) / 127.5f) - 1f)
            input.putFloat((((p shr 8) and 0xff) / 127.5f) - 1f)
            input.putFloat(((p and 0xff) / 127.5f) - 1f)
        }
        input.rewind()
        val output = Array(1) { FloatArray(outputSize) }
        interpreter.run(input, output)
        if (resized !== face) resized.recycle()
        return normalize(output[0])
    }

    override fun close() = interpreter.close()

    private fun normalize(vector: FloatArray): FloatArray {
        val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(1e-12f)
        return FloatArray(vector.size) { vector[it] / norm }
    }

    companion object {
        private fun loadAsset(context: Context, name: String): ByteBuffer {
            context.assets.open(name).use { input ->
                val bytes = input.readBytes()
                return ByteBuffer.allocateDirect(bytes.size)
                    .order(ByteOrder.nativeOrder())
                    .apply { put(bytes); rewind() }
            }
        }
    }
}
