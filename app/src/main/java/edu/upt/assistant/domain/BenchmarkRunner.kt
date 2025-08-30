package edu.upt.assistant.domain

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import edu.upt.assistant.R
import edu.upt.assistant.data.SettingsKeys
import android.util.Log
import edu.upt.assistant.domain.rag.RagChatRepository
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import edu.upt.assistant.ui.screens.Conversation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class BenchmarkPrompt(
    val id: String,
    val category: String,
    val text: String? = null,
    val expected_regex: String? = null,
    val temp: Double? = null,
    val max_tokens: Int? = null,
    val insert_memory: String? = null,
    val doc_id: String? = null,
    val doc_text: String? = null,
)

@Singleton
class BenchmarkRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chatRepository: ChatRepository,
    private val dataStore: DataStore<Preferences>,
    private val modelDownloadManager: ModelDownloadManager
) {
    suspend fun run() {
        val prompts = loadPrompts()
        val setupPrompts = prompts.filter {
            it.category == "memory_setup" || it.category == "rag_setup"
        }
        val testPrompts = prompts - setupPrompts

        val repo = chatRepository as? RagChatRepository
        for (prompt in setupPrompts) {
            when (prompt.category) {
                "memory_setup" -> prompt.insert_memory?.let { repo?.addMemory(it) }
                "rag_setup" -> if (prompt.doc_id != null && prompt.doc_text != null) {
                    repo?.addDocument(prompt.doc_id, prompt.doc_text)
                }
            }
        }

        val modelUrls = dataStore.data.map { prefs ->
            prefs[SettingsKeys.MODEL_URLS] ?: setOf(ModelDownloadManager.DEFAULT_MODEL_URL)
        }.first()
        val models = modelDownloadManager.getAllModelInfo(modelUrls)

        val threadOptions = listOf(6, 8)
        val ragOptions = listOf(false, true)
        val memoryOptions = listOf(false, true)
        val tokenOptions = listOf(64, 128)

        for (model in models) {
            // Select model (repo will destroy/recreate context on change)
            dataStore.edit { it[SettingsKeys.SELECTED_MODEL] = model.url }

            for (threads in threadOptions) {
                // Make repo honor threads (ensure ChatRepositoryImpl reads this on init)
                dataStore.edit { it[SettingsKeys.N_THREADS] = threads }

                for (rag in ragOptions) {
                    dataStore.edit { it[SettingsKeys.RAG_ENABLED] = rag }

                    for (memory in memoryOptions) {
                        // Set BOTH keys for compatibility
                        dataStore.edit {
                            it[SettingsKeys.MEMORY_ENABLED] = memory     // used by RagChatRepository
                            it[SettingsKeys.AUTO_SAVE_MEMORIES] = memory  // used by Settings UI
                        }

                        for (maxTokens in tokenOptions) {
                            dataStore.edit { it[SettingsKeys.MAX_TOKENS] = maxTokens }

                            for (prompt in testPrompts) {
                                prompt.temp?.let { t ->
                                    dataStore.edit { it[SettingsKeys.TEMP] = t.toFloat() }
                                }
                                prompt.max_tokens?.let { mt ->
                                    dataStore.edit { it[SettingsKeys.MAX_TOKENS] = mt }
                                }

                                val conversationId =
                                    "bench-${prompt.id}-${model.fileName}-t${threads}-rag${rag}-mem${memory}-tok${maxTokens}"
                                val timestamp = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                                    .format(Date())

                                val text = prompt.text ?: ""
                                chatRepository.createConversation(
                                    Conversation(
                                        id = conversationId,
                                        title = conversationId,
                                        lastMessage = text,
                                        timestamp = timestamp
                                    )
                                )

                                // Run and stream output; metrics are logged inside RagChatRepository.
                                val builder = StringBuilder()
                                chatRepository
                                    .sendMessage(conversationId, text)
                                    .collect { token -> builder.append(token) }

                                val out = builder.toString().trim()
                                prompt.expected_regex?.let { rx ->
                                    val passed = Regex(rx, RegexOption.IGNORE_CASE).containsMatchIn(out)
                                    Log.d("BenchmarkRunner", "${prompt.id} => $passed : $out")
                                }
                            }
                        }
                    }
                }
            }
        }
    }


    private fun loadPrompts(): List<BenchmarkPrompt> {
        val stream = context.resources.openRawResource(R.raw.benchmark_prompts)
        val json = stream.bufferedReader().use { it.readText() }
        val jsonCodec = Json { ignoreUnknownKeys = true }
        return jsonCodec.decodeFromString(json)
    }
}

