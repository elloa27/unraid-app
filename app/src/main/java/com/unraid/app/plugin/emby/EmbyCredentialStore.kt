package com.unraid.app.plugin.emby

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.unraid.app.data.local.AppErrorLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Emby 서버 URL, AccessToken, UserId 등 민감한 인증 정보를
 * Android Keystore 기반 EncryptedSharedPreferences에 암호화 보관하는 저장소.
 */
@Suppress("DEPRECATION")
class EmbyCredentialStore(context: Context) {

    private val prefs: SharedPreferences = createEncryptedPrefs(context.applicationContext)

    private val _configFlow = MutableStateFlow(readConfig())
    val configFlow: StateFlow<EmbyAuthConfig> = _configFlow.asStateFlow()

    fun getConfig(): EmbyAuthConfig = _configFlow.value

    fun saveServerUrls(serverUrl: String, tailscaleServerUrl: String) {
        val normHome = normalizeEmbyServerUrl(serverUrl)
        val normTs = normalizeEmbyServerUrl(tailscaleServerUrl)
        prefs.edit()
            .putString(KEY_SERVER_URL, normHome)
            .putString(KEY_TAILSCALE_SERVER_URL, normTs)
            .apply()
        _configFlow.value = readConfig()
    }

    fun saveLoginSession(
        serverUrl: String,
        tailscaleServerUrl: String,
        accessToken: String,
        userId: String,
        username: String
    ) {
        val normHome = normalizeEmbyServerUrl(serverUrl)
        val normTs = normalizeEmbyServerUrl(tailscaleServerUrl)
        prefs.edit()
            .putString(KEY_SERVER_URL, normHome)
            .putString(KEY_TAILSCALE_SERVER_URL, normTs)
            .putString(KEY_ACCESS_TOKEN, accessToken.trim())
            .putString(KEY_USER_ID, userId.trim())
            .putString(KEY_USERNAME, username.trim())
            .apply()
        _configFlow.value = readConfig()
    }

    fun setPreferHardwareDecoding(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PREFER_HWDEC, enabled).apply()
        _configFlow.value = readConfig()
    }

    fun logout() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_USERNAME)
            .apply()
        _configFlow.value = readConfig()
    }

    private fun readConfig(): EmbyAuthConfig {
        return EmbyAuthConfig(
            serverUrl = prefs.getString(KEY_SERVER_URL, "").orEmpty(),
            tailscaleServerUrl = prefs.getString(KEY_TAILSCALE_SERVER_URL, "").orEmpty(),
            accessToken = prefs.getString(KEY_ACCESS_TOKEN, "").orEmpty(),
            userId = prefs.getString(KEY_USER_ID, "").orEmpty(),
            username = prefs.getString(KEY_USERNAME, "").orEmpty(),
            preferHardwareDecoding = prefs.getBoolean(KEY_PREFER_HWDEC, true)
        )
    }

    private fun createEncryptedPrefs(appContext: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                appContext,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            AppErrorLogger.logError(
                tag = "EmbySecureStore",
                message = "Emby EncryptedSharedPreferences 초기화 실패 — 안전 폴백 수행",
                throwable = e
            )
            appContext.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    companion object {
        private const val PREFS_FILE_NAME = "emby_secure_credentials"
        private const val FALLBACK_PREFS_NAME = "emby_credentials_fallback"
        private const val KEY_SERVER_URL = "emby_server_url"
        private const val KEY_TAILSCALE_SERVER_URL = "emby_tailscale_server_url"
        private const val KEY_ACCESS_TOKEN = "emby_access_token"
        private const val KEY_USER_ID = "emby_user_id"
        private const val KEY_USERNAME = "emby_username"
        private const val KEY_PREFER_HWDEC = "emby_prefer_hwdec"
    }
}
