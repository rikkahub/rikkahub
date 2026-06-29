package me.rerere.rikkahub.data.ai.mcp

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.common.http.await
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.uuid.Uuid

private const val MCP_OAUTH_TAG = "McpOAuthManager"

class McpOAuthManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val client: OkHttpClient,
    private val store: McpOAuthTokenStore,
    private val json: Json,
) {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var callbackPort: Int? = null
    private val sessions = ConcurrentHashMap<String, OAuthSession>()
    private val refreshLocks = ConcurrentHashMap<Uuid, Mutex>()
    private val pendingScopes = ConcurrentHashMap<Uuid, List<String>>()
    private val mutex = Mutex()
    private val _status = MutableStateFlow<Map<Uuid, McpOAuthStatus>>(emptyMap())
    val status: StateFlow<Map<Uuid, McpOAuthStatus>> = _status.asStateFlow()

    init {
        restoreStoredAuthorizationStatuses()
    }

    fun getCachedAccessToken(serverId: Uuid): String? {
        val credential = store.read().credentials.firstOrNull { it.serverId == serverId } ?: return null
        if (credential.expiresAt != null && credential.expiresAt <= System.currentTimeMillis() + REFRESH_MARGIN_MS) {
            return null
        }
        return credential.accessToken
    }

    suspend fun ensureValidToken(serverId: Uuid): String? {
        val credential = mutex.withLock {
            store.read().credentials.firstOrNull { it.serverId == serverId }
        } ?: return null
        if (credential.expiresAt == null || credential.expiresAt > System.currentTimeMillis() + REFRESH_MARGIN_MS) {
            return credential.accessToken
        }
        return refreshToken(serverId)
    }

    suspend fun authorize(serverId: Uuid, serverUrl: String): OAuthResult {
        setStatus(serverId, McpOAuthStatus.Authorizing)
        var state: String? = null
        return runCatching {
            val challenge = fetchInitialChallenge(serverUrl)
            val discovered = discover(serverUrl, challenge)
            val requestedScopes = pendingScopes[serverId] ?: discovered.scopes
            val verifier = randomUrlSafe(64)
            val codeChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray())
            )
            val port = ensureCallbackServer()
            val redirectUri = "http://localhost:$port/auth/callback"
            val clientRegistration = ensureClient(serverId, discovered, redirectUri, requestedScopes)
            val oauthState = randomUrlSafe(32)
            state = oauthState
            val deferred = CompletableDeferred<StoredMcpOAuthCredential>()
            sessions[oauthState] = OAuthSession(
                serverId = serverId,
                verifier = verifier,
                redirectUri = redirectUri,
                discovered = discovered,
                client = clientRegistration,
                scopes = requestedScopes,
                deferred = deferred,
            )

            val authUrl = Uri.parse(discovered.asMetadata.authorizationEndpoint).buildUpon()
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("client_id", clientRegistration.clientId)
                .appendQueryParameter("redirect_uri", redirectUri)
                .appendQueryParameter("state", oauthState)
                .appendQueryParameter("code_challenge", codeChallenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("resource", discovered.resource)
                .apply {
                    if (requestedScopes.isNotEmpty()) {
                        appendQueryParameter("scope", requestedScopes.joinToString(" "))
                    }
                }
                .build()

            context.startActivity(
                Intent(Intent.ACTION_VIEW, authUrl).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )

            val credential = withTimeout(AUTH_TIMEOUT_MS) { deferred.await() }
            pendingScopes.remove(serverId)
            setStatus(serverId, McpOAuthStatus.Authorized)
            OAuthResult.Success(credential.accessToken)
        }.getOrElse { error ->
            state?.let { sessions.remove(it)?.deferred?.cancel() }
                ?: sessions.entries.removeIf { it.value.serverId == serverId }
            Log.e(MCP_OAUTH_TAG, "OAuth authorization failed: ${error.safeLogMessage()}")
            val message = when (error) {
                is TimeoutCancellationException -> "MCP OAuth authorization timed out. Please try again."
                else -> error.message ?: "MCP OAuth authorization failed"
            }
            setStatus(serverId, McpOAuthStatus.Error(message))
            OAuthResult.Error(message)
        }.also {
            stopCallbackServerIfIdle()
        }
    }

    suspend fun handleAuthFailure(serverId: Uuid, statusCode: Int, wwwAuthenticate: String?): Boolean {
        return when (statusCode) {
            401 -> {
                val refreshed = refreshToken(serverId) != null
                if (!refreshed) {
                    clearCredential(serverId)
                    setStatus(serverId, McpOAuthStatus.Error("MCP OAuth authorization expired. Please authorize again."))
                }
                refreshed
            }
            403 -> {
                val scope = parseWwwAuthenticate(wwwAuthenticate).scope.orEmpty()
                val scopes = scope.split(" ").filter { it.isNotBlank() }
                if (scopes.isNotEmpty()) {
                    pendingScopes[serverId] = scopes
                }
                val message = if (scope.isBlank()) {
                    "MCP OAuth permission is insufficient. Please authorize again."
                } else {
                    "MCP OAuth permission is insufficient. Please authorize again for: $scope"
                }
                setStatus(serverId, McpOAuthStatus.Error(message))
                false
            }
            else -> false
        }
    }

    suspend fun revoke(serverId: Uuid) {
        mutex.withLock {
            val state = store.read()
            store.write(
                state.copy(
                    credentials = state.credentials.filterNot { it.serverId == serverId },
                    clients = state.clients.filterNot { it.serverId == serverId },
                )
            )
        }
        sessions.entries.removeIf { it.value.serverId == serverId }
        stopCallbackServerIfIdle()
        setStatus(serverId, McpOAuthStatus.Idle)
    }

    private suspend fun clearCredential(serverId: Uuid) {
        mutex.withLock {
            val state = store.read()
            store.write(
                state.copy(
                    credentials = state.credentials.filterNot { it.serverId == serverId },
                )
            )
        }
    }

    private suspend fun refreshToken(serverId: Uuid): String? {
        val lock = refreshLocks.getOrPut(serverId) { Mutex() }
        return lock.withLock {
            val existing = mutex.withLock {
                store.read().credentials.firstOrNull { it.serverId == serverId }
            } ?: return@withLock null
            val refreshToken = existing.refreshToken ?: return@withLock null
            runCatching {
                val form = FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refreshToken)
                    .add("client_id", existing.clientId)
                    .add("resource", existing.resource)
                    .apply {
                        existing.clientSecret?.let { add("client_secret", it) }
                        if (existing.scopes.isNotEmpty()) add("scope", existing.scopes.joinToString(" "))
                    }
                    .build()
                val response = client.newCall(
                    Request.Builder()
                        .url(existing.tokenEndpoint)
                        .post(form)
                        .build()
                ).await()
                val body = response.body.string()
                if (!response.isSuccessful) {
                    throw OAuthHttpException(response.code, "MCP OAuth token refresh failed: ${response.code}")
                }
                val token = json.parseToJsonElement(body).jsonObject
                val updated = existing.copy(
                    accessToken = token.string("access_token") ?: error("Missing refreshed access token"),
                    refreshToken = token.string("refresh_token") ?: existing.refreshToken,
                    expiresAt = token.long("expires_in")?.let { System.currentTimeMillis() + it * 1000L },
                    tokenType = token.string("token_type") ?: existing.tokenType,
                )
                saveCredential(updated)
                setStatus(serverId, McpOAuthStatus.Authorized)
                updated.accessToken
            }.getOrElse {
                Log.e(MCP_OAUTH_TAG, "MCP OAuth token refresh failed: ${it.safeLogMessage()}")
                val statusCode = (it as? OAuthHttpException)?.statusCode
                if (statusCode == 400 || statusCode == 401 || statusCode == 403) {
                    clearCredential(serverId)
                }
                setStatus(serverId, McpOAuthStatus.Error("MCP OAuth token expired. Please authorize again."))
                null
            }
        }
    }

    private suspend fun exchangeCode(code: String, session: OAuthSession): StoredMcpOAuthCredential {
        awaitNetworkUnblocked()
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", session.client.clientId)
            .add("code", code)
            .add("redirect_uri", session.redirectUri)
            .add("code_verifier", session.verifier)
            .add("resource", session.discovered.resource)
            .apply {
                session.client.clientSecret?.let { add("client_secret", it) }
            }
            .build()
        val response = client.newCall(
            Request.Builder()
                .url(session.discovered.asMetadata.tokenEndpoint)
                .post(form)
                .build()
        ).await()
        val body = response.body.string()
        if (!response.isSuccessful) {
            error("MCP OAuth token exchange failed: ${response.code}")
        }
        val token = json.parseToJsonElement(body).jsonObject
        val credential = StoredMcpOAuthCredential(
            serverId = session.serverId,
            resource = session.discovered.resource,
            authorizationServerIssuer = session.discovered.asMetadata.issuer,
            tokenEndpoint = session.discovered.asMetadata.tokenEndpoint,
            clientId = session.client.clientId,
            clientSecret = session.client.clientSecret,
            scopes = session.scopes,
            accessToken = token.string("access_token") ?: error("Missing access token"),
            refreshToken = token.string("refresh_token"),
            expiresAt = token.long("expires_in")?.let { System.currentTimeMillis() + it * 1000L },
            tokenType = token.string("token_type") ?: "Bearer",
        )
        if (!session.deferred.isActive) {
            error("OAuth session is no longer active.")
        }
        saveCredential(credential)
        return credential
    }

    private suspend fun saveCredential(credential: StoredMcpOAuthCredential) = mutex.withLock {
        val state = store.read()
        store.write(
            state.copy(
                credentials = state.credentials.filterNot { it.serverId == credential.serverId } + credential
            )
        )
    }

    private suspend fun ensureClient(
        serverId: Uuid,
        discovered: DiscoveredMetadata,
        redirectUri: String,
        scopes: List<String>,
    ): StoredMcpOAuthClient {
        val existing = mutex.withLock {
            store.read().clients.firstOrNull {
                it.serverId == serverId && it.authorizationServerIssuer == discovered.asMetadata.issuer
            }
        }
        if (existing != null) return existing
        val registrationEndpoint = discovered.asMetadata.registrationEndpoint
            ?: error("This MCP server does not support automatic OAuth client registration. Manual client ID setup is not available in this build yet.")
        val body = buildJsonObject {
            put("client_name", "RikkaHub")
            putJsonArray("redirect_uris") { add(JsonPrimitive(redirectUri)) }
            putJsonArray("grant_types") {
                add(JsonPrimitive("authorization_code"))
                add(JsonPrimitive("refresh_token"))
            }
            putJsonArray("response_types") { add(JsonPrimitive("code")) }
            put("token_endpoint_auth_method", "none")
            if (scopes.isNotEmpty()) {
                put("scope", scopes.joinToString(" "))
            }
        }
        val response = client.newCall(
            Request.Builder()
                .url(registrationEndpoint)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
        ).await()
        val responseBody = response.body.string()
        if (!response.isSuccessful) {
            error("MCP OAuth client registration failed: ${response.code}")
        }
        val registered = json.parseToJsonElement(responseBody).jsonObject
        val stored = StoredMcpOAuthClient(
            serverId = serverId,
            authorizationServerIssuer = discovered.asMetadata.issuer,
            clientId = registered.string("client_id") ?: error("Missing registered client_id"),
            clientSecret = registered.string("client_secret"),
        )
        mutex.withLock {
            val state = store.read()
            store.write(
                state.copy(
                    clients = state.clients.filterNot {
                        it.serverId == serverId && it.authorizationServerIssuer == discovered.asMetadata.issuer
                    } + stored
                )
            )
        }
        return stored
    }

    private suspend fun discover(serverUrl: String, challenge: AuthChallenge): DiscoveredMetadata {
        val protectedResource = discoverProtectedResource(serverUrl, challenge.resourceMetadata)
        val scopes = when {
            challenge.scope.isNotBlank() -> challenge.scope.split(" ").filter { it.isNotBlank() }
            protectedResource.scopesSupported.isNotEmpty() -> protectedResource.scopesSupported
            else -> emptyList()
        }
        for (issuer in protectedResource.authorizationServers) {
            val asMetadata = discoverAuthorizationServer(issuer)
            if (asMetadata != null) {
                if (!asMetadata.codeChallengeMethodsSupported.contains("S256")) {
                    error("Authorization server does not support required PKCE S256.")
                }
                return DiscoveredMetadata(
                    resource = protectedResource.resource,
                    scopes = scopes,
                    asMetadata = asMetadata,
                )
            }
        }
        error("Unable to discover MCP OAuth authorization server metadata.")
    }

    private suspend fun discoverProtectedResource(serverUrl: String, resourceMetadataUrl: String?): ProtectedResourceMetadata {
        val candidates = buildList {
            resourceMetadataUrl?.takeIf { it.isNotBlank() }?.let(::add)
            val uri = Uri.parse(serverUrl)
            val origin = "${uri.scheme}://${uri.encodedAuthority}"
            val path = uri.encodedPath.orEmpty()
            if (path.isNotBlank() && path != "/") add("$origin/.well-known/oauth-protected-resource$path")
            add("$origin/.well-known/oauth-protected-resource")
        }.distinct()
        for (url in candidates) {
            val metadata = runCatching { getJson(url) }.getOrNull() ?: continue
            val resource = metadata.string("resource") ?: continue
            val authorizationServers = metadata.arrayStrings("authorization_servers")
            if (authorizationServers.isEmpty()) continue
            return ProtectedResourceMetadata(
                resource = resource,
                authorizationServers = authorizationServers,
                scopesSupported = metadata.arrayStrings("scopes_supported"),
            )
        }
        error("Unable to discover MCP protected-resource metadata.")
    }

    private suspend fun discoverAuthorizationServer(issuer: String): AsMetadata? {
        val candidates = authorizationServerMetadataCandidates(issuer)
        for (url in candidates) {
            val metadata = runCatching { getJson(url) }.getOrNull() ?: continue
            val authorizationEndpoint = metadata.string("authorization_endpoint") ?: continue
            val tokenEndpoint = metadata.string("token_endpoint") ?: continue
            requireHttpsOrLocalhost(authorizationEndpoint, "authorization_endpoint")
            requireHttpsOrLocalhost(tokenEndpoint, "token_endpoint")
            metadata.string("registration_endpoint")?.let {
                requireHttpsOrLocalhost(it, "registration_endpoint")
            }
            return AsMetadata(
                issuer = metadata.string("issuer") ?: issuer,
                authorizationEndpoint = authorizationEndpoint,
                tokenEndpoint = tokenEndpoint,
                registrationEndpoint = metadata.string("registration_endpoint"),
                codeChallengeMethodsSupported = metadata.arrayStrings("code_challenge_methods_supported"),
            )
        }
        return null
    }

    private fun authorizationServerMetadataCandidates(issuer: String): List<String> {
        val uri = Uri.parse(issuer)
        val origin = "${uri.scheme}://${uri.encodedAuthority}"
        val path = uri.encodedPath.orEmpty().trim('/')
        return if (path.isBlank()) {
            listOf(
                "$origin/.well-known/oauth-authorization-server",
                "$origin/.well-known/openid-configuration",
            )
        } else {
            listOf(
                "$origin/.well-known/oauth-authorization-server/$path",
                "$origin/.well-known/openid-configuration/$path",
                "${issuer.trimEnd('/')}/.well-known/openid-configuration",
            )
        }
    }

    private suspend fun getJson(url: String): JsonObject {
        val response = client.newCall(Request.Builder().url(url).get().build()).await()
        val body = response.body.string()
        if (!response.isSuccessful) {
            error("GET $url failed: ${response.code}")
        }
        return json.parseToJsonElement(body).jsonObject
    }

    private suspend fun fetchInitialChallenge(serverUrl: String): AuthChallenge {
        val response = runCatching {
            client.newCall(Request.Builder().url(serverUrl).get().build()).await()
        }.getOrNull()
        return parseWwwAuthenticate(response?.header("WWW-Authenticate"))
    }

    @Synchronized
    private fun ensureCallbackServer(): Int {
        callbackPort?.let { return it }
        var lastError: Throwable? = null
        for (port in CALLBACK_PORTS) {
            try {
                server = embeddedServer(CIO, host = "127.0.0.1", port = port) {
                    routing {
                        get("/auth/callback") {
                            val callbackState = call.request.queryParameters["state"]
                            val code = call.request.queryParameters["code"]
                            val error = call.request.queryParameters["error"]
                            val session = callbackState?.let(sessions::remove)
                            when {
                                session == null -> {
                                    call.respondText(callbackPage(false), ContentType.Text.Html)
                                    stopCallbackServerIfIdle()
                                }

                                !error.isNullOrBlank() -> {
                                    session.deferred.completeExceptionally(IllegalStateException(error))
                                    call.respondText(callbackPage(false), ContentType.Text.Html)
                                    stopCallbackServerIfIdle()
                                }

                                code.isNullOrBlank() -> {
                                    session.deferred.completeExceptionally(IllegalStateException("Missing authorization code"))
                                    call.respondText(callbackPage(false), ContentType.Text.Html)
                                    stopCallbackServerIfIdle()
                                }

                                else -> {
                                    call.respondText(callbackPage(true), ContentType.Text.Html)
                                    scope.launch {
                                        runCatching {
                                            withTimeout(TOKEN_EXCHANGE_TIMEOUT_MS) {
                                                exchangeCode(code, session)
                                            }
                                        }
                                            .onSuccess { session.deferred.complete(it) }
                                            .onFailure { session.deferred.completeExceptionally(it) }
                                        stopCallbackServerIfIdle()
                                    }
                                }
                            }
                        }
                    }
                }.start(wait = false)
                callbackPort = port
                return port
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw IllegalStateException("MCP OAuth callback ports are unavailable", lastError)
    }

    @Synchronized
    private fun stopCallbackServerIfIdle() {
        if (sessions.isNotEmpty()) return
        val currentServer = server ?: return
        server = null
        callbackPort = null
        runCatching {
            currentServer.stop(gracePeriodMillis = 500, timeoutMillis = 1_000)
        }.onFailure {
            Log.w(MCP_OAUTH_TAG, "Failed to stop OAuth callback server: ${it.safeLogMessage()}")
        }
    }

    private suspend fun awaitNetworkUnblocked() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        runCatching {
            withTimeout(NETWORK_UNBLOCK_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    lateinit var callback: ConnectivityManager.NetworkCallback
                    callback = object : ConnectivityManager.NetworkCallback() {
                        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                            if (!blocked && continuation.isActive) {
                                runCatching { connectivityManager.unregisterNetworkCallback(callback) }
                                continuation.resume(Unit)
                            }
                        }
                    }
                    continuation.invokeOnCancellation {
                        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
                    }
                    connectivityManager.registerDefaultNetworkCallback(callback)
                    val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                    if (
                        continuation.isActive &&
                        (capabilities == null ||
                            capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED))
                    ) {
                        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
                        continuation.resume(Unit)
                    }
                }
            }
        }.onFailure {
            Log.w(MCP_OAUTH_TAG, "Network unblock check skipped: ${it.safeLogMessage()}")
        }
    }

    private fun callbackPage(success: Boolean): String {
        return """
            <!doctype html>
            <html>
              <head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
              <body style="font-family: sans-serif; padding: 24px;">
                <h3>${if (success) "RikkaHub authorization complete" else "RikkaHub authorization failed"}</h3>
                <p>${if (success) "You can close this tab and return to RikkaHub." else "Please return to RikkaHub and try again."}</p>
              </body>
            </html>
        """.trimIndent()
    }

    private fun setStatus(serverId: Uuid, status: McpOAuthStatus) {
        _status.value = _status.value.toMutableMap().apply { put(serverId, status) }
    }

    private fun restoreStoredAuthorizationStatuses() {
        runCatching {
            val now = System.currentTimeMillis()
            _status.value = store.read().credentials
                .filter { it.isUsable(now) }
                .associate { it.serverId to McpOAuthStatus.Authorized }
        }.onFailure {
            Log.w(MCP_OAUTH_TAG, "Failed to restore MCP OAuth authorization status: ${it.safeLogMessage()}")
        }
    }

    private fun randomUrlSafe(size: Int): String {
        val bytes = ByteArray(size)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        const val REFRESH_MARGIN_MS = 30_000L
        const val AUTH_TIMEOUT_MS = 5 * 60 * 1000L
        const val TOKEN_EXCHANGE_TIMEOUT_MS = 60 * 1000L
        const val NETWORK_UNBLOCK_TIMEOUT_MS = 10 * 1000L
        val CALLBACK_PORTS = listOf(1455, 1457, 1459)
    }
}

