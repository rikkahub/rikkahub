package me.rerere.rikkahub.data.ai.mcp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private const val TAG = "McpOAuthCoordinator"
private const val TOKEN_REFRESH_LEEWAY_MS = 60_000L
private val OAUTH_CALLBACK_TIMEOUT = 5.minutes

/**
 * 负责 MCP OAuth 的授权、令牌刷新与持久化。
 *
 * 连接生命周期由配置流的消费者管理；令牌持久化后，配置变化会自然触发连接替换。
 */
internal class McpOAuthCoordinator(
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val oauthClient: McpOAuthClient,
    private val updateStatus: (Uuid, McpStatus) -> Unit,
) {
    private val authorizationJobs = ConcurrentHashMap<Uuid, Job>()
    private val refreshLocks = ConcurrentHashMap<Uuid, Mutex>()

    fun startAuthorization(config: McpServerConfig, context: Context) {
        authorizationJobs.remove(config.id)?.cancel()
        val job = appScope.launch {
            updateStatus(config.id, McpStatus.Authorizing)
            try {
                authorize(config, context.applicationContext)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "OAuth authorization failed for ${config.commonOptions.name}", e)
                updateStatus(config.id, McpStatus.Error.from(e, fallbackMessage = "OAuth authorization failed"))
            }
        }
        authorizationJobs[config.id] = job
        job.invokeOnCompletion { authorizationJobs.remove(config.id, job) }
    }

    fun cancelAuthorization(configId: Uuid) {
        authorizationJobs.remove(configId)?.cancel()
        updateStatus(configId, McpStatus.NeedsAuthorization)
    }

    fun forget(configId: Uuid) {
        authorizationJobs.remove(configId)?.cancel()
        refreshLocks.remove(configId)
    }

    suspend fun clearAuthorization(config: McpServerConfig): McpServerConfig {
        persistOAuthState(config.id, null)
        return settingsStore.settingsFlow.value.mcpServers.find { it.id == config.id }
            ?: config.clone(commonOptions = config.commonOptions.copy(oauth = null))
    }

    /**
     * 按 serverId 串行刷新。获得锁后重新读取配置，避免并发工具调用重复使用同一个 refresh token。
     */
    suspend fun ensureFreshToken(configInput: McpServerConfig): McpServerConfig {
        val lock = refreshLocks.computeIfAbsent(configInput.id) { Mutex() }
        return lock.withLock {
            val config = settingsStore.settingsFlow.value.mcpServers.find { it.id == configInput.id }
                ?: configInput
            val oauth = config.commonOptions.oauth ?: return@withLock config
            if (!oauth.enabled || oauth.refreshToken.isNullOrBlank()) return@withLock config

            val expired = oauth.expiresAt > 0 &&
                System.currentTimeMillis() >= oauth.expiresAt - TOKEN_REFRESH_LEEWAY_MS
            if (!oauth.accessToken.isNullOrBlank() && !expired) return@withLock config

            val tokenEndpoint = oauth.tokenEndpoint ?: return@withLock config
            val clientId = oauth.clientId ?: return@withLock config
            runCatching {
                val token = oauthClient.refreshToken(
                    tokenEndpoint = tokenEndpoint,
                    clientId = clientId,
                    clientSecret = oauth.clientSecret,
                    refreshToken = oauth.refreshToken,
                    resource = McpOAuthClient.canonicalResource(config.serverUrl),
                    scope = oauth.scope,
                )
                val updated = oauth.copy(
                    accessToken = token.accessToken,
                    refreshToken = token.refreshToken ?: oauth.refreshToken,
                    expiresAt = computeExpiry(token.expiresIn),
                    scope = token.scope ?: oauth.scope,
                )
                persistOAuthState(config.id, updated)
                config.clone(commonOptions = config.commonOptions.copy(oauth = updated))
            }.getOrElse {
                Log.w(TAG, "Token refresh failed for ${config.commonOptions.name}: ${it.message}")
                config
            }
        }
    }

    suspend fun needsAuthorization(config: McpServerConfig, error: Throwable): Boolean {
        if (!looksUnauthorized(error)) return false
        if (config.commonOptions.oauth?.enabled == true) return true
        if (config.commonOptions.headers.any { it.first.equals("Authorization", ignoreCase = true) }) {
            return false
        }
        return runCatching { oauthClient.discoverProtectedResource(config.serverUrl) }
            .onFailure {
                Log.i(TAG, "OAuth probe failed for ${config.commonOptions.name}: ${it.message}")
            }
            .isSuccess
    }

    private suspend fun authorize(config: McpServerConfig, context: Context) = withContext(Dispatchers.IO) {
        val serverUrl = config.serverUrl
        require(serverUrl.isNotBlank()) { "Server URL 为空，无法授权" }

        val protectedResource = oauthClient.discoverProtectedResource(serverUrl)
        val issuer = protectedResource.authorizationServers.firstOrNull()
            ?: error("受保护资源未声明授权服务器")
        val metadata = oauthClient.discoverAuthorizationServer(issuer)
        val authorizationEndpoint = metadata.authorizationEndpoint
            ?: error("授权服务器缺少 authorization_endpoint")
        val tokenEndpoint = metadata.tokenEndpoint
            ?: error("授权服务器缺少 token_endpoint")
        val scope = config.commonOptions.oauth?.scope
            ?: protectedResource.scopesSupported?.joinToString(" ")
            ?: metadata.scopesSupported?.joinToString(" ")

        val existing = config.commonOptions.oauth
        var clientId = existing?.clientId
        var clientSecret = existing?.clientSecret
        if (clientId.isNullOrBlank()) {
            val registrationEndpoint = metadata.registrationEndpoint
                ?: error("授权服务器不支持动态注册，且未预配置 client_id")
            val registration = oauthClient.registerClient(
                registrationEndpoint = registrationEndpoint,
                clientName = config.commonOptions.name,
                redirectUri = MCP_OAUTH_REDIRECT_URI,
                scope = scope,
            )
            clientId = registration.clientId
            clientSecret = registration.clientSecret
        }

        val pkce = oauthClient.generatePkce()
        val state = oauthClient.generateState()
        val resource = McpOAuthClient.canonicalResource(serverUrl)
        persistOAuthState(
            config.id,
            (existing ?: McpOAuthState()).copy(
                enabled = true,
                clientId = clientId,
                clientSecret = clientSecret,
                authorizationEndpoint = authorizationEndpoint,
                tokenEndpoint = tokenEndpoint,
                registrationEndpoint = metadata.registrationEndpoint,
                scope = scope,
            )
        )

        val authorizationUrl = oauthClient.buildAuthorizationUrl(
            authorizationEndpoint = authorizationEndpoint,
            clientId = clientId,
            redirectUri = MCP_OAUTH_REDIRECT_URI,
            pkce = pkce,
            state = state,
            scope = scope,
            resource = resource,
        )
        val callback = awaitCallbackAndLaunchBrowser(context, authorizationUrl, state)
            ?: error("OAuth 授权超时")
        callback.error?.let { error("授权失败: $it") }
        val code = callback.code ?: error("授权失败: 未返回授权码")

        val token = oauthClient.exchangeCode(
            tokenEndpoint = tokenEndpoint,
            clientId = clientId,
            clientSecret = clientSecret,
            code = code,
            codeVerifier = pkce.verifier,
            redirectUri = MCP_OAUTH_REDIRECT_URI,
            resource = resource,
        )
        persistOAuthState(
            config.id,
            McpOAuthState(
                enabled = true,
                clientId = clientId,
                clientSecret = clientSecret,
                authorizationEndpoint = authorizationEndpoint,
                tokenEndpoint = tokenEndpoint,
                registrationEndpoint = metadata.registrationEndpoint,
                scope = token.scope ?: scope,
                accessToken = token.accessToken,
                refreshToken = token.refreshToken,
                expiresAt = computeExpiry(token.expiresIn),
            )
        )

    }

    private suspend fun awaitCallbackAndLaunchBrowser(
        context: Context,
        authorizationUrl: String,
        state: String,
    ): AppEvent.McpOAuthCallback? = coroutineScope {
        val subscribed = CompletableDeferred<Unit>()
        val callback = async {
            withTimeoutOrNull(OAUTH_CALLBACK_TIMEOUT) {
                appEventBus.events
                    .onSubscription { subscribed.complete(Unit) }
                    .filterIsInstance<AppEvent.McpOAuthCallback>()
                    .first { it.state == state }
            }
        }
        subscribed.await()
        withContext(Dispatchers.Main) {
            launchOAuthAuthorization(context, authorizationUrl)
        }
        callback.await()
    }

    private suspend fun persistOAuthState(configId: Uuid, oauth: McpOAuthState?) {
        settingsStore.update { old ->
            old.copy(
                mcpServers = old.mcpServers.map { server ->
                    if (server.id != configId) server
                    else server.clone(commonOptions = server.commonOptions.copy(oauth = oauth))
                }
            )
        }
    }

    private fun computeExpiry(expiresIn: Long?): Long =
        if (expiresIn != null && expiresIn > 0) {
            System.currentTimeMillis() + expiresIn * 1000
        } else {
            0L
        }

    private fun looksUnauthorized(error: Throwable): Boolean {
        val message = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase()
        return message.contains("401") ||
            message.contains("unauthorized") ||
            message.contains("invalid_token") ||
            message.contains("invalid access token") ||
            message.contains("missing or invalid")
    }
}
