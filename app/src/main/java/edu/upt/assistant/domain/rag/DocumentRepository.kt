package edu.upt.assistant.domain.rag

import android.util.Log
import edu.upt.assistant.data.local.db.DocumentDao
import edu.upt.assistant.data.local.db.DocumentEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocumentRepository @Inject constructor(
    private val documentDao: DocumentDao,
    private val documentProcessor: DocumentProcessor,
    private val vectorStore: VectorStore
) {
    
    companion object {
        private const val TAG = "DocumentRepository"
        private const val MIN_SIMILARITY_THRESHOLD = 0.3f // Minimum similarity score to consider a chunk relevant
    }
    
    private val json = Json { ignoreUnknownKeys = true }
    
    suspend fun initialize() {
        vectorStore.initialize()
    }
    
    fun getAllDocuments(): Flow<List<RagDocument>> {
        return documentDao.getAllDocuments().map { entities ->
            entities.map { entity ->
                RagDocument(
                    id = entity.id,
                    title = entity.title,
                    content = entity.content,
                    contentType = entity.contentType,
                    chunks = json.decodeFromString(entity.chunks),
                    embeddings = json.decodeFromString<List<List<Float>>>(entity.embeddings)
                        .map { it.toFloatArray() },
                    metadata = emptyMap(),
                    createdAt = entity.createdAt,
                    updatedAt = entity.updatedAt
                )
            }
        }
    }
    
    suspend fun addDocument(title: String, content: String, contentType: String = "text/plain"): String {
        Log.d(TAG, "Adding document: $title")
        
        val processed = documentProcessor.processDocument(title, content, contentType)
        
        // Generate embeddings for each chunk
        val embeddings = processed.chunks.map { chunk ->
            vectorStore.generateEmbedding(chunk)
        }
        
        val entity = DocumentEntity(
            id = processed.id,
            title = processed.title,
            content = processed.content,
            contentType = processed.contentType,
            chunks = json.encodeToString(processed.chunks),
            embeddings = json.encodeToString(embeddings.map { it.toList() }),
            metadata = "{}"
        )
        
        documentDao.insertDocument(entity)
        Log.d(TAG, "Document added successfully: ${processed.id}")
        
        return processed.id
    }
    
    suspend fun deleteDocument(documentId: String) {
        Log.d(TAG, "Deleting document: $documentId")
        documentDao.deleteDocumentById(documentId)
    }
    
    suspend fun searchSimilarContent(
        query: String, 
        topK: Int = 3,
        minSimilarity: Float = MIN_SIMILARITY_THRESHOLD
    ): List<RetrievedChunk> {
        Log.d(TAG, "TIMING: Starting similarity search at ${System.currentTimeMillis()}")
        Log.d(TAG, "Searching for similar content: $query")
        
        Log.d(TAG, "TIMING: Starting embedding generation at ${System.currentTimeMillis()}")
        val queryEmbedding = vectorStore.generateEmbedding(query)
        Log.d(TAG, "TIMING: Embedding generation completed at ${System.currentTimeMillis()}")
        Log.d(TAG, "Generated query embedding")
        
        // Get current snapshot of documents instead of collecting indefinitely
        Log.d(TAG, "TIMING: Starting database query at ${System.currentTimeMillis()}")
        val entities = documentDao.getAllDocuments().first()
        Log.d(TAG, "TIMING: Database query completed at ${System.currentTimeMillis()}")
        Log.d(TAG, "Retrieved ${entities.size} documents from database")
        
        Log.d(TAG, "TIMING: Starting document processing at ${System.currentTimeMillis()}")
        // store chunks with their embeddings for deduplication
        val retrievedChunks = mutableListOf<Pair<RetrievedChunk, FloatArray>>()
        
        entities.forEach { entity ->
            try {
                val chunks: List<String> = json.decodeFromString(entity.chunks)
                val embeddings: List<FloatArray> = json.decodeFromString<List<List<Float>>>(entity.embeddings)
                    .map { it.toFloatArray() }
                
                Log.d(TAG, "Processing document '${entity.title}' with ${chunks.size} chunks")
                
                val similarChunks = vectorStore.findSimilarChunks(
                    queryEmbedding = queryEmbedding,
                    chunks = chunks,
                    embeddings = embeddings,
                    topK = topK
                )

                Log.d(TAG, "Found ${similarChunks.size} similar chunks in '${entity.title}'")

                similarChunks.forEach { similar ->
                    val chunk = RetrievedChunk(
                        text = similar.text,
                        similarity = similar.similarity,
                        documentId = entity.id,
                        documentTitle = entity.title,
                        chunkIndex = similar.index
                    )
                    retrievedChunks.add(chunk to embeddings[similar.index])
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing document '${entity.title}'", e)
            }
        }
        
        Log.d(TAG, "TIMING: Starting filtering and sorting at ${System.currentTimeMillis()}")
        val filtered = retrievedChunks
            .filter { it.first.similarity >= minSimilarity }
            .sortedByDescending { it.first.similarity }

        val result = mutableListOf<RetrievedChunk>()
        val usedEmbeddings = mutableListOf<FloatArray>()
        for ((chunk, emb) in filtered) {
            if (usedEmbeddings.none { vectorStore.cosineSimilarity(emb, it) > 0.8f }) {
                result.add(chunk)
                usedEmbeddings.add(emb)
            }
            if (result.size >= topK) break
        }
        
        Log.d(TAG, "TIMING: Search completed at ${System.currentTimeMillis()}")
        Log.d(TAG, "Search completed. ${retrievedChunks.size} total chunks, ${filtered.size} above threshold ($minSimilarity), returning top ${result.size}")
        result.forEach { chunk ->
            Log.d(TAG, "Chunk similarity: ${chunk.similarity} from '${chunk.documentTitle}'")
        }

        if (result.isEmpty()) {
            Log.d(TAG, "No similar chunks found, falling back to keyword search")
            val queryTerms = query.lowercase().split(Regex("\\W+")).filter { it.length > 2 }
            if (queryTerms.isNotEmpty()) {
                val fallback = mutableListOf<RetrievedChunk>()
                entities.forEach { entity ->
                    try {
                        val chunks: List<String> = json.decodeFromString(entity.chunks)
                        chunks.forEachIndexed { idx, text ->
                            val lower = text.lowercase()
                            if (queryTerms.any { lower.contains(it) }) {
                                fallback.add(
                                    RetrievedChunk(
                                        text = text,
                                        similarity = 1.0f,
                                        documentId = entity.id,
                                        documentTitle = entity.title,
                                        chunkIndex = idx
                                    )
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing document '${entity.title}' for fallback", e)
                    }
                }
                return fallback.take(topK)
            }
        }

        return result
    }
    
    suspend fun getDocumentCount(): Int {
        return documentDao.getDocumentCount()
    }
}

data class RagDocument(
    val id: String,
    val title: String,
    val content: String,
    val contentType: String,
    val chunks: List<String>,
    val embeddings: List<FloatArray>,
    val metadata: Map<String, String>,
    val createdAt: Long,
    val updatedAt: Long
)

data class RetrievedChunk(
    val text: String,
    val similarity: Float,
    val documentId: String,
    val documentTitle: String,
    val chunkIndex: Int
)