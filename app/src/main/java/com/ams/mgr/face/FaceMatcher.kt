package com.ams.mgr.face

import com.ams.mgr.data.FaceTemplate
import kotlin.math.sqrt

/** Cosine-similarity matcher. Threshold must be validated with real, consented enrollment samples. */
object FaceMatcher {
    data class Match(val studentId: Long, val similarity: Float)

    fun bestMatch(query: FloatArray, templates: List<FaceTemplate>, minimumSimilarity: Float = 0.72f): Match? {
        var best: Match? = null
        for (template in templates) {
            val candidate = decode(template.embedding, template.embeddingDimensions) ?: continue
            if (candidate.size != query.size) continue
            val score = cosine(query, candidate)
            if (best == null || score > best.similarity) best = Match(template.studentId, score)
        }
        // Return the strongest candidate even below threshold so UI can show diagnostics.
        // Callers must explicitly enforce minimumSimilarity before accepting a match.
        return best
    }

    fun encode(vector: FloatArray): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(vector.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        vector.forEach(buffer::putFloat)
        return buffer.array()
    }

    fun decode(bytes: ByteArray, dimensions: Int): FloatArray? {
        if (dimensions <= 0 || bytes.size != dimensions * 4) return null
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        return FloatArray(dimensions) { buffer.float }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0; var aa = 0.0; var bb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; aa += a[i] * a[i]; bb += b[i] * b[i] }
        val denominator = sqrt(aa * bb)
        return if (denominator == 0.0) -1f else (dot / denominator).toFloat()
    }
}
