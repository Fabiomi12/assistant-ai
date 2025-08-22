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

        val normalized = content.lowercase().replace("\\s+".toRegex(), " ").trim()
        val existing = memoryDao.getAll().first().firstOrNull {
            it.content.lowercase().replace("\\s+".toRegex(), " ").trim() == normalized
        }
        if (existing != null) {
            Log.d(TAG, "Duplicate memory detected, skipping insert: ${existing.id}")
            return existing.id
        }

        val memory = MemoryEntity(
            title = title,
            content = normalized,
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
                similarity = similarity,
                embedding = memoryEmbedding
            )
        }

        // Filter by similarity threshold and sort by relevance
        val candidates = similarities
            .filter { it.similarity >= MIN_SIMILARITY_THRESHOLD }
            .sortedByDescending { it.similarity }
            .take(topK)

        val mmrSelected = applyMmr(candidates, k = 2)
        Log.d(TAG, "Found ${mmrSelected.size} relevant memories after MMR")
        return mmrSelected.map { it.memory }
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
    val similarity: Float,
    val embedding: FloatArray
)

private fun applyMmr(candidates: List<MemoryMatch>, k: Int, lambda: Float = 0.7f): List<MemoryMatch> {
    if (candidates.isEmpty()) return emptyList()
    val selected = mutableListOf<MemoryMatch>()
    val remaining = candidates.toMutableList()

    selected.add(remaining.removeAt(0))
    while (selected.size < k && remaining.isNotEmpty()) {
        val next = remaining.maxByOrNull { cand ->
            val relevance = cand.similarity
            val diversity = selected.maxOf { sel ->
                // cosine similarity between embeddings
                val sim = sel.embedding.zip(cand.embedding) { a, b -> a * b }.sum()
                val normSel = kotlin.math.sqrt(sel.embedding.map { it * it }.sum())
                val normCand = kotlin.math.sqrt(cand.embedding.map { it * it }.sum())
                if (normSel > 0 && normCand > 0) sim / (normSel * normCand) else 0f
            }
            lambda * relevance - (1 - lambda) * diversity
        } ?: break
        remaining.remove(next)
        selected.add(next)
    }
    return selected
}