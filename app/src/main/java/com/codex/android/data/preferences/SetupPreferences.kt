package com.codex.android.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.setupDataStore by preferencesDataStore(name = "setup_preferences")

class SetupPreferences(private val context: Context) {

    companion object {
        private val IS_SETUP_COMPLETED = booleanPreferencesKey("is_setup_completed")
        private val IS_FIRST_RUN = booleanPreferencesKey("is_first_run")

        @Volatile
        private var INSTANCE: SetupPreferences? = null

        fun getInstance(context: Context): SetupPreferences {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SetupPreferences(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    val isSetupCompletedFlow: Flow<Boolean> = context.setupDataStore.data.map { prefs ->
        prefs[IS_SETUP_COMPLETED] ?: false
    }

    suspend fun isSetupCompleted(): Boolean {
        return context.setupDataStore.data.first()[IS_SETUP_COMPLETED] ?: false
    }

    suspend fun markSetupCompleted() {
        context.setupDataStore.edit { prefs ->
            prefs[IS_SETUP_COMPLETED] = true
            prefs[IS_FIRST_RUN] = false
        }
    }

    suspend fun resetSetup() {
        context.setupDataStore.edit { prefs ->
            prefs.clear()
        }
    }
}
