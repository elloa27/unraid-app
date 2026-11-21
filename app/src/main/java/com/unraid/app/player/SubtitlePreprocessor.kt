package com.unraid.app.player

import android.content.Context
import com.unraid.app.data.local.AppErrorLogger
import com.unraid.app.network.NetworkModule
import okhttp3.Request
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * libmpv에 전달할 준비된 로컬 자막 트랙 정보.
 */
data class PreparedSubtitleTrack(
    val filePath: String,
    val title: String,
    val language: String,
    val isPreferredKorean: Boolean
)

/**
 * 외부 자막(특히 한국어 SAMI `.smi` / CP949·EUC-KR 인코딩 자막)을 안정적으로 로드하기 위한 전처리기.
 *
 * - NetworkModule.okHttpClient(Tailscale tsnet 프록시 + 사설 SSL 허용)로 자막 다운로드
 * - Emby `/Stream.sami` 요청 실패 시 `Stream.smi` → `Stream.srt` → `Stream.vtt` 자동 폴백
 * - UTF-8 / UTF-16 / CP949(EUC-KR) 인코딩 자동 판별 및 UTF-8 정규화
 * - SAMI(`.smi`/`.sami`) 자막을 libass 호환 UTF-8 `.ass` 자막으로 변환하여
 *   libmpv의 SAMI 파서/인코딩 한계 없이 태그·색상·다국어 클래스(`KRCC` 등)를 완벽하게 렌더링
 */
object SubtitlePreprocessor {

