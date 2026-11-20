package tsnetbridge

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"tailscale.com/tsnet"
)

var (
	mu           sync.Mutex
	tsServer     *tsnet.Server
	proxyServer  *http.Server
	proxyLn      net.Listener
	proxyPort    int
	running      bool
	tailscaleIP  string
	statusDetail = "중지됨"
)

// Start 는 앱 내부 저장소(stateDir)에 Tailscale 상태를 보관하며 tsnet 노드를 시작하고,
// 로컬 HTTP/HTTPS(CONNECT) 프록시 서버를 127.0.0.1:<자동할당포트> 에 열어 포트 번호를 반환합니다.
func Start(stateDir string, hostname string, authKey string) (int, error) {
	mu.Lock()
	// 이미 실행 중인 인스턴스가 있으면 먼저 안전하게 정리
	stopLocked()
	statusDetail = "Tailscale 노드 초기화 중..."
	mu.Unlock()

	trimmedDir := strings.TrimSpace(stateDir)
	if trimmedDir == "" {
		setStatus("오류: stateDir 경로가 비어 있습니다.")
		return 0, errors.New("stateDir 경로가 비어 있습니다")
	}
	if err := os.MkdirAll(trimmedDir, 0o700); err != nil {
		setStatus(fmt.Sprintf("상태 폴더 생성 실패: %v", err))
		return 0, fmt.Errorf("상태 폴더 생성 실패: %w", err)
	}

	cleanHost := strings.TrimSpace(hostname)
	if cleanHost == "" {
		cleanHost = "unraid-hub-android"
	}

	srv := &tsnet.Server{
		Dir:      trimmedDir,
		Hostname: cleanHost,
		AuthKey:  strings.TrimSpace(authKey),
	}

	// Tailscale 컨트롤 서버 연결 및 IP 할당 대기 (최대 45초)
	upCtx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()

	status, err := srv.Up(upCtx)
	if err != nil {
		_ = srv.Close()
		setStatus(fmt.Sprintf("Tailscale 연결 실패: %v", err))
		return 0, fmt.Errorf("Tailscale 연결 실패: %w", err)
	}

	assignedIP := ""
	if status != nil && len(status.TailscaleIPs) > 0 {
		assignedIP = status.TailscaleIPs[0].String()
	}

	// 사용 가능한 임의의 로컬 포트(127.0.0.1:0) 바인딩
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		_ = srv.Close()
		setStatus(fmt.Sprintf("로컬 프록시 포트 바인딩 실패: %v", err))
		return 0, fmt.Errorf("로컬 프록시 포트 바인딩 실패: %w", err)
	}

	tcpAddr, ok := ln.Addr().(*net.TCPAddr)
	if !ok || tcpAddr.Port <= 0 {
		_ = ln.Close()
		_ = srv.Close()
		setStatus("할당된 로컬 프록시 포트를 확인할 수 없습니다.")
		return 0, errors.New("로컬 프록시 포트 확인 실패")
	}

	transport := &http.Transport{
		DialContext:           srv.Dial,
		Proxy:                 nil,
		ForceAttemptHTTP2:     false,
		MaxIdleConns:          64,
		IdleConnTimeout:       60 * time.Second,
		TLSHandshakeTimeout:   15 * time.Second,
		ExpectContinueTimeout: 2 * time.Second,
	}

	handler := newTsnetProxyHandler(srv, transport)
	httpSrv := &http.Server{
		Handler:           handler,
		ReadHeaderTimeout: 30 * time.Second,
	}

	mu.Lock()
	tsServer = srv
	proxyLn = ln
	proxyServer = httpSrv
	proxyPort = tcpAddr.Port
	tailscaleIP = assignedIP
	running = true
	if assignedIP != "" {
		statusDetail = fmt.Sprintf("연결됨 (%s · 프록시 127.0.0.1:%d)", assignedIP, tcpAddr.Port)
	} else {
		statusDetail = fmt.Sprintf("연결됨 (프록시 127.0.0.1:%d)", tcpAddr.Port)
	}
	mu.Unlock()

	go func() {
		if serveErr := httpSrv.Serve(ln); serveErr != nil && !errors.Is(serveErr, http.ErrServerClosed) {
			mu.Lock()
			if proxyServer == httpSrv {
				running = false
				statusDetail = fmt.Sprintf("로컬 프록시 중단: %v", serveErr)
			}
			mu.Unlock()
		}
	}()

	return tcpAddr.Port, nil
}

