package edu.upt.assistant.domain.memory

import android.util.Log
import edu.upt.assistant.data.local.db.MemoryDao
import edu.upt.assistant.data.local.db.MemoryEntity
import edu.upt.assistant.domain.rag.VectorStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.sqrt

@Singleton
class MemoryRepository @Inject constructor(
    private val memoryDao: MemoryDao,
    private val vectorStore: VectorStore
) {
    companion object {
        private const val TAG = "MemoryRepository"
        private const val MIN_SIMILARITY_THRESHOLD = 0.20f // tune as needed
    }

    // In-memory embedding cache (L2-normalized for fast cosine via dot)
    private val embedCache = ConcurrentHashMap<String, FloatArray>()

    init {
        // Best-effort background warm-up so first query isn't O(N) embedding
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val all = memoryDao.getAll().first()
                all.forEach { m ->
                    ensureCached(m)
                }
                Log.d(TAG, "Embedding cache warmed: ${embedCache.size} items")
            } catch (t: Throwable) {
                Log.w(TAG, "Warm-up skipped: ${t.message}")
            }
        }
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

        // de-dup by normalized text
        val existing = memoryDao.getAll().first().firstOrNull {
            it.content.lowercase().replace("\\s+".toRegex(), " ").trim() == normalized
        }
        if (existing != null) {
            Log.d(TAG, "Duplicate memory detected, skipping insert: ${existing.id}")
            ensureCached(existing) // make sure cache has it
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

        // Precompute & cache embedding once
        try {
            val emb = l2Normalize(vectorStore.generateEmbedding(memory.content))
            embedCache[memory.id] = emb
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to embed new memory ${memory.id}: ${t.message}")
        }

        Log.d(TAG, "Memory added successfully: ${memory.id}")
        return memory.id
    }

    suspend fun search(query: String, topK: Int = 3): List<MemoryEntity> {
        Log.d(TAG, "Searching memories for query: $query")

        // 1) Embed query (L2-normalized)
        val q = try {
            l2Normalize(vectorStore.generateEmbedding(query))
        } catch (t: Throwable) {
            Log.e(TAG, "Embedding failed for query", t)
            return emptyList()
        }

        // 2) Load all memories & ensure embeddings are cached (lazy, only missing ones)
        val memories = memoryDao.getAll().first()
        if (memories.isEmpty()) {
            Log.d(TAG, "No memories found in database")
            return emptyList()
        }
        memories.forEach { ensureCached(it) }

        // 3) Score (cosine = dot since everything is L2-normalized)
        val scored = memories.mapNotNull { m ->
            val emb = embedCache[m.id] ?: return@mapNotNull null
            val dot = dot(q, emb)
            MemoryMatch(m, dot, emb)
        }

        if (scored.isEmpty()) return emptyList()

        // 4) Filter by threshold but always keep at least top-1
        val sorted = scored.sortedByDescending { it.similarity }
        val filtered = sorted.filter { it.similarity >= MIN_SIMILARITY_THRESHOLD }
        val base = if (filtered.isEmpty()) listOf(sorted.first()) else filtered

        // 5) Apply MMR with correct K
        val k = max(1, minOf(topK, base.size))
        val mmr = applyMmr(base, k = k, lambda = 0.7f)

        Log.d(TAG, "Found ${mmr.size} relevant memories after MMR (k=$k)")
        return mmr.map { it.memory }
    }

    fun getAllMemories(): Flow<List<MemoryEntity>> = memoryDao.getAll()
    suspend fun getMemoryCount(): Int = memoryDao.count()

    suspend fun deleteMemory(id: String) {
        Log.d(TAG, "Deleting memory: $id")
        memoryDao.delete(id)
        embedCache.remove(id)
    }

    // --- helpers ---

    // Ensure cache has an embedding for this memory (compute if missing)
    private suspend fun ensureCached(m: MemoryEntity) {
        if (embedCache.containsKey(m.id)) return
        try {
            val emb = l2Normalize(vectorStore.generateEmbedding(m.content))
            embedCache[m.id] = emb
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to embed memory ${m.id}: ${t.message}")
        }
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x * x
        val n = sqrt(sum).toFloat()
        if (n == 0f) return v
        val out = FloatArray(v.size)
        for (i in v.indices) out[i] = v[i] / n
        return out
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0.0
        val n = minOf(a.size, b.size)
        for (i in 0 until n) s += a[i] * b[i]
        return s.toFloat()
    }
}

data class MemoryMatch(
    val memory: MemoryEntity,
    val similarity: Float,          // cosine similarity to query
    val embedding: FloatArray       // L2-normalized memory embedding
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MemoryMatch

        if (similarity != other.similarity) return false
        if (memory != other.memory) return false
        if (!embedding.contentEquals(other.embedding)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = similarity.hashCode()
        result = 31 * result + memory.hashCode()
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}

// Maximal Marginal Relevance (MMR) on pre-scored candidates.
// Assumes `similarity` is relevance to the query, and `embedding` are L2-normalized.
// Diversity is penalized by the max cosine between candidate and any selected.
private fun applyMmr(candidates: List<MemoryMatch>, k: Int, lambda: Float = 0.7f): List<MemoryMatch> {
    if (candidates.isEmpty() || k <= 0) return emptyList()
    val selected = mutableListOf<MemoryMatch>()
    val remaining = candidates.toMutableList()

    // Start with the most relevant
    remaining.sortByDescending { it.similarity }
    selected += remaining.removeAt(0)

    while (selected.size < k && remaining.isNotEmpty()) {
        var best: MemoryMatch? = null
        var bestScore = Float.NEGATIVE_INFINITY

        for (cand in remaining) {
            val div = selected.maxOf { sel ->
                // embeddings are L2-normalized → cosine = dot
                var s = 0.0
                val n = minOf(sel.embedding.size, cand.embedding.size)
                for (i in 0 until n) s += sel.embedding[i] * cand.embedding[i]
                s.toFloat()
            }
            val score = lambda * cand.similarity - (1f - lambda) * div
            if (score > bestScore) {
                bestScore = score
                best = cand
            }
        }

        if (best == null) break
        remaining.remove(best)
        selected += best
    }
    return selected
}
