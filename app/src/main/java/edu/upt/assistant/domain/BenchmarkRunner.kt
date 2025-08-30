package edu.upt.assistant.domain

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import edu.upt.assistant.R
import edu.upt.assistant.data.SettingsKeys
import edu.upt.assistant.data.metrics.GenerationMetrics
import edu.upt.assistant.data.metrics.MetricsLogger
import edu.upt.assistant.domain.rag.RagChatRepository
import edu.upt.assistant.ui.screens.Conversation
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

enum class BenchmarkProfile { FAST, FULL }

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
    private val ragRepository: RagChatRepository,
    private val dataStore: DataStore<Preferences>,
    private val modelDownloadManager: ModelDownloadManager
) {
    // Cache last-applied settings to avoid churny writes
    private var lastModelUrl: String? = null
    private var lastThreads: Int? = null
    private var lastRag: Boolean? = null
    private var lastMem: Boolean? = null
    private var lastTemp: Float? = null
    private var lastMaxTokens: Int? = null

    suspend fun run(profile: BenchmarkProfile = BenchmarkProfile.FAST) {
        val prompts = loadPrompts()
        val setupPrompts = prompts.filter { it.category == "memory_setup" || it.category == "rag_setup" }
        val testPrompts = prompts - setupPrompts

        // --- Ingest setup once (outside any sweeps) ---
        for (p in setupPrompts) {
            when (p.category) {
                "memory_setup" -> p.insert_memory?.let { ragRepository.addMemory(it.trim()) }
                "rag_setup" -> if (p.doc_id != null && p.doc_text != null) {
                    ragRepository.addDocument(p.doc_id, p.doc_text.trim())
                }
            }
        }

        // --- Models to run ---
        val modelUrls = dataStore.data.map { prefs ->
            prefs[SettingsKeys.MODEL_URLS] ?: setOf(ModelDownloadManager.DEFAULT_MODEL_URL)
        }.first()
        val models = modelDownloadManager.getAllModelInfo(modelUrls)

        val tokenSets = if (profile == BenchmarkProfile.FAST) listOf(64) else listOf(64, 128)

        for (model in models) {
            ensureModel(model.url)

            // --- Calibrate threads quickly (e.g., 6 vs 8) ---
            val bestThreads = calibrateThreads(model.fileName, candidates = listOf(6, 8), sampleText =
                testPrompts.firstOrNull { it.category == "general" && !it.text.isNullOrBlank() }?.text ?: "OK"
            )
            ensureThreads(bestThreads)

            for (defaultMaxTokens in tokenSets) {
                ensureMaxTokens(defaultMaxTokens)

                for (prompt in testPrompts) {
                    // Skip malformed entries
                    val text = prompt.text ?: continue

                    // Per-prompt feature gating
                    when (prompt.category) {
                        "general" -> { ensureRag(false); ensureMem(false) }
                        "memory"  -> { ensureRag(false); ensureMem(true)  }
                        "rag"     -> { ensureRag(true);  ensureMem(false) }
                        else -> continue // ignore *_setup
                    }

                    // Per-prompt overrides (only write if changed)
                    prompt.temp?.let { ensureTemp(it.toFloat()) }
                    prompt.max_tokens?.let { ensureMaxTokens(it) }

                    val ragOn = lastRag == true
                    val memOn = lastMem == true
                    val nThreads = lastThreads ?: bestThreads
                    val maxTok = lastMaxTokens ?: defaultMaxTokens

                    // --- Conversation ids ---
                    val baseId = "bench-${prompt.id}-${model.fileName}-t${nThreads}-rag${ragOn}-mem${memOn}-tok${maxTok}"
                    val timestamp = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date())

                    // --- 1) First-token latency (cheap) ---
                    val convFtl = "${baseId}-ftl"
                    chatRepository.createConversation(
                        Conversation(id = convFtl, title = convFtl, lastMessage = text, timestamp = timestamp)
                    )
                    // BEFORE full run:
                    val prevMax = lastMaxTokens
                    ensureMaxTokens(1) // FTL-friendly
                    val ftlMs = measureFirstToken(convFtl, text)
                    ensureMaxTokens(prevMax ?: maxTok) // restore

// FULL RUN BRANCH (regex or FULL profile)
                    if (prompt.expected_regex != null || profile == BenchmarkProfile.FULL) {
                        val convFull = "${baseId}-full"
                        chatRepository.createConversation(Conversation(convFull, convFull, text, timestamp))

                        // let RagChatRepository be source of truth for latency/throughput/battery/temp/etc.
                        val sb = StringBuilder()
                        val start = System.nanoTime()
                        chatRepository.sendMessage(convFull, text).collect { tok -> sb.append(tok) }
                        val out = sb.toString().trim()
                        val totalMs = (System.nanoTime() - start) / 1_000_000

                        // Optional: do ONLY correctness here (no MetricsLogger.log to avoid duplicates)
                        prompt.expected_regex?.let { rx ->
                            val passed = Regex(rx, RegexOption.IGNORE_CASE).matches(out)
                            Log.d("BenchmarkRunner", "${prompt.id} => passed=$passed ftl=${ftlMs}ms total=${totalMs}ms")
                        }
                    } else {
                        // FTL-only entry if you want to keep a tiny log (again, avoid MetricsLogger here)
                        Log.d("BenchmarkRunner", "${prompt.id} => ftl=${ftlMs}ms (FTL only)")
                    }



                    // Restore defaults if you overrode per-prompt
                    prompt.temp?.let { ensureTemp(null) } // remove override → repo default
                    prompt.max_tokens?.let { ensureMaxTokens(defaultMaxTokens) }
                }
            }
        }
    }

    // --- helpers ---

    private suspend fun measureFirstToken(conversationId: String, text: String): Long {
        val start = System.nanoTime()
        val firstTok = chatRepository.sendMessage(conversationId, text).take(1).firstOrNull()
        return if (firstTok == null) -1 else (System.nanoTime() - start) / 1_000_000
    }

    private suspend fun calibrateThreads(
        modelFileName: String,
        candidates: List<Int>,
        sampleText: String
    ): Int {
        var best = candidates.first()
        var bestMs = Long.MAX_VALUE
        ensureRag(false); ensureMem(false)
        ensureMaxTokens(16); ensureTemp(0f)

        for (t in candidates) {
            ensureThreads(t)
            val convId = "calib-$modelFileName-t$t-${System.currentTimeMillis()}"
            val ts = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date())
            chatRepository.createConversation(Conversation(convId, convId, sampleText, ts))
            val ms = measureFirstToken(convId, sampleText)
            if (ms in 1 until bestMs) { bestMs = ms; best = t }
            Log.d("BenchmarkRunner", "Calibrate threads=$t → FTL=${ms}ms")
        }
        Log.d("BenchmarkRunner", "Best threads=$best (FTL=${bestMs}ms)")
        return best
    }

    private suspend fun ensureModel(url: String) {
        if (lastModelUrl == url) return
        dataStore.edit { it[SettingsKeys.SELECTED_MODEL] = url }
        lastModelUrl = url
    }

    private suspend fun ensureThreads(n: Int) {
        if (lastThreads == n) return
        dataStore.edit { it[SettingsKeys.N_THREADS] = n }
        lastThreads = n
    }

    private suspend fun ensureRag(enabled: Boolean) {
        if (lastRag == enabled) return
        dataStore.edit { it[SettingsKeys.RAG_ENABLED] = enabled }
        lastRag = enabled
    }

    private suspend fun ensureMem(enabled: Boolean) {
        if (lastMem == enabled) return
        dataStore.edit {
            it[SettingsKeys.MEMORY_ENABLED] = enabled
            it[SettingsKeys.AUTO_SAVE_MEMORIES] = enabled
        }
        lastMem = enabled
    }

    private suspend fun ensureTemp(value: Float?) {
        val v = value ?: DEFAULT_TEMP
        if (lastTemp == v) return
        dataStore.edit { it[SettingsKeys.TEMP] = v }
        lastTemp = v
    }

    private suspend fun ensureMaxTokens(value: Int) {
        if (lastMaxTokens == value) return
        dataStore.edit { it[SettingsKeys.MAX_TOKENS] = value }
        lastMaxTokens = value
    }

    private fun loadPrompts(): List<BenchmarkPrompt> {
        val stream = context.resources.openRawResource(R.raw.benchmark_prompts)
        val json = stream.bufferedReader().use { it.readText() }
        return Json { ignoreUnknownKeys = true }.decodeFromString(json)
    }

    companion object {
        private const val DEFAULT_TEMP = 0.2f
    }
}
