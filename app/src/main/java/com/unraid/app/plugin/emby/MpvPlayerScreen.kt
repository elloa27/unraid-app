package com.unraid.app.plugin.emby

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
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private data class MpvTrackInfo(
    val id: Int,
    val type: String, // audio, sub
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

private enum class ActivePlayerDialog {
    NONE, AUDIO, SUBTITLE, SPEED
}

/**
 * dev.jdtech.mpv.MPVLib 인스턴스 + SurfaceView 기반 Emby Direct Play 전용 전체 화면(가로) 플레이어.
 * - 내장 Tailscale(tsnet) 프록시(http-proxy) 및 사설 SSL 허용(tls-verify=no) 자동 브릿지
 * - 하드웨어(auto-safe) / 소프트웨어 디코딩 전환, libass ASS/SSA 및 외부 SRT 자막 자동 로드
 * - Emby 재생 시작 / 10초 간격 진행 / 종료 시 이어보기 위치 동기화
 * - 탭 오버레이, 좌우 더블탭 10초 이동, 좌측 밝기·우측 볼륨 스와이프, 오디오/자막/속도/화면비율/잠금
 */
@Composable
fun MpvPlayerScreen(
    request: EmbyPlaybackRequest,
    apiClient: EmbyApiClient,
    credentialStore: EmbyCredentialStore,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val scope = rememberCoroutineScope()

    var mpvInstance by remember { mutableStateOf<MPVLib?>(null) }
    var fileLoadRequested by remember { mutableStateOf(false) }
    var externalSubsLoaded by remember { mutableStateOf(false) }
    var initError by remember { mutableStateOf<String?>(null) }

    var isPaused by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(true) }
    var positionSeconds by remember { mutableDoubleStateOf(request.startPositionTicks / TICKS_PER_SECOND.toDouble()) }
    var durationSeconds by remember { mutableDoubleStateOf(request.item.runTimeTicks / TICKS_PER_SECOND.toDouble()) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableFloatStateOf(0f) }

    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var fillScreen by remember { mutableStateOf(false) }
    var useHardwareDecoding by remember { mutableStateOf(credentialStore.getConfig().preferHardwareDecoding) }
    var isLocked by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var activeDialog by remember { mutableStateOf(ActivePlayerDialog.NONE) }

    var audioTracks by remember { mutableStateOf<List<MpvTrackInfo>>(emptyList()) }
    var subtitleTracks by remember { mutableStateOf<List<MpvTrackInfo>>(emptyList()) }

    // 제스처 HUD 상태 (더블탭 / 밝기 / 볼륨)
    var hudText by remember { mutableStateOf<String?>(null) }
    var hudIconType by remember { mutableStateOf<String?>(null) } // seek, brightness, volume
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // 뒤로가기 버튼 처리 (잠금 중에는 잠금 해제 안내만 표시)
    BackHandler(enabled = true) {
        if (isLocked) {
            controlsVisible = true
        } else {
            onExit()
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

    // HUD 자동 숨김
    LaunchedEffect(hudText) {
        if (hudText != null) {
            delay(1_100)
            hudText = null
            hudIconType = null
        }
    }

    // 컨트롤 오버레이 자동 숨김 (재생 중 4초 후)
    LaunchedEffect(controlsVisible, isPaused, activeDialog, isScrubbing) {
        if (controlsVisible && !isPaused && activeDialog == ActivePlayerDialog.NONE && !isScrubbing) {
            delay(4_000)
            controlsVisible = false
        }
    }

    // 10초 간격 Emby 재생 진행률 동기화
    LaunchedEffect(mpvInstance) {
        if (mpvInstance == null) return@LaunchedEffect
        while (true) {
            delay(10_000)
            val ticks = (positionSeconds * TICKS_PER_SECOND).toLong().coerceAtLeast(0L)
            apiClient.reportPlaybackProgress(
                request = request,
                positionTicks = ticks,
                isPaused = isPaused
            )
        }
    }

    // 가로 몰입 모드 + 화면 꺼짐 방지 + libmpv 인스턴스 생성/초기화/해제 생명주기
    DisposableEffect(request.playSessionId) {
        val previousOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.let { act ->
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WindowCompat.getInsetsController(act.window, act.window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }

        var createdMpv: MPVLib? = null

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
                            // Emby 외부 SRT/ASS 자막 자동 로드
                            if (mpv != null && !externalSubsLoaded && request.externalSubtitles.isNotEmpty()) {
                                externalSubsLoaded = true
                                request.externalSubtitles.forEach { sub ->
                                    runCatching {
                                        mpv.command(
                                            arrayOf("sub-add", sub.url, "auto", sub.title, sub.language)
                                        )
                                    }.onFailure { e ->
                                        AppErrorLogger.logError(
                                            tag = "EmbyMpv",
                                            message = "외부 자막 로드 실패 (${sub.title})",
                                            throwable = e
                                        )
                                    }
                                }
                            }
                            refreshTracks(mpv)
                            scope.launch {
                                apiClient.reportPlaybackStarted(request)
                            }
                        }

                        MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                            isBuffering = false
                        }
                    }
                }
            }
        }

        val logObserver = object : MPVLib.LogObserver {
            override fun logMessage(prefix: String, level: Int, text: String) {
                if (level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR && text.isNotBlank()) {
                    val trimmed = text.trim()
                    AppErrorLogger.logError(
                        tag = "EmbyMpv/$prefix",
                        message = trimmed
                    )
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

            // 비디오/오디오/자막(libass) 기본 옵션
            val embyConfig = credentialStore.getConfig()
            val fontsDir = EmbyFontStorage.getFontsDir(context)

            mpv.setOptionString("vo", "gpu")
            mpv.setOptionString("gpu-context", "android")
            mpv.setOptionString("opengl-es", "yes")
            mpv.setOptionString("ao", "audiotrack,opensles")
            mpv.setOptionString("hwdec", if (useHardwareDecoding) "auto-safe" else "no")
            mpv.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
            mpv.setOptionString("embeddedfonts", "yes")
            mpv.setOptionString("sub-ass", "yes")

            // 사용자가 앱에 추가한 커스텀 폰트(.ttf/.otf/.ttc) 폴더를 libass에 등록
            if (fontsDir.exists() && fontsDir.listFiles()?.isNotEmpty() == true) {
                mpv.setOptionString("sub-fonts-dir", fontsDir.absolutePath)
            }

            // 사용자 지정 기본 자막 폰트 및 ASS 강제 적용 옵션 반영
            val customFontName = embyConfig.subtitleFontName.trim()
            if (customFontName.isNotBlank()) {
                mpv.setOptionString("sub-font", customFontName)
            }
            if (embyConfig.forceSubtitleFontOnAss && customFontName.isNotBlank()) {
                mpv.setOptionString("sub-ass-override", "yes")
                mpv.setOptionString("sub-ass-style-overrides", "FontName=$customFontName")
            } else {
                mpv.setOptionString("sub-ass-override", "no")
            }

            mpv.setOptionString("keep-open", "yes")
            mpv.setOptionString("demuxer-max-bytes", "64MiB")
            mpv.setOptionString("demuxer-max-back-bytes", "32MiB")
            mpv.setOptionString("network-timeout", "30")

            // 핵심: 내장 Tailscale(tsnet) 로컬 프록시 브릿지 적용
            val proxyConfig = NetworkProxyManager.proxyConfig.value
            if (proxyConfig.isActive) {
                mpv.setOptionString("http-proxy", "http://${proxyConfig.host}:${proxyConfig.port}")
            } else {
                mpv.setOptionString("http-proxy", "")
            }

            // 자체 서명 SSL 인증서 허용 옵션 연동
            mpv.setOptionString(
                "tls-verify",
                if (NetworkModule.isSelfSignedSslAllowed()) "no" else "yes"
            )

            // 이어보기 시작 위치 지정
            val startSec = request.startPositionTicks / TICKS_PER_SECOND
            if (startSec > 1L) {
                mpv.setOptionString("start", "+$startSec")
            }

            mpv.init()
            mpv.addLogObserver(logObserver)
            mpv.addObserver(observer)
            mpv.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            mpv.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            mpv.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            mpv.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            mpv.observeProperty("track-list/count", MPVLib.MpvFormat.MPV_FORMAT_INT64)
            mpvInstance = mpv
        } catch (t: Throwable) {
            val msg = "libmpv 플레이어 초기화 실패: ${t.localizedMessage ?: t.javaClass.simpleName}"
            initError = msg
            AppErrorLogger.logError(
                tag = "EmbyMpv",
                message = msg,
                throwable = t,
                extraDetails = "스트림 URL: ${request.streamUrl}"
            )
        }

        onDispose {
            val finalTicks = (positionSeconds * TICKS_PER_SECOND).toLong().coerceAtLeast(0L)
            CoroutineScope(Dispatchers.IO).launch {
                apiClient.reportPlaybackStopped(request, finalTicks)
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
                        tag = "EmbyMpv",
                        message = "libmpv 종료 정리 중 오류 발생",
                        throwable = e
                    )
                }
            }
            mpvInstance = null

            activity?.let { act ->
                act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                WindowCompat.getInsetsController(act.window, act.window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
                act.requestedOrientation = previousOrientation
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { containerSize = it }
            // 싱글 탭(오버레이 토글) 및 좌/우 더블탭(±10초 이동)
            .pointerInput(isLocked, containerSize, mpvInstance) {
                detectTapGestures(
                    onTap = {
                        controlsVisible = !controlsVisible
                    },
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
            // 좌측 상하 스와이프(밝기), 우측 상하 스와이프(볼륨)
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
                            val pct = (nextBrightness * 100f).toInt()
                            showTransientHud("brightness", "밝기 $pct%")
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
                                        mpv.command(arrayOf("loadfile", request.streamUrl))
                                    }
                                }.onFailure { t ->
                                    val msg = "영상 Surface 연결 실패: ${t.localizedMessage ?: t.javaClass.simpleName}"
                                    initError = msg
                                    AppErrorLogger.logError(tag = "EmbyMpv", message = msg, throwable = t)
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

        // 버퍼링 로딩 인디케이터
        if (isBuffering && initError == null) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(44.dp),
                color = MaterialTheme.colorScheme.primary
            )
        }

        // 초기화/재생 오류 안내
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
                    TextButton(onClick = onExit) {
                        Text("뒤로 돌아가기")
                    }
                }
            }
        }

        // 제스처 중앙 HUD (밝기 / 볼륨 / ±10초)
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

        // 컨트롤 오버레이 (잠금 상태일 때는 잠금 해제 버튼만 표시)
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
                                Brush.verticalGradient(
                                    listOf(Color(0xCC000000), Color.Transparent)
                                )
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onExit) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "플레이어 닫기",
                                tint = Color.White
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = request.item.name,
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val metaLine = buildList {
                                if (request.item.displaySubtitle.isNotBlank()) add(request.item.displaySubtitle)
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

                    // 중앙 재생 컨트롤 (-10초 / 재생·일시정지 / +10초)
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
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
                    }

                    // 하단 시크바 및 퀵 버튼 영역
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color(0xDD000000))
                                )
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
                                text = formatPlayerTime(displayedPos),
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
                                text = formatPlayerTime(effectiveDuration),
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PlayerQuickChip(
                                icon = Icons.Filled.Audiotrack,
                                label = "오디오",
                                onClick = {
                                    refreshTracks()
                                    activeDialog = ActivePlayerDialog.AUDIO
                                }
                            )
                            PlayerQuickChip(
                                icon = Icons.Filled.Subtitles,
                                label = "자막",
                                onClick = {
                                    refreshTracks()
                                    activeDialog = ActivePlayerDialog.SUBTITLE
                                }
                            )
                            PlayerQuickChip(
                                icon = Icons.Filled.Speed,
                                label = "${playbackSpeed}x",
                                onClick = { activeDialog = ActivePlayerDialog.SPEED }
                            )
                            PlayerQuickChip(
                                icon = Icons.Filled.AspectRatio,
                                label = if (fillScreen) "채우기" else "맞춤",
                                onClick = {
                                    fillScreen = !fillScreen
                                    runCatching {
                                        mpvInstance?.setPropertyString("panscan", if (fillScreen) "1.0" else "0.0")
                                    }
                                }
                            )
                            PlayerQuickChip(
                                icon = Icons.Filled.Memory,
                                label = if (useHardwareDecoding) "HW" else "SW",
                                onClick = {
                                    val nextHw = !useHardwareDecoding
                                    useHardwareDecoding = nextHw
                                    credentialStore.setPreferHardwareDecoding(nextHw)
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

    // 오디오 트랙 선택 다이얼로그
    if (activeDialog == ActivePlayerDialog.AUDIO) {
        AlertDialog(
            onDismissRequest = { activeDialog = ActivePlayerDialog.NONE },
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
                                        activeDialog = ActivePlayerDialog.NONE
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
                TextButton(onClick = { activeDialog = ActivePlayerDialog.NONE }) {
                    Text("닫기")
                }
            }
        )
    }

    // 자막 트랙 선택 다이얼로그 (끄기 포함)
    if (activeDialog == ActivePlayerDialog.SUBTITLE) {
        val noSubSelected = subtitleTracks.none { it.selected }
        AlertDialog(
            onDismissRequest = { activeDialog = ActivePlayerDialog.NONE },
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
                                    activeDialog = ActivePlayerDialog.NONE
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
                                    activeDialog = ActivePlayerDialog.NONE
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
                TextButton(onClick = { activeDialog = ActivePlayerDialog.NONE }) {
                    Text("닫기")
                }
            }
        )
    }

    // 재생 속도 선택 다이얼로그 (0.5x ~ 2.0x)
    if (activeDialog == ActivePlayerDialog.SPEED) {
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        AlertDialog(
            onDismissRequest = { activeDialog = ActivePlayerDialog.NONE },
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
                                    activeDialog = ActivePlayerDialog.NONE
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
                TextButton(onClick = { activeDialog = ActivePlayerDialog.NONE }) {
                    Text("닫기")
                }
            }
        )
    }
}

@Composable
private fun PlayerQuickChip(
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

private fun formatPlayerTime(secondsDouble: Double): String {
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

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
