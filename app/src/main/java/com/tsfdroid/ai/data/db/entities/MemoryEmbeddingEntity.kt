package com.tsfdroid.ai.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * God-Tier Phase 2 (5.1): vector storage for semantic memory recall.
 *
 * One row per memory: the memory's key plus the packed embedding of its
 * content. Deliberately NO foreign key on memories.key — memories are
 * written with INSERT OR REPLACE, which deletes+reinserts the parent row
 * and would cascade-destroy the embedding on every save; the application
 * layer ([com.tsfdroid.ai.core.memory.SemanticMemory]) keeps both rows in
 * sync instead (store writes both, forget deletes both).
 *
 * [dims] is stored beside the blob so a future provider change (different
 * dimensionality) is detectable at read time instead of mis-cosining.
 * [model] records which embedding model produced the vector; rows from a
 * different model than the active provider are skipped at recall time.
 */
@Entity(tableName = "memory_embeddings")
data class MemoryEmbeddingEntity(
    @PrimaryKey val memoryKey: String,
    val embedding: ByteArray,
    val dims: Int,
    val model: String,
    val updatedAt: Long
) {
    // ByteArray in a data class: identity equality is wrong; compare by key.
    override fun equals(other: Any?): Boolean =
        other is MemoryEmbeddingEntity &&
            other.memoryKey == memoryKey &&
            other.dims == dims &&
            other.model == model &&
            other.updatedAt == updatedAt &&
            other.embedding.contentEquals(embedding)

    override fun hashCode(): Int = memoryKey.hashCode() * 31 + dims
}
