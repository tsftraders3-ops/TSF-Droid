package com.tsfdroid.ai.data.db.dao

import androidx.room.*
import com.tsfdroid.ai.data.db.entities.MacroEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MacroDao {
    @Query("SELECT * FROM macros")
    fun getAllMacrosFlow(): Flow<List<MacroEntity>>

    @Query("SELECT * FROM macros")
    suspend fun getAllMacros(): List<MacroEntity>

    @Query("SELECT * FROM macros WHERE id = :id LIMIT 1")
    suspend fun getMacroById(id: String): MacroEntity?

    @Query("SELECT * FROM macros WHERE name = :name LIMIT 1")
    suspend fun getMacroByName(name: String): MacroEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMacro(macro: MacroEntity)

    @Query("DELETE FROM macros WHERE id = :id")
    suspend fun deleteMacro(id: String)

    @Query("DELETE FROM macros")
    suspend fun clearAllMacros()
}
