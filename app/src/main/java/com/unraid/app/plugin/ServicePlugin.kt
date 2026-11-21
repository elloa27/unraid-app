package com.unraid.app.plugin

import androidx.compose.runtime.Composable

/**
 * 특정 도커 서비스(Emby, Kavita 등)의 전용 네이티브 UI를 앱에 손쉽게 추가하기 위한 플러그인 인터페이스.
 *
 * 새 플러그인을 구현한 뒤 ServicePluginRegistry에 1줄만 등록하면
 * 런처 자동 매칭, Native 배지 표시, 탭 실행, 롱프레스 메뉴, 설정 화면 플러그인 섹션에 자동 연동됩니다.
 */
interface ServicePlugin {

    /** 플러그인 고유 식별자 (예: "emby", "kavita") */
    val pluginId: String

    /** 사용자에게 표시되는 플러그인 이름 (예: "Emby 전용 UI") */
    val displayName: String

    /** 런처 아이콘 우측 상단에 표시할 짧은 배지 문구 (기본값: "Native") */
    val badgeLabel: String
        get() = "Native"

    /** 설정 화면 등에 표시할 플러그인 설명 */
    val description: String

    /**
     * 전달된 런처 서비스가 이 플러그인을 지원하는지 자동 판별합니다.
     * 예: service.imageName / service.containerName / service.name 키워드 검사
     */
    fun matches(service: LauncherService): Boolean

    /**
     * 전용 네이티브 UI 화면의 Compose 진입점.
     *
     * @param service 실행 대상 서비스 정보 (URL, 이름, 컨테이너 메타데이터 포함)
     * @param onOpenWebViewFallback 전용 UI 내부에서 웹뷰(PWA) 모드로 즉시 전환할 때 호출
     * @param onExit 전용 UI를 종료하고 런처/이전 화면으로 복귀할 때 호출
     */
    @Composable
    fun Content(
        service: LauncherService,
        onOpenWebViewFallback: () -> Unit,
        onExit: () -> Unit
    )

    /**
     * 설정 화면의 '서비스별 전용 UI 플러그인' 영역에 별도 설정 UI를 노출할지 여부.
     */
    val hasSettings: Boolean
        get() = false

    /**
     * 설정 화면에 자동으로 꽂아 넣을 플러그인 전용 설정 UI (로그인/API 키/플레이어 옵션 등).
     */
    @Composable
    fun SettingsContent() {}
}
