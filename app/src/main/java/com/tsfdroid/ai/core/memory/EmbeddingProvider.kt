package com.tsfdroid.ai.core.memory

/**
 * God-Tier Phase 2 (5.1): the embedding seam.
 *
 * An [EmbeddingProvider] turns text into a fixed-size float vector. The
 * semantic-memory stack (storage, cosine recall) is written against this
 * interface so the embedding SOURCE is a configuration decision:
 *
 *  - [LocalHashEmbeddingProvider] — the default: offline, deterministic,
 *    zero-dependency. A hashed bag-of-words vector, which honestly measures
 *    LEXICAL overlap, not deep semantics.
 *  - a remote embedding model — can implement the same interface later
 *    without touching storage or recall logic.
 *  - a fake — in tests, for exact vector control.
 */
interface EmbeddingProvider {
    /** Stable identifier of the embedding model (stored with each vector). */
    val modelId: String

    /** The dimensionality of every vector this provider emits. */
    val dimensions: Int

    /** Embeds [text]; implementations must be deterministic offline. */
    suspend fun embed(text: String): FloatArray
}

/**
 * The default local provider: a hashed bag-of-words embedding.
 *
 * Tokens (`[a-z0-9]+` of the lowercased text) are hashed with FNV-1a into a
 * fixed number of buckets, weighted by term frequency, and L2-normalized, so
 * cosine similarity is lexical overlap. This is a real, offline embedding —
 * simple by design and honest about what it captures. Empty/blank text
 * yields the zero vector (cosine 0 against everything, never NaN).
 */
class LocalHashEmbeddingProvider(
    override val dimensions: Int = DEFAULT_DIMENSIONS
) : EmbeddingProvider {

    override val modelId: String
        get() = "local-hash-$dimensions"

    override suspend fun embed(text: String): FloatArray {
        val vector = FloatArray(dimensions)
        var tokens = 0
        TOKEN_REGEX.findAll(text.lowercase()).forEach { token ->
            vector[Math.floorMod(fnv1a(token.value), dimensions)] += 1f
            tokens++
        }
        if (tokens == 0) return vector
        var sumSquares = 0.0
        for (i in vector.indices) {
            sumSquares += vector[i].toDouble() * vector[i]
        }
        val inverseNorm = (1.0 / kotlin.math.sqrt(sumSquares)).toFloat()
        for (i in vector.indices) {
            vector[i] *= inverseNorm
        }
        return vector
    }

    /** FNV-1a 32-bit: stable across JVM versions and platforms. */
    private fun fnv1a(s: String): Int {
        var hash = 0x811c9dc5.toInt()
        for (c in s) {
            hash = hash xor c.code
            hash *= 0x01000193
        }
        return hash
    }

    companion object {
        const val DEFAULT_DIMENSIONS = 256
        private val TOKEN_REGEX = Regex("[a-z0-9]+")
    }
}
