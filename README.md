# Unraid Hub (Android MVP - 1단계)

Proxmox 위 Unraid NAS와 여러 도커 서비스(Kavita 등)를 하나의 앱에서 관리하기 위한 개인용 안드로이드 네이티브 앱입니다.

## 주요 기능 (1단계 MVP)
1. **Unraid 네이티브 대시보드**
   - Unraid 공식 GraphQL API (`<서버주소>/graphql`, 헤더 `x-api-key`) 연동
   - 어레이 상태/용량, 디스크(패리티/어레이/캐시) 목록 및 온도, 도커 컨테이너 목록 표시
   - 도커 컨테이너 시작(Start) / 중지(Stop) / 재시작(Restart) 제어 및 당겨서 새로고침(Pull-to-Refresh)
2. **도커 웹뷰(WebView) 통합 허브**
   - 설정에서 등록한 Kavita 등 도커 웹 서비스를 주소창 없는 전체 화면으로 전환
   - `WebViewPoolManager` 메모리 캐싱으로 다른 탭 이동 시에도 새로고침 없이 상태 유지
   - `CookieManager` + DOM Storage 활성화 및 안드로이드 뒤로가기(`canGoBack`) 연동
3. **설정 및 자체 에러 로그**
   - Unraid 서버 URL(DataStore) 및 API 키(`EncryptedSharedPreferences`) 저장 + 연결 테스트
   - 도커 웹뷰 목록 추가/수정/삭제
   - API/웹뷰/앱 내부 오류 확인 및 원클릭 클립보드 복사
4. **확장 대비 구조**
   - **2단계(Tailscale `tsnet` 내장 대비):** `NetworkProxyManager.enableLocalProxy(port)` 호출만으로 OkHttp와 Android WebView(`androidx.webkit.ProxyController`) 트래픽이 중앙 로컬 프록시(`127.0.0.1:포트`)를 거치도록 네트워크 레이어 분리
   - **3단계(Emby 플레이어 대비):** `AppDestination` 기반 탭 구조로 손쉬운 탭 추가 지원

## GitHub Actions 자동 APK 빌드 및 배포
- `main` 브랜치에 푸시할 때마다 `.github/workflows/build-apk.yml`이 자동 실행되어 APK를 빌드하고 **GitHub Releases**에 업로드합니다.
- 서명 키 Secrets 미등록 시: 즉시 설치 가능한 **Debug APK** 자동 빌드/업로드
- 서명 키 Secrets(`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) 등록 시: 서명된 **Release APK** 자동 빌드/업로드
