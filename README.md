# Unraid Hub (Android - 동적 외부 플러그인 엔진 + 내장 libmpv)

Proxmox 위 Unraid NAS와 여러 도커 웹 서비스(Kavita, Emby 등)를 하나의 앱에서 관리하고, 내장 Tailscale(`tsnet`)로 홈 Wi-Fi ↔ 외부 망을 자동 전환하며, **앱을 다시 빌드하지 않고도 `.zip` 파일이나 URL로 서비스 전용 UI 플러그인을 동적 설치**할 수 있는 안드로이드 네이티브 앱입니다.

## 주요 기능
1. **Unraid 네이티브 대시보드**
   - Unraid 공식 GraphQL API (`<서버주소>/graphql`, 헤더 `x-api-key`) 연동
   - 어레이 상태/용량, 디스크(패리티/어레이/캐시) 목록 및 온도, 도커 컨테이너 제어(시작/중지/재시작) 및 당겨서 새로고침
   - 도커 라벨(`net.unraid.docker.webui`, `net.unraid.docker.icon`, `ports`) 자동 감지 및 원클릭 실행
   - 대시보드 상단 실시간 연결 경로 배지(`홈 Wi-Fi 직접 연결` / `Tailscale 연결됨` / `Tailscale 연결 중...`)
2. **스마트폰 홈 화면 스타일 아이콘 그리드 런처 & 100% 전체 화면(PWA) 웹뷰**
   - 사용자가 선택한 도커 앱 + 수동 등록 앱만 3~4열 아이콘 그리드로 배치 (`앱 편집 / 추가` 관리 시트 + 아이콘 길게 누르기 편집)
   - Coil(`coil-compose`, `coil-svg`) + `NetworkModule.okHttpClient`로 외부 Tailscale 망 및 사설 SSL 환경에서도 도커 아이콘 정상 로드
   - 100% 전체 화면 웹뷰 + 우측 가장자리 드래그 플로팅 메뉴 + `WebViewPoolManager` 메모리 캐싱 + `canGoBack` 뒤로가기
   - 설치된 외부 플러그인과 매칭되는 앱에 `Plugin` 배지 표시 및 `플러그인으로 열기` / `일반 웹뷰로 열기` 선택 지원
3. **내장 Tailscale(`tsnet`) 및 홈 Wi-Fi 자동 전환**
   - `tsnet-bridge/` Go 모듈을 GitHub Actions에서 `gomobile bind`(arm64)로 `.aar` 빌드해 앱에 직접 내장
   - `ConnectivityManager.NetworkCallback`으로 홈 Wi-Fi 감지 시 직접 연결, 외부(LTE/5G·외부 Wi-Fi)에서는 `127.0.0.1:<포트>` 로컬 프록시로 OkHttp·WebView·Coil·`libmpv`를 일괄 자동 전환
4. **동적 외부 플러그인 엔진 (`ExternalPluginManager` + `PluginRuntimeScreen`)**
   - 설정 → **외부 플러그인 관리**에서 로컬 `.zip` 파일 선택 또는 원격 `.zip` URL 입력으로 즉시 설치·업데이트·삭제
   - Zip Slip(`canonicalPath`) 경로 이탈 보안 검증, `filesDir/plugins/<id>/` 압축 해제, 플러그인 `id`별 격리된 `EncryptedSharedPreferences` 저장소
   - `WebViewAssetLoader` 가상 HTTPS 도메인(`https://plugins.unraidhub.local/<id>/...`)으로 서빙하며, Promise 기반 **`window.UnraidHub`** 네이티브 브릿지 자동 주입:
     - `UnraidHub.httpRequest({ url, method, headers, body })` — `tsnet` 프록시 + 사설 SSL 허용 + CORS 우회
     - `UnraidHub.storageGet(key)` / `storageSet(key, value)` / `storageRemove(key)` — 암호화 키-값 저장소
     - `UnraidHub.playVideo({ url, title, subtitle, startPositionSec, headers, subtitles })` — 내장 `libmpv` 실행 (`unraidhub:playerProgress`, `unraidhub:playerClosed` 이벤트 전달)
     - `UnraidHub.getContext()`, `UnraidHub.close()`, `UnraidHub.openWebView()`, `UnraidHub.logError(tag, message, detail)`
5. **내장 범용 `libmpv` 네이티브 비디오 플레이어**
   - `dev.jdtech.mpv:libmpv:1.0.0` (`arm64-v8a`) + `SurfaceView` 가로 전체 화면 몰입 플레이어
   - `NetworkProxyManager` 로컬 프록시(`http-proxy`) 및 사설 SSL(`tls-verify=no`) 자동 연동
   - HW/SW 디코딩 전환, `libass` 렌더링 + 커스텀 `.ttf`/`.otf`/`.ttc` 자막 폰트 관리 + 외부 자막 URL(`sub-add`), 제스처(더블탭 ±10초, 밝기/볼륨 스와이프) 및 오디오/자막/배속/비율/잠금 컨트롤
6. **자체 에러 로그**
   - Unraid API, 웹뷰, Tailscale `tsnet`, 외부 플러그인 JS/HTTP, `libmpv` 오류를 설정 화면에서 확인하고 원클릭 클립보드 복사

## 외부 플러그인 패키지 규격 (`manifest.json`)
```json
{
  "id": "emby-ui",
  "name": "Emby 전용 UI",
  "version": "1.0.0",
  "description": "Emby 라이브러리 탐색 및 내장 libmpv 재생 플러그인",
  "entry": "index.html",
  "updateUrl": "https://github.com/<owner>/<repo>/releases/latest/download/emby-ui.zip",
  "match": {
    "keywords": ["emby"],
    "ports": [8096, 8920]
  }
}
```
