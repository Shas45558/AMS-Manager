package com.ams.mgr.face

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * MiniFASNet/TFLite anti-spoof model wrapper. The supplied models expose 3 logits.
 * We intentionally keep the label mapping configurable because the exported model
 * does not contain reliable human-readable class labels.
 */
class SpoofDetector(context: Context, modelAsset: String = "spoof_model_scale_2_7.tflite") : AutoCloseable {
    private val interpreter = Interpreter(loadAsset(context, modelAsset), Interpreter.Options().apply { setNumThreads(2) })
    private val inputShape = interpreter.getInputTensor(0).shape()
    private val outputSize = interpreter.getOutputTensor(0).shape().last()

    init {
        require(inputShape.contentEquals(intArrayOf(1, 80, 80, 3))) { "Expected spoof input [1,80,80,3], got ${inputShape.contentToString()}" }
        require(outputSize == 3) { "Expected 3 spoof logits, got $outputSize" }
    }

    data class Result(val scores: FloatArray, val liveScore: Float)

    fun predict(face: Bitmap): Result {
        val resized = Bitmap.createScaledBitmap(face, 80, 80, true)
        val input = ByteBuffer.allocateDirect(1 * 80 * 80 * 3 * 4).order(ByteOrder.nativeOrder())
        val pixels = IntArray(80 * 80)
        resized.getPixels(pixels, 0, 80, 0, 0, 80, 80)
        for (p in pixels) {
            input.putFloat(((p shr 16) and 0xff) / 255f)
            input.putFloat(((p shr 8) and 0xff) / 255f)
            input.putFloat((p and 0xff) / 255f)
        }
        input.rewind()
        val raw = Array(1) { FloatArray(3) }
        interpreter.run(input, raw)
        if (resized !== face) resized.recycle()
        val exp = raw[0].map { kotlin.math.exp((it - raw[0].maxOrNull()!!).toDouble()).toFloat() }
        val sum = exp.sum().coerceAtLeast(1e-8f)
        val probs = FloatArray(3) { exp[it] / sum }
        // MiniFASNet exports commonly use class 1 as the live/real class.
        // Keep this as a score only; callers can require a conservative margin.
        return Result(probs, probs.getOrElse(1) { 0f })
    }

    override fun close() = interpreter.close()

    private fun loadAsset(context: Context, name: String): ByteBuffer {
        val descriptor = context.assets.openFd(name)
        FileInputStream(descriptor.fileDescriptor).use { stream ->
            return stream.channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.length)
        }
    }
}
