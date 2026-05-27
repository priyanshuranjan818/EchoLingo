package com.echolingo.app.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.echolingo.app.domain.model.FontSize
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "echolingo_settings")

data class AppSettings(
    val serverBaseUrl: String = "http://10.0.2.2:3001/",
    val showSource: Boolean = true,
    val showTrans: Boolean = true,
    val fontSize: FontSize = FontSize.M,
    val subtitleYPercent: Float = 0.78f,
)

class SettingsRepository(private val context: Context) {
    val settings: Flow<AppSettings> =
        context.dataStore.data.map { prefs ->
            AppSettings(
                serverBaseUrl = prefs[PreferencesKeys.SERVER_BASE_URL] ?: "http://10.0.2.2:3001/",
                showSource = prefs[PreferencesKeys.SHOW_SOURCE] ?: true,
                showTrans = prefs[PreferencesKeys.SHOW_TRANS] ?: true,
                fontSize = prefs[PreferencesKeys.FONT_SIZE]?.let { FontSize.valueOf(it) } ?: FontSize.M,
                subtitleYPercent = prefs[PreferencesKeys.SUBTITLE_Y_PERCENT] ?: 0.78f,
            )
        }

    suspend fun setServerBaseUrl(value: String) {
        context.dataStore.edit { it[PreferencesKeys.SERVER_BASE_URL] = value }
    }

    suspend fun setShowSource(value: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.SHOW_SOURCE] = value }
    }

    suspend fun setShowTrans(value: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.SHOW_TRANS] = value }
    }

    suspend fun setSubtitleYPercent(value: Float) {
        context.dataStore.edit { it[PreferencesKeys.SUBTITLE_Y_PERCENT] = value.coerceIn(0.12f, 0.86f) }
    }
}
