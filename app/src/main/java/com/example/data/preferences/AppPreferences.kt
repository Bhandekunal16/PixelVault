package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "image_vault_prefs")

class AppPreferences(private val context: Context) {

    companion object {
        private val KEY_BASE_URL = stringPreferencesKey("base_url")
        private val KEY_USER_EMAIL = stringPreferencesKey("user_email")
        private val KEY_USER_ID = stringPreferencesKey("user_id")
        private val KEY_USER_ROLE = stringPreferencesKey("user_role")
        private val KEY_IS_LOGGED_IN = booleanPreferencesKey("is_logged_in")
        private val KEY_ENCRYPTION_ENABLED = booleanPreferencesKey("encryption_enabled")
        private val KEY_ENCRYPTION_SECRET = stringPreferencesKey("encryption_secret")
        private val KEY_ENCRYPTION_ALGO = stringPreferencesKey("encryption_algo")

        // Standard default: 10.0.2.2:8000 for standard Android emulator to host machine
        const val DEFAULT_BASE_URL = "http://10.0.2.2:8000/"
        const val LOCALHOST_BASE_URL = "http://localhost:8000/"
    }

    val baseUrlFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_BASE_URL] ?: DEFAULT_BASE_URL
    }

    val userEmailFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_USER_EMAIL]
    }

    val userIdFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_USER_ID]
    }

    val userRoleFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_USER_ROLE] ?: "user"
    }

    /**
     * Note: As documented, the Express backend does not currently issue JWT tokens or session cookies.
     * This local login state is maintained solely as a client-side convenience.
     */
    val isLoggedInFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_IS_LOGGED_IN] ?: false
    }

    val encryptionEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_ENCRYPTION_ENABLED] ?: false
    }

    val encryptionSecretFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_ENCRYPTION_SECRET]
    }

    val encryptionAlgoFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_ENCRYPTION_ALGO] ?: "AES/CBC/PKCS5Padding"
    }

    suspend fun setBaseUrl(url: String) {
        val normalized = if (!url.endsWith("/")) "$url/" else url
        context.dataStore.edit { prefs ->
            prefs[KEY_BASE_URL] = normalized
        }
    }

    suspend fun saveUserSession(email: String, id: String?, role: String?) {
        context.dataStore.edit { prefs ->
            prefs[KEY_USER_EMAIL] = email
            if (id != null) prefs[KEY_USER_ID] = id
            if (role != null) prefs[KEY_USER_ROLE] = role
            prefs[KEY_IS_LOGGED_IN] = true
        }
    }

    suspend fun clearSession() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_USER_EMAIL)
            prefs.remove(KEY_USER_ID)
            prefs.remove(KEY_USER_ROLE)
            prefs[KEY_IS_LOGGED_IN] = false
        }
    }

    suspend fun setEncryptionConfig(enabled: Boolean, secret: String?, algo: String?) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ENCRYPTION_ENABLED] = enabled
            if (secret != null) prefs[KEY_ENCRYPTION_SECRET] = secret else prefs.remove(KEY_ENCRYPTION_SECRET)
            if (algo != null) prefs[KEY_ENCRYPTION_ALGO] = algo
        }
    }
}