    private val STREAM_EXT_REGEX = Regex(
        """(/Subtitles/[^/?#]+/Stream\.)([A-Za-z0-9]+)(?=$|\?)""",
        RegexOption.IGNORE_CASE
    )
    private val HTML_COMMENT_REGEX = Regex("""<!--[\s\S]*?-->""")
    private val SYNC_TAG_REGEX = Regex(
        """<SYNC\b[^>]*?\bStart\s*=\s*["']?(-?\d+)["']?[^>]*>""",
        RegexOption.IGNORE_CASE
    )
    private val P_TAG_REGEX = Regex("""<P\b([^>]*)>""", RegexOption.IGNORE_CASE)
    private val CLASS_ATTR_REGEX = Regex("""\bClass\s*=\s*["']?([A-Za-z0-9_-]+)["']?""", RegexOption.IGNORE_CASE)
    private val STYLE_BLOCK_REGEX = Regex("""<STYLE\b[^>]*>([\s\S]*?)</STYLE>""", RegexOption.IGNORE_CASE)
    private val CSS_CLASS_RULE_REGEX = Regex("""\.([A-Za-z0-9_-]+)\s*\{([^}]*)\}""")
    private val BR_TAG_REGEX = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val FONT_OPEN_REGEX = Regex("""<font\b([^>]*)>""", RegexOption.IGNORE_CASE)
    private val FONT_CLOSE_REGEX = Regex("""</font\s*>""", RegexOption.IGNORE_CASE)
    private val COLOR_ATTR_REGEX = Regex("""\bcolor\s*=\s*["']?([^"'\s>]+)["']?""", RegexOption.IGNORE_CASE)
    private val BOLD_OPEN_REGEX = Regex("""<b\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val BOLD_CLOSE_REGEX = Regex("""</b\s*>""", RegexOption.IGNORE_CASE)
    private val ITALIC_OPEN_REGEX = Regex("""<i\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val ITALIC_CLOSE_REGEX = Regex("""</i\s*>""", RegexOption.IGNORE_CASE)
    private val UNDERLINE_OPEN_REGEX = Regex("""<u\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val UNDERLINE_CLOSE_REGEX = Regex("""</u\s*>""", RegexOption.IGNORE_CASE)
    private val STRIKE_OPEN_REGEX = Regex("""<s\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val STRIKE_CLOSE_REGEX = Regex("""</s\s*>""", RegexOption.IGNORE_CASE)
    private val ANY_HTML_TAG_REGEX = Regex("""</?[a-zA-Z!][^>]*>""")
    private val ASS_OVERRIDE_REGEX = Regex("""\{[^}]*\}""")
    private val NUMERIC_ENTITY_REGEX = Regex("""&#(\d+);""")
    private val HEX_ENTITY_REGEX = Regex("""&#x([0-9a-fA-F]+);""", RegexOption.IGNORE_CASE)

    private val NAMED_COLORS = mapOf(
        "white" to "FFFFFF",
        "black" to "000000",
        "red" to "FF0000",
        "green" to "00FF00",
        "lime" to "00FF00",
        "blue" to "0000FF",
        "yellow" to "FFFF00",
        "cyan" to "00FFFF",
        "aqua" to "00FFFF",
        "magenta" to "FF00FF",
        "fuchsia" to "FF00FF",
        "orange" to "FFA500",
        "gold" to "FFD700",
        "pink" to "FFC0CB",
        "skyblue" to "87CEEB",
        "lightblue" to "ADD8E6",
        "lightgreen" to "90EE90",
        "violet" to "EE82EE",
        "purple" to "A855F7",
        "gray" to "9CA3AF",
        "grey" to "9CA3AF",
        "silver" to "D1D5DB"
    )

    private val BINARY_SUB_EXTENSIONS = setOf("sup", "pgs", "idx", "vob", "dvb", "xsub")

    /**
     * 요청된 외부 자막 목록을 모두 다운로드/변환하여 로컬 파일 경로 목록으로 반환합니다.
     * IO 스레드에서 호출해야 합니다.
     */
    fun prepareSubtitles(
        context: Context,
        sessionId: String,
        subtitles: List<MpvSubtitleRequest>,
        headers: Map<String, String>,
        preferredFontName: String
    ): List<PreparedSubtitleTrack> {
        if (subtitles.isEmpty()) return emptyList()

        val safeSession = sessionId.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "session" }
        val sessionDir = File(File(context.cacheDir, "mpv_subs"), safeSession).apply { mkdirs() }
        val prepared = mutableListOf<PreparedSubtitleTrack>()

        subtitles.forEachIndexed { index, sub ->
            val rawUrl = sub.url.trim()
            if (rawUrl.isBlank()) return@forEachIndexed

            val download = downloadWithFallback(rawUrl, headers)
            if (download == null) {
                AppErrorLogger.logError(
                    tag = "NativeMpv/Sub",
                    message = "외부 자막 다운로드 실패 (${sub.title.ifBlank { "#$index" }})",
                    extraDetails = "URL: $rawUrl"
                )
                return@forEachIndexed
            }

            val (usedUrl, bytes) = download
            val urlExt = extractExtensionFromUrl(usedUrl)
            val baseTitle = sub.title.ifBlank { "외부 자막 #${index + 1}" }
            val baseLang = sub.language.ifBlank { "und" }

            if (urlExt in BINARY_SUB_EXTENSIONS) {
                val outFile = File(sessionDir, "sub_${index}.$urlExt")
                runCatching {
                    outFile.writeBytes(bytes)
                    prepared += PreparedSubtitleTrack(
                        filePath = outFile.absolutePath,
                        title = baseTitle,
                        language = baseLang,
                        isPreferredKorean = isKoreanHint(baseLang, baseTitle)
                    )
                }
                return@forEachIndexed
            }

            val decodedText = decodeSubtitleBytes(bytes)
            if (decodedText.isBlank()) return@forEachIndexed

            if (isSamiContent(usedUrl, decodedText)) {
                val assTracks = convertSamiToAssTracks(
                    samiText = decodedText,
                    baseTitle = baseTitle,
                    baseLang = baseLang,
                    fontName = preferredFontName.ifBlank { "sans-serif" }
                )
                if (assTracks.isNotEmpty()) {
                    assTracks.forEachIndexed { trackIdx, track ->
                        val outFile = File(sessionDir, "sub_${index}_${trackIdx}.ass")
                        runCatching {
                            outFile.writeText(track.assContent, StandardCharsets.UTF_8)
                            prepared += PreparedSubtitleTrack(
                                filePath = outFile.absolutePath,
                                title = track.title,
                                language = track.language,
                                isPreferredKorean = track.isKorean || isKoreanHint(track.language, track.title)
                            )
                        }.onFailure { err ->
                            AppErrorLogger.logError(
                                tag = "NativeMpv/Sub",
                                message = "SAMI→ASS 자막 저장 실패 (${track.title})",
                                throwable = err
                            )
                        }
                    }
                    return@forEachIndexed
                }
            }

            // 일반 텍스트 자막(.srt, .ass, .ssa, .vtt 등)은 UTF-8로 정규화하여 로컬 저장
            val normalizedExt = detectTextSubtitleExtension(urlExt, decodedText)
            val outFile = File(sessionDir, "sub_${index}.$normalizedExt")
            runCatching {
                outFile.writeText(decodedText, StandardCharsets.UTF_8)
                prepared += PreparedSubtitleTrack(
                    filePath = outFile.absolutePath,
                    title = baseTitle,
                    language = baseLang,
                    isPreferredKorean = isKoreanHint(baseLang, baseTitle)
                )
            }.onFailure { err ->
                AppErrorLogger.logError(
                    tag = "NativeMpv/Sub",
                    message = "외부 자막 캐시 저장 실패 ($baseTitle)",
                    throwable = err
                )
            }
        }

        // 한국어 자막이 최우선 선택되도록 정렬
        return prepared.sortedByDescending { it.isPreferredKorean }
    }

    /**
     * 플레이어 종료 시 세션별 임시 자막 캐시 디렉터리를 정리합니다.
     */
    fun cleanupSession(context: Context, sessionId: String) {
        val safeSession = sessionId.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { return }
        runCatching {
            File(File(context.cacheDir, "mpv_subs"), safeSession).deleteRecursively()
        }
    }

    private fun downloadWithFallback(
        originalUrl: String,
        headers: Map<String, String>
    ): Pair<String, ByteArray>? {
        val candidates = buildCandidateUrls(originalUrl)
        val client = NetworkModule.okHttpClient

        for (candidateUrl in candidates) {
            try {
                val reqBuilder = Request.Builder()
                    .url(candidateUrl)
                    .get()
                headers.forEach { (k, v) ->
                    if (k.isNotBlank()) reqBuilder.header(k.trim(), v.trim())
                }

                client.newCall(reqBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use
                    }
                    val bytes = response.body?.bytes() ?: return@use
                    if (bytes.isEmpty()) return@use

                    // 서버 오류 HTML 페이지를 자막으로 오인하지 않도록 필터링
                    val headSample = String(bytes, 0, minOf(bytes.size, 256), StandardCharsets.ISO_8859_1)
                        .lowercase(Locale.US)
                    val looksLikeHtmlError = (headSample.contains("<!doctype html") || headSample.contains("<html")) &&
                        !headSample.contains("<sami") &&
                        !headSample.contains("<sync")
                    if (looksLikeHtmlError) {
                        return@use
                    }
                    return candidateUrl to bytes
                }
            } catch (_: Exception) {
                // 다음 폴백 후보 URL 시도
            }
        }
        return null
    }

