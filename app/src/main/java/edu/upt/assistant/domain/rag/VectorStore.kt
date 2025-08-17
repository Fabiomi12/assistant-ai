package edu.upt.assistant.domain.rag

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.MappedByteBuffer
import kotlin.math.*

class VectorStore(private val context: Context) {
    
    companion object {
        private const val TAG = "VectorStore"
        private const val MODEL_FILE = "universal_sentence_encoder.tflite"
        private const val EMBEDDING_DIM = 512
    }
    
    private var interpreter: Interpreter? = null
    
    suspend fun initialize() = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Initializing vector store")
            val model = loadModelFile()
            interpreter = Interpreter(model)
            Log.d(TAG, "Vector store initialized successfully with TFLite model")
        } catch (e: Exception) {
            Log.w(TAG, "TFLite model not found, using simple embeddings fallback")
            // Don't throw - just use simple embeddings
        }
    }
    
    private fun loadModelFile(): MappedByteBuffer {
        return try {
            FileUtil.loadMappedFile(context, MODEL_FILE)
        } catch (e: Exception) {
            Log.w(TAG, "TFLite model not found, using simple embeddings")
            throw e
        }
    }
    
    suspend fun generateEmbedding(text: String): FloatArray = withContext(Dispatchers.Default) {
        interpreter?.let { interpreter ->
            try {
                val input = arrayOf(text)
                val output = Array(1) { FloatArray(EMBEDDING_DIM) }
                
                interpreter.run(input, output)
                return@withContext output[0]
            } catch (e: Exception) {
                Log.e(TAG, "Error generating embedding with TFLite", e)
            }
        }
        
        // Fallback to simple hash-based embedding
        generateSimpleEmbedding(text)
    }
    
    private fun generateSimpleEmbedding(text: String): FloatArray {
        val words = text.lowercase().split(Regex("\\W+")).filter { it.isNotEmpty() }
        val embedding = FloatArray(EMBEDDING_DIM)
        
        // Simple word-based feature extraction
        words.forEachIndexed { index, word ->
            val hash = word.hashCode()
            val idx1 = abs(hash) % EMBEDDING_DIM
            val idx2 = abs(hash / 31) % EMBEDDING_DIM
            val idx3 = abs(hash / 37) % EMBEDDING_DIM
            
            embedding[idx1] += 1.0f
            embedding[idx2] += 0.5f
            embedding[idx3] += 0.25f
        }
        
        // Normalize
        val norm = sqrt(embedding.map { it * it }.sum())
        if (norm > 0) {
            for (i in embedding.indices) {
                embedding[i] /= norm
            }
        }
        
        return embedding
    }
    
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        
        var dotProduct = 0f
        var normA = 0f
        var normB = 0f
        
        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        
        val denominator = sqrt(normA) * sqrt(normB)
        return if (denominator > 0) dotProduct / denominator else 0f
    }
    
    fun findSimilarChunks(
        queryEmbedding: FloatArray,
        chunks: List<String>,
        embeddings: List<FloatArray>,
        topK: Int = 3
    ): List<SimilarChunk> {
        val similarities = embeddings.mapIndexed { index, embedding ->
            SimilarChunk(
                text = chunks[index],
                similarity = cosineSimilarity(queryEmbedding, embedding),
                index = index
            )
        }
        
        return similarities
            .sortedByDescending { it.similarity }
            .take(topK)
            .filter { it.similarity > 0.1f } // Minimum similarity threshold
    }
    
    fun close() {
        interpreter?.close()
        interpreter = null
    }
}

data class SimilarChunk(
    val text: String,
    val similarity: Float,
    val index: Int
)