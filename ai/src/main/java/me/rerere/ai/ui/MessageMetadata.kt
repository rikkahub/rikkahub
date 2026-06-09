package me.rerere.ai.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.util.json

/**
 * [UIMessagePart.metadata] 的类型安全 schema
 *
 * metadata 在序列化层仍然是 [JsonObject], 这里只是为读写提供编译期类型:
 * - 读: `part.metadataAs<ClaudeReasoningMetadata>()?.signature`
 * - 写: `part.metadata = ClaudeReasoningMetadata(signature = ...).toMetadata()`
 *
 * 所有字段必须可空且 key 与历史数据保持一致(必要时用 [SerialName]),
 * 否则旧会话中持久化的 metadata 将无法解析
 */
sealed interface PartMetadata

/**
 * Claude thinking block 的元数据, 回传时需要携带 signature
 */
@Serializable
data class ClaudeReasoningMetadata(
    val signature: String? = null,
) : PartMetadata

/**
 * OpenAI Responses API reasoning item 的元数据, 回传时需要携带 id 和 encrypted_content
 */
@Serializable
data class OpenAIReasoningMetadata(
    @SerialName("reasoning_id")
    val reasoningId: String? = null,
    @SerialName("encrypted_content")
    val encryptedContent: String? = null,
) : PartMetadata

/**
 * 按字段独立、容错地读取 OpenAI reasoning 元数据
 *
 * [metadataAs] 用生成的反序列化器整体解码: 任一**已知**字段类型不匹配(例如旧数据中
 * encrypted_content 是 object 而非 string)会让整个对象解码失败并返回 null,
 * 连同合法的 reasoning_id 一起丢失。回传时缺少 reasoning_id 会导致该 reasoning item 被丢弃。
 * 旧代码逐 key 独立取值, 坏的 encrypted_content 不会影响 reasoning_id —— 此函数保持同样的语义,
 * 让单个损坏字段无法波及兄弟字段。
 */
fun UIMessagePart.openAIReasoningMetadata(): OpenAIReasoningMetadata? {
    val obj = metadata ?: return null
    fun stringField(key: String): String? = (obj[key] as? JsonPrimitive)?.contentOrNull
    return OpenAIReasoningMetadata(
        reasoningId = stringField("reasoning_id"),
        encryptedContent = stringField("encrypted_content"),
    )
}

/**
 * Google Gemini 部件(functionCall/inlineData)的 thoughtSignature, 回传时需要携带
 */
@Serializable
data class GoogleThoughtMetadata(
    val thoughtSignature: String? = null,
) : PartMetadata

/**
 * 文件编辑类工具(如 workspace_edit_file)输出部件的元数据,
 * 携带 unified diff 文本供 UI 渲染 diff view, 不会发送给 API
 */
@Serializable
data class DiffMetadata(
    val diff: String? = null,
) : PartMetadata

/**
 * 将 metadata 解析为类型化的 [PartMetadata], 解析失败或 metadata 为 null 时返回 null
 *
 * 由于 json 配置了 ignoreUnknownKeys, 不同 provider 的 metadata 互不干扰
 * (例如切换 provider 后, OpenAI 写入的 reasoning 元数据不会影响 Claude 的解析)
 */
inline fun <reified T : PartMetadata> UIMessagePart.metadataAs(): T? = metadata?.let {
    runCatching { json.decodeFromJsonElement<T>(it) }.getOrNull()
}

/**
 * 将类型化的 [PartMetadata] 编码为 metadata [JsonObject]
 *
 * 由于 json 配置了 explicitNulls = false, 值为 null 的字段不会写入
 */
inline fun <reified T : PartMetadata> T.toMetadata(): JsonObject =
    json.encodeToJsonElement(this).jsonObject
