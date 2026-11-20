package com.unraid.app.data.model

import kotlinx.serialization.Serializable

/**
 * 앱 내부 에러 로그 항목 (설정 화면에서 확인 및 클립보드 복사 용도)
 */
@Serializable
data class ErrorLogEntry(
    val timestamp: String,
    val tag: String,
    val message: String,
    val details: String? = null
)
