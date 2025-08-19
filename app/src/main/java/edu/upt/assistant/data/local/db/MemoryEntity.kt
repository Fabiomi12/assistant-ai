package edu.upt.assistant.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "memory")
data class MemoryEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String? = null,                // optional short label
    val content: String,                      // the fact (1–3 sentences max)
    val keywords: String = "",                // comma-separated, optional
    val tags: String = "personal",            // e.g., "personal,hobby"
    val importance: Int = 3,                  // 1–5 (for ranking/TTL)
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val expiresAt: Long? = null               // nullable TTL
)