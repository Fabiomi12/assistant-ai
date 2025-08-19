package edu.upt.assistant.data.local.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(mem: MemoryEntity)

    @Query("DELETE FROM memory WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM memory ORDER BY updatedAt DESC")
    fun getAll(): Flow<List<MemoryEntity>>

    @Query("SELECT COUNT(*) FROM memory")
    suspend fun count(): Int

    @Query("SELECT * FROM memory WHERE id = :id")
    suspend fun getById(id: String): MemoryEntity?

    @Query("SELECT * FROM memory WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<MemoryEntity>
}