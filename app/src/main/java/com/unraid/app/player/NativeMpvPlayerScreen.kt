package com.unraid.app.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.unraid.app.data.local.AppErrorLogger
import com.unraid.app.network.NetworkModule
import com.unraid.app.network.NetworkProxyManager
import dev.jdtech.mpv.MPVLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

/**
 * 플러그인(window.UnraidHub.playVideo)에서 전달하는 외부 자막 트랙 정보
 */
data class MpvSubtitleRequest(
    val url: String,
    val title: String = "",
    val language: String = "und"
)

/**
 * 플러그인(window.UnraidHub.playVideo)에서 전달하는 범용 libmpv 재생 요청 모델
 */
data class MpvPlayRequest(
    val sessionId: String = UUID.randomUUID().toString(),
    val url: String,
    val title: String = "",
    val subtitle: String = "",
    val startPositionSec: Double = 0.0,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<MpvSubtitleRequest> = emptyList(),
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val previousTitle: String = "",
    val nextTitle: String = ""
)

private data class MpvTrackInfo(
    val id: Int,
    val type: String,
    val title: String,
    val lang: String,
    val selected: Boolean,
    val isExternal: Boolean
) {
    val displayLabel: String
        get() = buildList {
            if (title.isNotBlank()) add(title)
            if (lang.isNotBlank() && !title.contains(lang, ignoreCase = true)) {
                add(lang.uppercase(Locale.getDefault()))
            }
            if (isExternal) add("외부")
        }.joinToString(" · ").ifBlank { "트랙 #$id" }
}

private enum class MpvDialogType {
    NONE, AUDIO, SUBTITLE, SPEED
}

/**
 * 메인 앱에 내장된 범용 libmpv + SurfaceView 전체 화면(가로 몰입 모드) 네이티브 비디오 플레이어.
 *
 * - 내장 Tailscale(tsnet) 로컬 프록시(http-proxy) 및 자체 서명 SSL 허용(tls-verify=no) 자동 연동
 * - 하드웨어 디코딩(hwdec=auto-safe) / 소프트웨어 디코딩 실시간 전환
 * - libass ASS/SSA 렌더링, 앱 내 커스텀 폰트 폴더(sub-fonts-dir), 외부 자막 URL(sub-add) 지원
 * - 10초 주기 onProgress(positionSec, durationSec) 및 종료 시 onClosed(positionSec, durationSec, completed) 콜백 전달
 */
