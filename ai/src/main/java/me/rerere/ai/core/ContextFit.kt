package me.rerere.ai.core

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.math.ceil

data class FitResult(
    val payload: List<UIMessage>,
    val elidedTokens: Int,
    val droppedCount: Int,
    val overBudget: Boolean,
)

private data class MessageSlot(
    val index: Int,
    var message: UIMessage,
)

private const val ELIDED_PREFIX = "[elided ~"
private const val ELIDED_SUFFIX = " tokens]"
private const val TRUNCATED_PREFIX = "[truncated ~"
private const val TRUNCATED_SUFFIX = " tokens]"
private const val TOOL_SCHEMA_JSON_CHARS_PER_TOKEN = 2.0
private const val TOOL_SCHEMA_TEXT_FUDGE = 1.5
private const val TOOL_SCHEMA_PER_TOOL_OVERHEAD_CHARS = 32

/**
 * Deterministic payload fitter for one provider step. Pure and non-mutating: it never mutates [this].
 */
@Suppress("DEPRECATION")
fun List<UIMessage>.fitToWindow(
    budget: Int,
    pinSystem: Boolean = true,
    pinHeadUserCount: Int = 1,
    minTailCount: Int = 1,
): FitResult {
    val targetBudget = budget.coerceAtLeast(1)
    if (estimateTokensForMessages(this) <= targetBudget) {
        return FitResult(this, elidedTokens = 0, droppedCount = 0, overBudget = false)
    }

    val size = this.size
    if (size == 0) {
        return FitResult(emptyList(), elidedTokens = 0, droppedCount = 0, overBudget = false)
    }

    var headEnd = 0
    if (pinSystem) {
        while (headEnd < size && this[headEnd].role == MessageRole.SYSTEM) {
            headEnd++
        }
    }
    val headIndexes = linkedSetOf<Int>()
    repeat(headEnd) { headIndexes += it }
    var usersPinned = 0
    var cursor = headEnd
    while (cursor < size && usersPinned < pinHeadUserCount.coerceAtLeast(0)) {
        if (this[cursor].role == MessageRole.USER) {
            headIndexes += cursor
            usersPinned++
        }
        cursor++
    }

    val protectedTailCount = minTailCount.coerceAtLeast(0)
    val tailStart = (size - protectedTailCount).coerceAtLeast(0)
    val tailIndexes = linkedSetOf<Int>()
    for (index in tailStart until size) {
        tailIndexes += index
    }
    indexOfLast { it.role == MessageRole.USER }.takeIf { it >= 0 }?.let { tailIndexes += it }

    val head = mutableListOf<MessageSlot>()
    val middle = mutableListOf<MessageSlot>()
    val tail = mutableListOf<MessageSlot>()
    for (index in indices) {
        val slot = MessageSlot(index, this[index])
        when {
            index in headIndexes -> head += slot
            index in tailIndexes -> tail += slot
            else -> middle += slot
        }
    }

    var elidedTokens = 0
    var droppedCount = 0

    fun payload(): List<UIMessage> =
        (head + middle + tail).sortedBy { it.index }.map { it.message }

    fun overBudgetNow(): Boolean = estimateTokensForMessages(payload()) > targetBudget

    fun replaceMessage(slots: MutableList<MessageSlot>, slotIndex: Int, updated: UIMessage): Boolean {
        if (slots[slotIndex].message == updated) return false
        slots[slotIndex] = slots[slotIndex].copy(message = updated)
        return true
    }

    fun elideToolOutputIn(slots: MutableList<MessageSlot>): Boolean {
        for (slotIndex in slots.indices) {
            val message = slots[slotIndex].message
            val parts = message.parts
            for (partIndex in parts.indices) {
                val part = parts[partIndex]
                if (part is UIMessagePart.Tool && part.output.isNotEmpty() && !part.output.isElidedStub()) {
                    val originalTokens = estimateTokens(part.output)
                    if (originalTokens <= 0) continue
                    val updatedPart = part.copy(output = listOf(elideStub(originalTokens)))
                    val updatedMessage = message.copy(parts = parts.toMutableList().apply { this[partIndex] = updatedPart })
                    if (replaceMessage(slots, slotIndex, updatedMessage)) {
                        elidedTokens += originalTokens
                        return true
                    }
                }
            }
        }
        return false
    }

    fun elideEscalationPartsIn(slots: MutableList<MessageSlot>): Boolean {
        for (slotIndex in slots.indices) {
            val message = slots[slotIndex].message
            val parts = message.parts
            for (partIndex in parts.indices) {
                val part = parts[partIndex]
                when (part) {
                    is UIMessagePart.Tool -> {
                        if (part.output.isEmpty() || part.output.isElidedStub()) continue
                        val originalTokens = estimateTokens(part.output)
                        if (originalTokens <= 0) continue
                        val updatedPart = part.copy(output = listOf(elideStub(originalTokens)))
                        val updatedMessage =
                            message.copy(parts = parts.toMutableList().apply { this[partIndex] = updatedPart })
                        if (replaceMessage(slots, slotIndex, updatedMessage)) {
                            elidedTokens += originalTokens
                            return true
                        }
                    }

                    is UIMessagePart.Image,
                    is UIMessagePart.Video,
                    is UIMessagePart.Audio,
                    is UIMessagePart.Document -> {
                        val originalTokens = estimateTokens(part)
                        if (originalTokens <= 0) continue
                        val updatedMessage = message.copy(
                            parts = parts.toMutableList().apply { this[partIndex] = elideStub(originalTokens) }
                        )
                        if (replaceMessage(slots, slotIndex, updatedMessage)) {
                            elidedTokens += originalTokens
                            return true
                        }
                    }

                    is UIMessagePart.ToolResult -> {
                        val originalTokens = estimateTokens(part)
                        if (originalTokens <= 0) continue
                        val updatedPart = part.copy(content = JsonPrimitive(elideStubText(originalTokens)))
                        val updatedMessage =
                            message.copy(parts = parts.toMutableList().apply { this[partIndex] = updatedPart })
                        if (replaceMessage(slots, slotIndex, updatedMessage)) {
                            elidedTokens += originalTokens
                            return true
                        }
                    }

                    else -> Unit
                }
            }
        }
        return false
    }

    fun dropOldestMiddleCluster(): Boolean {
        if (middle.isEmpty()) return false
        for (first in middle) {
            val toRemove = linkedSetOf(first.index)
            val legacyIds = first.message.legacyToolIds().toMutableSet()
            if (legacyIds.isNotEmpty()) {
                var changed = true
                while (changed) {
                    changed = false
                    for (slot in middle) {
                        if (slot.index in toRemove) continue
                        if (slot.message.legacyToolIds().any { it in legacyIds }) {
                            toRemove += slot.index
                            legacyIds += slot.message.legacyToolIds()
                            changed = true
                        }
                    }
                }
            }
            val candidatePayload = (head + middle.filterNot { it.index in toRemove } + tail)
                .sortedBy { it.index }
                .map { it.message }
            if (!candidatePayload.isOrphanFreeForLegacyToolParts()) {
                continue
            }
            middle.removeAll { it.index in toRemove }
            droppedCount += toRemove.size
            return true
        }
        return false
    }

    fun hardTruncateLargestTextIn(slots: MutableList<MessageSlot>): Boolean {
        val candidate = slots
            .mapIndexedNotNull { slotIndex, slot ->
                val textCandidate = slot.message.parts
                    .mapIndexedNotNull { partIndex, part ->
                        val text = (part as? UIMessagePart.Text)?.text ?: return@mapIndexedNotNull null
                        if (text.isTextReliefStub()) return@mapIndexedNotNull null
                        TextPartCandidate(
                            slotIndex = slotIndex,
                            partIndex = partIndex,
                            messageIndex = slot.index,
                            messageTokens = estimateTokens(slot.message.parts),
                            partTokens = estimateTokens(part),
                        )
                    }
                    .maxWithOrNull(
                        compareBy<TextPartCandidate> { it.partTokens }
                            .thenByDescending { -it.partIndex }
                    )
                textCandidate
            }
            .filter { it.partTokens > estimateTokens(UIMessagePart.Text(truncatedStubText(it.partTokens))) }
            .maxWithOrNull(
                compareBy<TextPartCandidate> { it.messageTokens }
                    .thenBy { -it.messageIndex }
            )
            ?: return false

        val message = slots[candidate.slotIndex].message
        val updatedMessage = message.copy(
            parts = message.parts.toMutableList().apply {
                this[candidate.partIndex] = UIMessagePart.Text(truncatedStubText(candidate.partTokens))
            }
        )
        if (replaceMessage(slots, candidate.slotIndex, updatedMessage)) {
            elidedTokens += candidate.partTokens
            return true
        }
        return false
    }

    while (overBudgetNow() && elideToolOutputIn(middle)) {
        // keep relieving middle tool outputs until fit or no candidates
    }

    while (overBudgetNow() && dropOldestMiddleCluster()) {
        // keep dropping oldest middle message-clusters until fit or no safe drop
    }

    while (overBudgetNow()) {
        if (elideEscalationPartsIn(tail)) continue
        if (elideEscalationPartsIn(head)) continue
        break
    }

    while (overBudgetNow()) {
        if (hardTruncateLargestTextIn(middle)) continue
        if (hardTruncateLargestTextIn(tail)) continue
        if (hardTruncateLargestTextIn(head)) continue
        break
    }

    val finalPayload = payload()
    val overBudget = estimateTokensForMessages(finalPayload) > targetBudget
    return FitResult(
        payload = finalPayload,
        elidedTokens = elidedTokens,
        droppedCount = droppedCount,
        overBudget = overBudget,
    )
}

