package com.unraid.app.shares

import com.hierynomus.smbj.auth.AuthenticationContext
import com.unraid.app.data.local.SmbCredentials
import java.util.Locale

/**
 * SMB 접속 인증 정보
 */
data class SmbAuthConfig(
    val username: String = "",
    val password: String = "",
    val useGuest: Boolean = false
) {
    fun toSmbjAuthContext(): AuthenticationContext {
        return if (useGuest || username.isBlank()) {
            AuthenticationContext.guest()
        } else {
            AuthenticationContext(username, password.toCharArray(), null)
        }
    }

    companion object {
        fun fromCredentials(creds: SmbCredentials): SmbAuthConfig {
            return SmbAuthConfig(
                username = creds.username,
                password = creds.password,
                useGuest = creds.useGuest
            )
        }
    }
}

/**
 * 파일 유형 분류 (아이콘/색상/인앱 뷰어 분기용)
 */
enum class SmbFileCategory {
    FOLDER,
    VIDEO,
    IMAGE,
    AUDIO,
    TEXT,
    PDF,
    ARCHIVE,
    DOCUMENT,
    OTHER
}

/**
 * SMB 폴더 내부 항목 모델
 */
data class SmbFileEntry(
    val name: String,
    val relativePath: String, // 공유 루트 기준 슬래시('/') 구분 경로
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val extension: String = if (isDirectory) "" else name.substringAfterLast('.', "").lowercase(Locale.US)
) {
    val category: SmbFileCategory
        get() = if (isDirectory) {
            SmbFileCategory.FOLDER
        } else {
            classifyExtension(extension)
        }

    val isVideo: Boolean get() = category == SmbFileCategory.VIDEO
    val isImage: Boolean get() = category == SmbFileCategory.IMAGE
    val isTextPreviewable: Boolean get() = category == SmbFileCategory.TEXT
    val isSubtitle: Boolean get() = !isDirectory && extension in SUBTITLE_EXTENSIONS

    val baseNameWithoutExt: String
        get() = if (name.contains('.')) name.substringBeforeLast('.') else name

    companion object {
        val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "webm", "ts", "m4v", "wmv", "flv", "mpg", "mpeg")
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
        val AUDIO_EXTENSIONS = setOf("mp3", "flac", "wav", "aac", "m4a", "ogg", "opus", "wma")
        val TEXT_EXTENSIONS = setOf(
            "txt", "md", "json", "log", "yml", "yaml", "xml",
            "conf", "ini", "sh", "py", "kt", "js", "ts", "css", "html", "htm", "csv", "properties", "cfg"
        )
        val ARCHIVE_EXTENSIONS = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz")
        val DOCUMENT_EXTENSIONS = setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "hwp", "hwpx", "odt", "rtf", "epub")
        val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "smi", "sami")

        fun classifyExtension(ext: String): SmbFileCategory {
            val lower = ext.lowercase(Locale.US)
            return when {
                lower in VIDEO_EXTENSIONS -> SmbFileCategory.VIDEO
                lower in IMAGE_EXTENSIONS -> SmbFileCategory.IMAGE
                lower in AUDIO_EXTENSIONS -> SmbFileCategory.AUDIO
                lower == "pdf" -> SmbFileCategory.PDF
                lower in ARCHIVE_EXTENSIONS -> SmbFileCategory.ARCHIVE
                lower in DOCUMENT_EXTENSIONS -> SmbFileCategory.DOCUMENT
                lower in TEXT_EXTENSIONS -> SmbFileCategory.TEXT
                else -> SmbFileCategory.OTHER
            }
        }

        fun guessMimeType(fileName: String): String {
            return when (fileName.substringAfterLast('.', "").lowercase(Locale.US)) {
                "mp4", "m4v" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "avi" -> "video/x-msvideo"
                "mov" -> "video/quicktime"
                "webm" -> "video/webm"
                "ts" -> "video/mp2t"
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "bmp" -> "image/bmp"
                "mp3" -> "audio/mpeg"
                "flac" -> "audio/flac"
                "wav" -> "audio/wav"
                "m4a", "aac" -> "audio/mp4"
                "ogg" -> "audio/ogg"
                "pdf" -> "application/pdf"
                "txt", "log", "md", "ini", "conf", "sh", "py", "kt", "yml", "yaml", "csv" -> "text/plain"
                "json" -> "application/json"
                "xml" -> "application/xml"
                "html", "htm" -> "text/html"
                "zip" -> "application/zip"
                "apk" -> "application/vnd.android.package-archive"
                "doc" -> "application/msword"
                "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                "xls" -> "application/vnd.ms-excel"
                "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                "ppt" -> "application/vnd.ms-powerpoint"
                "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                "srt", "vtt", "ass", "ssa", "smi" -> "text/plain"
                else -> "application/octet-stream"
            }
        }
    }
}

/**
 * 루트 화면의 공유 폴더 카드 모델 (GraphQL 자동 조회 + 수동 등록 병합)
 */
data class ShareRootEntry(
    val name: String,
    val comment: String = "",
    val usedKb: Long? = null,
    val freeKb: Long? = null,
    val sizeKb: Long? = null,
    val cachePolicy: String? = null,
    val isManual: Boolean = false
)

enum class ShareSortMode(val labelKo: String) {
    NAME_ASC("이름순"),
    DATE_DESC("최신 수정순"),
    SIZE_DESC("크기 큰 순")
}

enum class ShareViewMode {
    LIST,
    GRID
}

enum class TransferDirection(val labelKo: String) {
    UPLOAD("업로드"),
    DOWNLOAD("다운로드"),
    PREPARE_OPEN("파일 준비")
}

data class FileTransferProgress(
    val direction: TransferDirection,
    val fileName: String,
    val transferredBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val currentFileIndex: Int = 1,
    val totalFileCount: Int = 1
) {
    val fraction: Float?
        get() = if (totalBytes > 0L) {
            (transferredBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            null
        }
}
