package com.tsfdroid.ai.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tsfdroid.ai.data.db.entities.MemoryEmbeddingEntity

/**
 * God-Tier Phase 2 (5.1): vector storage for semantic recall.
 *
 * MVP scale is a brute-force scan — every stored embedding is loaded and
 * cosined in memory (SQLite has no vector type on this minSdk). The API is
 * kept deliberately small: upsert, point read, full scan, delete, count.
 */
@Dao
interface MemoryEmbeddingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(embedding: MemoryEmbeddingEntity)

    @Query("SELECT * FROM memory_embeddings WHERE `memoryKey` = :key LIMIT 1")
    suspend fun byKey(key: String): MemoryEmbeddingEntity?

    @Query("SELECT * FROM memory_embeddings")
    suspend fun getAll(): List<MemoryEmbeddingEntity>

    @Query("DELETE FROM memory_embeddings WHERE `memoryKey` = :key")
    suspend fun deleteByKey(key: String)

    @Query("SELECT COUNT(*) FROM memory_embeddings")
    suspend fun count(): Int
}
