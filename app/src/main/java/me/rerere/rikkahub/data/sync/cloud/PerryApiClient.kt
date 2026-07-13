package me.rerere.rikkahub.data.sync.cloud

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import me.rerere.common.http.await
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class PerryApiException(
    val code: String,
    message: String,
    val httpStatus: Int,
) : Exception(message)

class PerryApiClient(
    private val httpClient: OkHttpClient,
    private val baseUrl: String,
    private val deviceToken: String? = null,
) {
    private val json = JsonInstant
    private val mediaType = "application/json".toMediaType()

    private val probeClient: OkHttpClient = httpClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        // Avoid callTimeout: shared interceptors + LAN jitter can exceed a hard 5s budget.
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    suspend fun healthLive(): HealthLiveResponse {
        return get("/health/live", auth = false, client = probeClient)
    }

    suspend fun healthReady(): HealthReadyResponse {
        return get("/health/ready", auth = false, client = probeClient)
    }

    suspend fun registerDevice(name: String, bootstrapToken: String): DeviceRegisterResponse {
        val body = json.encodeToString(DeviceRegisterRequest(name = name))
        return post(
            path = "/v1/devices/register",
            body = body,
            auth = false,
            extraHeaders = mapOf("X-Bootstrap-Token" to bootstrapToken),
            client = probeClient,
        )
    }

    suspend fun serverInfo(): ServerInfoResponse {
        return get("/v1/server-info", auth = true, client = probeClient)
    }

    suspend fun bootstrap(): SyncBootstrapResponse {
        return get("/v1/sync/bootstrap", auth = true)
    }

    suspend fun changes(cursor: Long, limit: Int = 100): SyncChangesResponse {
        return get("/v1/sync/changes?cursor=$cursor&limit=$limit", auth = true)
    }

    suspend fun mutations(request: SyncMutationsRequest): SyncMutationsResponse {
        val body = json.encodeToString(request)
        return post("/v1/sync/mutations", body = body, auth = true)
    }

    private suspend inline fun <reified T> get(
        path: String,
        auth: Boolean,
        client: OkHttpClient = httpClient,
        extraHeaders: Map<String, String> = emptyMap(),
    ): T {
        val request = Request.Builder()
            .url(join(baseUrl, path))
            .get()
            .applyAuth(auth)
            .apply {
                extraHeaders.forEach { (k, v) -> header(k, v) }
            }
            .build()
        return execute(client, request)
    }

    private suspend inline fun <reified T> post(
        path: String,
        body: String,
        auth: Boolean,
        client: OkHttpClient = httpClient,
        extraHeaders: Map<String, String> = emptyMap(),
    ): T {
        val request = Request.Builder()
            .url(join(baseUrl, path))
            .post(body.toRequestBody(mediaType))
            .applyAuth(auth)
            .apply {
                extraHeaders.forEach { (k, v) -> header(k, v) }
            }
            .build()
        return execute(client, request)
    }

    private fun Request.Builder.applyAuth(auth: Boolean): Request.Builder {
        if (auth) {
            val token = deviceToken
            if (!token.isNullOrBlank()) {
                header("Authorization", "Bearer $token")
            }
        }
        return this
    }

    private suspend inline fun <reified T> execute(client: OkHttpClient, request: Request): T {
        val response = client.newCall(request).await()
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val err = runCatching { json.decodeFromString<ErrorEnvelope>(raw) }.getOrNull()
            throw PerryApiException(
                code = err?.error?.code ?: "http_${response.code}",
                message = err?.error?.message ?: raw.ifBlank { "HTTP ${response.code}" },
                httpStatus = response.code,
            )
        }
        if (raw.isBlank()) {
            throw PerryApiException("empty_body", "empty response body", response.code)
        }
        return json.decodeFromString(raw)
    }

    companion object {
        fun join(baseUrl: String, path: String): String {
            val base = baseUrl.trimEnd('/')
            val p = if (path.startsWith("/")) path else "/$path"
            return base + p
        }
    }
}

@Serializable
data class HealthLiveResponse(
    val status: String,
)

@Serializable
data class HealthReadyResponse(
    val status: String,
    val database: String? = null,
)

@Serializable
data class DeviceRegisterRequest(
    val name: String,
)

@Serializable
data class DeviceRegisterResponse(
    @SerialName("device_id") val deviceId: String,
    @SerialName("user_id") val userId: String,
    val name: String,
    @SerialName("device_token") val deviceToken: String,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class ComponentStatusDto(
    val status: String,
    val detail: String? = null,
)

@Serializable
data class ServerInfoResponse(
    @SerialName("api_version") val apiVersion: String,
    @SerialName("min_client_version") val minClientVersion: String,
    @SerialName("server_time") val serverTime: String,
    val features: Map<String, Boolean> = emptyMap(),
    val components: Map<String, ComponentStatusDto> = emptyMap(),
)

@Serializable
data class SyncBootstrapResponse(
    val cursor: Long,
    @SerialName("server_time") val serverTime: String,
    val settings: List<JsonElement> = emptyList(),
    val assistants: List<JsonElement> = emptyList(),
)

@Serializable
data class SyncChangeItem(
    val seq: Long,
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_id") val entityId: String,
    val operation: String,
    val revision: Long,
    @SerialName("changed_at") val changedAt: String,
    val payload: JsonElement? = null,
)

@Serializable
data class SyncChangesResponse(
    val changes: List<SyncChangeItem> = emptyList(),
    @SerialName("next_cursor") val nextCursor: Long,
    @SerialName("has_more") val hasMore: Boolean,
)

@Serializable
data class SyncMutationItem(
    @SerialName("mutation_id") val mutationId: String,
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_id") val entityId: String,
    val operation: String,
    @SerialName("base_revision") val baseRevision: Long,
    @SerialName("payload_schema_version") val payloadSchemaVersion: Int = 1,
    val payload: JsonElement? = null,
)

@Serializable
data class SyncMutationsRequest(
    @SerialName("device_id") val deviceId: String,
    val mutations: List<SyncMutationItem>,
)

@Serializable
data class SyncMutationResult(
    @SerialName("mutation_id") val mutationId: String,
    val status: String,
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_id") val entityId: String,
    val revision: Long? = null,
    @SerialName("server_payload") val serverPayload: JsonElement? = null,
    val message: String? = null,
)

@Serializable
data class SyncMutationsResponse(
    val results: List<SyncMutationResult> = emptyList(),
    val cursor: Long = 0,
)

@Serializable
data class ErrorEnvelope(
    val error: ErrorBody,
)

@Serializable
data class ErrorBody(
    val code: String,
    val message: String,
    @SerialName("request_id") val requestId: String? = null,
)
