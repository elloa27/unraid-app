package com.unraid.app.plugin

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.unraid.app.data.local.AppErrorLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * 각 외부 플러그인의 id별로 완전히 격리된 EncryptedSharedPreferences를 제공하는 저장소 금고.
 * - window.UnraidHub.storageGet / storageSet / storageRemove API 백엔드
 * - 플러그인 삭제 시 해당 플러그인의 암호화 저장소도 깔끔하게 제거합니다.
 */
@Suppress("DEPRECATION")
class PluginStorageVault(context: Context) {

    private val appContext = context.applicationContext
    private val cache = ConcurrentHashMap<String, SharedPreferences>()

    fun get(pluginId: String, key: String): String? {
        if (key.isBlank()) return null
        return getPrefsForPlugin(pluginId).getString(key, null)
    }

    fun set(pluginId: String, key: String, value: String) {
        if (key.isBlank()) return
        getPrefsForPlugin(pluginId).edit().putString(key, value).apply()
    }

    fun remove(pluginId: String, key: String) {
        if (key.isBlank()) return
        getPrefsForPlugin(pluginId).edit().remove(key).apply()
    }

    /**
     * 플러그인 삭제 시 해당 플러그인의 암호화 저장소 데이터를 완전히 정리합니다.
     */
    fun clearPluginStorage(pluginId: String) {
        val prefsName = prefsFileName(pluginId)
        cache.remove(pluginId)?.edit()?.clear()?.apply()
        runCatching {
            appContext.deleteSharedPreferences(prefsName)
            appContext.deleteSharedPreferences("${prefsName}_fallback")
        }.onFailure { e ->
            AppErrorLogger.logError(
                tag = "PluginVault",
                message = "플러그인($pluginId) 저장소 정리 중 오류가 발생했습니다.",
                throwable = e
            )
        }
    }

    private fun getPrefsForPlugin(pluginId: String): SharedPreferences {
        return cache.computeIfAbsent(pluginId) { id ->
            val fileName = prefsFileName(id)
            try {
                val masterKey = MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                EncryptedSharedPreferences.create(
                    appContext,
                    fileName,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                AppErrorLogger.logError(
                    tag = "PluginVault",
                    message = "플러그인($id) EncryptedSharedPreferences 초기화 실패 — 안전 폴백 수행",
                    throwable = e
                )
                appContext.getSharedPreferences("${fileName}_fallback", Context.MODE_PRIVATE)
            }
        }
    }

    private fun prefsFileName(pluginId: String): String {
        val safeId = pluginId.lowercase().replace(Regex("[^a-z0-9_\\-]"), "_")
        return "unraid_plugin_vault_$safeId"
    }
}
