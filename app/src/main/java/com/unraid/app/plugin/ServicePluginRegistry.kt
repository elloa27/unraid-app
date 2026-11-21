package com.unraid.app.plugin

/**
 * 등록된 모든 서비스 전용 네이티브 UI 플러그인을 중앙에서 관리하는 레지스트리.
 *
 * [3단계 Emby(libmpv) 등 신규 플러그인 등록 방법]
 * 1. `plugin/` 하위 패키지에 `ServicePlugin` 구현 클래스를 작성합니다.
 * 2. 아래 `registeredPlugins` 리스트에 인스턴스 1줄을 추가합니다.
 *    (예: `EmbyServicePlugin()`)
 * 3. 별도의 기존 UI 코드 수정 없이 런처 아이콘 매칭, Native 배지, 네이티브 화면 진입,
 *    롱프레스 전환 메뉴, 설정 화면 플러그인 섹션까지 자동으로 연결됩니다.
 */
object ServicePluginRegistry {

    private val registeredPlugins: List<ServicePlugin> = listOf(
        // 3단계에서 Emby(libmpv) 등 전용 네이티브 UI 플러그인을 여기에 추가합니다.
        // 예: EmbyServicePlugin(),
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
