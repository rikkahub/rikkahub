package me.rerere.rikkahub.data.ai.openai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.provider.OPENAI_CODEX_BASE_URL
import me.rerere.ai.provider.OpenAIAuthType
import me.rerere.ai.provider.OpenAICodexCredentials
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.openai.OpenAICodexTokenProvider
import me.rerere.common.http.await
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val AUTH_BASE_URL = "https://auth.openai.com"
private const val DEVICE_AUTH_BASE_URL = "$AUTH_BASE_URL/api/accounts/deviceauth"
private const val DEVICE_VERIFICATION_URL = "$AUTH_BASE_URL/codex/device"
private const val TOKEN_URL = "$AUTH_BASE_URL/oauth/token"
private const val DEVICE_CALLBACK_URL = "$AUTH_BASE_URL/deviceauth/callback"

// Public OAuth client identifier used by the open-source Codex client. It is not a client secret.
private const val CODEX_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
private const val DEFAULT_POLL_INTERVAL_SECONDS = 5L
private const val DEVICE_AUTH_TIMEOUT_MS = 15 * 60 * 1000L
private const val TOKEN_REFRESH_LEEWAY_MS = 5 * 60 * 1000L
private val JSON_MEDIA_TYPE = "application/json".toMediaType()

/** Device-code information shown while the user authorizes RikkaHub in a browser. */
data class OpenAICodexDeviceCode(
    val userCode: String,
    val verificationUrl: String = DEVICE_VERIFICATION_URL,
)