    private fun buildCandidateUrls(originalUrl: String): List<String> {
        val list = LinkedHashSet<String>()
        list += originalUrl

        val match = STREAM_EXT_REGEX.find(originalUrl)
        if (match != null) {
            val ext = match.groupValues[2].lowercase(Locale.US)
            val fallbacks = when (ext) {
                "sami", "smi" -> listOf("smi", "srt", "vtt", "ass")
                "subrip" -> listOf("srt", "vtt")
                "ass", "ssa" -> listOf(ext, "srt", "vtt")
                else -> listOf("srt", "vtt")
            }
            for (altExt in fallbacks) {
                list += originalUrl.replaceRange(match.range, "${match.groupValues[1]}$altExt")
            }
        }
        return list.toList()
    }

    private fun extractExtensionFromUrl(url: String): String {
        val pathOnly = url.substringBefore('?').substringBefore('#')
        val lastSeg = pathOnly.substringAfterLast('/')
        return lastSeg.substringAfterLast('.', "").lowercase(Locale.US)
    }

    private fun detectTextSubtitleExtension(urlExt: String, content: String): String {
        val trimmed = content.trimStart()
        return when {
            trimmed.startsWith("WEBVTT", ignoreCase = true) -> "vtt"
            trimmed.contains("[Script Info]", ignoreCase = true) -> "ass"
            urlExt in setOf("srt", "ass", "ssa", "vtt", "sub", "txt") -> urlExt
            else -> "srt"
        }
    }

    private fun isKoreanHint(lang: String, title: String): Boolean {
        val l = lang.lowercase(Locale.US)
        val t = title.lowercase(Locale.US)
        return l.startsWith("ko") || l == "kr" || l == "kor" ||
            t.contains("한국") || t.contains("한글") || t.contains("kor") ||
            t.contains("krcc") || t.contains("korean")
    }

    /**
     * BOM / 엄격한 UTF-8 / 한국어 CP949(EUC-KR) 순으로 인코딩을 자동 판별하여 디코딩합니다.
     */
    internal fun decodeSubtitleBytes(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""

        // 1) BOM 판별
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        }