private data class TextPartCandidate(
    val slotIndex: Int,
    val partIndex: Int,
    val messageIndex: Int,
    val messageTokens: Int,
    val partTokens: Int,
)

/**
 * Conservative token estimate for provider tool-catalog overhead outside user messages.
 */
fun estimatedToolSchemaTokens(tools: List<Tool>): Int {
    if (tools.isEmpty()) return 0
    val chars = tools.sumOf { tool ->
        val schemaChars = tool.parameters()?.let { schema ->
            kotlinx.serialization.json.Json.encodeToString(InputSchema.serializer(), schema).length
        } ?: 0
        TOOL_SCHEMA_PER_TOOL_OVERHEAD_CHARS + tool.name.length + tool.description.length + schemaChars
    }
    return fudgedJsonChars(chars)
}

@Suppress("DEPRECATION")
private fun UIMessage.legacyToolIds(): Set<String> = buildSet {
    parts.forEach { part ->
        when (part) {
            is UIMessagePart.ToolCall -> add(part.toolCallId)
            is UIMessagePart.ToolResult -> add(part.toolCallId)
            else -> Unit
        }
    }
}

@Suppress("DEPRECATION")
private fun List<UIMessage>.isOrphanFreeForLegacyToolParts(): Boolean {
    val callIds = buildSet {
        this@isOrphanFreeForLegacyToolParts.forEach { message ->
            message.parts.forEach { part ->
                when (part) {
                    is UIMessagePart.Tool -> add(part.toolCallId)
                    is UIMessagePart.ToolCall -> add(part.toolCallId)
                    else -> Unit
                }
            }
        }
    }
    return all { message ->
        message.parts.all { part ->
            part !is UIMessagePart.ToolResult || part.toolCallId in callIds
        }
    }
}

