package edu.upt.assistant.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey val id: String,
    val title: String,
    val content: String,
    val contentType: String = "text/plain",
    val chunks: String, // JSON array of text chunks
    val embeddings: String, // JSON array of embedding vectors
    val metadata: String = "{}", // JSON metadata
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)