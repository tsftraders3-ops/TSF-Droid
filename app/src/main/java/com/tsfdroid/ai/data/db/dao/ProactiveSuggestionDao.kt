package com.tsfdroid.ai.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.tsfdroid.ai.data.db.entities.ProactiveSuggestionEntity
import kotlinx.coroutines.flow.Flow

/**
 * God-Tier Phase 2 (5.4): storage for the proactive background worker's
 * suggestions. The worker WRITES here; a future surface READS them. Kept
 * deliberately small: insert, read-all (flow + one-shot for tests), mark,
 * and prune.
 */
@Dao
interface ProactiveSuggestionDao {

    @Insert
    suspend fun insert(suggestion: ProactiveSuggestionEntity): Long

    @Query("SELECT * FROM proactive_suggestions ORDER BY createdAt DESC")
    fun getAll(): Flow<List<ProactiveSuggestionEntity>>

    @Query("SELECT * FROM proactive_suggestions ORDER BY createdAt DESC")
    suspend fun getAllOnce(): List<ProactiveSuggestionEntity>

    @Query("UPDATE proactive_suggestions SET status = 'READ' WHERE id = :id")
    suspend fun markRead(id: Long)

    @Query("UPDATE proactive_suggestions SET status = 'DISMISSED' WHERE id = :id")
    suspend fun dismiss(id: Long)

    @Query("DELETE FROM proactive_suggestions WHERE createdAt < :before")
    suspend fun pruneOlderThan(before: Long)
}
