package com.echolingo.app.data.preferences

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object PreferencesKeys {
    val SERVER_BASE_URL    = stringPreferencesKey("server_base_url")
    val SHOW_SOURCE        = booleanPreferencesKey("show_source")
    val SHOW_TRANS         = booleanPreferencesKey("show_trans")
    val FONT_SIZE          = stringPreferencesKey("font_size")
    val SUBTITLE_Y_PERCENT = floatPreferencesKey("subtitle_y_percent")
}