private fun List<UIMessagePart>.isElidedStub(): Boolean {
    if (size != 1) return false
    val text = (firstOrNull() as? UIMessagePart.Text)?.text ?: return false
    return text.isElidedStub()
}

private fun elideStub(tokens: Int): UIMessagePart.Text = UIMessagePart.Text(elideStubText(tokens))

private fun elideStubText(tokens: Int): String = "$ELIDED_PREFIX${tokens.coerceAtLeast(0)}$ELIDED_SUFFIX"

private fun truncatedStubText(tokens: Int): String = "$TRUNCATED_PREFIX${tokens.coerceAtLeast(0)}$TRUNCATED_SUFFIX"

private fun String.isTextReliefStub(): Boolean = isElidedStub() || isTruncatedStub()

private fun String.isElidedStub(): Boolean = startsWith(ELIDED_PREFIX) && endsWith(ELIDED_SUFFIX)

private fun String.isTruncatedStub(): Boolean = startsWith(TRUNCATED_PREFIX) && endsWith(TRUNCATED_SUFFIX)

private fun fudgedJsonChars(chars: Int): Int =
    if (chars <= 0) 0 else ceil(chars / TOOL_SCHEMA_JSON_CHARS_PER_TOKEN * TOOL_SCHEMA_TEXT_FUDGE).toInt()
