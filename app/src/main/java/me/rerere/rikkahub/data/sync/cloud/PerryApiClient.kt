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

    suspend fun initFile(request: FileInitRequest): FileInitResponse {
        val body = json.encodeToString(request)
        return post("/v1/files/init", body = body, auth = true)
    }

    suspend fun completeFile(fileId: String, request: FileCompleteRequest): FileDto {
        val body = json.encodeToString(request)
        return post("/v1/files/$fileId/complete", body = body, auth = true)
    }

    suspend fun downloadUrl(fileId: String): FileDownloadUrlResponse {
        return get("/v1/files/$fileId/download-url", auth = true)
    }

    suspend fun putFileContent(fileId: String, bytes: ByteArray, mimeType: String) {
        val media = (mimeType.ifBlank { "application/octet-stream" }).toMediaType()
        val request = Request.Builder()
            .url(join(baseUrl, "/v1/files/$fileId/content"))
            .put(bytes.toRequestBody(media))
            .applyAuth(true)
            .build()
        val response = httpClient.newCall(request).await()
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val err = runCatching { json.decodeFromString<ErrorEnvelope>(raw) }.getOrNull()
            throw PerryApiException(
                code = err?.error?.code ?: "http_${response.code}",
                message = err?.error?.message ?: raw.ifBlank { "HTTP ${response.code}" },
                httpStatus = response.code,
            )
        }
    }

    suspend fun getFileContent(fileId: String): ByteArray {
        val request = Request.Builder()
            .url(join(baseUrl, "/v1/files/$fileId/content"))
            .get()
            .applyAuth(true)
            .build()
        val response = httpClient.newCall(request).await()
        val body = response.body
        if (!response.isSuccessful) {
            val raw = body?.string().orEmpty()
            val err = runCatching { json.decodeFromString<ErrorEnvelope>(raw) }.getOrNull()
            throw PerryApiException(
                code = err?.error?.code ?: "http_${response.code}",
                message = err?.error?.message ?: raw.ifBlank { "HTTP ${response.code}" },
                httpStatus = response.code,
            )
        }
        return body?.bytes() ?: ByteArray(0)
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
    val conversations: List<JsonElement> = emptyList(),
    @SerialName("conversation_folders") val conversationFolders: List<JsonElement> = emptyList(),
    @SerialName("assistant_memories") val assistantMemories: List<JsonElement> = emptyList(),
    val favorites: List<JsonElement> = emptyList(),
    val files: List<JsonElement> = emptyList(),
)

@Serializable
data class FileInitRequest(
    val id: String? = null,
    val folder: String = "upload",
    @SerialName("display_name") val displayName: String,
    @SerialName("mime_type") val mimeType: String = "application/octet-stream",
    @SerialName("size_bytes") val sizeBytes: Long,
    val sha256: String,
)

@Serializable
data class FileInitResponse(
    val id: String,
    @SerialName("upload_status") val uploadStatus: String,
    @SerialName("object_key") val objectKey: String,
    @SerialName("transfer_mode") val transferMode: String = "proxy",
    @SerialName("content_path") val contentPath: String? = null,
    @SerialName("upload_url") val uploadUrl: String? = null,
    val revision: Int,
    val deduplicated: Boolean = false,
)

@Serializable
data class FileCompleteRequest(
    @SerialName("size_bytes") val sizeBytes: Long? = null,
    val sha256: String? = null,
)

@Serializable
data class FileDto(
    val id: String,
    val folder: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
    val sha256: String? = null,
    @SerialName("object_key") val objectKey: String? = null,
    @SerialName("upload_status") val uploadStatus: String? = null,
    val revision: Int? = null,
)

@Serializable
data class FileDownloadUrlResponse(
    val id: String,
    @SerialName("download_url") val downloadUrl: String,
    @SerialName("content_path") val contentPath: String? = null,
    @SerialName("transfer_mode") val transferMode: String = "proxy",
    @SerialName("expires_in_seconds") val expiresInSeconds: Int = 0,
    @SerialName("upload_status") val uploadStatus: String? = null,
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
