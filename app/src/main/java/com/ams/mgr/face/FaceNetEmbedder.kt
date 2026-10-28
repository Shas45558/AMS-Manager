package com.ams.mgr.face

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** MobileFaceNet TFLite inference. Model has two NCHW image inputs and returns two 128-D embeddings. */
class FaceNetEmbedder(context: Context, modelAsset: String = "mobile_facenet.tflite") : Closeable {
    private val interpreter = Interpreter(loadAsset(context, modelAsset), Interpreter.Options().apply { setNumThreads(4) })
    private val nativeLock = Any()
    @Volatile private var closed = false

    init {
        require(interpreter.inputTensorCount == 2) { "MobileFaceNet model expects two image inputs" }
        require(interpreter.getInputTensor(0).shape().contentEquals(intArrayOf(1, 3, 112, 112)))
        require(interpreter.getInputTensor(1).shape().contentEquals(intArrayOf(1, 3, 112, 112)))
        require(interpreter.getOutputTensor(0).shape().contentEquals(intArrayOf(2, 128)))
    }

    fun embed(face: Bitmap): FloatArray = synchronized(nativeLock) {
        check(!closed) { "Embedder is closed" }
        val input = imageTensor(face)
        val output = Array(2) { FloatArray(128) }
        interpreter.runForMultipleInputsOutputs(arrayOf(input, input), mapOf(0 to output))
        normalize(output[0])
    }

    private fun imageTensor(face: Bitmap): ByteBuffer {
        val resized = Bitmap.createScaledBitmap(face, 112, 112, true)
        val pixels = IntArray(112 * 112)
        resized.getPixels(pixels, 0, 112, 0, 0, 112, 112)
        val buffer = ByteBuffer.allocateDirect(1 * 3 * 112 * 112 * 4).order(ByteOrder.nativeOrder())
        // Model metadata specifies RGB float values in [0,1], NCHW layout.
        for (channel in 0..2) for (p in pixels) {
            val value = when (channel) { 0 -> (p shr 16) and 0xff; 1 -> (p shr 8) and 0xff; else -> p and 0xff }
            buffer.putFloat(value / 255f)
        }
        buffer.rewind()
        if (resized !== face) resized.recycle()
        return buffer
    }

    private fun normalize(v: FloatArray): FloatArray {
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(1e-12f)
        return FloatArray(v.size) { v[it] / norm }
    }

    override fun close() = synchronized(nativeLock) { if (!closed) { closed = true; interpreter.close() } }

    companion object {
        private fun loadAsset(context: Context, name: String): ByteBuffer {
            val bytes = context.assets.open(name).use { it.readBytes() }
            return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
        }
    }
}