sealed interface OAuthResult {
    data class Success(val accessToken: String) : OAuthResult
    data class Error(val message: String) : OAuthResult
}

sealed interface McpOAuthStatus {
    data object Idle : McpOAuthStatus
    data object Authorizing : McpOAuthStatus
    data object Authorized : McpOAuthStatus
    data class Error(val message: String) : McpOAuthStatus
}

private data class OAuthSession(
    val serverId: Uuid,
    val verifier: String,
    val redirectUri: String,
    val discovered: DiscoveredMetadata,
    val client: StoredMcpOAuthClient,
    val scopes: List<String>,
    val deferred: CompletableDeferred<StoredMcpOAuthCredential>,
)

private class OAuthHttpException(
    val statusCode: Int,
    message: String,
) : IllegalStateException(message)

private data class AuthChallenge(
    val resourceMetadata: String? = null,
    val scope: String = "",
)

private data class ProtectedResourceMetadata(
    val resource: String,
    val authorizationServers: List<String>,
    val scopesSupported: List<String>,
)

private data class DiscoveredMetadata(
    val resource: String,
    val scopes: List<String>,
    val asMetadata: AsMetadata,
)

private data class AsMetadata(
    val issuer: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val registrationEndpoint: String?,
    val codeChallengeMethodsSupported: List<String>,
)

