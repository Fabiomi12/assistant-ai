package edu.upt.assistant.domain

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import edu.upt.assistant.R
import edu.upt.assistant.data.SettingsKeys
import edu.upt.assistant.data.metrics.GenerationMetrics
import edu.upt.assistant.data.metrics.MetricsLogger
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
    val text: String,
    val category: String,
    val expected: String
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
        val modelUrls = dataStore.data.map { prefs ->
            prefs[SettingsKeys.MODEL_URLS] ?: setOf(ModelDownloadManager.DEFAULT_MODEL_URL)
        }.first()
        val models = modelDownloadManager.getAllModelInfo(modelUrls)

        val threadOptions = listOf(6, 8)
        val ragOptions = listOf(false, true)
        val memoryOptions = listOf(false, true)
        val tokenOptions = listOf(64, 128)

        for (model in models) {
            dataStore.edit { it[SettingsKeys.SELECTED_MODEL] = model.url }
            for (threads in threadOptions) {
                for (rag in ragOptions) {
                    dataStore.edit { it[SettingsKeys.RAG_ENABLED] = rag }
                    for (memory in memoryOptions) {
                        dataStore.edit { it[SettingsKeys.AUTO_SAVE_MEMORIES] = memory }
                        for (maxTokens in tokenOptions) {
                            for (prompt in prompts) {
                                val conversationId = "bench-${prompt.id}-${model.fileName}-t${threads}-rag${rag}-mem${memory}-tok${maxTokens}"
                                val timestamp = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date())
                                chatRepository.createConversation(
                                    Conversation(
                                        id = conversationId,
                                        title = conversationId,
                                        lastMessage = prompt.text,
                                        timestamp = timestamp
                                    )
                                )
                                val start = System.currentTimeMillis()
                                var firstToken: Long? = null
                                val builder = StringBuilder()
                                chatRepository.sendMessage(conversationId, prompt.text).collect { token ->
                                    if (firstToken == null) firstToken = System.currentTimeMillis()
                                    builder.append(token)
                                }
                                val end = System.currentTimeMillis()
                                val prefill = (firstToken ?: end) - start
                                val decode = end - (firstToken ?: end)
                                val decodeSpeed = if (decode > 0) (builder.length / 4.0) / (decode / 1000.0) else 0.0
                                val metrics = GenerationMetrics(
                                    timestamp = end,
                                    prefillTimeMs = prefill,
                                    firstTokenDelayMs = prefill,
                                    decodeSpeed = decodeSpeed,
                                    batteryDelta = 0f,
                                    startTempC = 0f,
                                    endTempC = 0f,
                                    promptChars = prompt.text.length,
                                    promptTokens = prompt.text.length / 4,
                                    outputTokens = builder.length / 4,
                                    promptId = prompt.id,
                                    category = prompt.category,
                                    ragEnabled = rag,
                                    memoryEnabled = memory,
                                    topK = 0,
                                    maxTokens = maxTokens,
                                    nThreads = threads,
                                    nBatch = 0,
                                    nUbatch = 0,
                                    model = model.fileName
                                )
                                MetricsLogger.log(context, metrics)
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
        return Json.decodeFromString(json)
    }
}