// Stop 은 로컬 프록시 서버와 내장 tsnet 노드를 안전하게 종료합니다.
func Stop() {
	mu.Lock()
	defer mu.Unlock()
	stopLocked()
	statusDetail = "중지됨"
}

// IsRunning 은 내장 Tailscale 프록시가 현재 동작 중인지 여부를 반환합니다.
func IsRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return running
}

// GetStatus 는 할당된 Tailscale IP와 프록시 포트 등 현재 상태 문자열을 반환합니다.
func GetStatus() string {
	mu.Lock()
	defer mu.Unlock()
	if running && tailscaleIP != "" {
		return fmt.Sprintf("연결됨 (%s · 127.0.0.1:%d)", tailscaleIP, proxyPort)
	}
	return statusDetail
}

func stopLocked() {
	if proxyServer != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		_ = proxyServer.Shutdown(ctx)
		cancel()
		proxyServer = nil
	}
	if proxyLn != nil {
		_ = proxyLn.Close()
		proxyLn = nil
	}
	if tsServer != nil {
		_ = tsServer.Close()
		tsServer = nil
	}
	proxyPort = 0
	tailscaleIP = ""
	running = false
}

func setStatus(msg string) {
	mu.Lock()
	statusDetail = msg
	mu.Unlock()
}

// newTsnetProxyHandler 는 일반 HTTP, WebSocket 업그레이드, HTTPS CONNECT 터널링을 모두
// tsnet.Server.Dial 을 통해 Tailscale 네트워크로 중계하는 프록시 핸들러를 생성합니다.
func newTsnetProxyHandler(srv *tsnet.Server, transport *http.Transport) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodConnect {
			handleConnectTunnel(w, r, srv)
			return
		}

		if isWebSocketUpgrade(r) {
			handleHttpUpgradeTunnel(w, r, srv)
			return
		}

		handleHttpForward(w, r, transport)
	})
}

func handleConnectTunnel(w http.ResponseWriter, r *http.Request, srv *tsnet.Server) {
	targetAddr := r.Host
	if !hasPort(targetAddr) {
		targetAddr = net.JoinHostPort(targetAddr, "443")
	}

	dialCtx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
	defer cancel()

	upstreamConn, err := srv.Dial(dialCtx, "tcp", targetAddr)
	if err != nil {
		http.Error(w, fmt.Sprintf("Tailscale 대상 연결 실패 (%s): %v", targetAddr, err), http.StatusBadGateway)
		return
	}

	hijacker, ok := w.(http.Hijacker)
	if !ok {
		_ = upstreamConn.Close()
		http.Error(w, "프록시 소켓 Hijack을 지원하지 않습니다.", http.StatusInternalServerError)
		return
	}

	clientConn, clientBuf, err := hijacker.Hijack()
	if err != nil {
		_ = upstreamConn.Close()
		return
	}

	_, err = io.WriteString(clientConn, "HTTP/1.1 200 Connection Established\r\n\r\n")
	if err != nil {
		_ = clientConn.Close()
		_ = upstreamConn.Close()
		return
	}

	// 버퍼에 남아 있는 선행 바이트가 있으면 먼저 업스트림으로 전송
	if clientBuf != nil && clientBuf.Reader.Buffered() > 0 {
		buffered := make([]byte, clientBuf.Reader.Buffered())
		if n, readErr := clientBuf.Read(buffered); readErr == nil && n > 0 {
			_, _ = upstreamConn.Write(buffered[:n])
		}
	}

	pipeBidirectional(clientConn, upstreamConn)
}