/** Implements Codex device login and keeps subscription access tokens fresh. */
class OpenAICodexAuthService(
    private val httpClient: OkHttpClient,
    private val settingsStore: SettingsStore,
) : OpenAICodexTokenProvider {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val credentialCache = ConcurrentHashMap<Uuid, OpenAICodexCredentials>()
    private val refreshLocks = ConcurrentHashMap<Uuid, Mutex>()

    @Serializable
    private data class DeviceCodeResponse(
        @SerialName("device_auth_id") val deviceAuthId: String,
        @SerialName("user_code") val userCode: String,
        val interval: Long = DEFAULT_POLL_INTERVAL_SECONDS,
    )

    @Serializable
    private data class DeviceAuthorizationResponse(
        @SerialName("authorization_code") val authorizationCode: String,
        @SerialName("code_verifier") val codeVerifier: String,
    )

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("id_token") val idToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long? = null,
        @SerialName("account_id") val accountId: String? = null,
    )

    suspend fun signIn(
        providerId: Uuid,
        onDeviceCodeReady: (OpenAICodexDeviceCode) -> Unit,
    ): OpenAICodexCredentials {
        val credentials = withContext(Dispatchers.IO) {
            val deviceCode = requestDeviceCode()
            withContext(Dispatchers.Main) {
                onDeviceCodeReady(OpenAICodexDeviceCode(deviceCode.userCode))
            }
            val authorization = awaitDeviceAuthorization(deviceCode)
            val token = exchangeAuthorizationCode(authorization)
            token.toCredentials(previous = null)
        }
        credentialCache[providerId] = credentials
        persistCredentials(providerId, credentials, activateSubscription = true)
        return credentials
    }

    suspend fun signOut(providerId: Uuid) {
        credentialCache.remove(providerId)
        refreshLocks.remove(providerId)
        persistCredentials(providerId, credentials = null, activateSubscription = false)
    }

    override suspend fun getCredentials(
        providerSetting: ProviderSetting.OpenAI,
    ): OpenAICodexCredentials {
        val initial = credentialCache[providerSetting.id]
            ?: providerSetting.codexCredentials
            ?: error("OpenAI Codex is not signed in. Sign in with ChatGPT first.")
        if (!initial.needsRefresh()) return initial

        val lock = refreshLocks.computeIfAbsent(providerSetting.id) { Mutex() }
        return lock.withLock {
            val latestSetting = settingsStore.settingsFlow.value.providers
                .filterIsInstance<ProviderSetting.OpenAI>()
                .firstOrNull { it.id == providerSetting.id }
            val current = credentialCache[providerSetting.id]
                ?: latestSetting?.codexCredentials
                ?: initial
            if (!current.needsRefresh()) return@withLock current

            val refreshed = refresh(current)
            credentialCache[providerSetting.id] = refreshed
            persistCredentials(
                providerId = providerSetting.id,
                credentials = refreshed,
                activateSubscription = false,
            )
            refreshed
        }
    }

    private suspend fun requestDeviceCode(): DeviceCodeResponse {
        val requestBody = json.encodeToString(
            buildJsonObject { put("client_id", CODEX_CLIENT_ID) }
        )
        val request = Request.Builder()
            .url("$DEVICE_AUTH_BASE_URL/usercode")
            .header("Accept", "application/json")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val response = execute(request)
        if (response.code !in 200..299) {
            error(httpError("Failed to start OpenAI Codex sign-in", response))
        }
        return json.decodeFromString(response.body)
    }

    private suspend fun awaitDeviceAuthorization(
        deviceCode: DeviceCodeResponse,
    ): DeviceAuthorizationResponse {
        val deadline = System.currentTimeMillis() + DEVICE_AUTH_TIMEOUT_MS
        val intervalMs = deviceCode.interval.coerceAtLeast(1L) * 1000L
        while (System.currentTimeMillis() < deadline) {
            val requestBody = json.encodeToString(
                buildJsonObject {
                    put("device_auth_id", deviceCode.deviceAuthId)
                    put("user_code", deviceCode.userCode)
                }
            )
            val request = Request.Builder()
                .url("$DEVICE_AUTH_BASE_URL/token")
                .header("Accept", "application/json")
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val response = execute(request)
            if (response.code in 200..299) {
                return json.decodeFromString(response.body)
            }
            if (!response.isAuthorizationPending()) {
                error(httpError("OpenAI Codex sign-in was rejected", response))
            }
            delay(intervalMs)
        }
        error("OpenAI Codex sign-in timed out. Start the sign-in flow again.")
    }

    private suspend fun exchangeAuthorizationCode(
        authorization: DeviceAuthorizationResponse,
    ): TokenResponse {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", authorization.authorizationCode)
            .add("redirect_uri", DEVICE_CALLBACK_URL)
            .add("client_id", CODEX_CLIENT_ID)
            .add("code_verifier", authorization.codeVerifier)
            .build()
        return executeTokenRequest(form, "Failed to finish OpenAI Codex sign-in")
    }

    private suspend fun refresh(previous: OpenAICodexCredentials): OpenAICodexCredentials {
        val form = FormBody.Builder()
            .add("client_id", CODEX_CLIENT_ID)
            .add("grant_type", "refresh_token")
            .add("refresh_token", previous.refreshToken)
            .build()
        val token = executeTokenRequest(form, "Failed to refresh OpenAI Codex sign-in")
        return token.toCredentials(previous)
    }

    private suspend fun executeTokenRequest(form: FormBody, errorPrefix: String): TokenResponse {
        val request = Request.Builder()
            .url(TOKEN_URL)
            .header("Accept", "application/json")
            .post(form)
            .build()
        val response = execute(request)
        if (response.code !in 200..299) error(httpError(errorPrefix, response))
        return json.decodeFromString(response.body)
    }

    private suspend fun execute(request: Request): HttpResult {
        return httpClient.newCall(request).await().use { response ->
            HttpResult(response.code, response.body.string())
        }
    }

    private fun TokenResponse.toCredentials(
        previous: OpenAICodexCredentials?,
    ): OpenAICodexCredentials {
        val accessClaims = decodeJwtPayload(accessToken)
        val idClaims = idToken?.let(::decodeJwtPayload)
        val authClaims = idClaims?.get("https://api.openai.com/auth")?.jsonObject
            ?: accessClaims?.get("https://api.openai.com/auth")?.jsonObject
        val resolvedAccountId = accountId
            ?: authClaims?.get("chatgpt_account_id")?.jsonPrimitive?.contentOrNull
            ?: idClaims?.get("chatgpt_account_id")?.jsonPrimitive?.contentOrNull
            ?: accessClaims?.get("chatgpt_account_id")?.jsonPrimitive?.contentOrNull
            ?: previous?.accountId
            ?: error("OpenAI Codex sign-in did not return a ChatGPT account ID.")
        val expiresAt = expiresIn?.takeIf { it > 0 }?.let {
            System.currentTimeMillis() + it * 1000L
        } ?: accessClaims?.get("exp")?.jsonPrimitive?.longOrNull?.times(1000L)
            ?: previous?.expiresAt
            ?: 0L

        return OpenAICodexCredentials(
            accessToken = accessToken,
            refreshToken = refreshToken ?: previous?.refreshToken
                ?: error("OpenAI Codex sign-in did not return a refresh token."),
            accountId = resolvedAccountId,
            expiresAt = expiresAt,
            email = idClaims?.get("email")?.jsonPrimitive?.contentOrNull
                ?: accessClaims?.get("email")?.jsonPrimitive?.contentOrNull
                ?: previous?.email,
            planType = authClaims?.get("chatgpt_plan_type")?.jsonPrimitive?.contentOrNull
                ?: previous?.planType,
        )
    }

    private fun decodeJwtPayload(token: String): kotlinx.serialization.json.JsonObject? {
        val payload = token.split('.').getOrNull(1) ?: return null
        return runCatching {
            val decoded = Base64.getUrlDecoder().decode(payload).decodeToString()
            json.parseToJsonElement(decoded).jsonObject
        }.getOrNull()
    }

    private fun OpenAICodexCredentials.needsRefresh(): Boolean =
        expiresAt > 0L && System.currentTimeMillis() >= expiresAt - TOKEN_REFRESH_LEEWAY_MS

    private suspend fun persistCredentials(
        providerId: Uuid,
        credentials: OpenAICodexCredentials?,
        activateSubscription: Boolean,
    ) {
        settingsStore.update { settings ->
            settings.copy(
                providers = settings.providers.map { provider ->
                    if (provider !is ProviderSetting.OpenAI || provider.id != providerId) {
                        provider
                    } else {
                        provider.copy(
                            authType = if (activateSubscription) {
                                OpenAIAuthType.CHATGPT_SUBSCRIPTION
                            } else {
                                provider.authType
                            },
                            codexCredentials = credentials,
                            baseUrl = if (activateSubscription) OPENAI_CODEX_BASE_URL else provider.baseUrl,
                            useResponseApi = if (activateSubscription) true else provider.useResponseApi,
                        )
                    }
                }
            )
        }
    }

    private fun HttpResult.isAuthorizationPending(): Boolean {
        if (code !in setOf(400, 403, 404, 409, 429)) return false
        val error = errorMessage().lowercase()
        return error.isBlank() ||
            error.contains("pending") ||
            error.contains("authorization") ||
            error.contains("not found") ||
            error.contains("slow_down")
    }

    private fun httpError(prefix: String, response: HttpResult): String {
        val detail = response.errorMessage().takeIf { it.isNotBlank() }
        return buildString {
            append(prefix)
            append(" (HTTP ${response.code})")
            if (detail != null) append(": $detail")
        }
    }

    private fun HttpResult.errorMessage(): String = runCatching {
        val obj = json.parseToJsonElement(body).jsonObject
        obj["error_description"]?.jsonPrimitive?.contentOrNull
            ?: obj["message"]?.jsonPrimitive?.contentOrNull
            ?: obj["error"]?.jsonPrimitive?.contentOrNull
            ?: ""
    }.getOrDefault("")

    private data class HttpResult(val code: Int, val body: String)
}
