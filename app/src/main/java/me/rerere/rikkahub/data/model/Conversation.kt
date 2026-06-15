package me.rerere.rikkahub.data.model

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.util.InstantSerializer
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * The synthetic tool_result a turn finalizer stamps over a genuinely-orphaned tool that did NOT
 * complete (interrupted before it ran, never resumable, not awaiting approval). Shared so the two
 * finalizers ([List.sanitizeForUpload]'s repairOrphanTools and ChatService.cancelToolByUser) emit a
 * byte-identical string, keeping sanitizeForUpload idempotent.
 */
internal const val TOOL_CANCELLED_MARKER =
    """{"status":"cancelled","error":"Tool execution did not complete."}"""

/**
 * The synthetic tool_result a turn finalizer stamps over a still-alive, auto-backgrounded
 * `workspace_shell` run (STOP_IS_DETACH_NOT_KILL, issue #291). The run was NOT cancelled — the
 * coordinator persisted it DETACHED and the real completion arrives later as a synthetic #290 event —
 * so the HONEST non-terminal marker is the SAME byte-shape the shipped Detached path already emits
 * (`{"status":"running"}`). Shared so repairOrphanTools and cancelToolByUser agree byte-for-byte; once
 * stamped the part is executed, so whichever finalizer runs first wins and the other no-ops.
 */
internal const val SHELL_BACKGROUNDED_MARKER = """{"status":"running"}"""

/**
 * Whether a tool part is a `workspace_shell` run that explicitly opted into auto-background
 * (`detachAfterSeconds > 0`). Shared pure predicate so the turn finalizers
 * (ChatService.shouldBackgroundShellOnStop and repairOrphanTools) classify a backgroundable shell
 * identically — a default-kill shell (detachAfterSeconds absent / 0) never gets the running marker.
 */
internal fun UIMessagePart.Tool.isBackgroundableShell(): Boolean {
    if (toolName != "workspace_shell") return false
    // Parse with the SAME lenient JSON the runtime executed this tool through (ChatTurnRuntime's
    // ToolArgsJson): models routinely emit relaxed JSON — most commonly UNQUOTED keys like
    // {command:"sleep 999",detachAfterSeconds:30}. A strict parse here (inputAsJson()) would fall back
    // to {} for that valid-but-relaxed input and misclassify a genuinely-detached run as
    // non-backgroundable, stamping {status:cancelled} over a live process — the exact bug the running
    // marker exists to prevent. Safe as?-cast helpers keep valid-but-non-object input ([], 1, "x") and
    // a non-primitive detachAfterSeconds ({}, []) from crashing repairOrphanTools; any parse failure
    // simply reads as non-backgroundable. contentOrNull.toIntOrNull accepts both a JSON number and a
    // quoted numeric string, matching how the shell tool itself reads detachAfterSeconds.
    val secs = runCatching { LenientToolInputJson.parseToJsonElement(input.ifBlank { "{}" }) }
        .getOrNull()
        ?.jsonObjectOrNull?.get("detachAfterSeconds")
        ?.jsonPrimitiveOrNull?.contentOrNull?.toIntOrNull() ?: 0
    return secs > 0
}

/**
 * Lenient JSON for re-reading a persisted, model-controlled tool input in the turn finalizers, mirroring
 * the runtime's ToolArgsJson (isLenient + ignoreUnknownKeys). Kept private to the data-model layer so the
 * sanitizer does not depend on the :ai-runtime internal that executes the tool.
 */
private val LenientToolInputJson = kotlinx.serialization.json.Json {
    isLenient = true
    ignoreUnknownKeys = true
}

@Serializable
data class Conversation(
    val id: Uuid = Uuid.random(),
    val assistantId: Uuid,
    val title: String = "",
    val messageNodes: List<MessageNode>,
    val chatSuggestions: List<String> = emptyList(),
    val isPinned: Boolean = false,
    @Serializable(with = InstantSerializer::class)
    val createAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updateAt: Instant = Instant.now(),
    val customSystemPrompt: String? = null,
    val modeInjectionIds: Set<Uuid> = emptySet(),
    val lorebookIds: Set<Uuid> = emptySet(),
    @Transient
    val newConversation: Boolean = false
) {
    val files: List<Uri>
        get() = messageNodes
            .flatMap { node -> node.messages.flatMap { it.parts } }
            .collectAllParts()
            .mapNotNull { it.fileUri() }

    /**
     *  当前选中的 message
     */
    val currentMessages
        get(): List<UIMessage> {
            return messageNodes.map { node -> node.messages[node.selectIndex] }
        }

    fun getMessageNodeByMessage(message: UIMessage): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.contains(message) }
    }

    fun getMessageNodeByMessageId(messageId: Uuid): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.any { it.id == messageId } }
    }

    fun updateCurrentMessages(messages: List<UIMessage>): Conversation {
        val newNodes = this.messageNodes.toMutableList()

        messages.forEachIndexed { index, message ->
            val node = newNodes
                .getOrElse(index) { message.toMessageNode() }

            val newMessages = node.messages.toMutableList()
            var newMessageIndex = node.selectIndex
            if (newMessages.any { it.id == message.id }) {
                newMessages[newMessages.indexOfFirst { it.id == message.id }] = message
            } else {
                newMessages.add(message)
                newMessageIndex = newMessages.lastIndex
            }

            val newNode = node.copy(
                messages = newMessages,
                selectIndex = newMessageIndex
            )

            // 更新newNodes
            if (index > newNodes.lastIndex) {
                newNodes.add(newNode)
            } else {
                newNodes[index] = newNode
            }
        }

        return this.copy(
            messageNodes = newNodes
        )
    }

    companion object {
        fun ofId(
            id: Uuid,
            assistantId: Uuid = DEFAULT_ASSISTANT_ID,
            messages: List<MessageNode> = emptyList(),
            newConversation: Boolean = false
        ) = Conversation(
            id = id,
            assistantId = assistantId,
            messageNodes = messages,
            newConversation = newConversation,
        )
    }
}

