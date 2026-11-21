# Unraid Hub (Android - 3단계 Emby + libmpv 완료)

Proxmox 위 Unraid NAS, 여러 도커 웹 서비스(Kavita 등), 그리고 Emby 미디어 서버(`libmpv` Direct Play)를 하나의 앱에서 관리하며, 내장 Tailscale(`tsnet`)로 홈 Wi-Fi ↔ 외부 망을 자동 전환하는 개인용 안드로이드 네이티브 앱입니다.

## 주요 기능
1. **Unraid 네이티브 대시보드**
   - Unraid 공식 GraphQL API (`<서버주소>/graphql`, 헤더 `x-api-key`) 연동
   - 어레이 상태/용량, 디스크(패리티/어레이/캐시) 목록 및 온도, 도커 컨테이너 제어(시작/중지/재시작) 및 당겨서 새로고침
   - 도커 라벨(`net.unraid.docker.webui`, `net.unraid.docker.icon`, `ports`) 자동 감지 및 원클릭 실행
   - 대시보드 상단 실시간 연결 경로 배지(`홈 Wi-Fi 직접 연결` / `Tailscale 연결됨` / `Tailscale 연결 중...`)
2. **스마트폰 홈 화면 스타일 아이콘 그리드 런처 & 100% 전체 화면(PWA) 웹뷰**
   - 사용자가 선택한 도커 앱 + 수동 등록 앱만 3~4열 아이콘 그리드로 배치 (`앱 편집 / 추가` 관리 시트 + 아이콘 길게 누르기 편집)
   - Coil(`coil-compose`, `coil-svg`) + `NetworkModule.okHttpClient`로 외부 Tailscale 망 및 사설 SSL 환경에서도 도커 아이콘·포스터 정상 로드
   - 100% 전체 화면 웹뷰 + 우측 가장자리 드래그 플로팅 메뉴 + `WebViewPoolManager` 메모리 캐싱 + `canGoBack` 뒤로가기
3. **내장 Tailscale(`tsnet`) 및 홈 Wi-Fi 자동 전환 (2단계)**
   - `tsnet-bridge/` Go 모듈을 GitHub Actions에서 `gomobile bind`(arm64)로 `.aar` 빌드해 앱에 직접 내장
   - `ConnectivityManager.NetworkCallback`으로 홈 Wi-Fi 감지 시 직접 연결, 외부(LTE/5G·외부 Wi-Fi)에서는 `127.0.0.1:<포트>` 로컬 프록시로 OkHttp·WebView·Coil·`libmpv`를 일괄 자동 전환
4. **서비스 전용 네이티브 UI 플러그인 & Emby `libmpv` 플레이어 (3단계)**
   - `ServicePlugin` / `ServicePluginRegistry` 플러그인 아키텍처 기반 첫 공식 플러그인 `EmbyServicePlugin` 탑재
   - Emby 자동 매칭(`emby` 키워드 또는 포트 `8096`/`8920`), `EncryptedSharedPreferences` 암호화 인증, 홈(이어보기/최근 추가/라이브러리)·검색·시리즈/시즌/에피소드·상세 탐색
   - `dev.jdtech.mpv:libmpv` Direct Play(트랜스코딩 없음), `http-proxy`/`tls-verify=no` 자동 브릿지, HW/SW 디코딩 전환, `libass` 및 외부 SRT 자막 자동 로드, 10초 간격 Emby 이어보기 동기화, 제스처(더블탭 ±10초, 밝기/볼륨 스와이프) 및 트랙/속도/비율/잠금 컨트롤
5. **자체 에러 로그**
   - Unraid API, 웹뷰, Tailscale `tsnet`, Emby API, `libmpv` 오류를 설정 화면에서 확인하고 원클릭 클립보드 복사
