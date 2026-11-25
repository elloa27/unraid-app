package com.unraid.app.shares

import com.unraid.app.network.NetworkProxyManager
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/**
 * NetworkProxyManager의 현재 상태(홈 Wi-Fi 직접 연결 ↔ 내장 Tailscale tsnet 로컬 프록시)에 따라
 * SMB(TCP 445) 소켓 연결 경로를 자동으로 전환하는 SocketFactory.
 *
 * - 홈 Wi-Fi(프록시 비활성): 일반 TCP Socket으로 직접 연결
 * - 외부 망(tsnet 프록시 활성): 로컬 HTTP 프록시에 CONNECT 터널을 열고
 *   1바이트씩 정확히 \r\n\r\n 까지만 읽어 SMB 바이너리 스트림 오염 없이 Raw 소켓 반환
 */
class TsnetAwareSocketFactory : SocketFactory() {

    override fun createSocket(): Socket {
        val proxy = NetworkProxyManager.proxyConfig.value
        return if (proxy.isActive) {
            TsnetTunnelSocket(proxyHost = proxy.host, proxyPort = proxy.port)
        } else {
            Socket()
        }
    }

    override fun createSocket(host: String, port: Int): Socket {
        val proxy = NetworkProxyManager.proxyConfig.value
        return if (proxy.isActive) {
            TsnetTunnelSocket(proxyHost = proxy.host, proxyPort = proxy.port).apply {
                connect(InetSocketAddress.createUnresolved(host, port), DEFAULT_CONNECT_TIMEOUT_MS)
            }
        } else {
            Socket().apply {
                connect(InetSocketAddress(host, port), DEFAULT_CONNECT_TIMEOUT_MS)
            }
        }
    }

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int
    ): Socket {
        val proxy = NetworkProxyManager.proxyConfig.value
        return if (proxy.isActive) {
            TsnetTunnelSocket(proxyHost = proxy.host, proxyPort = proxy.port).apply {
                connect(InetSocketAddress.createUnresolved(host, port), DEFAULT_CONNECT_TIMEOUT_MS)
            }
        } else {
            Socket(host, port, localHost, localPort)
        }
    }

    override fun createSocket(host: InetAddress, port: Int): Socket {
        val proxy = NetworkProxyManager.proxyConfig.value
        return if (proxy.isActive) {
            val targetHost = host.hostAddress ?: host.hostName
            TsnetTunnelSocket(proxyHost = proxy.host, proxyPort = proxy.port).apply {
                connect(InetSocketAddress.createUnresolved(targetHost, port), DEFAULT_CONNECT_TIMEOUT_MS)
            }
        } else {
            Socket(host, port)
        }
    }

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int
    ): Socket {
        val proxy = NetworkProxyManager.proxyConfig.value
        return if (proxy.isActive) {
            val targetHost = address.hostAddress ?: address.hostName
            TsnetTunnelSocket(proxyHost = proxy.host, proxyPort = proxy.port).apply {
                connect(InetSocketAddress.createUnresolved(targetHost, port), DEFAULT_CONNECT_TIMEOUT_MS)
            }
        } else {
            Socket(address, port, localAddress, localPort)
        }
    }

    /**
     * 로컬 tsnet HTTP 프록시에 CONNECT 터널을 수립한 뒤 Raw TCP 소켓으로 동작하는 커스텀 Socket.
     * - 절대 BufferedReader를 사용하지 않고 InputStream.read()로 1바이트씩 정확히 \r\n\r\n 까지만 읽어
     *   SMB 협상 프레임이 미리 소비(과독)되는 버그를 원천 차단합니다.
     */
    private class TsnetTunnelSocket(
        private val proxyHost: String,
        private val proxyPort: Int
    ) : Socket() {

        override fun connect(endpoint: SocketAddress) {
            connect(endpoint, DEFAULT_CONNECT_TIMEOUT_MS)
        }

        override fun connect(endpoint: SocketAddress, timeout: Int) {
            val inetEndpoint = endpoint as? InetSocketAddress
                ?: throw IOException("지원하지 않는 소켓 주소 형식입니다: $endpoint")

            val targetHost = inetEndpoint.hostString?.takeIf { it.isNotBlank() }
                ?: inetEndpoint.address?.hostAddress
                ?: throw IOException("대상 호스트를 확인할 수 없습니다.")
            val targetPort = inetEndpoint.port
            val effectiveTimeout = if (timeout > 0) timeout else DEFAULT_CONNECT_TIMEOUT_MS

            // 1) 로컬 tsnet 프록시(127.0.0.1:<port>)에 TCP 연결
            super.connect(InetSocketAddress(proxyHost, proxyPort), effectiveTimeout)

            val previousSoTimeout = runCatching { soTimeout }.getOrDefault(0)
            try {
                soTimeout = effectiveTimeout

                // 2) HTTP CONNECT 터널 요청 전송
                val hostPort = if (targetHost.contains(":") && !targetHost.startsWith("[")) {
                    "[$targetHost]:$targetPort"
                } else {
                    "$targetHost:$targetPort"
                }
                val connectReq = "CONNECT $hostPort HTTP/1.1\r\nHost: $hostPort\r\n\r\n"
                val out = getOutputStream()
                out.write(connectReq.toByteArray(Charsets.US_ASCII))
                out.flush()

                // 3) InputStream.read() 1바이트 단위 읽기로 정확히 \r\n\r\n 까지만 소비
                val input = getInputStream()
                val headerBuffer = ByteArrayOutputStream(256)
                var crlfState = 0 // 0 -> \r(1) -> \n(2) -> \r(3) -> \n(4)
                while (headerBuffer.size() < MAX_CONNECT_HEADER_BYTES) {
                    val byteRead = input.read()
                    if (byteRead == -1) {
                        throw IOException("Tailscale 프록시 CONNECT 응답 수신 중 연결이 끊어졌습니다 ($hostPort).")
                    }
                    headerBuffer.write(byteRead)
                    crlfState = when (crlfState) {
                        0 -> if (byteRead == '\r'.code) 1 else 0
                        1 -> when (byteRead) {
                            '\n'.code -> 2
                            '\r'.code -> 1
                            else -> 0
                        }
                        2 -> if (byteRead == '\r'.code) 3 else 0
                        3 -> when (byteRead) {
                            '\n'.code -> 4
                            '\r'.code -> 1
                            else -> 0
                        }
                        else -> 0
                    }
                    if (crlfState == 4) break
                }

                if (crlfState != 4) {
                    throw IOException("Tailscale 프록시 CONNECT 응답 헤더가 비정상적입니다 ($hostPort).")
                }

                val headerText = headerBuffer.toString(Charsets.US_ASCII.name())
                val statusLine = headerText.lineSequence().firstOrNull().orEmpty().trim()
                val statusCode = statusLine.split(" ").getOrNull(1)?.toIntOrNull()
                if (statusCode != 200) {
                    throw IOException("Tailscale 프록시 터널 수립 실패 ($hostPort): $statusLine")
                }

                // 4) 핸드셰이크 완료 후 타임아웃 복원 (이후 SMBJ가 자체 soTimeout 적용)
                soTimeout = previousSoTimeout
            } catch (e: Exception) {
                runCatching { close() }
                if (e is IOException) throw e
                throw IOException("Tailscale 프록시 CONNECT 핸드셰이크 오류: ${e.message}", e)
            }
        }
    }

    companion object {
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 20_000
        private const val MAX_CONNECT_HEADER_BYTES = 16 * 1024
    }
}
