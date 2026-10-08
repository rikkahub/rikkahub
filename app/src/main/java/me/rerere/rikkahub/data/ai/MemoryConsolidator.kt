package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.prompts.MEMORY_CONSOLIDATION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.buildMemoryConsolidationInput
import me.rerere.rikkahub.data.ai.tools.MemoryToolNames
import me.rerere.rikkahub.data.ai.tools.buildMemoryTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.memoryId

private const val MAX_STEPS = 8
private const val RECENT_CONTEXT_MESSAGES = 6
private const val RECENT_CONTEXT_MESSAGE_LENGTH = 500
private const val USER_MESSAGE_LENGTH = 8000
private const val ASSISTANT_MESSAGE_LENGTH = 2000

/**
 * 后台记忆整理：每轮对话结束后，从刚结束的这一轮里提取值得长期保存的事实写进记忆库。
 *
 * 主对话默认不写记忆，只在用户明确要求时才写；日常的积累都靠这里。
 */
class MemoryConsolidator(
    private val generationLoop: GenerationLoop,
    private val memoryRepository: MemoryRepository,
) {
    // 同一时间只整理一轮，免得相邻两轮抢着改同一个文件
    private val lock = Mutex()

    suspend fun consolidate(settings: Settings, assistant: Assistant, conversation: Conversation) {
        if (!assistant.enableMemory) return

        val messages = conversation.currentMessages
        val exchangeStart = messages.indexOfLast { it.role == MessageRole.USER }
        if (exchangeStart < 0) return
        val exchange = messages.subList(exchangeStart, messages.size)
        if (!shouldConsolidate(exchange)) return

        // 整理要调用工具；快速模型不支持时退回对话模型
        val model = (settings.findModelById(settings.fastModelId)?.takeIf { ModelAbility.TOOL in it.abilities }
            ?: settings.getChatModelOf(conversation)
            ?: return).copy(tools = emptySet())

        lock.withLock {
            val input = buildMemoryConsolidationInput(
                files = memoryRepository.getFiles(assistant.memoryId),
                recentContext = messages.subList(0, exchangeStart)
                    .takeLast(RECENT_CONTEXT_MESSAGES)
                    .toTranscript(RECENT_CONTEXT_MESSAGE_LENGTH, RECENT_CONTEXT_MESSAGE_LENGTH),
                exchange = exchange.toTranscript(USER_MESSAGE_LENGTH, ASSISTANT_MESSAGE_LENGTH),
            )
            generationLoop.generateText(
                settings = settings,
                model = model,
                messages = listOf(UIMessage.user(input)),
                assistant = Assistant(
                    systemPrompt = MEMORY_CONSOLIDATION_PROMPT,
                    streamOutput = false,
                    reasoningLevel = settings.fastModelReasoningLevel,
                ),
                tools = buildMemoryTools(memoryRepository, assistant.memoryId),
                maxSteps = MAX_STEPS,
                conversationId = conversation.id,
            ).collect()
        }
    }
}

/** [exchange] 是从最后一条用户消息到对话末尾的这一轮 */
internal fun shouldConsolidate(exchange: List<UIMessage>): Boolean {
    val user = exchange.firstOrNull() ?: return false
    val last = exchange.last()
    if (user.toText().isBlank()) return false
    // 回复还没出来，或者停在等待工具审批上，这一轮还没结束
    if (last.role != MessageRole.ASSISTANT || last.getTools().any { !it.isExecuted }) return false
    // 主模型这一轮已经按用户的要求改过记忆，以用户的明确修改为准，不再重复整理
    return exchange.none { message -> message.getTools().any { it.toolName in MemoryToolNames.WRITES } }
}

private fun List<UIMessage>.toTranscript(userLength: Int, assistantLength: Int): String =
    filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
        .joinToString("\n\n") {
            it.summaryAsText(maxLength = if (it.role == MessageRole.USER) userLength else assistantLength)
        }
