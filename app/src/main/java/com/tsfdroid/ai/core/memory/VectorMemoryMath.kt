package com.tsfdroid.ai.core.memory

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * God-Tier Phase 2 (5.1): the vector math behind semantic recall.
 *
 * Pure functions, no state, no platform dependency — cosine similarity with
 * zero-vector guards (never NaN), top-K ranking with a minimum-score floor,
 * and the FloatArray <-> little-endian ByteArray packing used by the Room
 * storage layer (BLOB column).
 */
object VectorMemoryMath {

    /**
     * Cosine similarity of two vectors; 0 for empty, mismatched, or
     * zero-magnitude inputs (a zero embedding must never poison a ranking
     * with NaN).
     */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || b.isEmpty() || a.size != b.size) return 0f
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            normA += a[i].toDouble() * a[i]
            normB += b[i].toDouble() * b[i]
        }
        if (normA == 0.0 || normB == 0.0) return 0f
        return (dot / (kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB))).toFloat()
    }

    /**
     * Ranks [candidates] against [query] by cosine similarity: scores below
     * [minScore] are dropped, the rest are ordered descending, and at most
     * [topK] results are returned.
     */
    fun <T> rank(
        query: FloatArray,
        candidates: List<Pair<T, FloatArray>>,
        topK: Int,
        minScore: Float
    ): List<Pair<T, Float>> =
        candidates
            .map { it.first to cosine(query, it.second) }
            .filter { it.second >= minScore }
            .sortedByDescending { it.second }
            .take(topK)

    /** Packs [vector] into a little-endian byte array (4 bytes per float). */
    fun pack(vector: FloatArray): ByteArray =
        ByteBuffer.allocate(vector.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { vector.forEach(::putFloat) }
            .array()

    /** Unpacks a little-endian byte array back into floats (4 bytes each). */
    fun unpack(bytes: ByteArray): FloatArray {
        if (bytes.isEmpty()) return FloatArray(0)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buffer.float }
    }
}
