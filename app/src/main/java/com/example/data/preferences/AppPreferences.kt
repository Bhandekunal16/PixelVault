package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "image_vault_prefs")

class AppPreferences internal constructor(
    private val context: Context,
    private val accessTokenCipher: TokenCipher
) {
    constructor(context: Context) : this(context, AccessTokenCipher())


    companion object {
        private val KEY_BASE_URL = stringPreferencesKey("base_url")
        private val KEY_USER_EMAIL = stringPreferencesKey("user_email")
        private val KEY_USER_ID = stringPreferencesKey("user_id")
        private val KEY_USER_ROLE = stringPreferencesKey("user_role")
        private val KEY_IS_LOGGED_IN = booleanPreferencesKey("is_logged_in")
        private val KEY_ACCESS_TOKEN = stringPreferencesKey("access_token")
        private val KEY_ENCRYPTION_ENABLED = booleanPreferencesKey("encryption_enabled")
        private val KEY_ENCRYPTION_SECRET = stringPreferencesKey("encryption_secret")
        private val KEY_ENCRYPTION_ALGO = stringPreferencesKey("encryption_algo")
        private val KEY_APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
        private val KEY_LOCK_TIMEOUT_SECONDS = androidx.datastore.preferences.core.longPreferencesKey("lock_timeout_seconds")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")

        // Default configured backend server path
        const val EMULATOR_BASE_URL = "http://10.0.2.2:8000/"
        const val HOSTED_BASE_URL = "http://147.224.251.137:8001/"
        const val DEFAULT_BASE_URL = HOSTED_BASE_URL
        const val LOCALHOST_BASE_URL = "http://localhost:8000/"
    }

    val baseUrlFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_BASE_URL]
            ?.takeUnless { it == EMULATOR_BASE_URL }
            ?: DEFAULT_BASE_URL
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

    val accessTokenFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_ACCESS_TOKEN]?.let(accessTokenCipher::decrypt)
    }

    val isLoggedInFlow: Flow<Boolean> = combine(
        context.dataStore.data.map { prefs -> prefs[KEY_IS_LOGGED_IN] ?: false },
        accessTokenFlow
    ) { isLoggedIn, accessToken ->
        isLoggedIn && !accessToken.isNullOrBlank()
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

    suspend fun saveUserSession(email: String, id: String?, role: String?, accessToken: String) {
        require(accessToken.isNotBlank()) { "A server access token is required to save a session" }
        context.dataStore.edit { prefs ->
            prefs[KEY_USER_EMAIL] = email
            if (id != null) prefs[KEY_USER_ID] = id
            if (role != null) prefs[KEY_USER_ROLE] = role
            prefs[KEY_ACCESS_TOKEN] = accessTokenCipher.encrypt(accessToken)
            prefs[KEY_IS_LOGGED_IN] = true
        }
    }

    suspend fun clearSession() {
        context.dataStore.edit { prefs ->
            clearSession(prefs)
        }
    }

    suspend fun clearSessionIfAccessToken(expectedAccessToken: String) {
        context.dataStore.edit { prefs ->
            val storedToken = prefs[KEY_ACCESS_TOKEN]?.let(accessTokenCipher::decrypt)
            if (storedToken == expectedAccessToken) clearSession(prefs)
        }
    }

    private fun clearSession(prefs: androidx.datastore.preferences.core.MutablePreferences) {
        prefs.remove(KEY_USER_EMAIL)
        prefs.remove(KEY_USER_ID)
        prefs.remove(KEY_USER_ROLE)
        prefs.remove(KEY_ACCESS_TOKEN)
        prefs[KEY_IS_LOGGED_IN] = false
    }

    suspend fun setEncryptionConfig(enabled: Boolean, secret: String?, algo: String?) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ENCRYPTION_ENABLED] = enabled
            if (secret != null) prefs[KEY_ENCRYPTION_SECRET] = secret else prefs.remove(KEY_ENCRYPTION_SECRET)
            if (algo != null) prefs[KEY_ENCRYPTION_ALGO] = algo
        }
    }

    val appLockEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_APP_LOCK_ENABLED] ?: false
    }

    val lockTimeoutSecondsFlow: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_LOCK_TIMEOUT_SECONDS] ?: 0L // Default immediate (0s)
    }

    suspend fun setAppLockEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_APP_LOCK_ENABLED] = enabled
        }
    }

    suspend fun setLockTimeoutSeconds(seconds: Long) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LOCK_TIMEOUT_SECONDS] = seconds
        }
    }

    val themeModeFlow: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        ThemeMode.fromString(prefs[KEY_THEME_MODE])
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = mode.name
        }
    }

    val dynamicColorFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_DYNAMIC_COLOR] ?: (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DYNAMIC_COLOR] = enabled
        }
    }
}