        // 2) 엄격한 UTF-8 검증
        val utf8Result = runCatching {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()
        if (utf8Result != null) {
            return utf8Result
        }

        // 3) 한국어 CP949 / MS949 / EUC-KR 폴백
        val koreanCharsetName = listOf("x-windows-949", "MS949", "CP949", "EUC-KR")
            .firstOrNull { name -> runCatching { Charset.isSupported(name) }.getOrDefault(false) }
        return if (koreanCharsetName != null) {
            String(bytes, Charset.forName(koreanCharsetName))
        } else {
            String(bytes, StandardCharsets.UTF_8)
        }
    }

    internal fun isSamiContent(url: String, text: String): Boolean {
        val head = text.take(4096)
        if (head.contains("<SAMI", ignoreCase = true) || head.contains("<SYNC", ignoreCase = true)) {
            return true
        }
        val ext = extractExtensionFromUrl(url)
        return (ext == "smi" || ext == "sami") && text.contains("<SYNC", ignoreCase = true)
    }

    private data class SamiRawEntry(
        val startMs: Long,
        val assText: String // 빈 문자열이면 이전 대사를 지우는 &nbsp; 클리어 마커
    )

    private data class ConvertedSamiTrack(
        val className: String,
        val title: String,
        val language: String,
        val isKorean: Boolean,
        val assContent: String
    )

