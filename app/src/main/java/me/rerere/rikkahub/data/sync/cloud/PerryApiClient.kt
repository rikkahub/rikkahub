package me.rerere.rikkahub.data.sync.cloud

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import me.rerere.common.http.await
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSink
import android.util.Base64
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

    private val fileTransferClient: OkHttpClient = httpClient.newBuilder()
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
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

    suspend fun listMessageNodes(
        conversationId: String,
        beforeIndex: Int? = null,
        limit: Int = 200,
    ): MessageNodeListResponse {
        val q = buildString {
            append("/v1/conversations/$conversationId/nodes?limit=$limit")
            if (beforeIndex != null) append("&before_index=$beforeIndex")
        }
        return get(q, auth = true)
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

    suspend fun putFileContent(
        fileId: String,
        bytes: ByteArray,
        mimeType: String,
        onProgress: ((bytesSent: Long, bytesTotal: Long) -> Unit)? = null,
    ) {
        val media = (mimeType.ifBlank { "application/octet-stream" }).toMediaType()
        val body = if (onProgress != null) {
            ProgressRequestBody(bytes, media, onProgress)
        } else {
            bytes.toRequestBody(media)
        }
        val request = Request.Builder()
            .url(join(baseUrl, "/v1/files/$fileId/content"))
            .put(body)
            .applyAuth(true)
            .build()
        val response = httpClient.newCall(request).await()
        val raw = response.readBodyString()
        if (!response.isSuccessful) {
            val err = runCatching { json.decodeFromString<ErrorEnvelope>(raw) }.getOrNull()
            throw PerryApiException(
                code = err?.error?.code ?: "http_${response.code}",
                message = err?.error?.message ?: raw.ifBlank { "HTTP ${response.code}" },
                httpStatus = response.code,
            )
        }
        onProgress?.invoke(bytes.size.toLong(), bytes.size.toLong())
    }

    suspend fun downloadFileContent(
        fileId: String,
        sink: BufferedSink,
        onProgress: (bytesRead: Long, bytesTotal: Long) -> Unit,
    ): Long {
        val request = Request.Builder()
            .url(join(baseUrl, "/v1/files/$fileId/content"))
            .get()
            .applyAuth(true)
            .build()
        val call = fileTransferClient.newCall(request)
        val cancellationHandle = currentCoroutineContext().job.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }
        try {
            val response = call.await()
            if (!response.isSuccessful) {
                val raw = response.readBodyString()
                val err = runCatching { json.decodeFromString<ErrorEnvelope>(raw) }.getOrNull()
                throw PerryApiException(
                    code = err?.error?.code ?: "http_${response.code}",
                    message = err?.error?.message ?: raw.ifBlank { "HTTP ${response.code}" },
                    httpStatus = response.code,
                )
            }
            return withContext(Dispatchers.IO) {
                response.use {
                    val body = response.body
                        ?: throw PerryApiException("empty_body", "empty response body", response.code)
                    val total = body.contentLength()
                    onProgress(0L, total)
                    val source = body.source()
                    val buffer = Buffer()
                    var copied = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = source.read(buffer, DOWNLOAD_CHUNK_BYTES)
                        if (read == -1L) break
                        sink.write(buffer, read)
                        copied += read
                        onProgress(copied, total)
                    }
                    sink.flush()
                    copied
                }
            }
        } finally {
            cancellationHandle.dispose()
        }
    }

    suspend fun listWorkspaces(): WorkspaceListResponse =
        get("/v1/workspaces", auth = true)

    suspend fun getWorkspace(workspaceId: String): CloudWorkspaceDto =
        get("/v1/workspaces/$workspaceId", auth = true)

    suspend fun createWorkspace(request: WorkspaceCreateRequest): CloudWorkspaceDto =
        post(
            "/v1/workspaces",
            body = json.encodeToString(request),
            auth = true,
        )

    suspend fun updateWorkspace(
        workspaceId: String,
        request: WorkspaceUpdateRequest,
    ): CloudWorkspaceDto {
        val body = json.encodeToString(request).toRequestBody(mediaType)
        val httpRequest = Request.Builder()
            .url(join(baseUrl, "/v1/workspaces/$workspaceId"))
            .patch(body)
            .applyAuth(true)
            .build()
        return execute(httpClient, httpRequest)
    }

    suspend fun deleteWorkspace(workspaceId: String) {
        val request = Request.Builder()
            .url(join(baseUrl, "/v1/workspaces/$workspaceId"))
            .delete()
            .applyAuth(true)
            .build()
        executeNoContent(httpClient, request)
    }

    suspend fun executeWorkspaceCommand(
        workspaceId: String,
        request: WorkspaceExecuteRequest,
    ): WorkspaceCommandResultDto = post(
        "/v1/workspaces/$workspaceId/execute",
        body = json.encodeToString(request),
        auth = true,
    )

    suspend fun listWorkspaceFiles(workspaceId: String, path: String): WorkspaceFileListResponse =
        get(workspaceUrl(workspaceId, "/files", path), auth = true)

    suspend fun statWorkspaceFile(workspaceId: String, path: String): WorkspaceFileEntryDto =
        get(workspaceUrl(workspaceId, "/files/stat", path), auth = true)

    suspend fun getWorkspaceFileContent(workspaceId: String, path: String): ByteArray {
        val request = Request.Builder()
            .url(workspaceUrl(workspaceId, "/files/content", path))
            .get()
            .applyAuth(true)
            .build()
        val response = httpClient.newCall(request).await()
        if (!response.isSuccessful) {
            val raw = response.readBodyString()
            throw decodeError(response.code, raw)
        }
        return response.readBodyBytes()
    }

    suspend fun putWorkspaceFileContent(
        workspaceId: String,
        path: String,
        bytes: ByteArray,
        overwrite: Boolean,
    ): WorkspaceFileEntryDto {
        val url = workspaceUrl(workspaceId, "/files/content", path)
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("overwrite", overwrite.toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .put(bytes.toRequestBody("application/octet-stream".toMediaType()))
            .applyAuth(true)
            .build()
        return execute(httpClient, request)
    }

    suspend fun moveWorkspaceFile(
        workspaceId: String,
        request: WorkspaceMoveRequest,
    ): WorkspaceFileEntryDto = post(
        "/v1/workspaces/$workspaceId/files/move",
        body = json.encodeToString(request),
        auth = true,
    )

    suspend fun deleteWorkspaceFile(
        workspaceId: String,
        path: String,
        recursive: Boolean,
    ): WorkspaceStatusResponse {
        val url = workspaceUrl(workspaceId, "/files", path)
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("recursive", recursive.toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .delete()
            .applyAuth(true)
            .build()
        return execute(httpClient, request)
    }

    private fun workspaceUrl(workspaceId: String, suffix: String, path: String): String =
        join(baseUrl, "/v1/workspaces/$workspaceId$suffix")
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("path", path)
            .build()
            .toString()

    /** Phase 8: Monel catalog via Perry (no Monel auth key on device). */
    suspend fun catalogProviders(): List<CatalogProviderDto> {
        return get("/v1/catalog/providers", auth = true)
    }

    suspend fun catalogModels(): List<CatalogModelDto> {
        return get("/v1/catalog/models", auth = true)
    }

    suspend fun catalogModelsByProvider(): List<CatalogProviderModelsDto> {
        return get("/v1/catalog/models/by-provider", auth = true)
    }

    suspend fun catalogModelsForProvider(providerId: String): CatalogProviderModelsDto {
        return get("/v1/catalog/models/by-provider/$providerId", auth = true)
    }

    /**
     * Base URL for OpenAI-compatible SDK against a Monel provider, e.g.
     * `{perry}/v1/ai/{providerId}/v1` with device token as apiKey.
     */
    fun aiProviderBaseUrl(providerId: String): String {
        return join(baseUrl, "/v1/ai/$providerId/v1")
    }

    private suspend inline fun <reified T> get(
        path: String,
        auth: Boolean,
        client: OkHttpClient = httpClient,
        extraHeaders: Map<String, String> = emptyMap(),
    ): T {
        val request = Request.Builder()
            .url(resolve(baseUrl, path))
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
            .url(resolve(baseUrl, path))
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
        val raw = response.readBodyString()
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

    private suspend fun executeNoContent(client: OkHttpClient, request: Request) {
        val response = client.newCall(request).await()
        val raw = response.readBodyString()
        if (!response.isSuccessful) throw decodeError(response.code, raw)
    }

    private fun decodeError(status: Int, raw: String): PerryApiException {
        val err = runCatching { json.decodeFromString<ErrorEnvelope>(raw) }.getOrNull()
        return PerryApiException(
            code = err?.error?.code ?: "http_$status",
            message = err?.error?.message ?: raw.ifBlank { "HTTP $status" },
            httpStatus = status,
        )
    }

    private suspend fun okhttp3.Response.readBodyString(): String =
        withContext(Dispatchers.IO) { body?.string().orEmpty() }

    private suspend fun okhttp3.Response.readBodyBytes(): ByteArray =
        withContext(Dispatchers.IO) { body?.bytes() ?: ByteArray(0) }

    companion object {
        private const val DOWNLOAD_CHUNK_BYTES = 64L * 1024L

        fun resolve(baseUrl: String, path: String): String =
            if (path.startsWith("http://") || path.startsWith("https://")) {
                path
            } else {
                join(baseUrl, path)
            }

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
data class CloudWorkspaceDto(
    val id: String,
    val name: String,
    val image: String,
    @SerialName("shell_status") val shellStatus: String,
    @SerialName("tool_approvals") val toolApprovals: Map<String, Boolean> = emptyMap(),
    @SerialName("created_at_ms") val createdAtMs: Long,
    @SerialName("updated_at_ms") val updatedAtMs: Long,
    @SerialName("last_access_at_ms") val lastAccessAtMs: Long? = null,
)

@Serializable
data class WorkspaceListResponse(val items: List<CloudWorkspaceDto> = emptyList())

@Serializable
data class WorkspaceCreateRequest(
    val id: String,
    val name: String,
    val image: String? = null,
    @SerialName("tool_approvals") val toolApprovals: Map<String, Boolean> = emptyMap(),
)

@Serializable
data class WorkspaceUpdateRequest(
    val name: String? = null,
    @SerialName("tool_approvals") val toolApprovals: Map<String, Boolean>? = null,
)

@Serializable
data class WorkspaceExecuteRequest(
    val command: String,
    val cwd: String = "",
    @SerialName("timeout_ms") val timeoutMs: Long,
    @SerialName("stdin_base64") val stdinBase64: String? = null,
) {
    companion object {
        fun create(command: String, cwd: String, timeoutMs: Long, stdin: ByteArray?) =
            WorkspaceExecuteRequest(
                command = command,
                cwd = cwd,
                timeoutMs = timeoutMs,
                stdinBase64 = stdin?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
            )
    }
}

@Serializable
data class WorkspaceCommandResultDto(
    @SerialName("exit_code") val exitCode: Int,
    val stdout: String,
    val stderr: String,
    @SerialName("timed_out") val timedOut: Boolean = false,
    val truncated: Boolean = false,
)

@Serializable
data class WorkspaceFileEntryDto(
    val path: String,
    val name: String,
    @SerialName("is_directory") val isDirectory: Boolean,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("updated_at_ms") val updatedAtMs: Long,
)

@Serializable
data class WorkspaceFileListResponse(val items: List<WorkspaceFileEntryDto> = emptyList())

@Serializable
data class WorkspaceMoveRequest(
    val source: String,
    val target: String,
    val overwrite: Boolean = false,
)

@Serializable
data class WorkspaceStatusResponse(val status: String)

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
data class MessageNodeDto(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("node_index") val nodeIndex: Int = 0,
    @SerialName("select_index") val selectIndex: Int = 0,
    val messages: JsonElement? = null,
    val revision: Long = 0,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
data class MessageNodeListResponse(
    val items: List<MessageNodeDto> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("oldest_index") val oldestIndex: Int? = null,
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

private class ProgressRequestBody(
    private val bytes: ByteArray,
    private val contentType: MediaType,
    private val onProgress: (bytesSent: Long, bytesTotal: Long) -> Unit,
) : RequestBody() {
    override fun contentType(): MediaType = contentType
    override fun contentLength(): Long = bytes.size.toLong()

    override fun writeTo(sink: BufferedSink) {
        val total = bytes.size.toLong()
        var offset = 0
        val chunk = 64 * 1024
        onProgress(0L, total)
        while (offset < bytes.size) {
            val end = minOf(offset + chunk, bytes.size)
            sink.write(bytes, offset, end - offset)
            offset = end
            onProgress(offset.toLong(), total)
        }
    }
}
