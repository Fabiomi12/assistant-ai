package edu.upt.assistant.domain.utils

import java.net.URI

object ModelUtils {
    fun fileNameFrom(url: String): String {
        val path = URI(url).path
        return path.substringAfterLast('/').substringBefore('?').takeIf { it.isNotBlank() }
            ?: "model_${url.hashCode()}.gguf"
    }
}