private fun parseWwwAuthenticate(header: String?): AuthChallenge {
    if (header.isNullOrBlank()) return AuthChallenge()
    fun find(name: String): String? {
        val quoted = Regex("""$name\s*=\s*"([^"]*)"""").find(header)?.groupValues?.getOrNull(1)
        if (quoted != null) return quoted
        return Regex("""$name\s*=\s*([^,\s]+)""").find(header)?.groupValues?.getOrNull(1)
    }
    return AuthChallenge(
        resourceMetadata = find("resource_metadata"),
        scope = find("scope").orEmpty(),
    )
}

private fun requireHttpsOrLocalhost(url: String, field: String) {
    val uri = Uri.parse(url)
    val scheme = uri.scheme.orEmpty().lowercase()
    val host = uri.host.orEmpty().lowercase()
    if (scheme == "https") return
    if (scheme == "http" && (host == "localhost" || host == "127.0.0.1" || host == "::1")) return
    error("OAuth $field must use HTTPS.")
}

private fun Throwable.safeLogMessage(): String {
    return "${this::class.java.simpleName}: ${message?.take(160).orEmpty()}"
}

private fun StoredMcpOAuthCredential.isUsable(nowMillis: Long): Boolean {
    return expiresAt == null || expiresAt > nowMillis + 30_000L || refreshToken != null
}

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.long(name: String): Long? = string(name)?.toLongOrNull()

private fun JsonObject.arrayStrings(name: String): List<String> {
    return (this[name] as? JsonArray)?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
}
