package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.provider.stream.DecodeResult
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.provider.stream.StreamChunkDecoder
import me.rerere.ai.ui.ServerToolMetadata
import me.rerere.ai.ui.ServerToolProtocol
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.json
import me.rerere.ai.util.parseErrorDetail
import me.rerere.common.http.jsonArrayOrNull
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull

/**
 * Interactions API 的流按 step 组织：`step.start` → `step.delta`* → `step.stop`，
 * 每个 step 通过 `index` 关联，最后由 `interaction.completed` 给出状态和用量。
 */
internal class InteractionsStreamDecoder(
    private val fallbackModel: String? = null,
) : StreamChunkDecoder {
    private val steps = linkedMapOf<Int, StepState>()
    private var interactionId: String? = null
    private var model: String? = null
    private var status: String? = null
    private var finished = false

    override fun accept(event: SseEvent): DecodeResult {
        if (finished) return DecodeResult(completed = true)
        if (event.data == "[DONE]") return DecodeResult(finish(), completed = true)

        val payload = json.parseToJsonElement(event.data).jsonObject
        val eventType = payload.stringOrNull("event_type") ?: event.event ?: payload.stringOrNull("type")
        return when (eventType) {
            "interaction.created" -> {
                captureInteraction(payload["interaction"]?.jsonObjectOrNull)
                DecodeResult()
            }

            "step.start" -> DecodeResult(startStep(payload))
            "step.delta" -> DecodeResult(stepDelta(payload))
            "step.stop" -> DecodeResult(stopStep(payload))

            "interaction.completed" -> {
                val interaction = payload["interaction"]?.jsonObjectOrNull
                captureInteraction(interaction)
                DecodeResult(
                    chunks = buildList {
                        parseInteractionsUsage(interaction?.get("usage")?.jsonObjectOrNull)?.let {
                            add(StreamChunk.Usage(it))
                        }
                        addAll(finish())
                    },
                    completed = true,
                )
            }

            "error" -> {
                finished = true
                throw payload.parseErrorDetail()
            }

            else -> {
                // interaction.status_update 等状态事件；未知事件按文档要求直接跳过
                payload.stringOrNull("status")?.let { status = it }
                DecodeResult()
            }
        }
    }

    override fun onClosed(): List<StreamChunk> = finish()

    private fun captureInteraction(interaction: JsonObject?) {
        if (interaction == null) return
        interaction.stringOrNull("id")?.let { interactionId = it }
        interaction.stringOrNull("model")?.let { model = it }
        interaction.stringOrNull("status")?.let { status = it }
    }

    private fun startStep(payload: JsonObject): List<StreamChunk> {
        val index = payload["index"]?.jsonPrimitiveOrNull?.intOrNull ?: return emptyList()
        val step = payload["step"]?.jsonObjectOrNull ?: return emptyList()
        val type = step.stringOrNull("type") ?: return emptyList()
        val state = StepState(index, type, step).also { steps[index] = it }

        return when {
            // step.start 可能已经带有首段内容
            type == "model_output" -> step["content"]?.jsonArrayOrNull.orEmpty().flatMap { content ->
                content.jsonObjectOrNull?.let { contentDelta(state, it) }.orEmpty()
            }

            type == "thought" -> buildList {
                add(StreamChunk.ReasoningStart(state.reasoningId, interactionsSignatureMetadata(state.signature)))
                step["summary"]?.jsonArrayOrNull.orEmpty().forEach { summary ->
                    summary.jsonObjectOrNull?.let { addAll(thoughtSummaryDelta(state, it)) }
                }
            }

            type == "function_call" -> listOf(
                StreamChunk.ToolCallStart(
                    id = state.toolCallId(interactionId),
                    toolName = step.stringOrNull("name") ?: "",
                    metadata = interactionsSignatureMetadata(state.signature),
                )
            )

            isInteractionsServerToolCall(type) -> listOf(state.serverToolStart())
            else -> emptyList()
        }
    }

    private fun stepDelta(payload: JsonObject): List<StreamChunk> {
        val index = payload["index"]?.jsonPrimitiveOrNull?.intOrNull ?: return emptyList()
        val delta = payload["delta"]?.jsonObjectOrNull ?: return emptyList()
        val deltaType = delta.stringOrNull("type") ?: return emptyList()
        // 容忍缺失的 step.start：根据 delta 类型推断 step 类型
        val state = steps.getOrPut(index) {
            val stepType = when (deltaType) {
                "thought_summary", "thought_signature" -> "thought"
                "arguments_delta" -> "function_call"
                "text", "image", "text_annotation_delta" -> "model_output"
                else -> deltaType
            }
            StepState(index, stepType, JsonObject(emptyMap()))
        }

        return when (deltaType) {
            "text", "image" -> contentDelta(state, delta)
            "text_annotation_delta" -> delta["annotations"].toInteractionsAnnotations()
                .takeIf { it.isNotEmpty() }
                ?.let { listOf(StreamChunk.Annotations(it)) }
                .orEmpty()

            "thought_summary" -> delta["content"]?.jsonObjectOrNull
                ?.let { thoughtSummaryDelta(state, it) }
                .orEmpty()

            "thought_signature" -> {
                delta.stringOrNull("signature")?.takeIf { it.isNotEmpty() }?.let { state.signature = it }
                emptyList()
            }

            "arguments_delta" -> {
                val arguments = delta.stringOrNull("arguments").orEmpty()
                if (arguments.isEmpty()) {
                    emptyList()
                } else {
                    state.hasArgumentsDelta = true
                    listOf(StreamChunk.ToolCallDelta(state.toolCallId(interactionId), inputDelta = arguments))
                }
            }

            else -> {
                delta.stringOrNull("signature")?.takeIf { it.isNotEmpty() }?.let { state.signature = it }
                // 服务端工具的 delta 与 step 同名，携带 arguments / result / signature 等字段，
                // 合并后得到与非流式响应一致的完整 step，供后续轮次原样回传
                val isServerToolCall = isInteractionsServerToolCall(state.type)
                if (isServerToolCall || isInteractionsServerToolResult(state.type)) {
                    state.raw = JsonObject(state.raw + delta.filterKeys { it != "type" })
                }
                if (isServerToolCall) listOf(state.serverToolStart()) else emptyList()
            }
        }
    }

    private fun contentDelta(state: StepState, content: JsonObject): List<StreamChunk> =
        when (content.stringOrNull("type")) {
            "text" -> buildList {
                val text = content.stringOrNull("text").orEmpty()
                if (text.isNotEmpty()) {
                    val id = state.textId ?: state.nextPartId("text").also {
                        state.textId = it
                        add(StreamChunk.TextStart(it))
                    }
                    add(StreamChunk.TextDelta(id, text))
                }
                content["annotations"].toInteractionsAnnotations()
                    .takeIf { it.isNotEmpty() }
                    ?.let { add(StreamChunk.Annotations(it)) }
            }

            // 每个 image delta 是一张完整图片，图片前后的文本属于不同的 text part
            "image" -> buildList {
                val data = content.stringOrNull("data")?.takeIf { it.isNotEmpty() } ?: return@buildList
                addAll(state.closeText())
                val id = state.nextPartId("image")
                add(StreamChunk.ImageStart(id, mimeType = content.stringOrNull("mime_type") ?: "image/png"))
                add(StreamChunk.ImageDelta(id, data))
                add(StreamChunk.ImageEnd(id))
            }

            else -> emptyList()
        }

    private fun thoughtSummaryDelta(state: StepState, content: JsonObject): List<StreamChunk> {
        val text = content.stringOrNull("text").orEmpty()
        if (content.stringOrNull("type") != "text" || text.isEmpty()) return emptyList()
        return listOf(StreamChunk.ReasoningDelta(state.reasoningId, text))
    }

    private fun stopStep(payload: JsonObject): List<StreamChunk> {
        val index = payload["index"]?.jsonPrimitiveOrNull?.intOrNull ?: return emptyList()
        val state = steps.remove(index) ?: return emptyList()
        return closeStep(state)
    }

    private fun closeStep(state: StepState): List<StreamChunk> = when {
        state.type == "model_output" -> state.closeText()

        // 签名是 thought step 的最后一个 delta，因此在结束时一并写入 metadata
        state.type == "thought" -> listOf(
            StreamChunk.ReasoningEnd(state.reasoningId, interactionsSignatureMetadata(state.signature))
        )

        state.type == "function_call" -> buildList {
            val id = state.toolCallId(interactionId)
            val metadata = interactionsSignatureMetadata(state.signature)
            // 非增量返回时参数直接放在 step.start 的 arguments 里
            val initialArguments = state.raw["arguments"]?.jsonObjectOrNull
                ?.takeIf { !state.hasArgumentsDelta && it.isNotEmpty() }
            if (initialArguments != null || metadata != null) {
                add(StreamChunk.ToolCallDelta(
                    id = id,
                    inputDelta = initialArguments?.toString().orEmpty(),
                    metadata = metadata,
                ))
            }
            add(StreamChunk.ToolCallEnd(id))
        }

        isInteractionsServerToolResult(state.type) -> listOf(
            StreamChunk.ServerToolEnd(
                id = state.raw.stringOrNull("call_id") ?: "",
                output = state.raw["result"],
                status = state.raw.interactionsServerToolResultStatus(),
                metadata = ServerToolMetadata(
                    protocol = ServerToolProtocol.GOOGLE_INTERACTIONS,
                    result = state.raw,
                    resultIndex = state.index,
                ).toMetadata(),
            )
        )

        else -> emptyList()
    }

    private fun finish(): List<StreamChunk> {
        if (finished) return emptyList()
        finished = true
        return buildList {
            // 连接提前断开时，未完成的服务端工具结果没有可回传的内容，不物化
            steps.values.filterNot { isInteractionsServerToolResult(it.type) }.forEach { addAll(closeStep(it)) }
            steps.clear()
            add(StreamChunk.Finish(status, interactionId, model ?: fallbackModel))
        }
    }

    private class StepState(val index: Int, val type: String, var raw: JsonObject) {
        var signature: String? = raw.stringOrNull("signature")?.takeIf { it.isNotEmpty() }
        var textId: String? = null
        var hasArgumentsDelta = false
        private var partSequence = 0

        val reasoningId: String get() = "step-$index:reasoning"

        fun nextPartId(kind: String): String = "step-$index:$kind-${++partSequence}"

        fun toolCallId(interactionId: String?): String =
            raw.stringOrNull("id") ?: "${interactionId ?: "interaction"}:tool-$index"

        fun closeText(): List<StreamChunk> =
            textId?.let { textId = null; listOf(StreamChunk.TextEnd(it)) }.orEmpty()

        fun serverToolStart() = StreamChunk.ServerToolStart(
            id = raw.stringOrNull("id") ?: "",
            toolName = type.removeSuffix("_call"),
            input = raw["arguments"],
            metadata = ServerToolMetadata(
                protocol = ServerToolProtocol.GOOGLE_INTERACTIONS,
                call = raw,
                callIndex = index,
            ).toMetadata(),
        )
    }
}
