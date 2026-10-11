package com.tsfdroid.ai.core.memory

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * God-Tier Phase 2 (5.1): the vector math behind semantic recall — cosine
 * similarity, top-K ranking with a floor, and the FloatArray <-> ByteArray
 * packing used by the Room storage layer.
 */
class VectorMemoryMathTest {

    @Test
    fun `cosine of identical vectors is one`() {
        val v = floatArrayOf(0.3f, -1.2f, 5f)
        assertEquals(1f, VectorMemoryMath.cosine(v, v), 1e-6f)
    }

    @Test
    fun `cosine of orthogonal vectors is zero`() {
        assertEquals(0f, VectorMemoryMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-6f)
    }

    @Test
    fun `cosine of opposite vectors is minus one`() {
        assertEquals(-1f, VectorMemoryMath.cosine(floatArrayOf(2f, 0f), floatArrayOf(-5f, 0f)), 1e-6f)
    }

    @Test
    fun `cosine of a zero vector is zero (never NaN)`() {
        assertEquals(0f, VectorMemoryMath.cosine(FloatArray(8), floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f)), 0f)
    }

    @Test
    fun `rank orders candidates by descending score and honors topK`() {
        val query = floatArrayOf(1f, 0f)
        val candidates = listOf(
            "low" to floatArrayOf(0f, 1f),        // cos 0
            "high" to floatArrayOf(0.8f, 0.2f),  // cos 0.97
            "mid" to floatArrayOf(0.5f, 0.5f)    // cos 0.707
        )
        val ranked = VectorMemoryMath.rank(query, candidates, topK = 2, minScore = 0.1f)
        assertEquals(listOf("high", "mid"), ranked.map { it.first })
    }

    @Test
    fun `rank applies the minimum score floor`() {
        val query = floatArrayOf(1f, 0f)
        val candidates = listOf(
            "orthogonal" to floatArrayOf(0f, 1f)  // cos 0 < floor
        )
        assertTrue(VectorMemoryMath.rank(query, candidates, topK = 5, minScore = 0.1f).isEmpty())
    }

    @Test
    fun `pack and unpack round-trip a vector exactly`() {
        val original = floatArrayOf(0.5f, -0.25f, 3.14159f, 0f, 1e-8f, -12.75f)
        val bytes = VectorMemoryMath.pack(original)
        assertEquals(original.size * 4, bytes.size)
        val restored = VectorMemoryMath.unpack(bytes)
        assertArrayEquals(original, restored, 0f)
    }

    @Test
    fun `unpack of empty bytes yields empty vector`() {
        assertEquals(0, VectorMemoryMath.unpack(byteArrayOf()).size)
    }
}
