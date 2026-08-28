package me.rerere.oauth

import android.content.Context
import androidx.annotation.StringRes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

data class OAuthCallback(
    val code: String?,
    val state: String,
    val error: String?,
    val errorDescription: String?,
)

/**
 * 临时 OAuth loopback 回调服务器。
 *
 * 服务器只绑定 IPv4 回环地址；首个授权会话打开时启动，最后一个会话关闭时停止。
 * 同一实例可以承载多个并发授权，并通过 state 将回调路由到对应会话。
 */
class OAuthLoopbackCallbackServer(
    private val port: Int = 0,
    callbackPath: String = "/oauth/callback",
) {
    private class CallbackRegistration {
        val result = CompletableDeferred<OAuthCallback>()
        val claimed = AtomicBoolean(false)
    }

    private val callbackPath = callbackPath.requireValidCallbackPath()
    private val lifecycleMutex = Mutex()
    private val callbacks = ConcurrentHashMap<String, CallbackRegistration>()

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var redirectUri: String? = null
    @Volatile
    private var localizedContext: Context? = null

    init {
        require(port in 0..65535) { "非法的 OAuth 回调端口: $port" }
    }

    suspend fun openSession(expectedState: String): OAuthLoopbackCallbackSession {
        return openSession(expectedState, foregroundServiceContext = null)
    }

    /**
     * 打开由前台服务保活的授权会话。
     *
     * 应在应用仍处于用户可见状态时调用；返回时前台服务已经成功启动，可以安全打开浏览器。
     */
    suspend fun openSession(
        context: Context,
        expectedState: String,
    ): OAuthLoopbackCallbackSession {
        return openSession(expectedState, context.applicationContext)
    }

    private suspend fun openSession(
        expectedState: String,
        foregroundServiceContext: Context?,
    ): OAuthLoopbackCallbackSession {
        require(expectedState.isNotBlank()) { "OAuth state 不能为空" }
        val registration = CallbackRegistration()
        return lifecycleMutex.withLock {
            foregroundServiceContext?.let { localizedContext = it }
            check(callbacks.putIfAbsent(expectedState, registration) == null) {
                "OAuth state 已存在待处理的授权会话"
            }
            try {
                val uri = ensureStarted()
                if (foregroundServiceContext != null) {
                    check(
                        OAuthCallbackForegroundService.acquire(
                            context = foregroundServiceContext,
                            sessionId = expectedState,
                        )
                    ) { "无法启动 OAuth 回调前台服务" }
                }
                OAuthLoopbackCallbackSession(
                    redirectUri = uri,
                    expectedState = expectedState,
                    callback = registration.result,
                    foregroundServiceContext = foregroundServiceContext,
                    owner = this,
                )
            } catch (e: Exception) {
                callbacks.remove(expectedState, registration)
                foregroundServiceContext?.let {
                    OAuthCallbackForegroundService.release(it, expectedState)
                }
                stopServerIfIdle()
                throw e
            }
        }
    }

    private suspend fun ensureStarted(): String {
        redirectUri?.let { return it }

        val newServer = embeddedServer(CIO, host = LOOPBACK_HOST, port = port) {
            routing {
                get(callbackPath) {
                    handleCallback(call)
                }
            }
        }
        try {
            newServer.startSuspend(wait = false)
            val resolvedPort = newServer.engine.resolvedConnectors().single().port
            return "http://$LOOPBACK_HOST:$resolvedPort$callbackPath".also {
                server = newServer
                redirectUri = it
            }
        } catch (e: Exception) {
            runCatching { newServer.stop(gracePeriodMillis = 0, timeoutMillis = 1_000) }
            throw e
        }
    }

    private suspend fun handleCallback(call: ApplicationCall) {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val state = call.request.queryParameters["state"]
        val registration = state?.let(callbacks::get)
        if (state.isNullOrBlank() || registration == null) {
            call.respondText(
                text = invalidCallbackHtml(),
                contentType = ContentType.Text.Html,
                status = HttpStatusCode.BadRequest,
            )
            return
        }

        val code = call.request.queryParameters["code"]
        val error = call.request.queryParameters["error"]
        if (code.isNullOrBlank() && error.isNullOrBlank()) {
            call.respondText(
                text = invalidCallbackHtml(),
                contentType = ContentType.Text.Html,
                status = HttpStatusCode.BadRequest,
            )
            return
        }

        val result = OAuthCallback(
            code = code,
            state = state,
            error = error,
            errorDescription = call.request.queryParameters["error_description"],
        )
        val responseHtml = if (error == null) successHtml() else errorHtml()
        if (!registration.claimed.compareAndSet(false, true)) {
            call.respondText(
                text = callbackAlreadyHandledHtml(),
                contentType = ContentType.Text.Html,
                status = HttpStatusCode.Conflict,
            )
            return
        }

        try {
            call.respondText(
                text = responseHtml,
                contentType = ContentType.Text.Html,
                status = HttpStatusCode.OK,
            )
        } finally {
            registration.result.complete(result)
        }
    }

    internal suspend fun closeSession(
        expectedState: String,
        callback: CompletableDeferred<OAuthCallback>,
        foregroundServiceContext: Context?,
    ) {
        lifecycleMutex.withLock {
            callbacks[expectedState]
                ?.takeIf { it.result === callback }
                ?.let { callbacks.remove(expectedState, it) }
            callback.cancel()
            foregroundServiceContext?.let {
                OAuthCallbackForegroundService.release(it, expectedState)
            }
            stopServerIfIdle()
        }
    }

    private suspend fun stopServerIfIdle() {
        if (callbacks.isEmpty()) {
            server?.stopSuspend(gracePeriodMillis = 0, timeoutMillis = 1_000)
            server = null
            redirectUri = null
            localizedContext = null
        }
    }

    private fun String.requireValidCallbackPath(): String {
        require(startsWith('/') && !startsWith("//")) {
            "OAuth callbackPath 必须是以单个 / 开头的绝对路径"
        }
        require('?' !in this && '#' !in this) {
            "OAuth callbackPath 不能包含 query 或 fragment"
        }
        return this
    }

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
    }

    private fun successHtml() = callbackPage(
        tone = "success",
        symbol = "&#10003;",
        title = localizedString(R.string.oauth_callback_success_title, "Authorization complete"),
        message = localizedString(
            R.string.oauth_callback_success_message,
            "The account has been connected successfully.",
        ),
        hint = localizedString(
            R.string.oauth_callback_success_hint,
            "You can close this tab and return to RikkaHub.",
        ),
    )

    private fun errorHtml() = callbackPage(
        tone = "error",
        symbol = "&#10005;",
        title = localizedString(R.string.oauth_callback_error_title, "Authorization failed"),
        message = localizedString(
            R.string.oauth_callback_error_message,
            "The authorization provider did not approve this request.",
        ),
        hint = localizedString(
            R.string.oauth_callback_error_hint,
            "Close this tab and try again from RikkaHub.",
        ),
    )

    private fun invalidCallbackHtml() = callbackPage(
        tone = "warning",
        symbol = "!",
        title = localizedString(
            R.string.oauth_callback_invalid_title,
            "Invalid authorization callback",
        ),
        message = localizedString(
            R.string.oauth_callback_invalid_message,
            "The authorization callback is missing information or has expired.",
        ),
        hint = localizedString(
            R.string.oauth_callback_invalid_hint,
            "Return to RikkaHub and start the authorization again.",
        ),
    )

    private fun callbackAlreadyHandledHtml() = callbackPage(
        tone = "info",
        symbol = "i",
        title = localizedString(
            R.string.oauth_callback_handled_title,
            "Authorization already handled",
        ),
        message = localizedString(
            R.string.oauth_callback_handled_message,
            "This authorization callback has already been handled.",
        ),
        hint = localizedString(
            R.string.oauth_callback_handled_hint,
            "You can safely close this tab and return to RikkaHub.",
        ),
    )

    private fun localizedString(@StringRes resourceId: Int, fallback: String): String =
        localizedContext?.getString(resourceId) ?: fallback

    private fun callbackPage(
        tone: String,
        symbol: String,
        title: String,
        message: String,
        hint: String,
    ): String {
        val safeTitle = title.escapeHtml()
        val safeMessage = message.escapeHtml()
        val safeHint = hint.escapeHtml()
        val languageTag = localizedContext
            ?.resources
            ?.configuration
            ?.locales
            ?.get(0)
            ?.toLanguageTag()
            ?.escapeHtml()
            ?: "en"
        return """
            <!doctype html>
            <html lang="$languageTag">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
              <meta name="color-scheme" content="light dark">
              <title>$safeTitle · RikkaHub</title>
              <style>
                :root {
                  color-scheme: light dark;
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                  --page: #ffffff;
                  --text: #202124;
                  --muted: #5f6368;
                  --border: #dadce0;
                }
                @media (prefers-color-scheme: dark) {
                  :root {
                    --page: #202124;
                    --text: #f1f3f4;
                    --muted: #bdc1c6;
                    --border: #3c4043;
                  }
                }
                * { box-sizing: border-box; }
                body {
                  --accent: #1a73e8;
                  min-height: 100vh;
                  min-height: 100svh;
                  margin: 0;
                  display: grid;
                  place-items: center;
                  padding: max(32px, env(safe-area-inset-top)) max(24px, env(safe-area-inset-right))
                    max(32px, env(safe-area-inset-bottom)) max(24px, env(safe-area-inset-left));
                  color: var(--text);
                  background: var(--page);
                }
                body.success { --accent: #188038; }
                body.error { --accent: #d93025; }
                body.warning { --accent: #b06000; }
                main {
                  width: min(100%, 520px);
                }
                .brand {
                  margin: 0 0 44px;
                  font-size: 20px;
                  font-weight: 600;
                }
                .status {
                  display: flex;
                  align-items: flex-start;
                  gap: 18px;
                }
                .icon {
                  flex: 0 0 46px;
                  width: 46px;
                  height: 46px;
                  display: grid;
                  place-items: center;
                  color: var(--accent);
                  border: 2px solid currentColor;
                  border-radius: 50%;
                  font-size: 24px;
                  font-weight: 700;
                  line-height: 1;
                }
                h1 {
                  margin: 4px 0 0;
                  font-size: clamp(28px, 7vw, 34px);
                  font-weight: 600;
                  line-height: 1.2;
                  letter-spacing: -.015em;
                }
                .message {
                  margin: 26px 0 0 64px;
                  color: var(--muted);
                  font-size: clamp(18px, 4.6vw, 20px);
                  line-height: 1.6;
                }
                .hint {
                  margin: 34px 0 0 64px;
                  padding-top: 24px;
                  color: var(--muted);
                  border-top: 1px solid var(--border);
                  font-size: clamp(17px, 4.3vw, 18px);
                  line-height: 1.55;
                }
                @media (max-width: 420px) {
                  .brand { margin-bottom: 36px; }
                  .message, .hint { margin-left: 0; }
                }
              </style>
            </head>
            <body class="$tone">
              <main role="status" aria-live="polite">
                <p class="brand">RikkaHub</p>
                <div class="status">
                  <div class="icon" aria-hidden="true">$symbol</div>
                  <h1>$safeTitle</h1>
                </div>
                <p class="message">$safeMessage</p>
                <p class="hint">$safeHint</p>
              </main>
            </body>
            </html>
        """.trimIndent()
    }

    private fun String.escapeHtml(): String = this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}

class OAuthLoopbackCallbackSession internal constructor(
    val redirectUri: String,
    private val expectedState: String,
    private val callback: CompletableDeferred<OAuthCallback>,
    private val foregroundServiceContext: Context?,
    private val owner: OAuthLoopbackCallbackServer,
) {
    private val closed = AtomicBoolean(false)

    suspend fun awaitCallback(timeout: Duration): OAuthCallback? =
        withTimeoutOrNull(timeout) { callback.await() }

    suspend fun close() {
        if (closed.compareAndSet(false, true)) {
            owner.closeSession(expectedState, callback, foregroundServiceContext)
        }
    }
}
