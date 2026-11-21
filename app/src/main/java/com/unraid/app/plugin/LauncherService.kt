package com.unraid.app.plugin

import com.unraid.app.data.model.WebServiceSource

/**
 * 앱 런처, 전체 화면 웹뷰, 서비스 전용 네이티브 UI 플러그인에서 공통으로 사용하는 통합 서비스 모델.
 * 플러그인의 matches() 메서드가 도커 이미지명, 컨테이너명, 앱 이름/URL을 기반으로 자동 매칭할 수 있습니다.
 */
data class LauncherService(
    val id: String,
    val name: String,
    val url: String,
    val source: WebServiceSource,
    val isRunning: Boolean = true,
    val iconUrl: String? = null,
    val containerName: String? = null,
    val imageName: String? = null,
    val preferNativeUi: Boolean = true
)
