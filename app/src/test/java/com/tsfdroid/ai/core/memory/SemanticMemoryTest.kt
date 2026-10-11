package com.tsfdroid.ai.core.memory

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tsfdroid.ai.data.db.OpenDroidDatabase
import com.tsfdroid.ai.data.db.entities.MemoryEmbeddingEntity
import com.tsfdroid.ai.data.db.entities.MemoryEntity
import com.tsfdroid.ai.data.repository.MemoryRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * God-Tier Phase 2 (5.1): the semantic recall API over a REAL (in-memory)
 * Room database — storeFact embeds and persists, recall ranks by measured
 * cosine similarity, forget removes both rows, and TTL-expired memories are
 * never recalled. All with offline providers (local hash + a fake with
 * exact vector control); no live provider is touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SemanticMemoryTest.TestApplication::class)
class SemanticMemoryTest {

    class TestApplication : Application()

    private lateinit var db: OpenDroidDatabase
    private lateinit var repository: MemoryRepository

    /** Exact-vector control: the test assigns every text its vector. */
    private class FakeEmbeddingProvider : EmbeddingProvider {
        override val modelId = "fake-test"
        override val dimensions = 3
        val vectors = mutableMapOf<String, FloatArray>()
        override suspend fun embed(text: String): FloatArray {
            return vectors[text] ?: FloatArray(dimensions)
        }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, OpenDroidDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MemoryRepository(db.memoryDao(), db.taskHistoryDao(), db.macroDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun semanticMemory(provider: EmbeddingProvider) =
        SemanticMemory(repository, MemoryExtractor(repository), db.memoryEmbeddingDao(), provider)

    @Test
    fun `storeFact persists the memory and its embedding`() = runBlocking {
        val fake = FakeEmbeddingProvider()
        fake.vectors["gold-trading-rule Always check XAU/USD support before entries"] =
            floatArrayOf(1f, 0f, 0f)

        semanticMemory(fake).storeFact(
            "gold-trading-rule",
            "Always check XAU/USD support before entries"
        )

        val row = db.memoryDao().getMemoryByKey("gold-trading-rule")
        assertEquals("Always check XAU/USD support before entries", row?.value)
        assertEquals("SEMANTIC", row?.type)
        val embedding = db.memoryEmbeddingDao().byKey("gold-trading-rule")
        assertEquals("fake-test", embedding?.model)
        assertEquals(3, embedding?.dims)
    }

    @Test
    fun `blacklisted technical content is never stored or embedded`() = runBlocking {
        val fake = FakeEmbeddingProvider()
        fake.vectors["invalid_action something went wrong"] = floatArrayOf(1f, 0f, 0f)

        semanticMemory(fake).storeFact("invalid_action", "something went wrong")

        assertEquals(null, db.memoryDao().getMemoryByKey("invalid_action"))
        assertEquals(null, db.memoryEmbeddingDao().byKey("invalid_action"))
    }

    @Test
    fun `recall ranks by cosine similarity and applies the floor`() = runBlocking {
        val fake = FakeEmbeddingProvider()
        val memory = semanticMemory(fake)

        // Store two facts with known vectors through the real store path.
        fake.vectors["gold-rule a golden rule for trading"] = floatArrayOf(0.9f, 0.1f, 0f)
        fake.vectors["cake-rule a recipe for cake"] = floatArrayOf(0f, 1f, 0f)
        memory.storeFact("gold-rule", "a golden rule for trading")
        memory.storeFact("cake-rule", "a recipe for cake")

        // Query closer to the gold memory than the cake memory.
        fake.vectors["how do I trade gold?"] = floatArrayOf(1f, 0f, 0f)
        val results = memory.recall("how do I trade gold?", topK = 5, minScore = 0.1f)

        assertTrue("expected at least the gold memory", results.isNotEmpty())
        assertEquals("gold-rule", results.first().key)
        assertTrue("expected a positive score", results.first().score > 0.5f)
        // The orthogonal cake memory is below the floor and must not appear.
        assertFalse(results.any { it.key == "cake-rule" })
    }

    @Test
    fun `recall with the real local provider finds lexically related memory`() = runBlocking {
        val memory = semanticMemory(LocalHashEmbeddingProvider(dimensions = 256))
        memory.storeFact("xauusd-entry-rule", "Always check XAU USD support levels before gold entries")
        memory.storeFact("browser-shortcut", "The browser opens fastest from the dock icon")

        val hits = memory.recall("what is my gold XAU USD entry rule?", topK = 2, minScore = 0.05f)

        assertTrue("expected the gold rule in results", hits.any { it.key == "xauusd-entry-rule" })
        assertEquals("xauusd-entry-rule", hits.first().key)
    }

    @Test
    fun `forget removes the memory and its embedding`() = runBlocking {
        val memory = semanticMemory(LocalHashEmbeddingProvider())
        memory.storeFact("temp-rule", "temporary value")
        assertTrue(memory.recall("temporary value").isNotEmpty())

        memory.forget("temp-rule")

        assertEquals(null, db.memoryDao().getMemoryByKey("temp-rule"))
        assertEquals(null, db.memoryEmbeddingDao().byKey("temp-rule"))
        assertTrue(memory.recall("temporary value").isEmpty())
    }

    @Test
    fun `expired memories are never recalled`() = runBlocking {
        // Insert a memory that expired an hour ago plus its embedding row
        // directly (the public store API always stores fresh timestamps).
        val now = System.currentTimeMillis()
        db.memoryDao().insertOrUpdateMemory(
            MemoryEntity(
                key = "stale-fact",
                value = "a fact that expired",
                type = "SEMANTIC",
                timestamp = now - 2 * 3600_000,
                ttlHours = 1
            )
        )
        val provider = LocalHashEmbeddingProvider(dimensions = 64)
        val vector = provider.embed("a fact that expired")
        db.memoryEmbeddingDao().upsert(
            MemoryEmbeddingEntity(
                memoryKey = "stale-fact",
                embedding = VectorMemoryMath.pack(vector),
                dims = vector.size,
                model = provider.modelId,
                updatedAt = now - 3600_000
            )
        )

        val memory = semanticMemory(provider)
        assertTrue(memory.recall("a fact that expired").isEmpty())
    }

    @Test
    fun `recall is bounded by topK`() = runBlocking {
        val fake = FakeEmbeddingProvider()
        val memory = semanticMemory(fake)
        repeat(6) { i ->
            fake.vectors["mem-$i memory number $i"] = floatArrayOf(1f, 0f, 0f)
            memory.storeFact("mem-$i", "memory number $i")
        }
        fake.vectors["query"] = floatArrayOf(1f, 0f, 0f)
        val results = memory.recall("query", topK = 3, minScore = 0f)
        assertEquals(3, results.size)
    }

    @Test
    fun `embeddings from a different model dimensionality are skipped`() = runBlocking {
        val fake = FakeEmbeddingProvider()
        val memory = semanticMemory(fake)
        fake.vectors["wide-fact a fact embedded at 8 dims"] = FloatArray(8) { 1f }
        memory.storeFact("wide-fact", "a fact embedded at 8 dims")
        // The active provider is 3-dim; the 8-dim row must never mis-cosine.
        fake.vectors["query"] = floatArrayOf(1f, 0f, 0f)
        assertTrue(memory.recall("query").isEmpty())
    }
}
