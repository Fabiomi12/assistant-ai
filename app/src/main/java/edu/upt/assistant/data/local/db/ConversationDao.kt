package edu.upt.assistant.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY timestamp DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(convo: ConversationEntity): Long

    @Update
    suspend fun update(convo: ConversationEntity)

    /** Upsert without delete→insert so cascade never fires */
    @Transaction
    suspend fun upsert(convo: ConversationEntity) {
        if (insert(convo) == -1L) {
            update(convo)
        }
    }

    @Query("DELETE FROM conversations WHERE id = :convId")
    suspend fun deleteById(convId: String)
}
