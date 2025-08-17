package edu.upt.assistant.domain

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import edu.upt.assistant.data.SettingsKeys
import edu.upt.assistant.domain.ModelDownloadManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
  private val dataStore: DataStore<Preferences>
) : ViewModel() {

  // Reactive userName flow
  val username: StateFlow<String> = dataStore.data
    .map { prefs -> prefs[SettingsKeys.USERNAME] ?: "" }
    .stateIn(viewModelScope, SharingStarted.Eagerly, "")

  // Reactive notificationsEnabled flow
  val notificationsEnabled: StateFlow<Boolean> = dataStore.data
    .map { prefs -> prefs[SettingsKeys.NOTIFICATIONS] ?: false }
    .stateIn(viewModelScope, SharingStarted.Eagerly, false)

  val setupDone: StateFlow<Boolean> = dataStore.data
    .map { prefs -> prefs[SettingsKeys.SETUP_DONE] ?: false }
    .stateIn(viewModelScope, SharingStarted.Eagerly, false)

  val modelUrls: StateFlow<Set<String>> = dataStore.data
    .map { prefs -> prefs[SettingsKeys.MODEL_URLS] ?: setOf(ModelDownloadManager.DEFAULT_MODEL_URL) }
    .stateIn(viewModelScope, SharingStarted.Eagerly, setOf(ModelDownloadManager.DEFAULT_MODEL_URL))

  val activeModel: StateFlow<String> = dataStore.data
    .map { prefs -> prefs[SettingsKeys.SELECTED_MODEL] ?: ModelDownloadManager.DEFAULT_MODEL_URL }
    .stateIn(viewModelScope, SharingStarted.Eagerly, ModelDownloadManager.DEFAULT_MODEL_URL)

  // Update username
  fun setUsername(name: String) = viewModelScope.launch {
    dataStore.edit { prefs ->
      prefs[SettingsKeys.USERNAME] = name
    }
  }

  // Toggle notifications
  fun setNotificationsEnabled(enabled: Boolean) = viewModelScope.launch {
    dataStore.edit { prefs ->
      prefs[SettingsKeys.NOTIFICATIONS] = enabled
    }
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
    dataStore.edit { prefs ->
      val current = prefs[SettingsKeys.MODEL_URLS] ?: emptySet()
      val updated = current - url
      prefs[SettingsKeys.MODEL_URLS] = updated
      if (prefs[SettingsKeys.SELECTED_MODEL] == url) {
        prefs[SettingsKeys.SELECTED_MODEL] = updated.firstOrNull()
          ?: ModelDownloadManager.DEFAULT_MODEL_URL
      }
    }
  }
}
