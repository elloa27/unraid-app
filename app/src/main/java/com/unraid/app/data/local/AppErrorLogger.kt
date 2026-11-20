package com.unraid.app.data.local

import android.content.Context
import android.util.Log
import com.unraid.app.data.model.ErrorLogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 안드로이드 스튜디오 없이 기기에서 직접 오류를 확인하고 복사할 수 있도록 돕는
 * 앱 전역 에러 로그 수집기 (메모리 캐시 + 내부 파일 영구 백업).
 */
object AppErrorLogger {
    private const val MAX_LOG_COUNT = 100
    private const val LOG_FILE_NAME = "app_error_logs.json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val lock = Any()
    private var logFile: File? = null

    private val _logs = MutableStateFlow<List<ErrorLogEntry>>(emptyList())
    val logs: StateFlow<List<ErrorLogEntry>> = _logs.asStateFlow()

    /**
     * Application.onCreate 시 호출하여 이전 세션의 로그를 불러오고
     * 미처리 전역 예외(UncaughtException) 핸들러를 등록합니다.
     */
    fun init(context: Context) {
        synchronized(lock) {
            logFile = File(context.filesDir, LOG_FILE_NAME)
            loadFromDisk()
        }

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            logError(
                tag = "UncaughtException",
                message = "스레드(${thread.name})에서 처리되지 않은 예외가 발생했습니다.",
                throwable = throwable
            )
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * API 오류, WebView 오류, 내부 예외 등을 기록합니다.
     */
    fun logError(
        tag: String,
        message: String,
        throwable: Throwable? = null,
        extraDetails: String? = null
    ) {
        val stackTrace = throwable?.let {
            val sw = StringWriter()
            it.printStackTrace(PrintWriter(sw))
            sw.toString().take(3000)
        }
        val combinedDetails = listOfNotNull(
            extraDetails?.takeIf { it.isNotBlank() },
            stackTrace?.takeIf { it.isNotBlank() }
        ).joinToString("\n\n").ifBlank { null }

        Log.e("UnraidHub/$tag", "$message\n${combinedDetails.orEmpty()}")

        val entry = ErrorLogEntry(
            timestamp = currentTimestamp(),
            tag = tag,
            message = message,
            details = combinedDetails
        )

        synchronized(lock) {
            val updated = (listOf(entry) + _logs.value).take(MAX_LOG_COUNT)
            _logs.value = updated
            saveToDisk(updated)
        }
    }

    /**
     * 저장된 모든 에러 로그를 초기화합니다.
     */
    fun clearLogs() {
        synchronized(lock) {
            _logs.value = emptyList()
            runCatching { logFile?.delete() }
        }
    }

    /**
     * 클립보드 복사용 전체 에러 로그 텍스트를 생성합니다.
     */
    fun formatLogsForClipboard(): String {
        val current = _logs.value
        if (current.isEmpty()) return "기록된 에러 로그가 없습니다."

        return buildString {
            appendLine("=== Unraid Hub 에러 로그 (최근 ${current.size}건) ===")
            appendLine()
            current.forEachIndexed { index, entry ->
                appendLine("[#${index + 1}] ${entry.timestamp} [${entry.tag}]")
                appendLine("메시지: ${entry.message}")
                if (!entry.details.isNullOrBlank()) {
                    appendLine("상세 내용:")
                    appendLine(entry.details)
                }
                appendLine("----------------------------------------")
            }
        }
    }

    private fun currentTimestamp(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return formatter.format(Date())
    }

    private fun loadFromDisk() {
        val file = logFile ?: return
        if (!file.exists()) return
        runCatching {
            val raw = file.readText()
            if (raw.isNotBlank()) {
                _logs.value = json.decodeFromString<List<ErrorLogEntry>>(raw)
            }
        }
    }

    private fun saveToDisk(entries: List<ErrorLogEntry>) {
        val file = logFile ?: return
        runCatching {
            file.writeText(json.encodeToString(entries))
        }
    }
}
