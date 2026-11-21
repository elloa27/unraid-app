package com.unraid.app.plugin

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.unraid.app.data.local.AppErrorLogger
import com.unraid.app.network.NetworkModule
import com.unraid.app.player.MpvPlayRequest
import com.unraid.app.player.MpvSubtitleRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.lang.ref.WeakReference
import java.util.Locale

@Serializable
private data class JsHttpRequestPayload(
    val url: String = "",
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null
)

@Serializable
private data class JsHttpResponsePayload(
    val ok: Boolean,
    val status: Int,
    val headers: Map<String, String>,
    val body: String
)

@Serializable
private data class JsSubtitlePayload(
    val url: String = "",
    val title: String = "",
    val language: String = "und"
)

@Serializable
private data class JsPlayVideoPayload(
    val url: String = "",
    val title: String = "",
    val subtitle: String = "",
    val startPositionSec: Double = 0.0,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<JsSubtitlePayload> = emptyList(),
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val previousTitle: String = "",
    val nextTitle: String = ""
)

@Serializable
private data class JsContextPayload(
    val pluginId: String,
    val pluginVersion: String,
    val serviceName: String,
    val serviceUrl: String,
    val tailscaleUrl: String,
    val activeBaseUrl: String,
    val isTailscaleActive: Boolean
)

@Serializable
private data class JsPlayerProgressEvent(
    val positionSec: Double,
    val durationSec: Double
)

@Serializable
private data class JsPlayerClosedEvent(
    val positionSec: Double,
    val durationSec: Double,
    val completed: Boolean
)

@Serializable
private data class JsPlayerActionEvent(
    val action: String,
    val positionSec: Double,
    val durationSec: Double,
    val completed: Boolean
)

/**
 * 외부 플러그인 WebView에 주입되는 네이티브 브릿지(@JavascriptInterface).
 * - JS 측 window.UnraidHub Promise 래퍼가 내부적으로 window.__UnraidHubNative 를 호출합니다.
 * - 모든 HTTP 요청은 앱의 NetworkModule.okHttpClient(Tailscale 프록시 + 자체 서명 SSL 허용)를 경유하므로
 *   WebView CORS 제약과 사설 인증서 오류 없이 홈 서버 API와 직접 통신할 수 있습니다.
 */
