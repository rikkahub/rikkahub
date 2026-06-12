package me.rerere.ai.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.ClaudeAuthType
import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ClaudeReasoningMetadata
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.AiLog
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.STREAM_MAX_RETRIES
import me.rerere.ai.util.StreamRetryController
import me.rerere.ai.util.bufferStreamChunks
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.MediaTooLargeException
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.isRetryableStreamFailure
import me.rerere.ai.util.jitteredBackoffMillis
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.parseErrorDetail
import me.rerere.ai.util.parseHttpErrorBody
import me.rerere.ai.util.retryAfterMillisFromHeaders
import me.rerere.ai.util.stringSafe
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
import me.rerere.common.http.jsonPrimitiveOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "ClaudeProvider"
private const val ANTHROPIC_VERSION = "2023-06-01"

// OAuth (Claude Code) constants — pinned to Claude Code 2.1.x; Anthropic rotates these.
private const val CLAUDE_OAUTH_BETAS =
    "claude-code-20250219,oauth-2025-04-20,interleaved-thinking-2025-05-14,context-management-2025-06-27,prompt-caching-scope-2026-01-05,structured-outputs-2025-12-15,fast-mode-2026-02-01,redact-thinking-2026-02-12,token-efficient-tools-2026-03-28"
private const val CLAUDE_OAUTH_CONTEXT_1M_BETA = "context-1m-2025-08-07"
private const val CLAUDE_CODE_USER_AGENT = "ClaudeCode/2.1.128"
private const val CLAUDE_FP_BILLING =
    "x-anthropic-billing-header: cc_version=2.1.126.88c; cc_entrypoint=cli; cch=00000;"
private const val CLAUDE_FP_IDENTITY = "You are Claude Code, Anthropic's official CLI for Claude."