@Serializable
data class MessageNode(
    val id: Uuid = Uuid.random(),
    val messages: List<UIMessage>,
    val selectIndex: Int = 0,
    @Transient
    val isFavorite: Boolean = false,
) {
    val currentMessage get() = if (messages.isEmpty() || selectIndex !in messages.indices) {
        throw IllegalStateException("MessageNode has no valid current message: messages.size=${messages.size}, selectIndex=$selectIndex")
    } else {
        messages[selectIndex]
    }

    val role get() = messages.firstOrNull()?.role ?: MessageRole.USER

    companion object {
        fun of(message: UIMessage) = MessageNode(
            messages = listOf(message),
            selectIndex = 0
        )
    }
}

fun UIMessage.toMessageNode(): MessageNode {
    return MessageNode(
        messages = listOf(this),
        selectIndex = 0
    )
}

/**
 * Sanitize a message-node sequence into a state that is valid to hand to a
 * provider request builder.
 *
 * Invariant enforced (must hold for every sequence sent to a provider):
 * no empty/non-uploadable message, no two consecutive same-role messages,
 * every assistant tool_use balanced by a tool_result, and no
 * unterminated/unsigned thinking block.
 *
 * The invariant is first violated when an interrupted assistant turn is
 * finalized (a stream cancelled before its first delta leaves an empty
 * assistant node, an orphaned unexecuted tool, or an unsigned reasoning part).
 * This function repairs the persisted/forwarded structure (messageNodes), it
 * never fabricates content, and it is idempotent (a stable fixed point so the
 * interruption finalizer and the next-send check always agree).
 */
fun List<MessageNode>.sanitizeForUpload(): List<MessageNode> {
    val repaired = this.mapNotNull { node -> node.sanitizeNode() }
    return repaired.collapseConsecutiveSameRole()
}

/**
 * Repair a single node's selected message, or drop the node entirely if its
 * selected message cannot be uploaded:
 * - finish any unterminated reasoning, and drop a reasoning part that was left
 *   unterminated by an interrupted stream AND carries no signature (it would be
 *   uploaded as an unsigned thinking block, which Anthropic rejects);
 * - REPAIR (not drop) any genuinely orphaned tool_use in place by giving it a
 *   synthetic tool_result, so the tool_use is balanced while ALL text/reasoning
 *   parts in the same message survive (dropping the branch discarded them);
 * - drop the whole node only if no uploadable content remains.
 */
private fun MessageNode.sanitizeNode(): MessageNode? {
    if (messages.isEmpty()) return null
    // Repair a corrupt selectIndex rather than dropping the node.
    val node = if (selectIndex in messages.indices) this else copy(selectIndex = 0)

    val original = node.messages[node.selectIndex]
    val selected = original.finishReasoning()
        .dropUnterminatedUnsignedReasoning(original)
        .repairOrphanTools()

    // A resumable (approved/denied/answered) but not-yet-executed tool is
    // intentionally pending and must be kept for the resume path. A legitimately
    // Pending tool is also kept untouched (repairOrphanTools skips it).
    val hasResumableTool = selected.getTools()
        .any { !it.isExecuted && it.approvalState.canResumeToolExecution() }

    if (!selected.isValidToUpload() && !hasResumableTool) return null

    if (selected == original && node === this) return this
    val newMessages = node.messages.toMutableList().also { it[node.selectIndex] = selected }
    return node.copy(messages = newMessages)
}

