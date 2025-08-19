package edu.upt.assistant.domain.memory

import android.util.Log
import edu.upt.assistant.data.local.db.MemoryDao
import edu.upt.assistant.data.local.db.MemoryEntity
import edu.upt.assistant.domain.rag.VectorStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryRepository @Inject constructor(
    private val memoryDao: MemoryDao,
    private val vectorStore: VectorStore
) {
    companion object {
        private const val TAG = "MemoryRepository"
        private const val MIN_SIMILARITY_THRESHOLD = 0.2f // Lower threshold for personal memories
    }

    suspend fun addMemory(
        content: String,
        title: String? = null,
        tags: List<String> = listOf("personal"),
        keywords: List<String> = emptyList(),
        importance: Int = 3
    ): String {
        Log.d(TAG, "Adding memory: $content")
        
        val memory = MemoryEntity(
            title = title,
            content = content.trim(),
            keywords = keywords.joinToString(","),
            tags = tags.joinToString(","),
            importance = importance
        )
        
        memoryDao.upsert(memory)
        Log.d(TAG, "Memory added successfully: ${memory.id}")
        
        return memory.id
    }

    suspend fun search(query: String, topK: Int = 3): List<MemoryEntity> {
        Log.d(TAG, "Searching memories for query: $query")
        
        // Get query embedding
        val queryEmbedding = vectorStore.generateEmbedding(query)
        
        // Get all memories
        val memories = memoryDao.getAll().first()
        
        if (memories.isEmpty()) {
            Log.d(TAG, "No memories found in database")
            return emptyList()
        }
        
        // Generate embeddings for all memory contents and calculate similarities
        val similarities = memories.map { memory ->
            val memoryEmbedding = vectorStore.generateEmbedding(memory.content)
            val similarity = vectorStore.cosineSimilarity(queryEmbedding, memoryEmbedding)
            
            MemoryMatch(
                memory = memory,
                similarity = similarity
            )
        }
        
        // Filter by similarity threshold and sort by combined score
        val results = similarities
            .filter { it.similarity >= MIN_SIMILARITY_THRESHOLD }
            .sortedByDescending { 
                // Combined score: similarity weighted by importance and recency
                val recencyScore = (System.currentTimeMillis() - it.memory.updatedAt) / (1000 * 60 * 60 * 24.0) // days ago
                val recencyWeight = maxOf(0.1, 1.0 - (recencyScore / 30.0)) // decay over 30 days
                it.similarity * 0.7 + (it.memory.importance / 5.0) * 0.2 + recencyWeight * 0.1
            }
            .take(topK)
            .map { it.memory }
        
        Log.d(TAG, "Found ${results.size} relevant memories")
        return results
    }

    fun getAllMemories(): Flow<List<MemoryEntity>> = memoryDao.getAll()

    suspend fun getMemoryCount(): Int = memoryDao.count()

    suspend fun deleteMemory(id: String) {
        Log.d(TAG, "Deleting memory: $id")
        memoryDao.delete(id)
    }

    suspend fun getMemoryById(id: String): MemoryEntity? = memoryDao.getById(id)

    suspend fun updateMemory(memory: MemoryEntity) {
        val updated = memory.copy(updatedAt = System.currentTimeMillis())
        memoryDao.upsert(updated)
        Log.d(TAG, "Memory updated: ${memory.id}")
    }
}

data class MemoryMatch(
    val memory: MemoryEntity,
    val similarity: Float
)