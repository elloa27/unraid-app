package com.unraid.app.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Unraid API 키 등 민감한 인증 정보를 Android Keystore 기반
 * EncryptedSharedPreferences에 암호화하여 안전하게 보관하는 저장소.
 */
@Suppress("DEPRECATION")
class SecureCredentialStore(context: Context) {

    private val prefs: SharedPreferences = createEncryptedPrefs(context.applicationContext)

    private val _apiKeyFlow = MutableStateFlow(prefs.getString(KEY_UNRAID_API_KEY, "").orEmpty())
    val apiKeyFlow: StateFlow<String> = _apiKeyFlow.asStateFlow()

    fun getUnraidApiKey(): String = _apiKeyFlow.value

    fun saveUnraidApiKey(apiKey: String) {
        val trimmed = apiKey.trim()
        prefs.edit().putString(KEY_UNRAID_API_KEY, trimmed).apply()
        _apiKeyFlow.value = trimmed
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
            // 키스토어 손상 등으로 암호화 저장소 초기화에 실패한 경우 에러 로그를 남기고 재생성 시도
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
    }
}