class UnraidHubJsBridge(
    private val plugin: InstalledPlugin,
    private val contextProvider: () -> PluginBridgeContext,
    private val storageVault: PluginStorageVault,
    private val httpClientProvider: () -> OkHttpClient = { NetworkModule.okHttpClient },
    private val onPlayVideo: (MpvPlayRequest) -> Unit,
    private val onClose: () -> Unit,
    private val onOpenWebView: () -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Volatile
    private var webViewRef: WeakReference<WebView>? = null

    @Volatile
    private var isDisposed = false

    fun attachWebView(webView: WebView) {
        webViewRef = WeakReference(webView)
    }

    fun dispose() {
        isDisposed = true
        webViewRef = null
        bridgeScope.cancel()
    }

    /**
     * JS: UnraidHub.httpRequest({ url, method, headers, body })
     */
    @JavascriptInterface
    fun httpRequest(callbackId: String, requestJson: String) {
        if (isDisposed) return
        bridgeScope.launch {
            try {
                val req = json.decodeFromString<JsHttpRequestPayload>(requestJson)
                val targetUrl = req.url.trim()
                if (!targetUrl.startsWith("http://", ignoreCase = true) &&
                    !targetUrl.startsWith("https://", ignoreCase = true)
                ) {
                    rejectCallback(callbackId, "지원하지 않는 URL 형식입니다: $targetUrl")
                    return@launch
                }

                val method = req.method.trim().uppercase(Locale.US).ifBlank { "GET" }
                val contentTypeHeader = req.headers.entries
                    .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                    ?.value

                val requestBody = when {
                    method == "GET" || method == "HEAD" -> null
                    req.body != null -> req.body.toRequestBody(contentTypeHeader?.toMediaTypeOrNull())
                    method == "POST" || method == "PUT" || method == "PATCH" -> {
                        "".toRequestBody(contentTypeHeader?.toMediaTypeOrNull())
                    }
                    else -> null
                }

                val okRequestBuilder = Request.Builder()
                    .url(targetUrl)
                    .method(method, requestBody)

                req.headers.forEach { (key, value) ->
                    if (key.isNotBlank()) {
                        okRequestBuilder.header(key, value)
                    }
                }

                httpClientProvider().newCall(okRequestBuilder.build()).execute().use { response ->
                    val responseHeaders = LinkedHashMap<String, String>()
                    for (i in 0 until response.headers.size) {
                        val headerName = response.headers.name(i).lowercase(Locale.US)
                        val headerValue = response.headers.value(i)
                        val existing = responseHeaders[headerName]
                        responseHeaders[headerName] = if (existing == null) {
                            headerValue
                        } else {
                            "$existing, $headerValue"
                        }
                    }

                    val bodyText = response.body?.string().orEmpty()
                    val payload = JsHttpResponsePayload(
                        ok = response.isSuccessful,
                        status = response.code,
                        headers = responseHeaders,
                        body = bodyText
                    )
                    resolveCallback(callbackId, json.encodeToString(payload))
                }
            } catch (e: Exception) {
                AppErrorLogger.logError(
                    tag = "Plugin/${plugin.id}/HTTP",
                    message = "플러그인 HTTP 요청 실패: ${e.localizedMessage ?: e.javaClass.simpleName}",
                    throwable = e
                )
                rejectCallback(callbackId, e.localizedMessage ?: "HTTP 요청 중 오류가 발생했습니다.")
            }
        }
    }

    /**
     * JS: UnraidHub.storageGet(key)
     */
    @JavascriptInterface
    fun storageGet(key: String): String {
        val value = storageVault.get(plugin.id, key)
        return if (value == null) "null" else JsonPrimitive(value).toString()
    }

    /**
     * JS: UnraidHub.storageSet(key, value)
     */
    @JavascriptInterface
    fun storageSet(key: String, value: String) {
        storageVault.set(plugin.id, key, value)
    }

    /**
     * JS: UnraidHub.storageRemove(key)
     */
    @JavascriptInterface
    fun storageRemove(key: String) {
        storageVault.remove(plugin.id, key)
    }

    /**
     * JS: UnraidHub.playVideo({ url, title, subtitle, startPositionSec, headers, subtitles })
     */
    @JavascriptInterface
    fun playVideo(requestJson: String): String {
        return try {
            val payload = json.decodeFromString<JsPlayVideoPayload>(requestJson)
            val streamUrl = payload.url.trim()
            if (streamUrl.isBlank()) {
                return "재생할 영상 URL이 비어 있습니다."
            }
            val request = MpvPlayRequest(
                url = streamUrl,
                title = payload.title,
                subtitle = payload.subtitle,
                startPositionSec = payload.startPositionSec.coerceAtLeast(0.0),
                headers = payload.headers,
                subtitles = payload.subtitles
                    .filter { it.url.isNotBlank() }
                    .map { sub ->
                        MpvSubtitleRequest(
                            url = sub.url.trim(),
                            title = sub.title,
                            language = sub.language.ifBlank { "und" }
                        )
                    },
                hasPrevious = payload.hasPrevious,
                hasNext = payload.hasNext,
                previousTitle = payload.previousTitle,
                nextTitle = payload.nextTitle
            )
            mainHandler.post {
                if (!isDisposed) {
                    onPlayVideo(request)
                }
            }
            ""
        } catch (e: Exception) {
            AppErrorLogger.logError(
                tag = "Plugin/${plugin.id}/Player",
                message = "playVideo 파라미터 파싱 실패",
                throwable = e,
                extraDetails = requestJson
            )
            e.localizedMessage ?: "playVideo 요청 형식이 올바르지 않습니다."
        }
    }

    /**
     * JS: UnraidHub.getContext()
     */
    @JavascriptInterface
    fun getContextJson(): String {
        val ctx = contextProvider()
        val payload = JsContextPayload(
            pluginId = ctx.pluginId,
            pluginVersion = ctx.pluginVersion,
            serviceName = ctx.serviceName,
            serviceUrl = ctx.serviceUrl,
            tailscaleUrl = ctx.tailscaleUrl,
            activeBaseUrl = ctx.activeBaseUrl,
            isTailscaleActive = ctx.isTailscaleActive
        )
        return json.encodeToString(payload)
    }

    /**
     * JS: UnraidHub.close()
     */
    @JavascriptInterface
    fun close() {
        mainHandler.post {
            if (!isDisposed) {
                onClose()
            }
        }
    }

    /**
     * JS: UnraidHub.openWebView()
     */
    @JavascriptInterface
    fun openWebView() {
        mainHandler.post {
            if (!isDisposed) {
                onOpenWebView()
            }
        }
    }

    /**
     * JS: UnraidHub.logError(tag, message, detail)
     */
    @JavascriptInterface
    fun logError(tag: String, message: String, detail: String?) {
        val safeTag = tag.trim().ifBlank { "JS" }
        AppErrorLogger.logError(
            tag = "Plugin/${plugin.id}/$safeTag",
            message = message.ifBlank { "플러그인 오류 로그" },
            extraDetails = detail?.takeIf { it.isNotBlank() }
        )
    }

    /**
     * 내장 libmpv 플레이어의 10초 주기 재생 진행률 이벤트를 플러그인 WebView로 전달합니다.
     */
    fun dispatchPlayerProgress(positionSec: Double, durationSec: Double) {
        val detailJson = json.encodeToString(
            JsPlayerProgressEvent(
                positionSec = positionSec,
                durationSec = durationSec
            )
        )
        dispatchCustomEvent(EVENT_PLAYER_PROGRESS, detailJson)
    }

    /**
     * 내장 libmpv 플레이어 종료 이벤트를 플러그인 WebView로 전달합니다.
     */
    fun dispatchPlayerClosed(positionSec: Double, durationSec: Double, completed: Boolean) {
        val detailJson = json.encodeToString(
            JsPlayerClosedEvent(
                positionSec = positionSec,
                durationSec = durationSec,
                completed = completed
            )
        )
        dispatchCustomEvent(EVENT_PLAYER_CLOSED, detailJson)
    }

    /**
     * 내장 libmpv 플레이어 내부 이전화/다음화 전환 액션 이벤트를 플러그인 WebView로 전달합니다.
     */
    fun dispatchPlayerAction(
        action: String,
        positionSec: Double,
        durationSec: Double,
        completed: Boolean
    ) {
        val detailJson = json.encodeToString(
            JsPlayerActionEvent(
                action = action,
                positionSec = positionSec,
                durationSec = durationSec,
                completed = completed
            )
        )
        dispatchCustomEvent(EVENT_PLAYER_ACTION, detailJson)
    }

    private fun dispatchCustomEvent(eventName: String, detailJson: String) {
        if (isDisposed) return
        val jsEventName = toJsStringLiteral(eventName)
        val jsDetailArg = toJsStringLiteral(detailJson)
        val script = """
            (function() {
              try {
                var detail = JSON.parse($jsDetailArg);
                window.dispatchEvent(new CustomEvent($jsEventName, { detail: detail }));
              } catch (e) {}
            })();
        """.trimIndent()
        evaluateJsOnMainThread(script)
    }

    private fun resolveCallback(callbackId: String, resultJson: String) {
        if (isDisposed) return
        val script = "window.__UnraidHubResolve(${toJsStringLiteral(callbackId)}, true, ${toJsStringLiteral(resultJson)});"
        evaluateJsOnMainThread(script)
    }

    private fun rejectCallback(callbackId: String, errorMessage: String) {
        if (isDisposed) return
        val script = "window.__UnraidHubResolve(${toJsStringLiteral(callbackId)}, false, ${toJsStringLiteral(errorMessage)});"
        evaluateJsOnMainThread(script)
    }

    private fun evaluateJsOnMainThread(script: String) {
        mainHandler.post {
            if (isDisposed) return@post
            val webView = webViewRef?.get() ?: return@post
            runCatching {
                webView.evaluateJavascript(script, null)
            }
        }
    }

    private fun toJsStringLiteral(raw: String): String {
        return JsonPrimitive(raw).toString()
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")
    }

    companion object {
        const val NATIVE_INTERFACE_NAME = "__UnraidHubNative"
        const val EVENT_PLAYER_PROGRESS = "unraidhub:playerProgress"
        const val EVENT_PLAYER_CLOSED = "unraidhub:playerClosed"
        const val EVENT_PLAYER_ACTION = "unraidhub:playerAction"
        const val PROXY_PATH_PREFIX = "/__proxy__"

        /**
         * 플러그인 HTML에 자동 주입되어 async/await 기반 window.UnraidHub API를 노출하는 부트스트랩 스크립트.
         */
        val BRIDGE_BOOTSTRAP_JS: String = """
            (function() {
              if (window.UnraidHub && window.UnraidHub.__initialized) {
                return;
              }
              var pendingCallbacks = Object.create(null);
              var seq = 0;

              window.__UnraidHubResolve = function(callbackId, ok, payload) {
                var entry = pendingCallbacks[callbackId];
                if (!entry) return;
                delete pendingCallbacks[callbackId];
                if (ok) {
                  try {
                    entry.resolve(JSON.parse(payload));
                  } catch (e) {
                    entry.resolve(payload);
                  }
                } else {
                  entry.reject(new Error(payload || 'UnraidHub 브릿지 요청 실패'));
                }
              };

              function getNative() {
                var bridge = window.$NATIVE_INTERFACE_NAME;
                if (!bridge) {
                  throw new Error('UnraidHub 네이티브 브릿지를 사용할 수 없습니다.');
                }
                return bridge;
              }

              window.UnraidHub = {
                __initialized: true,

                httpRequest: function(options) {
                  return new Promise(function(resolve, reject) {
                    try {
                      var bridge = getNative();
                      var opts = options || {};
                      var callbackId = 'uh_' + (++seq) + '_' + Date.now();
                      pendingCallbacks[callbackId] = { resolve: resolve, reject: reject };
                      var payload = JSON.stringify({
                        url: String(opts.url || ''),
                        method: String(opts.method || 'GET'),
                        headers: opts.headers || {},
                        body: (opts.body === undefined || opts.body === null)
                          ? null
                          : (typeof opts.body === 'string' ? opts.body : JSON.stringify(opts.body))
                      });
                      bridge.httpRequest(callbackId, payload);
                    } catch (e) {
                      reject(e);
                    }
                  });
                },

                storageGet: function(key) {
                  return new Promise(function(resolve, reject) {
                    try {
                      var raw = getNative().storageGet(String(key || ''));
                      resolve(JSON.parse(raw));
                    } catch (e) {
                      reject(e);
                    }
                  });
                },

                storageSet: function(key, value) {
                  return new Promise(function(resolve, reject) {
                    try {
                      var strVal = (value === undefined || value === null)
                        ? ''
                        : (typeof value === 'string' ? value : JSON.stringify(value));
                      getNative().storageSet(String(key || ''), strVal);
                      resolve();
                    } catch (e) {
                      reject(e);
                    }
                  });
                },

                storageRemove: function(key) {
                  return new Promise(function(resolve, reject) {
                    try {
                      getNative().storageRemove(String(key || ''));
                      resolve();
                    } catch (e) {
                      reject(e);
                    }
                  });
                },

                playVideo: function(options) {
                  return new Promise(function(resolve, reject) {
                    try {
                      var opts = options || {};
                      var payload = JSON.stringify({
                        url: String(opts.url || ''),
                        title: String(opts.title || ''),
                        subtitle: String(opts.subtitle || ''),
                        startPositionSec: Number(opts.startPositionSec || 0),
                        headers: opts.headers || {},
                        subtitles: Array.isArray(opts.subtitles) ? opts.subtitles : [],
                        hasPrevious: Boolean(opts.hasPrevious),
                        hasNext: Boolean(opts.hasNext),
                        previousTitle: String(opts.previousTitle || ''),
                        nextTitle: String(opts.nextTitle || '')
                      });
                      var errMsg = getNative().playVideo(payload);
                      if (errMsg) {
                        reject(new Error(errMsg));
                      } else {
                        resolve();
                      }
                    } catch (e) {
                      reject(e);
                    }
                  });
                },

                proxyUrl: function(rawUrl) {
                  var target = String(rawUrl || '').trim();
                  if (!target) return '';
                  if (/^https?:\/\/${InstalledPlugin.PLUGIN_VIRTUAL_HOST}\//i.test(target)) {
                    return target;
                  }
                  if (!/^https?:\/\//i.test(target)) {
                    return target;
                  }
                  return 'https://${InstalledPlugin.PLUGIN_VIRTUAL_HOST}${PROXY_PATH_PREFIX}?url=' + encodeURIComponent(target);
                },

                getContext: function() {
                  return new Promise(function(resolve, reject) {
                    try {
                      var raw = getNative().getContextJson();
                      resolve(JSON.parse(raw));
                    } catch (e) {
                      reject(e);
                    }
                  });
                },

                close: function() {
                  try { getNative().close(); } catch (e) {}
                },

                openWebView: function() {
                  try { getNative().openWebView(); } catch (e) {}
                },

                logError: function(tag, message, detail) {
                  try {
                    getNative().logError(
                      String(tag || 'JS'),
                      String(message || ''),
                      detail === undefined || detail === null ? null : String(detail)
                    );
                  } catch (e) {}
                }
              };
            })();
        """.trimIndent()
    }
}
