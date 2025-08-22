package edu.upt.assistant.domain.rag

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class DocumentProcessor {
    
    companion object {
        private const val TAG = "DocumentProcessor"
        // RAG-friendly defaults: 512–800 char chunks with 50–80% overlap
        private const val CHUNK_SIZE = 700
        private const val CHUNK_OVERLAP = 420 // ~60% overlap
    }

    suspend fun processDocument(
        title: String,
        content: String,
        contentType: String = "text/plain"
    ): ProcessedDocument = withContext(Dispatchers.Default) {
        Log.d(TAG, "Processing document: $title")
        
        val chunks = chunkText(content)
        Log.d(TAG, "Created ${chunks.size} chunks")
        
        ProcessedDocument(
            id = UUID.randomUUID().toString(),
            title = title,
            content = content,
            contentType = contentType,
            chunks = chunks
        )
    }
    
    private fun chunkText(text: String): List<String> {
        if (text.length <= CHUNK_SIZE) {
            return listOf(text)
        }
        
        val chunks = mutableListOf<String>()
        var start = 0
        
        while (start < text.length) {
            val end = minOf(start + CHUNK_SIZE, text.length)
            var actualEnd = end
            
            // Try to break at sentence boundary if possible
            if (end < text.length) {
                val lastPeriod = text.lastIndexOf('.', end)
                val lastNewline = text.lastIndexOf('\n', end)
                val lastSpace = text.lastIndexOf(' ', end)
                
                // Use the closest sentence boundary within reasonable range
                when {
                    lastPeriod > start + CHUNK_SIZE / 2 -> actualEnd = lastPeriod + 1
                    lastNewline > start + CHUNK_SIZE / 2 -> actualEnd = lastNewline + 1
                    lastSpace > start + CHUNK_SIZE / 2 -> actualEnd = lastSpace + 1
                }
            }
            
            chunks.add(text.substring(start, actualEnd).trim())
            start = if (actualEnd >= text.length) {
                actualEnd
            } else {
                maxOf(actualEnd - CHUNK_OVERLAP, 0)
            }
        }
        
        return chunks
    }
}

data class ProcessedDocument(
    val id: String,
    val title: String,
    val content: String,
    val contentType: String,
    val chunks: List<String>
)