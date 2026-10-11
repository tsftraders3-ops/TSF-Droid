package com.tsfdroid.ai.core.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * God-Tier Phase 2 (5.1): the LOCAL embedding provider — deterministic,
 * offline, no network. It is a hashed bag-of-words vector: lexical overlap
 * similarity, honestly NOT deep semantics. The seam exists so a real remote
 * embedding model can be wired later without touching recall logic.
 */
class LocalHashEmbeddingProviderTest {

    private val provider = LocalHashEmbeddingProvider(dimensions = 256)

    @Test
    fun `embedding is deterministic across calls`() = runBlocking {
        val a = provider.embed("Always check XAU/USD support before entries")
        val b = provider.embed("Always check XAU/USD support before entries")
        assertArrayEquals(a, b, 0f)
    }

    @Test
    fun `non-empty text yields a unit-length vector`() = runBlocking {
        val v = provider.embed("gold price today")
        val norm = kotlin.math.sqrt(v.map { it * it }.sum().toDouble())
        assertEquals(1.0, norm, 1e-5)
    }

    @Test
    fun `empty text yields the zero vector`() = runBlocking {
        val v = provider.embed("   ")
        assertEquals(provider.dimensions, v.size)
        assertTrue(v.all { it == 0f })
    }

    @Test
    fun `dimensions are honored`() = runBlocking {
        val small = LocalHashEmbeddingProvider(dimensions = 64)
        assertEquals(64, small.embed("hello").size)
        assertEquals(256, provider.embed("hello").size)
    }

    @Test
    fun `model id is stable and identifies the dims`() {
        assertEquals("local-hash-256", LocalHashEmbeddingProvider(dimensions = 256).modelId)
        assertEquals("local-hash-64", LocalHashEmbeddingProvider(dimensions = 64).modelId)
    }

    @Test
    fun `lexically overlapping texts score higher than unrelated texts`() = runBlocking {
        val target = provider.embed("gold price today")
        val related = provider.embed("gold price forecast")
        val unrelated = provider.embed("birthday cake recipe")
        val cosRelated = VectorMemoryMath.cosine(target, related)
        val cosUnrelated = VectorMemoryMath.cosine(target, unrelated)
        assertTrue(
            "related=$cosRelated must beat unrelated=$cosUnrelated",
            cosRelated > cosUnrelated
        )
    }

    @Test
    fun `same text from two provider instances is identical`() = runBlocking {
        val a = LocalHashEmbeddingProvider(dimensions = 128).embed("stability across instances")
        val b = LocalHashEmbeddingProvider(dimensions = 128).embed("stability across instances")
        assertArrayEquals(a, b, 0f)
    }
}