    private fun convertSamiToAssTracks(
        samiText: String,
        baseTitle: String,
        baseLang: String,
        fontName: String
    ): List<ConvertedSamiTrack> {
        val classMeta = parseStyleClassMeta(samiText)
        val withoutComments = HTML_COMMENT_REGEX.replace(samiText, "")
        val syncMatches = SYNC_TAG_REGEX.findAll(withoutComments).toList()
        if (syncMatches.isEmpty()) return emptyList()

        val entriesByClass = LinkedHashMap<String, MutableList<SamiRawEntry>>()

        for (i in syncMatches.indices) {
            val match = syncMatches[i]
            val startMs = match.groupValues[1].toLongOrNull()?.coerceAtLeast(0L) ?: continue
            val chunkStart = match.range.last + 1
            val chunkEnd = if (i + 1 < syncMatches.size) {
                syncMatches[i + 1].range.first
            } else {
                withoutComments.length
            }
            var chunk = withoutComments.substring(chunkStart, chunkEnd)
            val bodyEndIdx = chunk.indexOf("</body", ignoreCase = true)
            if (bodyEndIdx >= 0) chunk = chunk.substring(0, bodyEndIdx)
            val samiEndIdx = chunk.indexOf("</sami", ignoreCase = true)
            if (samiEndIdx >= 0) chunk = chunk.substring(0, samiEndIdx)

            val pMatches = P_TAG_REGEX.findAll(chunk).toList()
            if (pMatches.isEmpty()) {
                val converted = convertSamiFragmentToAss(chunk)
                entriesByClass.getOrPut("DEFAULT") { mutableListOf() } += SamiRawEntry(startMs, converted)
            } else {
                for (pIdx in pMatches.indices) {
                    val pMatch = pMatches[pIdx]
                    val attrs = pMatch.groupValues[1]
                    val cls = CLASS_ATTR_REGEX.find(attrs)?.groupValues?.get(1)
                        ?.uppercase(Locale.US)
                        ?.ifBlank { "DEFAULT" }
                        ?: "DEFAULT"
                    val fragStart = pMatch.range.last + 1
                    val fragEnd = if (pIdx + 1 < pMatches.size) {
                        pMatches[pIdx + 1].range.first
                    } else {
                        chunk.length
                    }
                    val fragment = chunk.substring(fragStart, fragEnd)
                    val converted = convertSamiFragmentToAss(fragment)
                    entriesByClass.getOrPut(cls) { mutableListOf() } += SamiRawEntry(startMs, converted)
                }
            }
        }

        // 실제 대사가 1개 이상 있는 클래스만 추림
        val validClasses = entriesByClass.filterValues { list -> list.any { it.assText.isNotBlank() } }
        if (validClasses.isEmpty()) return emptyList()

        val multipleTracks = validClasses.size > 1
        val tracks = mutableListOf<ConvertedSamiTrack>()

        validClasses.forEach { (cls, rawList) ->
            val sorted = rawList.sortedBy { it.startMs }

            // 같은 startMs에 연속으로 들어온 대사를 하나로 병합
            val merged = mutableListOf<SamiRawEntry>()
            for (entry in sorted) {
                val last = merged.lastOrNull()
                if (last != null && last.startMs == entry.startMs) {
                    val combinedText = when {
                        last.assText.isBlank() -> entry.assText
                        entry.assText.isBlank() -> last.assText
                        else -> "${last.assText}\\N${entry.assText}"
                    }
                    merged[merged.lastIndex] = last.copy(assText = combinedText)
                } else {
                    merged += entry
                }
            }

            val dialogues = StringBuilder()
            var cueCount = 0
            for (idx in merged.indices) {
                val cur = merged[idx]
                if (cur.assText.isBlank()) continue

                val nextStart = merged.getOrNull(idx + 1)?.startMs
                val endMs = when {
                    nextStart != null && nextStart > cur.startMs -> nextStart
                    else -> cur.startMs + 5_000L
                }
                val startStamp = formatAssTime(cur.startMs)
                val endStamp = formatAssTime(endMs)
                dialogues.append("Dialogue: 0,")
                    .append(startStamp)
                    .append(',')
                    .append(endStamp)
                    .append(",Default,,0,0,0,,")
                    .append(cur.assText)
                    .append('\n')
                cueCount++
            }

            if (cueCount > 0) {
                val meta = classMeta[cls]
                val isKr = isKoreanClass(cls, meta)
                val trackLang = when {
                    isKr -> "kor"
                    meta?.lang?.isNotBlank() == true -> meta.lang
                    cls.startsWith("EN") -> "eng"
                    cls.startsWith("JP") || cls.startsWith("JA") -> "jpn"
                    else -> baseLang
                }
                val trackTitle = if (multipleTracks) {
                    val label = meta?.name?.takeIf { it.isNotBlank() } ?: cls
                    "$baseTitle ($label)"
                } else {
                    baseTitle
                }

                val safeFont = fontName.replace(",", " ").trim().ifBlank { "sans-serif" }
                val assContent = buildString {
                    append("[Script Info]\n")
                    append("Title: ").append(trackTitle.replace('\n', ' ')).append('\n')
                    append("ScriptType: v4.00+\n")
                    append("PlayResX: 1920\n")
                    append("PlayResY: 1080\n")
                    append("WrapStyle: 0\n")
                    append("ScaledBorderAndShadow: yes\n\n")
                    append("[V4+ Styles]\n")
                    append("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
                    append("Style: Default,")
                    append(safeFont)
                    append(",62,&H00FFFFFF,&H000000FF,&H00000000,&H80000000,0,0,0,0,100,100,0,0,1,3,1.5,2,60,60,48,1\n\n")
                    append("[Events]\n")
                    append("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")
                    append(dialogues)
                }

                tracks += ConvertedSamiTrack(
                    className = cls,
                    title = trackTitle,
                    language = trackLang,
                    isKorean = isKr,
                    assContent = assContent
                )
            }
        }

        return tracks.sortedByDescending { it.isKorean }
    }

    private data class SamiClassMeta(val name: String, val lang: String)

    private fun parseStyleClassMeta(samiText: String): Map<String, SamiClassMeta> {
        val result = HashMap<String, SamiClassMeta>()
        val styleMatch = STYLE_BLOCK_REGEX.find(samiText) ?: return result
        val css = styleMatch.groupValues[1]
        CSS_CLASS_RULE_REGEX.findAll(css).forEach { m ->
            val cls = m.groupValues[1].uppercase(Locale.US)
            val body = m.groupValues[2]
            val name = Regex("""Name\s*:\s*([^;]+)""", RegexOption.IGNORE_CASE)
                .find(body)?.groupValues?.get(1)?.trim().orEmpty()
            val lang = Regex("""lang\s*:\s*([^;]+)""", RegexOption.IGNORE_CASE)
                .find(body)?.groupValues?.get(1)?.trim().orEmpty()
            result[cls] = SamiClassMeta(name = name, lang = lang)
        }
        return result
    }

    private fun isKoreanClass(cls: String, meta: SamiClassMeta?): Boolean {
        val upper = cls.uppercase(Locale.US)
        if (upper in setOf("KRCC", "KOCC", "KORCC", "KR", "KO", "KOR", "KOREAN")) return true
        val metaText = "${meta?.name.orEmpty()} ${meta?.lang.orEmpty()}".lowercase(Locale.US)
        return metaText.contains("ko") || metaText.contains("kr") ||
            metaText.contains("한국") || metaText.contains("한글")
    }

    private fun convertSamiFragmentToAss(fragment: String): String {
        // SAMI HTML의 원시 줄바꿈은 공백 취급하고, <br> 태그만 실제 줄바꿈(\N)으로 변환
        var s = fragment
            .replace("\r\n", " ")
            .replace('\r', ' ')
            .replace('\n', ' ')

        // 중괄호가 ASS 제어 태그와 충돌하지 않도록 이스케이프
        s = s.replace("{", "(").replace("}", ")")

        // <br> -> ASS 줄바꿈 마커
        s = BR_TAG_REGEX.replace(s, "\u0001")

        // <font color=...> -> {\c&HBBGGRR&}
        s = FONT_OPEN_REGEX.replace(s) { m ->
            val colorVal = COLOR_ATTR_REGEX.find(m.groupValues[1])?.groupValues?.get(1)
            val bgr = colorVal?.let { parseColorToAssBgr(it) }
            if (bgr != null) "{\\c&H${bgr}&}" else ""
        }
        s = FONT_CLOSE_REGEX.replace(s, "{\\c}")

        // 스타일 태그 변환
        s = BOLD_OPEN_REGEX.replace(s, "{\\b1}")
        s = BOLD_CLOSE_REGEX.replace(s, "{\\b0}")
        s = ITALIC_OPEN_REGEX.replace(s, "{\\i1}")
        s = ITALIC_CLOSE_REGEX.replace(s, "{\\i0}")
        s = UNDERLINE_OPEN_REGEX.replace(s, "{\\u1}")
        s = UNDERLINE_CLOSE_REGEX.replace(s, "{\\u0}")
        s = STRIKE_OPEN_REGEX.replace(s, "{\\s1}")
        s = STRIKE_CLOSE_REGEX.replace(s, "{\\s0}")

        // 나머지 HTML 태그 제거
        s = ANY_HTML_TAG_REGEX.replace(s, "")

        // HTML 엔티티 디코딩
        s = decodeHtmlEntities(s)

        // 줄별 공백 정리 및 앞뒤 빈 줄 제거
        val lines = s.split('\u0001')
            .map { line -> line.replace(Regex("""[ \t\u00A0]+"""), " ").trim() }
            .dropWhile { line -> ASS_OVERRIDE_REGEX.replace(line, "").isBlank() }
            .dropLastWhile { line -> ASS_OVERRIDE_REGEX.replace(line, "").isBlank() }

        val joined = lines.joinToString("\\N").trim()
        val visibleOnly = ASS_OVERRIDE_REGEX.replace(joined, "").replace("\\N", "").trim()
        return if (visibleOnly.isEmpty()) "" else joined
    }

    private fun parseColorToAssBgr(rawColor: String): String? {
        val cleaned = rawColor.trim().lowercase(Locale.US)
        val hex = when {
            cleaned in NAMED_COLORS -> NAMED_COLORS[cleaned]
            cleaned.startsWith("#") && cleaned.length == 7 -> cleaned.substring(1)
            cleaned.startsWith("#") && cleaned.length == 4 -> {
                val r = cleaned[1]
                val g = cleaned[2]
                val b = cleaned[3]
                "$r$r$g$g$b$b"
            }
            cleaned.length == 6 && cleaned.all { it in '0'..'9' || it in 'a'..'f' } -> cleaned
            else -> null
        } ?: return null

        val upper = hex.uppercase(Locale.US)
        if (upper.length != 6 || !upper.all { it in '0'..'9' || it in 'A'..'F' }) return null
        val rr = upper.substring(0, 2)
        val gg = upper.substring(2, 4)
        val bb = upper.substring(4, 6)
        return "$bb$gg$rr"
    }

    private fun decodeHtmlEntities(input: String): String {
        var out = input
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&apos;", "'", ignoreCase = true)
            .replace("&#39;", "'")
            .replace("&amp;", "&", ignoreCase = true)

        out = NUMERIC_ENTITY_REGEX.replace(out) { m ->
            val code = m.groupValues[1].toIntOrNull()
            if (code != null && code in 1..0x10FFFF) {
                String(Character.toChars(code))
            } else {
                ""
            }
        }
        out = HEX_ENTITY_REGEX.replace(out) { m ->
            val code = m.groupValues[1].toIntOrNull(16)
            if (code != null && code in 1..0x10FFFF) {
                String(Character.toChars(code))
            } else {
                ""
            }
        }
        return out
    }

    private fun formatAssTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val hours = safe / 3_600_000L
        val minutes = (safe % 3_600_000L) / 60_000L
        val seconds = (safe % 60_000L) / 1_000L
        val centis = (safe % 1_000L) / 10L
        return String.format(Locale.US, "%d:%02d:%02d.%02d", hours, minutes, seconds, centis)
    }
}