class ClaudeProvider(
    private val client: OkHttpClient,
    context: Context? = null,
    // SSE 使用带有短 readTimeout 的专用客户端，使冻结的后台 socket 在 ~120s（而非共享客户端的 10 分钟）
    // 内触发 SocketTimeoutException -> onFailure -> close(exception) -> flow 错误 -> 上层 sanitize/finalize。
    private val streamClient: OkHttpClient = client,
) : Provider<ProviderSetting.Claude> {
    private val keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()

    // OAuth token is used verbatim (not via KeyRoulette, which splits on whitespace/commas).
    private fun Request.Builder.applyClaudeAuth(p: ProviderSetting.Claude): Request.Builder = when (p.authType) {
        ClaudeAuthType.ApiKey -> this
            .addHeader("x-api-key", keyRoulette.next(p.apiKey, p.id.toString()))
            .addHeader("anthropic-version", ANTHROPIC_VERSION)

        ClaudeAuthType.OAuth -> this
            .addHeader("Authorization", "Bearer ${p.oauthToken}")
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader(
                "anthropic-beta",
                if (p.oauthContext1M) "$CLAUDE_OAUTH_BETAS,$CLAUDE_OAUTH_CONTEXT_1M_BETA" else CLAUDE_OAUTH_BETAS
            )
            .header("User-Agent", CLAUDE_CODE_USER_AGENT)
    }

    override suspend fun listModels(providerSetting: ProviderSetting.Claude): List<Model> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${providerSetting.baseUrl}/models")
                .applyClaudeAuth(providerSetting)
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                error("Failed to get models: ${response.code} ${response.body.string()}")
            }

            val bodyStr = response.body.string()
            val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
            val data = bodyJson["data"]?.jsonArray ?: return@withContext emptyList()

            data.mapNotNull { modelJson ->
                val modelObj = modelJson.jsonObject
                val id = modelObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val displayName = modelObj["display_name"]?.jsonPrimitive?.contentOrNull ?: id

                Model(
                    modelId = id,
                    displayName = displayName,
                )
            }
        }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams
    ): Flow<ImageGenerationItem> {
        error("Claude provider does not support image generation")
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): MessageChunk = withContext(Dispatchers.IO) {
        val requestBody = buildMessageRequest(providerSetting, messages, params)
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/messages")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .applyClaudeAuth(providerSetting)
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        AiLog.request(TAG, "claude", params.model.modelId, false)

        val response = client.newCall(request).await(params.callTimeoutMillis?.milliseconds)
        if (!response.isSuccessful) {
            throw parseHttpErrorBody(response.code, response.body.string())
        }

        val bodyStr = response.body.string()
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject

        // 从 JsonObject 中提取必要的信息
        val id = bodyJson["id"]?.jsonPrimitive?.contentOrNull ?: ""
        val model = bodyJson["model"]?.jsonPrimitive?.contentOrNull ?: ""
        val content = bodyJson["content"]?.jsonArray ?: JsonArray(emptyList())
        val stopReason = bodyJson["stop_reason"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val usage = parseTokenUsage(bodyJson)

        MessageChunk(
            id = id,
            model = model,
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = null,
                    // Non-streaming response: every tool_use block carries its
                    // complete input inline, so each parsed Tool is finished.
                    message = parseMessage(content).also { msg ->
                        msg.parts.forEach { if (it is UIMessagePart.Tool) it.finished = true }
                    },
                    finishReason = stopReason
                )
            ),
            usage = usage
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<MessageChunk> = callbackFlow {
        val requestBody = buildMessageRequest(providerSetting, messages, params, stream = true)
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/messages")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .applyClaudeAuth(providerSetting)
            .addHeader("Content-Type", "application/json")
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        AiLog.request(TAG, "claude", params.model.modelId, true)

        // Tracks which content-block indices are tool_use blocks so that
        // content_block_stop can mark only those tools finished.
        val toolBlockIndices = mutableSetOf<Int>()

        val factory = EventSources.createFactory(streamClient)
        lateinit var listener: EventSourceListener

        // All pre-first-frame retry state lives in the controller behind one lock, so the okhttp
        // dispatcher threads, the retry coroutine, and awaitClose cannot race on it (visibility +
        // leak-on-cancel). Once the first SSE event lands the gate disarms permanently — retrying
        // then would duplicate already-delivered (interleaved-thinking) content. See
        // StreamRetryPolicy / StreamRetryController.
        val controller = StreamRetryController(STREAM_MAX_RETRIES) {
            StreamRetryController.Cancellable(factory.newEventSource(request, listener)::cancel)
        }

        fun scheduleRetry(attempt: Int, backoffMillis: Long) {
            Log.i(TAG, "transient pre-first-frame failure, retrying (attempt $attempt/$STREAM_MAX_RETRIES)")
            launch {
                delay(backoffMillis)
                controller.reopen()
            }
        }

        listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                // Any frame — including the message_stop terminal sentinel and an in-band error
                // frame — disarms the retry gate before the close() paths below. Otherwise a
                // post-terminal cancellation could be misread as a pre-first-frame transient and
                // replay an already-completed request.
                controller.onFrame()
                AiLog.event(TAG, type, id)
                if (data == "[DONE]") {
                    return
                }

                val dataJson = json.parseToJsonElement(data).jsonObject
                // Anthropic content-block index lives at the top level of the
                // SSE frame (content_block_start / content_block_delta). It is
                // the protocol key that disambiguates parallel tool calls, so
                // attach it to any Tool delta parts for the streaming merge.
                val blockIndex = dataJson["index"]?.jsonPrimitive?.intOrNull

                // content_block_start opening a tool_use registers the block
                // index as a tool block; content_block_stop on that index marks
                // the open tool finished (its input is now complete). Emitted as
                // a finished Tool delta carrying only the streamIndex so the merge
                // flips the flag on the matching open tool via sticky-OR.
                if (type == "content_block_start" && blockIndex != null &&
                    dataJson["content_block"]?.jsonObject?.get("type")
                        ?.jsonPrimitive?.contentOrNull == "tool_use"
                ) {
                    toolBlockIndices.add(blockIndex)
                }
                if (type == "content_block_stop" && blockIndex != null &&
                    blockIndex in toolBlockIndices
                ) {
                    trySend(
                        MessageChunk(
                            id = id ?: "",
                            model = "",
                            choices = listOf(
                                UIMessageChoice(
                                    index = 0,
                                    delta = UIMessage(
                                        role = MessageRole.ASSISTANT,
                                        parts = listOf(
                                            UIMessagePart.Tool(
                                                toolCallId = "",
                                                toolName = "",
                                                input = "",
                                                output = emptyList()
                                            ).also {
                                                it.streamIndex = blockIndex
                                                it.finished = true
                                            }
                                        )
                                    ),
                                    message = null,
                                    finishReason = null
                                )
                            )
                        )
                    )
                    return
                }

                val deltaMessage = parseMessage(buildJsonArray {
                    val contentBlockObj = dataJson["content_block"]?.jsonObject
                    val deltaObj = dataJson["delta"]?.jsonObject
                    if (contentBlockObj != null) {
                        add(contentBlockObj)
                    }
                    if (deltaObj != null) {
                        add(deltaObj)
                    }
                }).let { msg ->
                    if (blockIndex == null) msg
                    else msg.copy(
                        parts = msg.parts.map { part ->
                            if (part is UIMessagePart.Tool) part.also { it.streamIndex = blockIndex }
                            else part
                        }
                    )
                }
                val tokenUsage = parseTokenUsage(dataJson)
                val messageChunk = MessageChunk(
                    id = id ?: "",
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = deltaMessage,
                            message = null,
                            finishReason = null
                        )
                    ),
                    usage = tokenUsage
                )

                // trySend is reachable ONLY through the non-terminal branch's binding —
                // a terminal frame returns before producing the chunk to send, so the emit
                // is structurally unreachable after close(), not merely guarded by a return.
                val chunkToSend = when (claudeStreamFrameTerminal(type)) {
                    ClaudeStreamTerminal.MessageStop -> {
                        Log.d(TAG, "Stream ended")
                        close()
                        return
                    }

                    ClaudeStreamTerminal.Error -> {
                        close(resolveStreamError(dataJson))
                        return
                    }

                    null -> messageChunk
                }

                trySend(chunkToSend)
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val httpCode = response?.code
                val retryAfterMs = retryAfterMillisFromHeaders(
                    response?.header("retry-after-ms"),
                    response?.header("Retry-After")
                )
                val outcome = controller.onFailure(
                    transient = isRetryableStreamFailure(t, httpCode),
                    backoffFor = { attempt -> jitteredBackoffMillis(attempt - 1, retryAfterMs) },
                    error = t,
                )
                if (outcome is StreamRetryController.Outcome.Retry) {
                    scheduleRetry(outcome.attempt, outcome.backoffMillis)
                    return
                }

                var exception = t

                AiLog.failure(TAG, t, response?.code)

                val bodyRaw = response?.body?.stringSafe()
                try {
                    if (!bodyRaw.isNullOrBlank()) {
                        val bodyElement = Json.parseToJsonElement(bodyRaw)
                        exception = bodyElement.parseErrorDetail()
                    }
                } catch (e: Throwable) {
                    AiLog.parseFailure(TAG, e)
                } finally {
                    close(exception)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                // A clean EOF before the first frame is still a pre-first-frame death: retry it if
                // budget remains, otherwise complete normally.
                val outcome = controller.onClosed(
                    backoffFor = { attempt -> jitteredBackoffMillis(attempt - 1, null) },
                )
                if (outcome is StreamRetryController.Outcome.Retry) {
                    scheduleRetry(outcome.attempt, outcome.backoffMillis)
                } else {
                    close()
                }
            }
        }

        controller.start()

        awaitClose {
            Log.d(TAG, "Closing eventSource")
            controller.close()
        }
    }.bufferStreamChunks()

    private fun buildMessageRequest(
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean = false
    ): JsonObject {
        return buildJsonObject {
            put("model", params.model.modelId)
            put(
                "messages",
                buildMessages(messages, providerSetting.promptCaching, providerSetting.promptCacheTtl)
            )
            put("max_tokens", params.maxTokens ?: 64_000)

            // 顶层 cache_control: 让 Anthropic 自动管理缓存断点
            if (providerSetting.promptCaching) {
                put("cache_control", cacheControlEphemeral(providerSetting.promptCacheTtl))
            }

            if (params.temperature != null && !params.reasoningLevel.isEnabled) put(
                "temperature",
                params.temperature
            )
            if (params.topP != null) put("top_p", params.topP)

            put("stream", stream)

            // system prompt
            val systemMessage = messages.firstOrNull { it.role == MessageRole.SYSTEM }
            val callerSystemTexts = systemMessage?.parts
                ?.filterIsInstance<UIMessagePart.Text>()
                ?.map { it.text }
                .orEmpty()
            // OAuth gate: the two fingerprint blocks must be the first two system blocks.
            // Strip any pre-existing copies first so repeated builds don't stack them.
            val systemTexts = if (providerSetting.authType == ClaudeAuthType.OAuth) {
                listOf(CLAUDE_FP_BILLING, CLAUDE_FP_IDENTITY) +
                    callerSystemTexts.filter { it != CLAUDE_FP_BILLING && it != CLAUDE_FP_IDENTITY }
            } else {
                callerSystemTexts
            }
            if (systemTexts.isNotEmpty()) {
                put("system", buildJsonArray {
                    systemTexts.forEachIndexed { index, text ->
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", text)
                            if (providerSetting.promptCaching && index == systemTexts.lastIndex) {
                                put("cache_control", cacheControlEphemeral(providerSetting.promptCacheTtl))
                            }
                        })
                    }
                })
            }

            // 处理 thinking
            // Anthropic 新 API: adaptive 模式 + output_config.effort 控制强度
            // 旧的 type=enabled + budget_tokens 在 Opus 4.7+ 上已不支持
            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                when (params.reasoningLevel) {
                    ReasoningLevel.OFF -> {
                        put("thinking", buildJsonObject { put("type", "disabled") })
                    }

                    ReasoningLevel.AUTO -> {
                        put("thinking", buildJsonObject {
                            put("type", "adaptive")
                            put("display", "summarized")
                        })
                    }

                    else -> {
                        put("thinking", buildJsonObject {
                            put("type", "adaptive")
                            put("display", "summarized")
                        })
                        put("output_config", buildJsonObject {
                            put("effort", params.reasoningLevel.effort)
                        })
                    }
                }
            }

            // 处理工具
            if (params.model.abilities.contains(ModelAbility.TOOL) && params.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    params.tools.forEachIndexed { index, tool ->
                        add(buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("input_schema", json.encodeToJsonElement(tool.parameters()))
                            if (providerSetting.promptCaching && index == params.tools.lastIndex) {
                                put("cache_control", cacheControlEphemeral(providerSetting.promptCacheTtl))
                            }
                        })
                    }
                }
            }
        }.mergeCustomBody(params.customBody).let { merged ->
            // mergeCustomBody runs last and replaces the `system` array wholesale
            // when a custom body provides its own `system` (it only deep-merges
            // JsonObjects, not arrays). In OAuth mode that drops the two mandatory
            // fingerprint blocks and breaks the gate, so re-assert the invariant here.
            // Only when the custom body actually overrode `system`: otherwise the
            // original assembly above is untouched (and already cache_control-correct),
            // and re-running it through textSystemBlock() would silently drop the
            // prompt-cache breakpoint placed on the last system block.
            val overrodeSystem = params.customBody.any { it.key.isNotBlank() && it.key == "system" }
            if (providerSetting.authType == ClaudeAuthType.OAuth && overrodeSystem) {
                ensureOAuthFingerprints(merged)
            } else {
                merged
            }
        }
    }

    // OAuth requires the two fingerprint blocks to be the first two system blocks.
    // Strip any existing copies (anywhere in the array) and prepend them, preserving
    // the rest of the merged system content. If `system` is absent or not the expected
    // array-of-text-blocks shape, conservatively replace it with just the fingerprints
    // rather than risk shipping a request that fails the gate.
    private fun ensureOAuthFingerprints(request: JsonObject): JsonObject {
        val fingerprints = buildJsonArray {
            add(textSystemBlock(CLAUDE_FP_BILLING))
            add(textSystemBlock(CLAUDE_FP_IDENTITY))
        }
        val existing = request["system"] as? JsonArray
        val rest = existing
            ?.filter { block ->
                val text = (block as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
                text != CLAUDE_FP_BILLING && text != CLAUDE_FP_IDENTITY
            }
            .orEmpty()
        val normalizedSystem = buildJsonArray {
            fingerprints.forEach { add(it) }
            rest.forEach { add(it) }
        }
        return JsonObject(request + ("system" to normalizedSystem))
    }

    private fun textSystemBlock(text: String) = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    private fun cacheControlEphemeral(promptCacheTtl: ClaudePromptCacheTtl) = buildJsonObject {
        put("type", "ephemeral")
        promptCacheTtl.apiValue?.let { put("ttl", it) }
    }

    // An `event: error` frame must always terminate the stream with a non-null Throwable;
    // close(null) completes the callbackFlow as a success and silently swallows the error.
    // Prefer the nested "error" object (preserves the well-formed message), otherwise parse
    // the whole frame — parseErrorDetail is total and never returns null.
    private fun resolveStreamError(frame: JsonObject): HttpException {
        return (frame["error"] ?: frame).parseErrorDetail()
    }

    private fun buildMessages(
        messages: List<UIMessage>,
        promptCaching: Boolean,
        promptCacheTtl: ClaudePromptCacheTtl
    ) = buildJsonArray {
        messages
            .filter { it.isValidToUpload() && it.role != MessageRole.SYSTEM }
            .forEach { message ->
                if (message.role == MessageRole.ASSISTANT) {
                    addAssistantMessage(message)
                } else {
                    addUserMessage(message)
                }
            }
    }.let { messagesArray ->
        if (!promptCaching) return@let messagesArray
        insertMessagesCacheControl(messagesArray, promptCacheTtl)
    }

    /**
     * 在倒数第二条非 tool_result 的 user message 的最后一个 content block 上插入 cache_control
     */
    private fun insertMessagesCacheControl(
        messages: JsonArray,
        promptCacheTtl: ClaudePromptCacheTtl
    ): JsonArray {
        // 找出所有非 tool_result 的 user message 的索引
        val realUserIndices = messages.mapIndexedNotNull { index, msg ->
            val obj = msg.jsonObject
            if (obj["role"]?.jsonPrimitive?.contentOrNull == "user") {
                val content = obj["content"]?.jsonArray
                val isToolResult = content?.any {
                    it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "tool_result"
                } == true
                if (!isToolResult) index else null
            } else null
        }

        // 取倒数第二条
        val targetIndex = if (realUserIndices.size >= 2) {
            realUserIndices[realUserIndices.size - 2]
        } else return messages

        // 在目标 message 的最后一个 content block 上添加 cache_control
        return JsonArray(messages.mapIndexed { index, msg ->
            if (index == targetIndex) {
                val obj = msg.jsonObject
                val content = obj["content"]?.jsonArray ?: return@mapIndexed msg
                val newContent = JsonArray(content.mapIndexed { contentIndex, block ->
                    if (contentIndex == content.lastIndex) {
                        JsonObject(
                            block.jsonObject + mapOf("cache_control" to cacheControlEphemeral(promptCacheTtl))
                        )
                    } else block
                })
                JsonObject(obj + mapOf("content" to newContent))
            } else msg
        })
    }

    private fun JsonArrayBuilder.addAssistantMessage(message: UIMessage) {
        val groups = groupPartsByToolBoundary(message.parts)
        val contentBuffer = mutableListOf<JsonObject>()

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    group.parts.mapNotNull { it.toContentBlock() }.forEach { contentBuffer.add(it) }
                }

                is PartGroup.Tools -> {
                    // 添加 tool_use 到内容缓冲
                    group.tools.forEach { contentBuffer.add(it.toToolUseBlock()) }

                    // 输出 assistant 消息
                    add(buildJsonObject {
                        put("role", "assistant")
                        putJsonArray("content") { contentBuffer.forEach { add(it) } }
                    })
                    contentBuffer.clear()

                    // 紧跟 tool_result
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            group.tools.forEach { add(it.toToolResultBlock()) }
                        }
                    })
                }
            }
        }

        // 输出剩余内容
        if (contentBuffer.isNotEmpty()) {
            add(buildJsonObject {
                put("role", "assistant")
                putJsonArray("content") { contentBuffer.forEach { add(it) } }
            })
        }
    }

    private fun JsonArrayBuilder.addUserMessage(message: UIMessage) {
        add(buildJsonObject {
            put("role", message.role.name.lowercase())
            putJsonArray("content") {
                message.parts.mapNotNull { it.toContentBlock() }.forEach { add(it) }
            }
        })
    }

    private fun UIMessagePart.toContentBlock(): JsonObject? = when (this) {
        is UIMessagePart.Text -> buildJsonObject {
            put("type", "text")
            put("text", text)
        }

        is UIMessagePart.Image -> buildJsonObject {
            encodeBase64(withPrefix = false).onSuccess { encoded ->
                put("type", "image")
                put("source", buildJsonObject {
                    put("type", "base64")
                    put("media_type", encoded.mimeType)
                    put("data", encoded.base64)
                })
            }.onFailure {
                // Oversized media is a user-facing rejection, not a benign encode failure:
                // surface it instead of degrading to empty text.
                if (it is MediaTooLargeException) throw it
                // The throwable message and `url` can carry raw base64/file data (issue #96):
                // log only the failure type, never the payload.
                AiLog.failure(TAG, it, null)
                put("type", "text")
                put("text", "")
            }
        }

        is UIMessagePart.Reasoning -> reasoningContentBlock(reasoning, metadata)

        else -> null
    }

    private fun UIMessagePart.Tool.toToolUseBlock() = buildJsonObject {
        put("type", "tool_use")
        put("id", toolCallId)
        put("name", toolName)
        put("input", inputAsJson())
    }

    private fun UIMessagePart.Tool.toToolResultBlock() = buildJsonObject {
        put("type", "tool_result")
        put("tool_use_id", toolCallId)
        putJsonArray("content") {
            output.mapNotNull { it.toContentBlock() }.forEach { add(it) }
        }
    }

    private fun parseMessage(content: JsonArray): UIMessage {
        val parts = mutableListOf<UIMessagePart>()

        content.forEach { contentBlock ->
            val block = contentBlock.jsonObject
            val type = block["type"]?.jsonPrimitive?.contentOrNull

            when (type) {
                "text", "text_delta" -> {
                    val text = block["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (text.isNotEmpty()) {
                        parts.add(UIMessagePart.Text(text))
                    }
                }

                "thinking", "thinking_delta", "signature_delta" -> {
                    val thinking = block["thinking"]?.jsonPrimitive?.contentOrNull ?: ""
                    val signature = block["signature"]?.jsonPrimitive?.contentOrNull
                    if (thinking.isNotEmpty() || signature != null) {
                        val reasoning = UIMessagePart.Reasoning(
                            reasoning = thinking,
                            createdAt = Clock.System.now(),
                            finishedAt = null
                        )
                        if (signature != null) {
                            reasoning.metadata = ClaudeReasoningMetadata(signature = signature).toMetadata()
                        }
                        parts.add(reasoning)
                    }
                }

                "redacted_thinking" -> {
                    // Encrypted reasoning is not surfaced; never log it.
                }

                "tool_use" -> {
                    val id = block["id"]?.jsonPrimitive?.contentOrNull ?: ""
                    val name = block["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val input = block["input"]?.jsonObject ?: JsonObject(emptyMap())
                    parts.add(
                        UIMessagePart.Tool(
                            toolCallId = id,
                            toolName = name,
                            input = if (input.isEmpty()) "" else json.encodeToString(input),
                            output = emptyList()
                        )
                    )
                }

                "input_json_delta" -> {
                    val input = block["partial_json"]?.jsonPrimitive?.contentOrNull
                    parts.add(
                        UIMessagePart.Tool(
                            toolCallId = "",
                            toolName = "",
                            input = input ?: "",
                            output = emptyList()
                        )
                    )
                }
            }
        }

        return UIMessage(
            role = MessageRole.ASSISTANT,
            parts = parts
        )
    }

    private fun parseTokenUsage(bodyJson: JsonObject?): TokenUsage? {
        if (bodyJson == null) return null

        // 回退到标准 usage 字段
        val usageJson = bodyJson["usage"]?.jsonObject
            ?: bodyJson["message"]?.jsonObject?.get("usage")?.jsonObject
            ?: return null
        val inputTokens = usageJson["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val cachedInputTokens = usageJson["cache_read_input_tokens"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val cachedCreationTokens = usageJson["cache_creation_input_tokens"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val completionTokens = usageJson["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val promptTokens = inputTokens + cachedInputTokens + cachedCreationTokens
        return TokenUsage(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = promptTokens + completionTokens,
            cachedTokens = cachedInputTokens,
        )
    }
}

/**
 * Whether an Anthropic SSE frame `type` terminates the stream, and how.
 *
 * Terminal frames go through `close(error?); return` in onEvent; once classified terminal, the
 * trySend path is skipped — so no chunk is constructed/sent after close() (issue #240). Pure (no
 * network, no android.util.Log) so the no-emit-after-close invariant is unit-testable in isolation.
 */
internal enum class ClaudeStreamTerminal { MessageStop, Error }

/** Classify an Anthropic stream frame `type` into its terminal outcome, or `null` if non-terminal. */
internal fun claudeStreamFrameTerminal(type: String?): ClaudeStreamTerminal? = when (type) {
    "message_stop" -> ClaudeStreamTerminal.MessageStop
    "error" -> ClaudeStreamTerminal.Error
    else -> null
}

// Serialize a UIMessagePart.Reasoning into an Anthropic `thinking` content block — or
// drop it (null) when it carries no signature.
//
// Anthropic requires a cryptographic `signature` on every `thinking` block replayed in the
// message history. Reasoning produced by another provider (OpenAI Responses, DeepSeek,
// <think> tags) has no Anthropic signature, so replaying it as a thinking block makes the
// API reject the WHOLE request with 400 "messages.N.content.M.thinking.signature: Field
// required" — reproducibly hit when switching OpenAI -> Claude mid-conversation. Switching
// Claude -> OpenAI is fine because the OpenAI side does not require an Anthropic signature.
// We omit an unsigned reasoning part (callers use mapNotNull, so null is dropped) rather
// than send an invalid block; a signed, native-Claude reasoning part is preserved verbatim.
internal fun reasoningContentBlock(reasoning: String, metadata: JsonObject?): JsonObject? {
    if (metadata == null) return null
    // The signature must be a non-blank STRING. A missing key, JsonNull, an empty/blank
    // string, or a non-string (array/object) is not a usable Anthropic signature — keeping
    // any of those would only move the 400 from "field required" to "invalid signature".
    // runCatching: a non-string `signature` (array/object) fails to decode into the typed
    // schema; treat that decode failure as "no signature" rather than letting it throw.
    val signature = runCatching {
        json.decodeFromJsonElement<ClaudeReasoningMetadata>(metadata).signature
    }.getOrNull()
    if (signature.isNullOrBlank()) return null
    // Emit ONLY the canonical `signature` key: replaying unrelated cross-provider keys
    // (e.g. an OpenAI reasoning_id) into an Anthropic thinking block is not part of the wire
    // contract and would push arbitrary persisted keys to the API.
    return buildJsonObject {
        put("type", "thinking")
        put("thinking", reasoning)
        put("signature", signature)
    }
}
