package edu.upt.assistant.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object SettingsKeys {
  val USERNAME = stringPreferencesKey("user_name")
  val NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
  val SETUP_DONE = booleanPreferencesKey("setup_done")
  val INTERESTS = stringPreferencesKey("interests")
  val CUSTOM_INTEREST = stringPreferencesKey("custom_interest")
  val BIRTHDAY = stringPreferencesKey("birthday")
  val MODEL_URL = stringPreferencesKey("model_url")
}
