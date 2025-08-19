package edu.upt.assistant.domain.memory

object KeywordExtractor {
    private val stopWords = setOf(
        "a", "an", "the", "and", "or", "but", "for", "to", "of", "in", "on", "at", "by", "with", 
        "is", "are", "am", "was", "were", "be", "been", "being", "have", "has", "had", "do", "does", "did",
        "my", "your", "our", "their", "his", "her", "its", "that", "this", "these", "those", "what", "which",
        "who", "when", "where", "why", "how", "can", "could", "will", "would", "should", "may", "might",
        "i", "you", "he", "she", "it", "we", "they", "me", "him", "her", "us", "them", "myself", "yourself"
    )

    fun extract(text: String, max: Int = 6): List<String> {
        val words = text
            .lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { word -> 
                word.isNotBlank() && 
                word !in stopWords && 
                word.length > 2 
            }

        return words
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(max)
            .map { it.key }
    }

    fun extractFromPersonalStatement(text: String): List<String> {
        val personalKeywords = mutableListOf<String>()
        val lowerText = text.lowercase()

        // Extract hobbies/interests
        val hobbyPatterns = listOf(
            Regex("""my hobbies? (?:are?|include)?\s*([^.!?]+)"""),
            Regex("""i (?:like|enjoy|love)\s+([^.!?]+)"""),
            Regex("""i'm (?:into|interested in)\s+([^.!?]+)""")
        )

        hobbyPatterns.forEach { pattern ->
            pattern.findAll(lowerText).forEach { match ->
                val extracted = match.groupValues[1]
                    .split(Regex("[,&]|\\s+and\\s+"))
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it.length > 2 }
                personalKeywords.addAll(extracted)
            }
        }

        // Extract preferences
        val prefPatterns = listOf(
            Regex("""i prefer ([^.!?]+)"""),
            Regex("""my favorite ([^.!?]+)"""),
            Regex("""i usually ([^.!?]+)""")
        )

        prefPatterns.forEach { pattern ->
            pattern.findAll(lowerText).forEach { match ->
                personalKeywords.add(match.groupValues[1].trim())
            }
        }

        return personalKeywords.distinct().take(8)
    }
}