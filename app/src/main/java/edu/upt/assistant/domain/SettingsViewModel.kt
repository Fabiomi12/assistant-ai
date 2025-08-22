package edu.upt.assistant.domain

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import edu.upt.assistant.data.SettingsKeys
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ModelManagementState(
  val models: List<ModelInfo> = emptyList(),
  val activeModelUrl: String = ModelDownloadManager.DEFAULT_MODEL_URL,
  val hasActiveDownloads: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
  private val dataStore: DataStore<Preferences>,
  private val downloadManager: ModelDownloadManager
) : ViewModel() {

  // Basic settings
  val username: StateFlow<String> = dataStore.data
    .map { prefs -> prefs[SettingsKeys.USERNAME] ?: "" }
    .stateIn(viewModelScope, SharingStarted.Eagerly, "")

  val notificationsEnabled: StateFlow<Boolean> = dataStore.data
    .map { prefs -> prefs[SettingsKeys.NOTIFICATIONS] ?: false }
    .stateIn(viewModelScope, SharingStarted.Eagerly, false)

  val ragEnabled: StateFlow<Boolean> = dataStore.data
    .map { prefs -> prefs[SettingsKeys.RAG_ENABLED] ?: true }
    .stateIn(viewModelScope, SharingStarted.Eagerly, true)

  val autoSaveMemories: StateFlow<Boolean> = dataStore.data
    .map { prefs -> prefs[SettingsKeys.AUTO_SAVE_MEMORIES] ?: false }
    .stateIn(viewModelScope, SharingStarted.Eagerly, false)

  val setupDone: StateFlow<Boolean> = dataStore.data
    .map { prefs -> prefs[SettingsKeys.SETUP_DONE] ?: false }
    .stateIn(viewModelScope, SharingStarted.Eagerly, false)

  private val modelUrls: StateFlow<Set<String>> = dataStore.data
    .map { prefs -> prefs[SettingsKeys.MODEL_URLS] ?: setOf(ModelDownloadManager.DEFAULT_MODEL_URL) }
    .stateIn(viewModelScope, SharingStarted.Eagerly, setOf(ModelDownloadManager.DEFAULT_MODEL_URL))

  val activeModelUrl: StateFlow<String> = dataStore.data
    .map { prefs -> prefs[SettingsKeys.SELECTED_MODEL] ?: ModelDownloadManager.DEFAULT_MODEL_URL }
    .stateIn(viewModelScope, SharingStarted.Eagerly, ModelDownloadManager.DEFAULT_MODEL_URL)

  // Enhanced model management state that persists across navigation
  val modelManagementState: StateFlow<ModelManagementState> = combine(
    modelUrls,
    activeModelUrl,
    downloadManager.downloadStates,
    downloadManager.downloadProgress
  ) { urls, activeUrl, downloadStates, _ ->
    ModelManagementState(
      models = downloadManager.getAllModelInfo(urls),
      activeModelUrl = activeUrl,
      hasActiveDownloads = downloadStates.values.any { it == ModelState.Downloading }
    )
  }.stateIn(viewModelScope, SharingStarted.Eagerly, ModelManagementState())

  fun getDownloadManager(): ModelDownloadManager = downloadManager

  fun setUsername(name: String) = viewModelScope.launch {
    dataStore.edit { prefs -> prefs[SettingsKeys.USERNAME] = name }
  }

  fun setNotificationsEnabled(enabled: Boolean) = viewModelScope.launch {
    dataStore.edit { prefs -> prefs[SettingsKeys.NOTIFICATIONS] = enabled }
  }

  fun setRagEnabled(enabled: Boolean) = viewModelScope.launch {
    dataStore.edit { prefs -> prefs[SettingsKeys.RAG_ENABLED] = enabled }
  }

  fun setAutoSaveMemories(enabled: Boolean) = viewModelScope.launch {
    dataStore.edit { prefs -> prefs[SettingsKeys.AUTO_SAVE_MEMORIES] = enabled }
  }

  fun setActiveModel(url: String) = viewModelScope.launch {
    dataStore.edit { prefs ->
      val urls = prefs[SettingsKeys.MODEL_URLS] ?: emptySet()
      if (urls.contains(url)) {
        prefs[SettingsKeys.SELECTED_MODEL] = url
      }
    }
  }

  fun addModelUrl(url: String) = viewModelScope.launch {
    dataStore.edit { prefs ->
      val current = prefs[SettingsKeys.MODEL_URLS] ?: emptySet()
      prefs[SettingsKeys.MODEL_URLS] = current + url
    }
  }

  fun removeModelUrl(url: String) = viewModelScope.launch {
    // Cancel download and delete model
    downloadManager.cancelDownload(url)
    downloadManager.deleteModel(url)

    // Remove from preferences
    dataStore.edit { prefs ->
      val current = prefs[SettingsKeys.MODEL_URLS] ?: emptySet()
      val updated = current - url
      prefs[SettingsKeys.MODEL_URLS] = updated

      // Update active model if this was the active one
      if (prefs[SettingsKeys.SELECTED_MODEL] == url) {
        prefs[SettingsKeys.SELECTED_MODEL] = updated.firstOrNull()
          ?: ModelDownloadManager.DEFAULT_MODEL_URL
      }
    }
  }

  fun startDownload(url: String) = viewModelScope.launch {
    downloadManager.startDownload(url, viewModelScope)
  }

  fun cancelDownload(url: String) = viewModelScope.launch {
    downloadManager.cancelDownload(url)
  }

  fun deleteModel(url: String) = viewModelScope.launch {
    downloadManager.deleteModel(url)
  }
}
