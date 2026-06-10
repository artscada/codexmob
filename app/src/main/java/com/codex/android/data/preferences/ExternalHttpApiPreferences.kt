package com.codex.android.data.preferences

import android.content.Context
import java.util.UUID

class ExternalHttpApiPreferences private constructor(
    private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "external_http_api"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "token"
        private const val DEFAULT_PORT = 8094
        private const val MIN_PORT = 1024
        private const val MAX_PORT = 65535

        @Volatile
        private var INSTANCE: ExternalHttpApiPreferences? = null

        fun getInstance(context: Context): ExternalHttpApiPreferences {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ExternalHttpApiPreferences(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getPort(): Int = prefs.getInt(KEY_PORT, DEFAULT_PORT)

    fun setPort(port: Int) {
        val safePort = port.coerceIn(MIN_PORT, MAX_PORT)
        prefs.edit().putInt(KEY_PORT, safePort).apply()
    }

    fun getBearerToken(): String {
        val existing = prefs.getString(KEY_TOKEN, null)?.trim().orEmpty()
        if (existing.isNotBlank()) {
            return existing
        }

        val generated = UUID.randomUUID().toString().replace("-", "")
        prefs.edit().putString(KEY_TOKEN, generated).apply()
        return generated
    }

    fun setBearerToken(token: String) {
        val normalized = token.trim()
        if (normalized.isNotBlank()) {
            prefs.edit().putString(KEY_TOKEN, normalized).apply()
        }
    }

    fun resetBearerToken(): String {
        val generated = UUID.randomUUID().toString().replace("-", "")
        prefs.edit().putString(KEY_TOKEN, generated).apply()
        return generated
    }
}
