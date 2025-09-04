package me.rhunk.snapenhance.common.ui

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Single DataStore instance for this Context (name must be unique per-process)
private val Context.themeDataStore by preferencesDataStore(name = "theme_prefs")

// App theme modes supported by the UI
enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

object ThemePreferences {
    private val THEME_KEY = stringPreferencesKey("theme_mode")

    // Observe theme mode as a Flow with a default of SYSTEM
    fun getThemeModeFlow(context: Context): Flow<ThemeMode> =
        context.themeDataStore.data.map { prefs ->
            when (prefs[THEME_KEY]) {
                ThemeMode.LIGHT.name -> ThemeMode.LIGHT
                ThemeMode.DARK.name -> ThemeMode.DARK
                ThemeMode.AMOLED.name -> ThemeMode.AMOLED
                else -> ThemeMode.SYSTEM
            }
        }

    // Persist theme mode
    suspend fun setThemeMode(context: Context, mode: ThemeMode) {
        context.themeDataStore.edit { it[THEME_KEY] = mode.name }
    }
}