/**
 * Balance a genuinely-orphaned tool_use (unexecuted, NOT resumable, and NOT
 * legitimately Pending-awaiting-approval) by attaching a synthetic tool_result.
 *
 * The orphan arises when an assistant turn is interrupted after emitting a
 * tool_use but before the tool ran. Previously sanitizeNode dropped the whole
 * branch, which also discarded the assistant's text/reasoning in the same
 * message (data loss). Repairing in place keeps that content and makes the
 * tool_use balanced (mirrors ChatService.cancelToolByUser's cancelled marker,
 * but does NOT flip approvalState — Denied is resumable and would change the
 * persisted resume semantics).
 *
 * EXCEPTION — an auto-backgrounded `workspace_shell` (STOP_IS_DETACH_NOT_KILL,
 * issue #291): such a run was NOT cancelled, it is still alive (the coordinator
 * persisted it DETACHED and its completion arrives later as a synthetic #290
 * event). Stamping {status:cancelled} over it would lie about a running process,
 * so a backgroundable shell gets the HONEST [SHELL_BACKGROUNDED_MARKER]
 * (`{"status":"running"}`, byte-identical to the shipped Detached path) instead.
 * Either marker makes the part executed, so the two order-independent finalizers
 * (this one under NonCancellable in onCompletion, and cancelToolByUser) converge:
 * whichever runs first stamps the marker, the other sees isExecuted and no-ops —
 * which is also why sanitizeForUpload stays idempotent.
 *
 * A Pending tool is left untouched so the approval/resume UI path is unchanged.
 */
private fun UIMessage.repairOrphanTools(): UIMessage {
    val newParts = parts.map { part ->
        if (part is UIMessagePart.Tool &&
            !part.isExecuted &&
            !part.approvalState.canResumeToolExecution() &&
            part.approvalState !is ToolApprovalState.Pending
        ) {
            val marker = if (part.isBackgroundableShell()) SHELL_BACKGROUNDED_MARKER else TOOL_CANCELLED_MARKER
            part.copy(output = listOf(UIMessagePart.Text(marker)))
        } else {
            part
        }
    }
    return if (newParts == parts) this else copy(parts = newParts)
}

/**
 * Drop a Reasoning part only when an interrupted stream left it unterminated
 * (finishedAt == null in [original]) AND it carries no signature. Such a part is
 * a half-streamed Anthropic thinking block that would be forwarded without its
 * signature, which Anthropic rejects.
 *
 * A reasoning part that finished normally (finishedAt != null) is kept even
 * without a signature: OpenAI ChatCompletions and Google text-thoughts build
 * valid, signature-less Reasoning, and stripping those would be a data-loss
 * regression. [original] is the pre-finishReasoning() message so its finishedAt
 * still reflects the interruption.
 */
private fun UIMessage.dropUnterminatedUnsignedReasoning(original: UIMessage): UIMessage {
    val newParts = parts.filterIndexed { index, part ->
        if (part !is UIMessagePart.Reasoning) return@filterIndexed true
        val wasUnterminated =
            (original.parts.getOrNull(index) as? UIMessagePart.Reasoning)?.finishedAt == null
        val unsigned = part.metadata?.get("signature") == null
        !(wasUnterminated && unsigned)
    }
    return if (newParts == parts) this else copy(parts = newParts)
}

/**
 * Collapse two consecutive same-role nodes so the forwarded sequence alternates.
 *
 * When both messages carry content, their parts are MERGED into a single node
 * (a provider role turn accepts multiple content blocks) so no real user/assistant
 * turn is lost — e.g. user "hi", generation interrupted, user "again" must upload
 * BOTH, not just "again". An empty/non-uploadable message is dropped in favour of
 * the content-bearing one. Content is never fabricated.
 */
private fun List<MessageNode>.collapseConsecutiveSameRole(): List<MessageNode> {
    val result = mutableListOf<MessageNode>()
    for (node in this) {
        val previous = result.lastOrNull()
        // SYNTHETIC_DISTINCTNESS (#290): never merge a synthetic agent-event message into an adjacent
        // same-role turn (nor a real turn into it) — merging would fuse the synthetic marker onto real
        // user content (so FTS/stats would wrongly exclude it) and blur distinct injected context.
        if (previous != null && previous.currentMessage.role == node.currentMessage.role &&
            !previous.currentMessage.isSyntheticAgentEvent() && !node.currentMessage.isSyntheticAgentEvent()
        ) {
            val prevMsg = previous.currentMessage
            val nodeMsg = node.currentMessage
            val merged = when {
                !prevMsg.isValidToUpload() -> nodeMsg
                !nodeMsg.isValidToUpload() -> prevMsg
                else -> prevMsg.copy(parts = prevMsg.parts + nodeMsg.parts)
            }
            result[result.lastIndex] = previous.replaceCurrentMessage(merged)
            continue
        }
        result.add(node)
    }
    return result
}

/** Replace this node's selected message in place, preserving branch siblings. */
private fun MessageNode.replaceCurrentMessage(message: UIMessage): MessageNode {
    if (message === currentMessage) return this
    val newMessages = messages.toMutableList().also { it[selectIndex] = message }
    return copy(messages = newMessages)
}

/**
 * 递归展开所有 parts，包括工具调用结果中的嵌套 parts。
 */
private fun List<UIMessagePart>.collectAllParts(): List<UIMessagePart> =
    this + filterIsInstance<UIMessagePart.Tool>().flatMap { it.output.collectAllParts() }

/**
 * 提取 part 中引用的本地文件 URI，新增文件类型时只需在此处添加。
 */
private fun UIMessagePart.fileUri(): Uri? = when (this) {
    is UIMessagePart.Image -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Document -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Video -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Audio -> url.takeIf { it.startsWith("file://") }?.toUri()
    else -> null
}
