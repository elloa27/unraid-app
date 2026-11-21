package com.unraid.app.player

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import com.unraid.app.data.local.AppErrorLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 내장 libmpv 비디오 플레이어 전역 설정 모델
 */
data class PlayerSettings(
    val preferHardwareDecoding: Boolean = true,
    val subtitleFontName: String = "",
    val forceSubtitleFontOnAss: Boolean = false
)

/**
 * 내장 libmpv 플레이어의 디코딩 및 자막 폰트 설정 저장소.
 */
class PlayerSettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settingsFlow = MutableStateFlow(readSettings())
    val settingsFlow: StateFlow<PlayerSettings> = _settingsFlow.asStateFlow()

    fun getSettings(): PlayerSettings = _settingsFlow.value

    fun setPreferHardwareDecoding(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PREFER_HWDEC, enabled).apply()
        _settingsFlow.value = readSettings()
    }

    fun saveSubtitleFontSettings(fontName: String, forceOnAss: Boolean) {
        prefs.edit()
            .putString(KEY_SUBTITLE_FONT_NAME, fontName.trim())
            .putBoolean(KEY_FORCE_SUBTITLE_FONT_ON_ASS, forceOnAss)
            .apply()
        _settingsFlow.value = readSettings()
    }

    private fun readSettings(): PlayerSettings {
        return PlayerSettings(
            preferHardwareDecoding = prefs.getBoolean(KEY_PREFER_HWDEC, true),
            subtitleFontName = prefs.getString(KEY_SUBTITLE_FONT_NAME, "").orEmpty(),
            forceSubtitleFontOnAss = prefs.getBoolean(KEY_FORCE_SUBTITLE_FONT_ON_ASS, false)
        )
    }

    companion object {
        private const val PREFS_NAME = "unraid_hub_player_settings"
        private const val KEY_PREFER_HWDEC = "prefer_hwdec"
        private const val KEY_SUBTITLE_FONT_NAME = "subtitle_font_name"
        private const val KEY_FORCE_SUBTITLE_FONT_ON_ASS = "force_subtitle_font_on_ass"
    }
}

/**
 * libmpv의 sub-fonts-dir에 사용할 앱 내부 자막 폰트(.ttf / .otf / .ttc) 저장소 관리 유틸리티.
 */
object MpvFontStorage {
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
                throw IllegalArgumentException("지원하지 않는 파일 형식입니다 ($safeName). .ttf, .otf, .ttc 파일만 추가할 수 있습니다.")
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
                tag = "MpvFontStorage",
                message = "자막 폰트 파일 가져오기 실패: ${e.localizedMessage ?: e.javaClass.simpleName}",
                throwable = e
            )
        }
    }

    fun deleteFont(file: File): Boolean = runCatching { file.delete() }.getOrDefault(false)

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
