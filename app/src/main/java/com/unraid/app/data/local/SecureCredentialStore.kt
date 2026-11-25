package com.unraid.app.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 암호화 저장된 Unraid SMB 자격 증명 스냅샷
 */
data class SmbCredentials(
    val username: String = "",
    val password: String = "",
    val useGuest: Boolean = false,
    val customHost: String = ""
) {
    val isConfigured: Boolean
        get() = useGuest || username.isNotBlank()
}

/**
 * Unraid API 키, Tailscale Auth Key, SMB 계정 정보 등 민감한 인증 정보를
 * Android Keystore 기반 EncryptedSharedPreferences에 암호화하여 안전하게 보관하는 저장소.
 */
@Suppress("DEPRECATION")
class SecureCredentialStore(context: Context) {

    private val prefs: SharedPreferences = createEncryptedPrefs(context.applicationContext)

    private val _apiKeyFlow = MutableStateFlow(prefs.getString(KEY_UNRAID_API_KEY, "").orEmpty())
    val apiKeyFlow: StateFlow<String> = _apiKeyFlow.asStateFlow()

    private val _tailscaleAuthKeyFlow = MutableStateFlow(prefs.getString(KEY_TAILSCALE_AUTH_KEY, "").orEmpty())
    val tailscaleAuthKeyFlow: StateFlow<String> = _tailscaleAuthKeyFlow.asStateFlow()

    private val _smbCredentialsFlow = MutableStateFlow(readSmbCredentials())
    val smbCredentialsFlow: StateFlow<SmbCredentials> = _smbCredentialsFlow.asStateFlow()

    fun getUnraidApiKey(): String = _apiKeyFlow.value

    fun saveUnraidApiKey(apiKey: String) {
        val trimmed = apiKey.trim()
        prefs.edit().putString(KEY_UNRAID_API_KEY, trimmed).apply()
        _apiKeyFlow.value = trimmed
    }

    fun getTailscaleAuthKey(): String = _tailscaleAuthKeyFlow.value

    fun saveTailscaleAuthKey(authKey: String) {
        val trimmed = authKey.trim()
        prefs.edit().putString(KEY_TAILSCALE_AUTH_KEY, trimmed).apply()
        _tailscaleAuthKeyFlow.value = trimmed
    }

    fun getSmbCredentials(): SmbCredentials = _smbCredentialsFlow.value

    fun saveSmbCredentials(
        username: String,
        password: String,
        useGuest: Boolean,
        customHost: String
    ) {
        val cleanUser = username.trim()
        val cleanHost = customHost.trim()
        prefs.edit()
            .putString(KEY_SMB_USERNAME, cleanUser)
            .putString(KEY_SMB_PASSWORD, password)
            .putBoolean(KEY_SMB_USE_GUEST, useGuest)
            .putString(KEY_SMB_CUSTOM_HOST, cleanHost)
            .apply()
        _smbCredentialsFlow.value = SmbCredentials(
            username = cleanUser,
            password = password,
            useGuest = useGuest,
            customHost = cleanHost
        )
    }

    private fun readSmbCredentials(): SmbCredentials {
        return SmbCredentials(
            username = prefs.getString(KEY_SMB_USERNAME, "").orEmpty(),
            password = prefs.getString(KEY_SMB_PASSWORD, "").orEmpty(),
            useGuest = prefs.getBoolean(KEY_SMB_USE_GUEST, false),
            customHost = prefs.getString(KEY_SMB_CUSTOM_HOST, "").orEmpty()
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
                tag = "SecureStore",
                message = "EncryptedSharedPreferences 초기화 오류 발생 — 키스토어 재생성 또는 안전 폴백 수행",
                throwable = e
            )
            runCatching {
                appContext.deleteSharedPreferences(PREFS_FILE_NAME)
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
            }.getOrElse { fallbackError ->
                AppErrorLogger.logError(
                    tag = "SecureStore",
                    message = "EncryptedSharedPreferences 재생성 실패 — 일반 내부 저장소로 폴백",
                    throwable = fallbackError
                )
                appContext.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
            }
        }
    }

    companion object {
        private const val PREFS_FILE_NAME = "unraid_secure_credentials"
        private const val FALLBACK_PREFS_NAME = "unraid_credentials_fallback"
        private const val KEY_UNRAID_API_KEY = "unraid_api_key"
        private const val KEY_TAILSCALE_AUTH_KEY = "tailscale_auth_key"
        private const val KEY_SMB_USERNAME = "smb_username"
        private const val KEY_SMB_PASSWORD = "smb_password"
        private const val KEY_SMB_USE_GUEST = "smb_use_guest"
        private const val KEY_SMB_CUSTOM_HOST = "smb_custom_host"
    }
}
