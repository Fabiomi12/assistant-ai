package edu.upt.assistant.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object SettingsKeys {
  val USERNAME = stringPreferencesKey("user_name")
  val NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
}
