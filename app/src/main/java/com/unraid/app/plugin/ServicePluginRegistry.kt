package com.unraid.app.plugin

import com.unraid.app.plugin.emby.EmbyServicePlugin

/**
 * 등록된 모든 서비스 전용 네이티브 UI 플러그인을 중앙에서 관리하는 레지스트리.
 *
 * [신규 플러그인 추가 방법]
 * 새 `ServicePlugin` 구현 클래스를 작성해 아래 `registeredPlugins` 리스트에 1줄만 추가하면
 * 런처 자동 매칭, Native 배지 표시, 네이티브 화면 진입, 롱프레스 메뉴, 설정 화면 플러그인 섹션에 자동 연동됩니다.
 */
object ServicePluginRegistry {

    private val registeredPlugins: List<ServicePlugin> = listOf(
        EmbyServicePlugin()
    )

    /** 등록된 전체 플러그인 목록 반환 */
    fun allPlugins(): List<ServicePlugin> = registeredPlugins

    /** 고유 ID로 플러그인 조회 */
    fun findById(pluginId: String): ServicePlugin? =
        registeredPlugins.firstOrNull { it.pluginId == pluginId }

    /** 런처 서비스와 매칭되는 첫 번째 전용 UI 플러그인 탐색 */
    fun findMatchingPlugin(service: LauncherService): ServicePlugin? =
        registeredPlugins.firstOrNull { it.matches(service) }
}