func handleHttpUpgradeTunnel(w http.ResponseWriter, r *http.Request, srv *tsnet.Server) {
	targetHost := r.URL.Host
	if targetHost == "" {
		targetHost = r.Host
	}
	if !hasPort(targetHost) {
		targetHost = net.JoinHostPort(targetHost, "80")
	}

	dialCtx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
	defer cancel()

	upstreamConn, err := srv.Dial(dialCtx, "tcp", targetHost)
	if err != nil {
		http.Error(w, fmt.Sprintf("WebSocket 업스트림 연결 실패: %v", err), http.StatusBadGateway)
		return
	}

	hijacker, ok := w.(http.Hijacker)
	if !ok {
		_ = upstreamConn.Close()
		http.Error(w, "WebSocket Hijack 미지원", http.StatusInternalServerError)
		return
	}

	clientConn, clientBuf, err := hijacker.Hijack()
	if err != nil {
		_ = upstreamConn.Close()
		return
	}

	// 업스트림 서버에 원본 HTTP 업그레이드 요청 전달
	r.RequestURI = ""
	if err := r.Write(upstreamConn); err != nil {
		_ = clientConn.Close()
		_ = upstreamConn.Close()
		return
	}

	if clientBuf != nil && clientBuf.Reader.Buffered() > 0 {
		buffered := make([]byte, clientBuf.Reader.Buffered())
		if n, readErr := clientBuf.Read(buffered); readErr == nil && n > 0 {
			_, _ = upstreamConn.Write(buffered[:n])
		}
	}

	pipeBidirectional(clientConn, upstreamConn)
}

func handleHttpForward(w http.ResponseWriter, r *http.Request, transport *http.Transport) {
	outReq := r.Clone(r.Context())
	outReq.RequestURI = ""
	if outReq.URL.Scheme == "" {
		outReq.URL.Scheme = "http"
	}
	if outReq.URL.Host == "" {
		outReq.URL.Host = r.Host
	}
	removeHopByHopHeaders(outReq.Header)

	resp, err := transport.RoundTrip(outReq)
	if err != nil {
		http.Error(w, fmt.Sprintf("Tailscale HTTP 프록시 오류: %v", err), http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()

	removeHopByHopHeaders(resp.Header)
	copyHeaders(w.Header(), resp.Header)
	w.WriteHeader(resp.StatusCode)
	_, _ = io.Copy(w, resp.Body)
}

func pipeBidirectional(a net.Conn, b net.Conn) {
	var wg sync.WaitGroup
	wg.Add(2)

	copyOneWay := func(dst net.Conn, src net.Conn) {
		defer wg.Done()
		_, _ = io.Copy(dst, src)
		if tcpDst, ok := dst.(*net.TCPConn); ok {
			_ = tcpDst.CloseWrite()
		}
	}

	go copyOneWay(b, a)
	go copyOneWay(a, b)

	wg.Wait()
	_ = a.Close()
	_ = b.Close()
}

func isWebSocketUpgrade(r *http.Request) bool {
	return strings.EqualFold(r.Header.Get("Upgrade"), "websocket")
}

func hasPort(hostPort string) bool {
	_, _, err := net.SplitHostPort(hostPort)
	return err == nil
}

func removeHopByHopHeaders(header http.Header) {
	hopHeaders := []string{
		"Proxy-Connection",
		"Proxy-Authenticate",
		"Proxy-Authorization",
		"Keep-Alive",
		"TE",
		"Trailer",
		"Transfer-Encoding",
	}
	for _, h := range hopHeaders {
		header.Del(h)
	}
}

func copyHeaders(dst, src http.Header) {
	for k, values := range src {
		for _, v := range values {
			dst.Add(k, v)
		}
	}
}
