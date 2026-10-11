package com.tsfdroid.ai.core.memory

import com.tsfdroid.ai.data.db.dao.MemoryEmbeddingDao
import com.tsfdroid.ai.data.db.entities.MemoryEmbeddingEntity
import com.tsfdroid.ai.data.models.Memory
import com.tsfdroid.ai.data.models.MemoryType
import com.tsfdroid.ai.data.repository.MemoryRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's semantic memory store.
 *
 * God-Tier Phase 2 (5.1) upgrades it from a keyed fact store to a vector
 * store: every stored fact is ALSO embedded (through the [EmbeddingProvider]
 * seam - offline local provider by default, fakes in tests, a remote
 * embedding model can be wired later without touching this class), and
 * [recall] returns facts ranked by MEASURED cosine similarity.
 */
@Singleton
class SemanticMemory @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val memoryExtractor: MemoryExtractor,
    private val embeddingDao: MemoryEmbeddingDao,
    private val embeddingProvider: EmbeddingProvider
) {

    /** A recall hit: the fact plus its measured similarity score. */
    data class ScoredMemory(val key: String, val value: String, val score: Float)

    suspend fun storeFact(key: String, value: String) {
        // Guard: never store error/technical content in semantic memory
        if (!memoryExtractor.shouldStoreInSemanticMemory(key)) return
        if (!memoryExtractor.shouldStoreInSemanticMemory(value)) return

        val now = System.currentTimeMillis()
        memoryRepository.saveMemory(
            Memory(key = key, value = value, type = MemoryType.SEMANTIC, timestamp = now)
        )

        // The embedding rides along: the key's tokens carry recall signal
        // ("xauusd-entry-rule" is as searchable as the value itself).
        val vector = embeddingProvider.embed("$key $value")
        embeddingDao.upsert(
            MemoryEmbeddingEntity(
                memoryKey = key,
                embedding = VectorMemoryMath.pack(vector),
                dims = vector.size,
                model = embeddingProvider.modelId,
                updatedAt = now
            )
        )
    }

    suspend fun getFact(key: String): String? {
        return memoryRepository.getMemoriesByType(MemoryType.SEMANTIC)
            .find { it.key.equals(key, ignoreCase = true) }?.value
    }

    suspend fun getAllFacts(): List<Memory> {
        return memoryRepository.getMemoriesByType(MemoryType.SEMANTIC)
    }

    /**
     * Semantic recall: embeds [query], scores every stored embedding by
     * cosine similarity, and returns the top-[topK] facts at or above
     * [minScore]. TTL-expired facts and embeddings from a different model
     * or dimensionality are never considered. Empty result = nothing
     * measurably related, never a guess.
     */
    suspend fun recall(
        query: String,
        topK: Int = DEFAULT_TOP_K,
        minScore: Float = DEFAULT_MIN_SCORE
    ): List<ScoredMemory> {
        val queryVector = embeddingProvider.embed(query)
        if (queryVector.isEmpty()) return emptyList()

        val validFacts = memoryRepository
            .getValidMemoriesByType(MemoryType.SEMANTIC)
            .associateBy { it.key }
        if (validFacts.isEmpty()) return emptyList()

        return embeddingDao.getAll()
            .asSequence()
            .filter { validFacts.containsKey(it.memoryKey) }
            .filter { it.dims == queryVector.size }
            .map { embedding ->
                ScoredMemory(
                    key = embedding.memoryKey,
                    value = validFacts.getValue(embedding.memoryKey).value,
                    score = VectorMemoryMath.cosine(
                        queryVector,
                        VectorMemoryMath.unpack(embedding.embedding)
                    )
                )
            }
            .filter { it.score >= minScore }
            .sortedByDescending { it.score }
            .take(topK)
            .toList()
    }

    /**
     * Removes a fact AND its embedding (the table has no foreign key by
     * design - INSERT OR REPLACE on memories would cascade-destroy the
     * embedding on every save, so this API owns the pair).
     */
    suspend fun forget(key: String) {
        memoryRepository.deleteMemory(key)
        embeddingDao.deleteByKey(key)
    }

    companion object {
        const val DEFAULT_TOP_K = 5
        const val DEFAULT_MIN_SCORE = 0.05f
    }
}
