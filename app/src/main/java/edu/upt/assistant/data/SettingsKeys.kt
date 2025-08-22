package edu.upt.assistant.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

object SettingsKeys {
  val USERNAME = stringPreferencesKey("user_name")
  val NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
  val SETUP_DONE = booleanPreferencesKey("setup_done")
  val INTERESTS = stringPreferencesKey("interests")
  val CUSTOM_INTEREST = stringPreferencesKey("custom_interest")
  val BIRTHDAY = stringPreferencesKey("birthday")
  val MODEL_URLS = stringSetPreferencesKey("model_urls")
  val SELECTED_MODEL = stringPreferencesKey("selected_model")
  val RAG_ENABLED = booleanPreferencesKey("rag_enabled")
  val AUTO_SAVE_MEMORIES = booleanPreferencesKey("auto_save_memories")
}
