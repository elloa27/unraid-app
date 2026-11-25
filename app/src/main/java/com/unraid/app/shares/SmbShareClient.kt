package com.unraid.app.shares

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.share.DiskShare
import com.unraid.app.data.local.AppErrorLogger
import com.unraid.app.network.NetworkProxyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * SMBJ 기반 Unraid SMB2/SMB3 파일 탐색·관리 클라이언트.
 * - TsnetAwareSocketFactory를 통해 홈 Wi-Fi 직결 및 내장 Tailscale(tsnet) 프록시를 자동 전환합니다.
 * - 모든 연산은 Dispatchers.IO에서 실행되며 Connection/Session/DiskShare/File 리소스를 안전하게 닫습니다.
 */
class SmbShareClient(
    private val socketFactory: TsnetAwareSocketFactory = TsnetAwareSocketFactory()
) {

    internal fun buildSmbConfig(): SmbConfig {
        return SmbConfig.builder()
            .withSocketFactory(socketFactory)
            .withTimeout(120, TimeUnit.SECONDS)
            .withSoTimeout(180, TimeUnit.SECONDS)
            .withMultiProtocolNegotiate(true)
            .build()
    }

    /**
     * SMB 연결 및 인증 테스트 (sampleShareName이 지정되면 해당 공유 마운트까지 검증)
     */
    suspend fun testConnection(
        host: String,
        auth: SmbAuthConfig,
        sampleShareName: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanHost = host.trim()
        if (cleanHost.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("SMB 서버 호스트가 설정되지 않았습니다."))
        }
        val routeDesc = currentRouteDescription()
        runCatching {
            SMBClient(buildSmbConfig()).use { client ->
                client.connect(cleanHost, DEFAULT_SMB_PORT).use { connection ->
                    connection.authenticate(auth.toSmbjAuthContext()).use { session ->
                        if (!sampleShareName.isNullOrBlank()) {
                            val share = session.connectShare(sampleShareName) as? DiskShare
                                ?: throw IllegalStateException("'$sampleShareName' 공유 폴더에 디스크 공유로 접근할 수 없습니다.")
                            share.use { it.list("") }
                            "SMB 연결 성공 ($cleanHost/$sampleShareName · $routeDesc)"
                        } else {
                            "SMB 인증 성공 ($cleanHost · $routeDesc)"
                        }
                    }
                }
            }
        }.onFailure { e ->
            logSmbError("연결 테스트", cleanHost, sampleShareName.orEmpty(), "", e)
        }
    }

    /**
     * 지정 공유 폴더/하위 경로의 파일 및 디렉터리 목록을 조회합니다.
     */
    suspend fun listDirectory(
        host: String,
        auth: SmbAuthConfig,
        shareName: String,
        relativePath: String
    ): Result<List<SmbFileEntry>> = withContext(Dispatchers.IO) {
        val cleanHost = host.trim()
        val normPath = normalizeRelativePath(relativePath)
        val smbPath = toSmbPath(normPath)

        runCatching {
            withDiskShare(cleanHost, auth, shareName) { share ->
                share.list(smbPath)
                    .asSequence()
                    .filter { info ->
                        val name = info.fileName
                        name.isNotBlank() && name != "." && name != ".."
                    }
                    .map { info ->
                        val name = info.fileName
                        val isDir = EnumWithValue.EnumUtils.isSet(
                            info.fileAttributes,
                            FileAttributes.FILE_ATTRIBUTE_DIRECTORY
                        )
                        val childRelative = if (normPath.isEmpty()) name else "$normPath/$name"
                        SmbFileEntry(
                            name = name,
                            relativePath = childRelative,
                            isDirectory = isDir,
                            sizeBytes = if (isDir) 0L else info.endOfFile.coerceAtLeast(0L),
                            lastModifiedMillis = runCatching { info.lastWriteTime.toEpochMillis() }.getOrDefault(0L)
                        )
                    }
                    .sortedWith(
                        compareByDescending<SmbFileEntry> { it.isDirectory }
                            .thenBy { it.name.lowercase() }
                    )
                    .toList()
            }
        }.onFailure { e ->
            logSmbError("폴더 목록 조회", cleanHost, shareName, normPath, e)
        }
    }

    /**
     * 새 하위 폴더를 생성합니다.
     */
    suspend fun createDirectory(
        host: String,
        auth: SmbAuthConfig,
        shareName: String,
        parentRelativePath: String,
        newFolderName: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val cleanName = newFolderName.trim()
        if (cleanName.isBlank() || cleanName.contains('/') || cleanName.contains('\\')) {
            return@withContext Result.failure(IllegalArgumentException("올바른 폴더 이름을 입력해 주세요."))
        }
        val parentNorm = normalizeRelativePath(parentRelativePath)
        val targetRelative = if (parentNorm.isEmpty()) cleanName else "$parentNorm/$cleanName"
        val smbPath = toSmbPath(targetRelative)

        runCatching {
            withDiskShare(host.trim(), auth, shareName) { share ->
                if (share.folderExists(smbPath)) {
                    throw IllegalStateException("같은 이름의 폴더가 이미 존재합니다: $cleanName")
                }
                share.mkdir(smbPath)
            }
        }.onFailure { e ->
            logSmbError("새 폴더 생성", host, shareName, targetRelative, e)
        }
    }

    /**
     * 파일 또는 폴더의 이름을 변경합니다.
     */
    suspend fun renameEntry(
        host: String,
        auth: SmbAuthConfig,
        shareName: String,
        entry: SmbFileEntry,
        newName: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val cleanName = newName.trim()
        if (cleanName.isBlank() || cleanName.contains('/') || cleanName.contains('\\')) {
            return@withContext Result.failure(IllegalArgumentException("올바른 새 이름을 입력해 주세요."))
        }
        val oldNorm = normalizeRelativePath(entry.relativePath)
        val parentNorm = oldNorm.substringBeforeLast('/', "")
        val newNorm = if (parentNorm.isEmpty()) cleanName else "$parentNorm/$cleanName"
        val oldSmbPath = toSmbPath(oldNorm)
        val newSmbPath = toSmbPath(newNorm)

        runCatching {
            withDiskShare(host.trim(), auth, shareName) { share ->
                val access = EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_WRITE)
                if (entry.isDirectory) {
                    share.openDirectory(
                        oldSmbPath,
                        access,
                        null,
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OPEN,
                        null
                    ).use { dir ->
                        dir.rename(newSmbPath, false)
                    }
                } else {
                    share.openFile(
                        oldSmbPath,
                        access,
                        null,
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OPEN,
                        null
                    ).use { file ->
                        file.rename(newSmbPath, false)
                    }
                }
            }
        }.onFailure { e ->
            logSmbError("이름 변경 ($oldNorm -> $newNorm)", host, shareName, oldNorm, e)
        }
    }

    /**
     * 파일 또는 폴더를 삭제합니다. (폴더는 recursive 옵션에 따라 하위 항목 포함 재귀 삭제)
     */
    suspend fun deleteEntry(
        host: String,
        auth: SmbAuthConfig,
        shareName: String,
        entry: SmbFileEntry,
        recursive: Boolean = true
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val normPath = normalizeRelativePath(entry.relativePath)
        val smbPath = toSmbPath(normPath)

        runCatching {
            withDiskShare(host.trim(), auth, shareName) { share ->
                if (entry.isDirectory) {
                    share.rmdir(smbPath, recursive)
                } else {
                    share.rm(smbPath)
                }
            }
        }.onFailure { e ->
            logSmbError("항목 삭제", host, shareName, normPath, e)
        }
    }

    /**
     * SMB 파일을 지정 OutputStream으로 청크 스트리밍 다운로드합니다.
     */
    suspend fun downloadToStream(
        host: String,
        auth: SmbAuthConfig,
        shareName: String,
        relativePath: String,
        outputStream: OutputStream,
        onProgress: (transferredBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Result<Long> = withContext(Dispatchers.IO) {
        val normPath = normalizeRelativePath(relativePath)
        val smbPath = toSmbPath(normPath)

        runCatching {
            withDiskShare(host.trim(), auth, shareName) { share ->
                share.openFile(
                    smbPath,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null
                ).use { smbFile ->
                    val totalSize = runCatching {
                        smbFile.fileInformation.standardInformation.endOfFile
                    }.getOrDefault(-1L)

                    val buffer = ByteArray(IO_BUFFER_SIZE)
                    var copied = 0L
                    var lastReportAt = 0L

                    smbFile.inputStream.use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            outputStream.write(buffer, 0, read)
                            copied += read

                            val now = System.currentTimeMillis()
                            if (now - lastReportAt >= PROGRESS_THROTTLE_MS) {
                                lastReportAt = now
                                onProgress(copied, totalSize)
                            }
                        }
                    }
                    outputStream.flush()
                    onProgress(copied, if (totalSize > 0L) totalSize else copied)
                    copied
                }
            }
        }.onFailure { e ->
            logSmbError("파일 다운로드", host, shareName, normPath, e)
        }
    }

    /**
     * 로컬 InputStream 데이터를 SMB 공유 폴더에 스트림 업로드합니다.
     */
    suspend fun uploadFromStream(
        host: String,
        auth: SmbAuthConfig,
        shareName: String,
        parentRelativePath: String,
        fileName: String,
        inputStream: InputStream,
        totalBytes: Long = -1L,
        onProgress: (transferredBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Result<Long> = withContext(Dispatchers.IO) {
        val cleanName = fileName.trim().replace('/', '_').replace('\\', '_')
        val parentNorm = normalizeRelativePath(parentRelativePath)
        val targetRelative = if (parentNorm.isEmpty()) cleanName else "$parentNorm/$cleanName"
        val smbPath = toSmbPath(targetRelative)

        runCatching {
            withDiskShare(host.trim(), auth, shareName) { share ->
                share.openFile(
                    smbPath,
                    EnumSet.of(AccessMask.GENERIC_WRITE),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OVERWRITE_IF,
                    null
                ).use { smbFile ->
                    val buffer = ByteArray(IO_BUFFER_SIZE)
                    var written = 0L
                    var lastReportAt = 0L

                    smbFile.outputStream.use { output ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = inputStream.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            written += read

                            val now = System.currentTimeMillis()
                            if (now - lastReportAt >= PROGRESS_THROTTLE_MS) {
                                lastReportAt = now
                                onProgress(written, totalBytes)
                            }
                        }
                        output.flush()
                    }
                    onProgress(written, if (totalBytes > 0L) totalBytes else written)
                    written
                }
            }
        }.onFailure { e ->
            logSmbError("파일 업로드", host, shareName, targetRelative, e)
        }
    }

    /**
     * 인앱 이미지/텍스트/자막 미리보기용으로 최대 maxBytes 한도 내에서 안전하게 바이트를 읽어옵니다.
     */
    suspend fun readSmallFileBytes(
        host: String,
        auth: SmbAuthConfig,
        shareName: String,
        relativePath: String,
        maxBytes: Int = 10 * 1024 * 1024
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        val normPath = normalizeRelativePath(relativePath)
        val smbPath = toSmbPath(normPath)

        runCatching {
            withDiskShare(host.trim(), auth, shareName) { share ->
                share.openFile(
                    smbPath,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null
                ).use { smbFile ->
                    val fileSize = runCatching {
                        smbFile.fileInformation.standardInformation.endOfFile
                    }.getOrDefault(0L)
                    if (fileSize > maxBytes.toLong()) {
                        throw IllegalStateException("파일 크기가 미리보기 제한(${maxBytes / (1024 * 1024)}MB)을 초과했습니다.")
                    }

                    val out = ByteArrayOutputStream(fileSize.toInt().coerceIn(4096, maxBytes))
                    val buffer = ByteArray(64 * 1024)
                    smbFile.inputStream.use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            if (out.size() + read > maxBytes) {
                                throw IllegalStateException("파일 크기가 미리보기 제한을 초과했습니다.")
                            }
                            out.write(buffer, 0, read)
                        }
                    }
                    out.toByteArray()
                }
            }
        }.onFailure { e ->
            logSmbError("파일 미리보기 읽기", host, shareName, normPath, e)
        }
    }

    private inline fun <T> withDiskShare(
        host: String,
        auth: SmbAuthConfig,
        shareName: String,
        block: (DiskShare) -> T
    ): T {
        if (host.isBlank()) {
            throw IllegalArgumentException("SMB 서버 호스트가 설정되지 않았습니다.")
        }
        if (shareName.isBlank()) {
            throw IllegalArgumentException("공유 폴더 이름이 비어 있습니다.")
        }
        SMBClient(buildSmbConfig()).use { client ->
            client.connect(host, DEFAULT_SMB_PORT).use { connection ->
                connection.authenticate(auth.toSmbjAuthContext()).use { session ->
                    val share = session.connectShare(shareName) as? DiskShare
                        ?: throw IllegalStateException("'$shareName' 공유는 디스크 공유 형식이 아닙니다.")
                    return share.use(block)
                }
            }
        }
    }

    private fun logSmbError(
        operation: String,
        host: String,
        shareName: String,
        relativePath: String,
        error: Throwable
    ) {
        val proxy = NetworkProxyManager.proxyConfig.value
        val detail = buildString {
            appendLine("호스트: $host:$DEFAULT_SMB_PORT")
            appendLine("공유: $shareName")
            appendLine("경로: /$relativePath")
            appendLine("Tailscale 프록시 활성: ${proxy.isActive} (${if (proxy.isActive) "${proxy.host}:${proxy.port}" else "직결"})")
        }
        AppErrorLogger.logError(
            tag = "ShareSMB",
            message = "SMB $operation 실패: ${error.localizedMessage ?: error.javaClass.simpleName}",
            throwable = error,
            extraDetails = detail
        )
    }

    private fun currentRouteDescription(): String {
        val proxy = NetworkProxyManager.proxyConfig.value
        return if (proxy.isActive) "Tailscale 프록시 ${proxy.host}:${proxy.port}" else "홈 Wi-Fi 직접 연결"
    }

    companion object {
        const val DEFAULT_SMB_PORT = 445
        private const val IO_BUFFER_SIZE = 128 * 1024
        private const val PROGRESS_THROTTLE_MS = 120L

        fun normalizeRelativePath(path: String): String {
            return path
                .replace('\\', '/')
                .split('/')
                .filter { it.isNotBlank() && it != "." && it != ".." }
                .joinToString("/")
        }

        fun toSmbPath(relativePath: String): String {
            return normalizeRelativePath(relativePath).replace('/', '\\')
        }
    }
}