@Composable
fun NativeMpvPlayerScreen(
    request: MpvPlayRequest,
    playerSettingsStore: PlayerSettingsStore,
    onProgress: (positionSec: Double, durationSec: Double) -> Unit,
    onClosed: (positionSec: Double, durationSec: Double, completed: Boolean) -> Unit,
    onNavigateEpisode: ((direction: String, positionSec: Double, durationSec: Double, completed: Boolean) -> Unit)? = null,
    manageActivityOrientation: Boolean = true
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var mpvInstance by remember { mutableStateOf<MPVLib?>(null) }
    var fileLoadRequested by remember { mutableStateOf(false) }
    var externalSubsLoaded by remember { mutableStateOf(false) }
    var initError by remember { mutableStateOf<String?>(null) }
    var reachedEof by remember { mutableStateOf(false) }
    var closeReported by remember { mutableStateOf(false) }
    var switchingMessage by remember { mutableStateOf<String?>(null) }

    var isPaused by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(true) }
    var positionSeconds by remember { mutableDoubleStateOf(request.startPositionSec.coerceAtLeast(0.0)) }
    var durationSeconds by remember { mutableDoubleStateOf(0.0) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableFloatStateOf(0f) }

    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var fillScreen by remember { mutableStateOf(false) }
    var useHardwareDecoding by remember {
        mutableStateOf(playerSettingsStore.getSettings().preferHardwareDecoding)
    }
    var isLocked by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var activeDialog by remember { mutableStateOf(MpvDialogType.NONE) }

    var audioTracks by remember { mutableStateOf<List<MpvTrackInfo>>(emptyList()) }
    var subtitleTracks by remember { mutableStateOf<List<MpvTrackInfo>>(emptyList()) }

    var hudText by remember { mutableStateOf<String?>(null) }
    var hudIconType by remember { mutableStateOf<String?>(null) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    fun finishAndNotifyClosed() {
        if (closeReported) return
        closeReported = true
        val pos = positionSeconds.coerceAtLeast(0.0)
        val dur = durationSeconds.coerceAtLeast(0.0)
        val completed = reachedEof || (dur > 0.0 && pos >= dur * 0.90)
        onClosed(pos, dur, completed)
    }

    fun triggerEpisodeNavigation(direction: String) {
        val navigator = onNavigateEpisode ?: return
        if (switchingMessage != null) return
        val label = if (direction == "next") {
            request.nextTitle.ifBlank { "다음화" }
        } else {
            request.previousTitle.ifBlank { "이전화" }
        }
        switchingMessage = "$label 불러오는 중..."
        // 새 에피소드로 교체될 때 onDispose가 중복으로 onClosed를 호출하지 않도록 표시
        closeReported = true
        runCatching { mpvInstance?.setPropertyString("pause", "yes") }
        val pos = positionSeconds.coerceAtLeast(0.0)
        val dur = durationSeconds.coerceAtLeast(0.0)
        val completed = reachedEof || (dur > 0.0 && pos >= dur * 0.90)
        navigator(direction, pos, dur, completed)
    }

    // 에피소드 전환이 실패하거나 타임아웃된 경우 상태 복구
    LaunchedEffect(switchingMessage) {
        if (switchingMessage != null) {
            delay(15_000)
            switchingMessage = null
            closeReported = false
        }
    }

    BackHandler(enabled = true) {
        if (isLocked) {
            controlsVisible = true
        } else {
            finishAndNotifyClosed()
        }
    }

    fun refreshTracks(targetMpv: MPVLib? = mpvInstance) {
        val mpv = targetMpv ?: return
        runCatching {
            val count = mpv.getPropertyString("track-list/count")?.toIntOrNull() ?: 0
            val audios = mutableListOf<MpvTrackInfo>()
            val subs = mutableListOf<MpvTrackInfo>()
            for (i in 0 until count) {
                val type = mpv.getPropertyString("track-list/$i/type").orEmpty()
                if (type != "audio" && type != "sub") continue
                val id = mpv.getPropertyString("track-list/$i/id")?.toIntOrNull() ?: continue
                val title = mpv.getPropertyString("track-list/$i/title").orEmpty()
                val lang = mpv.getPropertyString("track-list/$i/lang").orEmpty()
                val selected = mpv.getPropertyString("track-list/$i/selected") == "yes"
                val external = mpv.getPropertyString("track-list/$i/external") == "yes"
                val info = MpvTrackInfo(id, type, title, lang, selected, external)
                if (type == "audio") audios += info else subs += info
            }
            audioTracks = audios
            subtitleTracks = subs
        }
    }

    fun showTransientHud(type: String, text: String) {
        hudIconType = type
        hudText = text
    }

    // 제스처 HUD 자동 숨김
    LaunchedEffect(hudText) {
        if (hudText != null) {
            delay(1_100)
            hudText = null
            hudIconType = null
        }
    }

    // 컨트롤 오버레이 자동 숨김
    LaunchedEffect(controlsVisible, isPaused, activeDialog, isScrubbing) {
        if (controlsVisible && !isPaused && activeDialog == MpvDialogType.NONE && !isScrubbing) {
            delay(4_000)
            controlsVisible = false
        }
    }

    // 10초마다 플러그인에 재생 진행 상태(positionSec, durationSec) 콜백 전달
    LaunchedEffect(mpvInstance) {
        if (mpvInstance == null) return@LaunchedEffect
        while (true) {
            delay(10_000)
            onProgress(positionSeconds.coerceAtLeast(0.0), durationSeconds.coerceAtLeast(0.0))
        }
    }

    // 가로 몰입 모드 + libmpv 인스턴스 생명주기
    DisposableEffect(request.sessionId) {
        val previousOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (manageActivityOrientation) {
            activity?.let { act ->
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                WindowCompat.getInsetsController(act.window, act.window.decorView).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
        }

        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var isDisposed = false
        var createdMpv: MPVLib? = null
        val preferredSubFont = playerSettingsStore.getSettings().subtitleFontName.trim()

        val observer = object : MPVLib.EventObserver {
            override fun eventProperty(property: String) = Unit

            override fun eventProperty(property: String, value: Long) {
                if (property == "track-list/count") {
                    mainHandler.post { refreshTracks(createdMpv) }
                }
            }

            override fun eventProperty(property: String, value: Boolean) {
                mainHandler.post {
                    when (property) {
                        "pause" -> isPaused = value
                        "paused-for-cache" -> isBuffering = value
                        "eof-reached" -> if (value) {
                            reachedEof = true
                            controlsVisible = true
                        }
                    }
                }
            }

            override fun eventProperty(property: String, value: String) = Unit

            override fun eventProperty(property: String, value: Double) {
                mainHandler.post {
                    when (property) {
                        "time-pos" -> if (!isScrubbing) positionSeconds = value
                        "duration" -> if (value > 0.0) durationSeconds = value
                    }
                }
            }

            override fun event(eventId: Int) {
                mainHandler.post {
                    when (eventId) {
                        MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                            isBuffering = false
                            val mpv = createdMpv
                            if (mpv != null && !externalSubsLoaded && request.subtitles.isNotEmpty()) {
                                externalSubsLoaded = true
                                // 외부 자막(특히 SAMI .smi / CP949 인코딩)을 IO 스레드에서 다운로드·UTF-8/ASS 변환 후 로컬 로드
                                ioScope.launch {
                                    val preparedSubs = SubtitlePreprocessor.prepareSubtitles(
                                        context = context.applicationContext,
                                        sessionId = request.sessionId,
                                        subtitles = request.subtitles,
                                        headers = request.headers,
                                        preferredFontName = preferredSubFont
                                    )
                                    if (isDisposed || preparedSubs.isEmpty()) return@launch

                                    mainHandler.post {
                                        if (isDisposed) return@post
                                        // 현재 선택된 내장 한국어 자막이 없다면 첫 번째 외부(한국어 우선) 자막을 자동 활성화
                                        val currentSid = runCatching { mpv.getPropertyString("sid") }.getOrNull()
                                        var hasSelectedKoreanTrack = false
                                        if (!currentSid.isNullOrBlank() && currentSid != "no" && currentSid != "auto") {
                                            val trackCount = runCatching {
                                                mpv.getPropertyString("track-list/count")?.toIntOrNull() ?: 0
                                            }.getOrDefault(0)
                                            for (i in 0 until trackCount) {
                                                val tType = mpv.getPropertyString("track-list/$i/type")
                                                val tSelected = mpv.getPropertyString("track-list/$i/selected") == "yes"
                                                val tLang = mpv.getPropertyString("track-list/$i/lang").orEmpty().lowercase(Locale.US)
                                                if (tType == "sub" && tSelected &&
                                                    (tLang.startsWith("ko") || tLang == "kr" || tLang == "kor")
                                                ) {
                                                    hasSelectedKoreanTrack = true
                                                    break
                                                }
                                            }
                                        }

                                        var selectedExternalOnce = false
                                        preparedSubs.forEach { prepared ->
                                            val flag = if (!hasSelectedKoreanTrack && !selectedExternalOnce) {
                                                selectedExternalOnce = true
                                                "select"
                                            } else {
                                                "auto"
                                            }
                                            runCatching {
                                                mpv.command(
                                                    arrayOf(
                                                        "sub-add",
                                                        prepared.filePath,
                                                        flag,
                                                        prepared.title.ifBlank { "외부 자막" },
                                                        prepared.language.ifBlank { "und" }
                                                    )
                                                )
                                            }.onFailure { e ->
                                                AppErrorLogger.logError(
                                                    tag = "NativeMpv",
                                                    message = "외부 자막 로드 실패 (${prepared.title})",
                                                    throwable = e
                                                )
                                            }
                                        }
                                        refreshTracks(mpv)
                                    }
                                }
                            }
                            refreshTracks(mpv)
                        }

                        MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                            isBuffering = false
                            controlsVisible = true
                        }
                    }
                }
            }
        }

        val logObserver = object : MPVLib.LogObserver {
            override fun logMessage(prefix: String, level: Int, text: String) {
                if (level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR && text.isNotBlank()) {
                    val trimmed = text.trim()
                    AppErrorLogger.logError(tag = "NativeMpv/$prefix", message = trimmed)
                    if (level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_FATAL) {
                        mainHandler.post {
                            if (initError == null) {
                                initError = "재생 오류 ($prefix): $trimmed"
                            }
                        }
                    }
                }
            }
        }

        try {
            val mpv = MPVLib.create(context.applicationContext)
                ?: throw IllegalStateException("MPVLib.create()가 null을 반환했습니다.")
            createdMpv = mpv

            val playerSettings = playerSettingsStore.getSettings()
            val fontsDir = MpvFontStorage.getFontsDir(context)

            mpv.setOptionString("vo", "gpu")
            mpv.setOptionString("gpu-context", "android")
            mpv.setOptionString("opengl-es", "yes")
            mpv.setOptionString("ao", "audiotrack,opensles")
            mpv.setOptionString("hwdec", if (useHardwareDecoding) "auto-safe" else "no")
            mpv.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
            mpv.setOptionString("embeddedfonts", "yes")
            mpv.setOptionString("sub-ass", "yes")
            mpv.setOptionString("slang", "kor,ko,kr,korean")
            mpv.setOptionString("sub-codepage", "utf8:cp949")

            // 앱 내 커스텀 폰트(.ttf/.otf/.ttc) 폴더 및 사용자 폰트 설정 적용
            if (fontsDir.exists() && fontsDir.listFiles()?.isNotEmpty() == true) {
                mpv.setOptionString("sub-fonts-dir", fontsDir.absolutePath)
            }
            val customFont = playerSettings.subtitleFontName.trim()
            if (customFont.isNotBlank()) {
                mpv.setOptionString("sub-font", customFont)
            }
            if (playerSettings.forceSubtitleFontOnAss && customFont.isNotBlank()) {
                mpv.setOptionString("sub-ass-override", "yes")
                mpv.setOptionString("sub-ass-style-overrides", "FontName=$customFont")
            } else {
                mpv.setOptionString("sub-ass-override", "no")
            }

            mpv.setOptionString("keep-open", "yes")
            mpv.setOptionString("demuxer-max-bytes", "64MiB")
            mpv.setOptionString("demuxer-max-back-bytes", "32MiB")
            mpv.setOptionString("network-timeout", "30")

            // 내장 Tailscale(tsnet) 로컬 프록시 자동 브릿지
            // 단, 로컬 SMB 스트림 서버(127.0.0.1 / localhost) URL은 외부 Tailscale 활성 상태에서도 프록시 우회 직결
            val proxyConfig = NetworkProxyManager.proxyConfig.value
            val isLoopbackStream = runCatching {
                val host = java.net.URI(request.url).host.orEmpty()
                host.equals("127.0.0.1", ignoreCase = true) ||
                    host.equals("localhost", ignoreCase = true) ||
                    host == "::1"
            }.getOrDefault(false)
            if (proxyConfig.isActive && !isLoopbackStream) {
                mpv.setOptionString("http-proxy", "http://${proxyConfig.host}:${proxyConfig.port}")
            } else {
                mpv.setOptionString("http-proxy", "")
            }

            // 자체 서명 SSL 허용 옵션 연동
            mpv.setOptionString(
                "tls-verify",
                if (NetworkModule.isSelfSignedSslAllowed()) "no" else "yes"
            )

            // 플러그인에서 전달한 커스텀 HTTP 헤더(인증 토큰 등) 적용
            if (request.headers.isNotEmpty()) {
                val headerFields = request.headers.entries
                    .filter { it.key.isNotBlank() }
                    .joinToString(",") { "${it.key.trim()}: ${it.value.trim()}" }
                if (headerFields.isNotBlank()) {
                    mpv.setOptionString("http-header-fields", headerFields)
                }
            }

            // 시작 위치(초) 적용
            if (request.startPositionSec >= 1.0) {
                mpv.setOptionString("start", "+${request.startPositionSec.toLong()}")
            }

            mpv.init()
            mpv.addLogObserver(logObserver)
            mpv.addObserver(observer)
            mpv.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            mpv.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            mpv.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            mpv.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            mpv.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            mpv.observeProperty("track-list/count", MPVLib.MpvFormat.MPV_FORMAT_INT64)
            mpvInstance = mpv
        } catch (t: Throwable) {
            val msg = "libmpv 플레이어 초기화 실패: ${t.localizedMessage ?: t.javaClass.simpleName}"
            initError = msg
            AppErrorLogger.logError(
                tag = "NativeMpv",
                message = msg,
                throwable = t,
                extraDetails = "스트림 URL: ${request.url}"
            )
        }

        onDispose {
            isDisposed = true
            ioScope.cancel()

            if (!closeReported) {
                closeReported = true
                val pos = positionSeconds.coerceAtLeast(0.0)
                val dur = durationSeconds.coerceAtLeast(0.0)
                val completed = reachedEof || (dur > 0.0 && pos >= dur * 0.90)
                onClosed(pos, dur, completed)
            }

            createdMpv?.let { mpv ->
                runCatching {
                    mpv.removeObserver(observer)
                    mpv.removeLogObserver(logObserver)
                    mpv.setPropertyString("vo", "null")
                    mpv.detachSurface()
                    mpv.destroy()
                }.onFailure { e ->
                    AppErrorLogger.logError(
                        tag = "NativeMpv",
                        message = "libmpv 종료 정리 중 오류 발생",
                        throwable = e
                    )
                }
            }
            mpvInstance = null
            SubtitlePreprocessor.cleanupSession(context.applicationContext, request.sessionId)

            if (manageActivityOrientation) {
                activity?.let { act ->
                    act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    WindowCompat.getInsetsController(act.window, act.window.decorView)
                        .show(WindowInsetsCompat.Type.systemBars())
                    act.requestedOrientation = previousOrientation
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { containerSize = it }
            .pointerInput(isLocked, containerSize, mpvInstance) {
                detectTapGestures(
                    onTap = { controlsVisible = !controlsVisible },
                    onDoubleTap = { offset ->
                        val mpv = mpvInstance ?: return@detectTapGestures
                        if (isLocked) return@detectTapGestures
                        val width = containerSize.width.coerceAtLeast(1)
                        when {
                            offset.x < width * 0.35f -> {
                                runCatching { mpv.command(arrayOf("seek", "-10", "relative")) }
                                showTransientHud("seek", "-10초")
                            }

                            offset.x > width * 0.65f -> {
                                runCatching { mpv.command(arrayOf("seek", "10", "relative")) }
                                showTransientHud("seek", "+10초")
                            }

                            else -> {
                                runCatching { mpv.command(arrayOf("cycle", "pause")) }
                            }
                        }
                    }
                )
            }
            .pointerInput(isLocked, containerSize) {
                var dragStartX = 0f
                var initialBrightness = 0.5f
                var initialVolume = 0
                var accumulatedDeltaY = 0f

                detectVerticalDragGestures(
                    onDragStart = { startOffset ->
                        dragStartX = startOffset.x
                        accumulatedDeltaY = 0f
                        val currentAttrBrightness = activity?.window?.attributes?.screenBrightness ?: -1f
                        initialBrightness = if (currentAttrBrightness in 0f..1f) currentAttrBrightness else 0.5f
                        initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    },
                    onVerticalDrag = { change, dragAmount ->
                        if (isLocked) return@detectVerticalDragGestures
                        change.consume()
                        val height = containerSize.height.coerceAtLeast(1).toFloat()
                        val width = containerSize.width.coerceAtLeast(1).toFloat()
                        accumulatedDeltaY -= dragAmount
                        val ratioDelta = accumulatedDeltaY / (height * 0.65f)

                        if (dragStartX < width * 0.5f) {
                            val nextBrightness = (initialBrightness + ratioDelta).coerceIn(0.05f, 1.0f)
                            activity?.window?.let { win ->
                                val lp = win.attributes
                                lp.screenBrightness = nextBrightness
                                win.attributes = lp
                            }
                            showTransientHud("brightness", "밝기 ${(nextBrightness * 100f).toInt()}%")
                        } else {
                            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                            val nextVol = (initialVolume + (ratioDelta * maxVol).toInt()).coerceIn(0, maxVol)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, nextVol, 0)
                            val pct = ((nextVol.toFloat() / maxVol.toFloat()) * 100f).toInt()
                            showTransientHud("volume", "볼륨 $pct%")
                        }
                    }
                )
            }
    ) {
        // libmpv 영상 출력 SurfaceView
        mpvInstance?.let { mpv ->
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                runCatching {
                                    mpv.attachSurface(holder.surface)
                                    mpv.setPropertyString("vo", "gpu")
                                    if (!fileLoadRequested) {
                                        fileLoadRequested = true
                                        mpv.command(arrayOf("loadfile", request.url))
                                    }
                                }.onFailure { t ->
                                    val msg = "영상 Surface 연결 실패: ${t.localizedMessage ?: t.javaClass.simpleName}"
                                    initError = msg
                                    AppErrorLogger.logError(tag = "NativeMpv", message = msg, throwable = t)
                                }
                            }

                            override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int
                            ) {
                                runCatching {
                                    mpv.setPropertyString("android-surface-size", "${width}x${height}")
                                }
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                runCatching {
                                    mpv.setPropertyString("vo", "null")
                                    mpv.detachSurface()
                                }
                            }
                        })
                    }
                }
            )
        }

        if (isBuffering && initError == null && switchingMessage == null) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(44.dp),
                color = MaterialTheme.colorScheme.primary
            )
        }

        // 이전화 / 다음화 전환 로딩 오버레이
        switchingMessage?.let { msg ->
            Surface(
                modifier = Modifier.align(Alignment.Center),
                color = Color(0xDD111622),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = msg,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        if (!initError.isNullOrBlank()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                color = Color(0xDD1A1D24),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "재생 오류",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = initError.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                    TextButton(onClick = { finishAndNotifyClosed() }) {
                        Text("닫기")
                    }
                }
            }
        }

        // 중앙 제스처 HUD
        hudText?.let { text ->
            Surface(
                modifier = Modifier.align(Alignment.Center),
                color = Color(0xBB000000),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val icon = when (hudIconType) {
                        "brightness" -> Icons.Filled.Brightness6
                        "volume" -> Icons.AutoMirrored.Filled.VolumeUp
                        else -> if (text.startsWith("-")) Icons.Filled.Replay10 else Icons.Filled.Forward10
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = text,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
        }

        // 컨트롤 오버레이
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            if (isLocked) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                ) {
                    Surface(
                        onClick = { isLocked = false },
                        color = Color(0xAA000000),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.align(Alignment.CenterStart)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = "화면 잠금 해제",
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "잠금 해제",
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    // 상단 바
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { finishAndNotifyClosed() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "플레이어 닫기",
                                tint = Color.White
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = request.title.ifBlank { "동영상 재생" },
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val metaLine = buildList {
                                if (request.subtitle.isNotBlank()) add(request.subtitle)
                                add(if (useHardwareDecoding) "HW 디코딩" else "SW 디코딩")
                                if (NetworkProxyManager.proxyConfig.value.isActive) add("Tailscale")
                            }.joinToString(" · ")
                            Text(
                                text = metaLine,
                                color = Color(0xCCFFFFFF),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { isLocked = true }) {
                            Icon(
                                imageVector = Icons.Filled.LockOpen,
                                contentDescription = "화면 잠금",
                                tint = Color.White
                            )
                        }
                    }

                    // 중앙 재생 컨트롤 (에피소드 시리즈일 경우 이전화/다음화 버튼 자동 표시)
                    val showEpisodeNav = onNavigateEpisode != null && (request.hasPrevious || request.hasNext)
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(if (showEpisodeNav) 20.dp else 28.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (showEpisodeNav) {
                            Surface(
                                onClick = {
                                    if (request.hasPrevious) triggerEpisodeNavigation("previous")
                                },
                                enabled = request.hasPrevious && switchingMessage == null,
                                shape = CircleShape,
                                color = Color(if (request.hasPrevious) 0x88000000L else 0x44000000L),
                                modifier = Modifier.size(48.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Filled.SkipPrevious,
                                        contentDescription = "이전화",
                                        tint = if (request.hasPrevious) Color.White else Color(0x66FFFFFF),
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                        }

                        Surface(
                            onClick = {
                                runCatching { mpvInstance?.command(arrayOf("seek", "-10", "relative")) }
                                showTransientHud("seek", "-10초")
                            },
                            shape = CircleShape,
                            color = Color(0x88000000),
                            modifier = Modifier.size(52.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.Replay10,
                                    contentDescription = "10초 뒤로",
                                    tint = Color.White,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }

                        Surface(
                            onClick = {
                                runCatching { mpvInstance?.command(arrayOf("cycle", "pause")) }
                            },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                            modifier = Modifier.size(64.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                    contentDescription = if (isPaused) "재생" else "일시정지",
                                    tint = Color.Black,
                                    modifier = Modifier.size(38.dp)
                                )
                            }
                        }

                        Surface(
                            onClick = {
                                runCatching { mpvInstance?.command(arrayOf("seek", "10", "relative")) }
                                showTransientHud("seek", "+10초")
                            },
                            shape = CircleShape,
                            color = Color(0x88000000),
                            modifier = Modifier.size(52.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.Forward10,
                                    contentDescription = "10초 앞으로",
                                    tint = Color.White,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }

                        if (showEpisodeNav) {
                            Surface(
                                onClick = {
                                    if (request.hasNext) triggerEpisodeNavigation("next")
                                },
                                enabled = request.hasNext && switchingMessage == null,
                                shape = CircleShape,
                                color = Color(if (request.hasNext) 0x88000000L else 0x44000000L),
                                modifier = Modifier.size(48.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Filled.SkipNext,
                                        contentDescription = "다음화",
                                        tint = if (request.hasNext) Color.White else Color(0x66FFFFFF),
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 영상 종료(EOF) 시 다음화 바로 재생 플로팅 버튼
                    if (reachedEof && request.hasNext && onNavigateEpisode != null && switchingMessage == null) {
                        Surface(
                            onClick = { triggerEpisodeNavigation("next") },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 20.dp, bottom = 112.dp),
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.SkipNext,
                                    contentDescription = null,
                                    tint = Color.Black,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = if (request.nextTitle.isNotBlank()) {
                                        "다음화 · ${request.nextTitle}"
                                    } else {
                                        "다음화 재생"
                                    },
                                    color = Color.Black,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // 하단 시크바 및 퀵 액션
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000)))
                            )
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        val effectiveDuration = durationSeconds.coerceAtLeast(1.0)
                        val displayedPos = if (isScrubbing) scrubPosition.toDouble() else positionSeconds

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = formatMpvTime(displayedPos),
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium
                            )
                            Slider(
                                value = displayedPos.toFloat().coerceIn(0f, effectiveDuration.toFloat()),
                                onValueChange = { next ->
                                    isScrubbing = true
                                    scrubPosition = next
                                },
                                onValueChangeFinished = {
                                    val target = scrubPosition.toDouble()
                                    positionSeconds = target
                                    isScrubbing = false
                                    runCatching {
                                        mpvInstance?.command(
                                            arrayOf("seek", target.toInt().toString(), "absolute")
                                        )
                                    }
                                },
                                valueRange = 0f..effectiveDuration.toFloat(),
                                colors = SliderDefaults.colors(
                                    thumbColor = MaterialTheme.colorScheme.primary,
                                    activeTrackColor = MaterialTheme.colorScheme.primary,
                                    inactiveTrackColor = Color(0x55FFFFFF)
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 10.dp)
                            )
                            Text(
                                text = formatMpvTime(effectiveDuration),
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MpvQuickActionChip(
                                icon = Icons.Filled.Audiotrack,
                                label = "오디오",
                                onClick = {
                                    refreshTracks()
                                    activeDialog = MpvDialogType.AUDIO
                                }
                            )
                            MpvQuickActionChip(
                                icon = Icons.Filled.Subtitles,
                                label = "자막",
                                onClick = {
                                    refreshTracks()
                                    activeDialog = MpvDialogType.SUBTITLE
                                }
                            )
                            MpvQuickActionChip(
                                icon = Icons.Filled.Speed,
                                label = "${playbackSpeed}x",
                                onClick = { activeDialog = MpvDialogType.SPEED }
                            )
                            MpvQuickActionChip(
                                icon = Icons.Filled.AspectRatio,
                                label = if (fillScreen) "채우기" else "맞춤",
                                onClick = {
                                    fillScreen = !fillScreen
                                    runCatching {
                                        mpvInstance?.setPropertyString("panscan", if (fillScreen) "1.0" else "0.0")
                                    }
                                }
                            )
                            MpvQuickActionChip(
                                icon = Icons.Filled.Memory,
                                label = if (useHardwareDecoding) "HW" else "SW",
                                onClick = {
                                    val nextHw = !useHardwareDecoding
                                    useHardwareDecoding = nextHw
                                    playerSettingsStore.setPreferHardwareDecoding(nextHw)
                                    runCatching {
                                        mpvInstance?.setPropertyString("hwdec", if (nextHw) "auto-safe" else "no")
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // 오디오 트랙 선택
    if (activeDialog == MpvDialogType.AUDIO) {
        AlertDialog(
            onDismissRequest = { activeDialog = MpvDialogType.NONE },
            title = { Text("오디오 트랙 선택") },
            text = {
                if (audioTracks.isEmpty()) {
                    Text("선택 가능한 오디오 트랙 정보가 없습니다.")
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(audioTracks, key = { it.id }) { track ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        runCatching { mpvInstance?.setPropertyString("aid", track.id.toString()) }
                                        refreshTracks()
                                        activeDialog = MpvDialogType.NONE
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = track.selected, onClick = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = track.displayLabel)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { activeDialog = MpvDialogType.NONE }) { Text("닫기") }
            }
        )
    }

    // 자막 트랙 선택 (끄기 포함)
    if (activeDialog == MpvDialogType.SUBTITLE) {
        val noSubSelected = subtitleTracks.none { it.selected }
        AlertDialog(
            onDismissRequest = { activeDialog = MpvDialogType.NONE },
            title = { Text("자막 트랙 선택") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item(key = "sub_off") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    runCatching { mpvInstance?.setPropertyString("sid", "no") }
                                    refreshTracks()
                                    activeDialog = MpvDialogType.NONE
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = noSubSelected, onClick = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "자막 끄기")
                        }
                    }
                    items(subtitleTracks, key = { it.id }) { track ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    runCatching { mpvInstance?.setPropertyString("sid", track.id.toString()) }
                                    refreshTracks()
                                    activeDialog = MpvDialogType.NONE
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = track.selected, onClick = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = track.displayLabel)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { activeDialog = MpvDialogType.NONE }) { Text("닫기") }
            }
        )
    }

    // 재생 속도 선택 (0.5x ~ 2.0x)
    if (activeDialog == MpvDialogType.SPEED) {
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        AlertDialog(
            onDismissRequest = { activeDialog = MpvDialogType.NONE },
            title = { Text("재생 속도") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(speeds) { speed ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    playbackSpeed = speed
                                    runCatching { mpvInstance?.setPropertyString("speed", speed.toString()) }
                                    activeDialog = MpvDialogType.NONE
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = playbackSpeed == speed, onClick = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "${speed}x")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { activeDialog = MpvDialogType.NONE }) { Text("닫기") }
            }
        )
    }
}

@Composable
private fun MpvQuickActionChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun formatMpvTime(secondsDouble: Double): String {
    val totalSec = secondsDouble.toLong().coerceAtLeast(0L)
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}

/**
 * 플레이어가 열려 있는 동안(이전화/다음화 에피소드 전환 포함) 가로 몰입 모드를 끊김 없이 유지합니다.
 */
@Composable
fun PlayerImmersiveOrientationEffect() {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(activity) {
        val previousOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.let { act ->
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WindowCompat.getInsetsController(act.window, act.window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            activity?.let { act ->
                act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                WindowCompat.getInsetsController(act.window, act.window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
                act.requestedOrientation = previousOrientation
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
