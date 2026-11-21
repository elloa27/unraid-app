package com.unraid.app.plugin.emby

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.unraid.app.data.local.AppErrorLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Emby 서버 URL, AccessToken, UserId 및 플레이어 자막 폰트 설정을
 * EncryptedSharedPreferences에 보관하는 저장소.
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

    fun saveSubtitleFontSettings(fontName: String, forceOnAss: Boolean) {
        prefs.edit()
            .putString(KEY_SUBTITLE_FONT_NAME, fontName.trim())
            .putBoolean(KEY_FORCE_SUBTITLE_FONT_ON_ASS, forceOnAss)
            .apply()
        _configFlow.value = readConfig()
    }

    fun setLibrarySortOption(option: EmbySortOption) {
        prefs.edit()
            .putString(KEY_LIBRARY_SORT_OPTION, option.storageKey)
            .apply()
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
            preferHardwareDecoding = prefs.getBoolean(KEY_PREFER_HWDEC, true),
            subtitleFontName = prefs.getString(KEY_SUBTITLE_FONT_NAME, "").orEmpty(),
            forceSubtitleFontOnAss = prefs.getBoolean(KEY_FORCE_SUBTITLE_FONT_ON_ASS, false),
            librarySortOption = EmbySortOption.fromStorageKey(prefs.getString(KEY_LIBRARY_SORT_OPTION, null))
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
        private const val KEY_SUBTITLE_FONT_NAME = "emby_subtitle_font_name"
        private const val KEY_FORCE_SUBTITLE_FONT_ON_ASS = "emby_force_subtitle_font_on_ass"
        private const val KEY_LIBRARY_SORT_OPTION = "emby_library_sort_option"
    }
}

/**
 * libmpv의 sub-fonts-dir에 사용할 앱 내부 자막 폰트(.ttf / .otf / .ttc) 저장소 관리 유틸리티.
 * Scoped Storage 권한 문제 없이 앱 전용 내부 폴더(filesDir/mpv-fonts)에 복사해 보관합니다.
 */
object EmbyFontStorage {
    private const val FONTS_DIR_NAME = "mpv-fonts"
    private val ALLOWED_EXTENSIONS = setOf("ttf", "otf", "ttc")

    fun getFontsDir(context: Context): File {
        return File(context.applicationContext.filesDir, FONTS_DIR_NAME).apply {
            if (!exists()) mkdirs()
        }
    }

    fun listInstalledFonts(context: Context): List<File> {
        val dir = getFontsDir(context)
        return dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in ALLOWED_EXTENSIONS }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
    }

    fun importFontUri(context: Context, uri: Uri): Result<String> {
        return runCatching {
            val resolver = context.applicationContext.contentResolver
            val displayName = resolveDisplayName(context, uri)
                ?: "custom_font_${System.currentTimeMillis()}.ttf"
            val safeName = displayName.replace(Regex("[^a-zA-Z0-9._가-힣\\- ]"), "_")
            val ext = safeName.substringAfterLast('.', "").lowercase()
            if (ext !in ALLOWED_EXTENSIONS) {
                throw IllegalArgumentException("지원하지 않는 파일 형식입니다 ($safeName). .ttf, .otf, .ttc 폰트 파일만 추가할 수 있습니다.")
            }

            val targetFile = File(getFontsDir(context), safeName)
            resolver.openInputStream(uri)?.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("선택한 폰트 파일을 열 수 없습니다.")

            safeName
        }.onFailure { e ->
            AppErrorLogger.logError(
                tag = "EmbyFontStorage",
                message = "자막 폰트 파일 가져오기 실패: ${e.localizedMessage ?: e.javaClass.simpleName}",
                throwable = e
            )
        }
    }

    fun deleteFont(file: File): Boolean {
        return runCatching { file.delete() }.getOrDefault(false)
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.applicationContext.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        }.getOrNull()
    }
}
