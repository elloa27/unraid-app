package com.unraid.app.shares

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.share.DiskShare
import com.unraid.app.data.local.AppErrorLogger
import com.unraid.app.player.MpvSubtitleRequest
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.EnumSet
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class SmbStreamSessionInfo(
    val token: String,
    val videoUrl: String,
    val subtitles: List<MpvSubtitleRequest>
)

/**
 * SMB 동영상 및 동명 자막 파일을 내장 libmpv 플레이어로 즉시 스트리밍하기 위한
 * 루프백 전용(127.0.0.1) 경량 HTTP Range 스트림 서버.
 *
 * - 랜덤 UUID 토큰 기반 단일 세션 URL 발급으로 외부/타 세션 접근 차단
 * - HEAD / GET 및 Range: bytes=start-end (206 Partial Content) 완벽 지원으로 대용량 영상 즉시 탐색 가능
 * - 같은 폴더의 동명 자막(.srt/.ass/.ssa/.vtt/.smi)도 로컬 HTTP URL로 서빙하여 SubtitlePreprocessor 자동 연동
 */
class SmbLocalStreamServer(
    private val smbClient: SmbShareClient
) {
    private data class SubtitleAsset(
        val entry: SmbFileEntry,
        val language: String,
        @Volatile var cachedBytes: ByteArray? = null
    )

    private data class ActiveStreamSession(
        val token: String,
        val host: String,
        val auth: SmbAuthConfig,
        val shareName: String,
        val videoEntry: SmbFileEntry,
        val subtitles: List<SubtitleAsset>,
        @Volatile var resolvedVideoSize: Long
    )

    private val lock = Any()
    private var serverSocket: ServerSocket? = null
    private var acceptExecutor: ExecutorService? = null
    private var workerExecutor: ExecutorService? = null
    private val activeClientSockets = ConcurrentHashMap.newKeySet<Socket>()

    @Volatile
    private var activeSession: ActiveStreamSession? = null

    /**
     * 새로운 동영상 스트리밍 세션을 시작하고 로컬 HTTP URL과 자막 목록을 반환합니다.
     */
    fun startSession(
        host: String,
        auth: SmbAuthConfig,
        shareName: String,
        videoEntry: SmbFileEntry,
        siblingSubtitles: List<SmbFileEntry>
    ): SmbStreamSessionInfo {
        synchronized(lock) {
            ensureServerStartedLocked()
            // 이전 세션의 잔여 스트림 소켓 정리
            closeActiveClientSockets()

            val port = serverSocket?.localPort
                ?: throw IllegalStateException("로컬 SMB 스트림 서버 포트를 할당받지 못했습니다.")

            val token = UUID.randomUUID().toString()
            val subtitleAssets = siblingSubtitles.map { sub ->
                SubtitleAsset(
                    entry = sub,
                    language = guessSubtitleLanguage(sub.name)
                )
            }

            activeSession = ActiveStreamSession(
                token = token,
                host = host.trim(),
                auth = auth,
                shareName = shareName,
                videoEntry = videoEntry,
                subtitles = subtitleAssets,
                resolvedVideoSize = videoEntry.sizeBytes
            )

            val encodedVideoName = encodePathSegment(videoEntry.name)
            val videoUrl = "http://127.0.0.1:$port/stream/$token/$encodedVideoName"

            val mpvSubtitles = subtitleAssets.mapIndexed { idx, asset ->
                val encodedSubName = encodePathSegment(asset.entry.name)
                MpvSubtitleRequest(
                    url = "http://127.0.0.1:$port/sub/$token/$idx/$encodedSubName",
                    title = asset.entry.name,
                    language = asset.language
                )
            }

            return SmbStreamSessionInfo(
                token = token,
                videoUrl = videoUrl,
                subtitles = mpvSubtitles
            )
        }
    }

    /**
     * 현재 스트리밍 세션을 종료하고 열려 있는 클라이언트 소켓과 로컬 HTTP 서버를 정리합니다.
     */
    fun stop() {
        synchronized(lock) {
            activeSession = null
            closeActiveClientSockets()
            runCatching { serverSocket?.close() }
            serverSocket = null
            acceptExecutor?.shutdownNow()
            acceptExecutor = null
            workerExecutor?.shutdownNow()
            workerExecutor = null
        }
    }

    private fun ensureServerStartedLocked() {
        if (serverSocket != null && serverSocket?.isClosed == false) return

        val loopback = InetAddress.getByName("127.0.0.1")
        val srv = ServerSocket(0, 16, loopback)
        serverSocket = srv
        acceptExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "SmbStreamAccept").apply { isDaemon = true }
        }
        workerExecutor = Executors.newCachedThreadPool { r ->
            Thread(r, "SmbStreamWorker").apply { isDaemon = true }
        }

        acceptExecutor?.execute {
            while (!srv.isClosed) {
                val client = try {
                    srv.accept()
                } catch (_: SocketException) {
                    break
                } catch (e: IOException) {
                    if (!srv.isClosed) {
                        AppErrorLogger.logError(
                            tag = "SmbStreamServer",
                            message = "로컬 스트림 서버 accept 오류",
                            throwable = e
                        )
                    }
                    break
                }

                activeClientSockets.add(client)
                workerExecutor?.execute {
                    try {
                        handleClient(client)
                    } finally {
                        activeClientSockets.remove(client)
                        runCatching { client.close() }
                    }
                }
            }
        }
    }

    private fun closeActiveClientSockets() {
        val snapshot = activeClientSockets.toList()
        activeClientSockets.clear()
        snapshot.forEach { sock ->
            runCatching { sock.close() }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val requestHeaderText = readHttpRequestHeader(input) ?: return
            val lines = requestHeaderText.split("\r\n")
            val requestLine = lines.firstOrNull()?.trim().orEmpty()
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                writeSimpleError(output, 400, "Bad Request")
                return
            }

            val method = parts[0].uppercase(Locale.US)
            if (method != "GET" && method != "HEAD") {
                writeSimpleError(output, 405, "Method Not Allowed")
                return
            }

            val rawPath = parts[1].substringBefore('?')
            val headers = LinkedHashMap<String, String>()
            for (i in 1 until lines.size) {
                val line = lines[i]
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase(Locale.US)] =
                        line.substring(colon + 1).trim()
                }
            }

            val session = activeSession
            if (session == null) {
                writeSimpleError(output, 404, "No Active Stream")
                return
            }

            val segments = rawPath.trimStart('/').split('/')
            if (segments.size < 2 || segments[1] != session.token) {
                writeSimpleError(output, 404, "Not Found")
                return
            }

            when (segments[0]) {
                "stream" -> serveVideoStream(session, method, headers["range"], output)
                "sub" -> {
                    val index = segments.getOrNull(2)?.toIntOrNull()
                    if (index == null || index !in session.subtitles.indices) {
                        writeSimpleError(output, 404, "Subtitle Not Found")
                        return
                    }
                    serveSubtitle(session, session.subtitles[index], method, output)
                }
                else -> writeSimpleError(output, 404, "Not Found")
            }
        } catch (_: SocketException) {
            // libmpv가 시크하거나 종료하면서 소켓을 닫는 정상적인 상황
        } catch (e: Exception) {
            if (activeSession != null) {
                AppErrorLogger.logError(
                    tag = "SmbStreamServer",
                    message = "로컬 SMB 스트림 요청 처리 오류: ${e.localizedMessage ?: e.javaClass.simpleName}",
                    throwable = e
                )
            }
        }
    }

    private fun serveSubtitle(
        session: ActiveStreamSession,
        asset: SubtitleAsset,
        method: String,
        output: OutputStream
    ) {
        val bytes = asset.cachedBytes ?: readSmbBytesBlocking(
            host = session.host,
            auth = session.auth,
            shareName = session.shareName,
            relativePath = asset.entry.relativePath,
            maxBytes = MAX_SUBTITLE_BYTES
        ).also { asset.cachedBytes = it }

        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(Charsets.US_ASCII))
        if (method == "GET") {
            output.write(bytes)
        }
        output.flush()
    }

    private fun serveVideoStream(
        session: ActiveStreamSession,
        method: String,
        rangeHeader: String?,
        output: OutputStream
    ) {
        val smbPath = SmbShareClient.toSmbPath(session.videoEntry.relativePath)
        val mimeType = SmbFileEntry.guessMimeType(session.videoEntry.name)

        SMBClient(smbClient.buildSmbConfig()).use { client ->
            client.connect(session.host, SmbShareClient.DEFAULT_SMB_PORT).use { connection ->
                connection.authenticate(session.auth.toSmbjAuthContext()).use { smbSession ->
                    val share = smbSession.connectShare(session.shareName) as? DiskShare
                        ?: throw IllegalStateException("디스크 공유 연결 실패: ${session.shareName}")
                    share.use { diskShare ->
                        diskShare.openFile(
                            smbPath,
                            EnumSet.of(AccessMask.GENERIC_READ),
                            null,
                            SMB2ShareAccess.ALL,
                            SMB2CreateDisposition.FILE_OPEN,
                            null
                        ).use { smbFile ->
                            val fileSize = runCatching {
                                smbFile.fileInformation.standardInformation.endOfFile
                            }.getOrDefault(session.resolvedVideoSize).coerceAtLeast(0L)
                            if (fileSize > 0L) {
                                session.resolvedVideoSize = fileSize
                            }

                            if (fileSize == 0L) {
                                writeSimpleError(output, 404, "Empty File")
                                return
                            }

                            val range = parseRangeHeader(rangeHeader, fileSize)
                            if (rangeHeader != null && range == null) {
                                val resp = buildString {
                                    append("HTTP/1.1 416 Range Not Satisfiable\r\n")
                                    append("Content-Range: bytes */$fileSize\r\n")
                                    append("Connection: close\r\n\r\n")
                                }
                                output.write(resp.toByteArray(Charsets.US_ASCII))
                                output.flush()
                                return
                            }

                            val start = range?.first ?: 0L
                            val end = range?.second ?: (fileSize - 1L)
                            val contentLength = (end - start + 1L).coerceAtLeast(0L)

                            val responseHeader = buildString {
                                if (range != null) {
                                    append("HTTP/1.1 206 Partial Content\r\n")
                                    append("Content-Range: bytes $start-$end/$fileSize\r\n")
                                } else {
                                    append("HTTP/1.1 200 OK\r\n")
                                }
                                append("Content-Type: $mimeType\r\n")
                                append("Accept-Ranges: bytes\r\n")
                                append("Content-Length: $contentLength\r\n")
                                append("Connection: close\r\n\r\n")
                            }
                            output.write(responseHeader.toByteArray(Charsets.US_ASCII))
                            output.flush()

                            if (method == "HEAD") return

                            val buffer = ByteArray(STREAM_CHUNK_SIZE)
                            var offset = start
                            var remaining = contentLength

                            while (remaining > 0L && activeSession?.token == session.token) {
                                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                                val bytesRead = smbFile.read(buffer, offset, 0, toRead)
                                if (bytesRead <= 0) break
                                try {
                                    output.write(buffer, 0, bytesRead)
                                } catch (_: IOException) {
                                    // libmpv가 시크 또는 종료하며 소켓을 닫은 경우 즉시 중단
                                    break
                                }
                                offset += bytesRead
                                remaining -= bytesRead
                            }
                            runCatching { output.flush() }
                        }
                    }
                }
            }
        }
    }

    private fun readSmbBytesBlocking(
        host: String,
        auth: SmbAuthConfig,
        shareName: String,
        relativePath: String,
        maxBytes: Int
    ): ByteArray {
        val smbPath = SmbShareClient.toSmbPath(relativePath)
        SMBClient(smbClient.buildSmbConfig()).use { client ->
            client.connect(host, SmbShareClient.DEFAULT_SMB_PORT).use { connection ->
                connection.authenticate(auth.toSmbjAuthContext()).use { smbSession ->
                    val share = smbSession.connectShare(shareName) as? DiskShare
                        ?: throw IllegalStateException("디스크 공유 연결 실패: $shareName")
                    return share.use { diskShare ->
                        diskShare.openFile(
                            smbPath,
                            EnumSet.of(AccessMask.GENERIC_READ),
                            null,
                            SMB2ShareAccess.ALL,
                            SMB2CreateDisposition.FILE_OPEN,
                            null
                        ).use { smbFile ->
                            val out = ByteArrayOutputStream()
                            val buf = ByteArray(32 * 1024)
                            smbFile.inputStream.use { input ->
                                while (true) {
                                    val r = input.read(buf)
                                    if (r <= 0) break
                                    if (out.size() + r > maxBytes) break
                                    out.write(buf, 0, r)
                                }
                            }
                            out.toByteArray()
                        }
                    }
                }
            }
        }
    }

    private fun readHttpRequestHeader(input: InputStream): String? {
        val out = ByteArrayOutputStream(512)
        var state = 0
        while (out.size() < MAX_HTTP_HEADER_BYTES) {
            val b = input.read()
            if (b == -1) return null
            out.write(b)
            state = when (state) {
                0 -> if (b == '\r'.code) 1 else 0
                1 -> if (b == '\n'.code) 2 else if (b == '\r'.code) 1 else 0
                2 -> if (b == '\r'.code) 3 else 0
                3 -> if (b == '\n'.code) 4 else if (b == '\r'.code) 1 else 0
                else -> 0
            }
            if (state == 4) break
        }
        return if (state == 4) out.toString(StandardCharsets.US_ASCII.name()) else null
    }

    private fun parseRangeHeader(rangeHeader: String?, fileSize: Long): Pair<Long, Long>? {
        if (rangeHeader.isNullOrBlank()) return null
        val trimmed = rangeHeader.trim()
        if (!trimmed.startsWith("bytes=", ignoreCase = true)) return null
        val spec = trimmed.substring(6).substringBefore(',').trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return null

        val startPart = spec.substring(0, dash).trim()
        val endPart = spec.substring(dash + 1).trim()

        return if (startPart.isEmpty()) {
            // bytes=-N (끝에서 N바이트)
            val suffix = endPart.toLongOrNull() ?: return null
            if (suffix <= 0L) return null
            val start = (fileSize - suffix).coerceAtLeast(0L)
            start to (fileSize - 1L)
        } else {
            val start = startPart.toLongOrNull() ?: return null
            val end = if (endPart.isEmpty()) {
                fileSize - 1L
            } else {
                (endPart.toLongOrNull() ?: return null).coerceAtMost(fileSize - 1L)
            }
            if (start < 0L || start >= fileSize || end < start) return null
            start to end
        }
    }

    private fun writeSimpleError(output: OutputStream, code: Int, reason: String) {
        val body = "$code $reason".toByteArray(StandardCharsets.UTF_8)
        val resp = "HTTP/1.1 $code $reason\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        output.write(resp.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private fun encodePathSegment(raw: String): String {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8.name()).replace("+", "%20")
    }

    private fun guessSubtitleLanguage(fileName: String): String {
        val lower = fileName.lowercase(Locale.US)
        return when {
            lower.contains(".ko.") || lower.contains(".kor.") || lower.contains("korean") || lower.contains("한글") || lower.contains("한국") -> "kor"
            lower.contains(".en.") || lower.contains(".eng.") || lower.contains("english") -> "eng"
            lower.contains(".ja.") || lower.contains(".jpn.") || lower.contains("japanese") -> "jpn"
            else -> "kor"
        }
    }

    companion object {
        private const val STREAM_CHUNK_SIZE = 256 * 1024
        private const val MAX_HTTP_HEADER_BYTES = 16 * 1024
        private const val MAX_SUBTITLE_BYTES = 12 * 1024 * 1024
    }
}
