# Unraid Hub (Android - 2단계 내장 Tailscale 완료)

Proxmox 위 Unraid NAS와 여러 도커 서비스(Kavita 등)를 하나의 앱에서 관리하고, 외부에서도 별도 VPN 앱 없이 내장 Tailscale(`tsnet`)로 자동 연결되는 개인용 안드로이드 네이티브 앱입니다.

## 주요 기능
1. **Unraid 네이티브 대시보드**
   - Unraid 공식 GraphQL API (`<서버주소>/graphql`, 헤더 `x-api-key`) 연동
   - 어레이 상태/용량, 디스크(패리티/어레이/캐시) 목록 및 온도, 도커 컨테이너 제어(시작/중지/재시작) 및 당겨서 새로고침
   - 도커 컨테이너 라벨(`net.unraid.docker.webui`, `ports`)을 파싱해 웹 UI 주소 자동 감지 및 원클릭 실행
   - 대시보드 상단 실시간 연결 경로 배지(`홈 Wi-Fi 직접 연결` / `Tailscale 연결됨` / `Tailscale 연결 중...`)
2. **100% 전체 화면(PWA 모드) 도커 웹뷰 허브**
   - 상단 바·하단 내비게이션 바 없이 화면 전체를 웹뷰로 채우고, 우측 가장자리 드래그 가능 플로팅 메뉴로 빠른 전환
   - `WebViewPoolManager` 메모리 캐싱, `CookieManager` + DOM Storage, 뒤로가기(`canGoBack`), 개인 NAS 사설 SSL 허용
3. **내장 Tailscale(`tsnet`) 및 홈 Wi-Fi 자동 전환 (2단계)**
   - `tsnet-bridge/` Go 모듈을 GitHub Actions에서 `gomobile bind`(arm64)로 `.aar` 빌드해 앱에 직접 내장
   - `ConnectivityManager.NetworkCallback`으로 홈 Wi-Fi SSID 연결 여부를 실시간 감지하여, 홈 Wi-Fi에서는 직접 연결(Tailscale 중지), 외부(LTE/5G·외부 Wi-Fi)에서는 `127.0.0.1:<포트>` 로컬 HTTP/HTTPS(`CONNECT`) 프록시로 OkHttp와 WebView를 자동 전환
   - 연결 모드 선택(`자동 전환` / `항상 직접 연결` / `항상 Tailscale`), 현재 Wi-Fi 원클릭 등록, Auth Key 암호화 보관, Tailscale용 별도 Unraid URL 지원
4. **자체 에러 로그**
   - API, 웹뷰, 위치 권한, `tsnet` 시작 실패 오류를 설정 화면에서 즉시 확인 및 클립보드 복사

## GitHub Actions 자동 빌드 및 릴리스
- `main` 브랜치 푸시 시 `.github/workflows/build-apk.yml`이 Go `tsnetbridge.aar`(arm64) 빌드 → Android APK(Secrets 유무에 따라 서명된 Release 또는 Debug) 빌드 → **GitHub Releases** 자동 업로드를 수행합니다.
