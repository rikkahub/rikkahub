package me.rerere.rikkahub.data.ai.subagent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.runtime.contract.TurnConfig
import me.rerere.ai.runtime.subagent.extractFinalAssistantText
import me.rerere.ai.runtime.subagent.filterToolsForSubagent
import me.rerere.ai.runtime.subagent.resolveSubagentModel
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.runtime.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.runtime.toAssistantConfig
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

/**
 * The shape of the agentic engine the runner drives. This is the abstraction the runner depends
 * on (DIP); the concrete is [GenerationHandler.generateText], injected at the composition root.
 * Keeping it a function type (not the concrete handler) is what makes the runner JVM-unit-testable
 * with a fake flow — no Context / Provider / network required.
 */
typealias SubagentGenerate = (
    settings: Settings,
    model: Model,
    messages: List<UIMessage>,
    assistant: Assistant,
    tools: List<Tool>,
    maxSteps: Int,
    processingStatus: MutableStateFlow<String?>,
) -> Flow<GenerationChunk>

/**
 * The lean orchestrator that runs one self-contained sub-task against another [Assistant] and
 * returns just its final text (issue #201, slice 3).
 *
 * SoC: this is intentionally NOT the heavy [me.rerere.rikkahub.service.ChatService]. It calls the
 * agentic engine ([GenerationHandler.generateText]) DIRECTLY and collects the resulting [Flow]
 * inline in the caller's coroutine. Conversation persistence lives only in
 * `ChatService.saveConversation`, NOT in `generateText`, so direct collection has no
 * conversation-write path — a sub-task never touches Room, never auto-compacts, and never creates a
 * [me.rerere.rikkahub.data.model.Conversation]. Running inline means cancellation is inherited via
 * structured concurrency: when the parent generation's Job is cancelled, this collection is too.
 */
class SubagentRunner(
    private val generate: SubagentGenerate,
) {
    /** DI/composition-root constructor: bind the engine to [GenerationHandler.generateText]. */
    constructor(generationHandler: GenerationHandler) : this(
        generate = { settings, model, messages, assistant, tools, maxSteps, processingStatus ->
            generationHandler.generateText(
                settings = settings,
                model = model,
                messages = messages,
                assistant = assistant,
                tools = tools,
                maxSteps = maxSteps,
                processingStatus = processingStatus,
            )
        }
    )

    /**
     * @param tools the subagent's own tool pool, built from the TARGET (sub) assistant's allowlist
     *   by the caller. The runner applies [filterToolsForSubagent] to it unconditionally, so the
     *   spawn tool can never reach a subagent regardless of how the caller assembled the pool —
     *   the recursion guard (depth bounded at 1) is enforced here at the lowest correct point.
     */
    suspend fun run(
        sub: Assistant,
        prompt: String,
        parentModelId: Uuid?,
        settings: Settings,
        tools: List<Tool> = emptyList(),
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
    ): String {
        // Only `defaultModelId` is read by resolveSubagentModel; map the app Settings onto a minimal
        // neutral TurnConfig (defaultModelId = settings.chatModelId, both non-null Uuid) so the
        // sub-pin > parent > global-default resolution order is preserved across the module boundary.
        val modelId = resolveSubagentModel(
            sub = sub.toAssistantConfig(),
            parentModelId = parentModelId,
            turn = TurnConfig(
                defaultModelId = settings.chatModelId,
                providers = emptyList(),
                assistants = emptyList(),
            ),
        )
        val model = settings.findModelById(modelId)
            ?: error("Subagent model not found for id $modelId")

        // Force memory OFF on the ephemeral sub-Assistant so a throwaway sub-task can never
        // mutate (or read from) the PARENT's memory. GenerationHandler gates both the
        // create/update/delete memory tools (enableMemory) and the recent-chats prompt
        // (enableRecentChatsReference) on these flags; a subagent must not write into either.
        val ephemeralSub = sub.copy(
            chatModelId = model.id,
            enableMemory = false,
            enableRecentChatsReference = false,
        )

        // A subagent gets a conservative step budget (vs the main loop's 256) to bound runaway
        // spend; a sub-Assistant may raise it via its own maxSteps.
        val maxSteps = sub.maxSteps ?: SUBAGENT_DEFAULT_MAX_STEPS

        // A fresh, throwaway message list — the sub-task starts from just the prompt.
        val messages = listOf(UIMessage.user(prompt))

        // Strip the spawn tool from the sub's pool (recursion guard, depth bounded at 1). Enforced
        // here so the guard holds no matter how the caller built `tools`. The reserved spawn-tool
        // name is supplied app-side (SPAWN_TOOL_NAME) — the neutral primitive names no concrete tool.
        val subagentTools = filterToolsForSubagent(tools, SPAWN_TOOL_NAME)

        var finalMessages: List<UIMessage> = messages
        generate(settings, model, messages, ephemeralSub, subagentTools, maxSteps, processingStatus).collect { chunk ->
            when (chunk) {
                is GenerationChunk.Messages -> finalMessages = chunk.messages
            }
        }
        return extractFinalAssistantText(finalMessages)
    }

    companion object {
        /** Default agentic step ceiling for a subagent run when the sub-Assistant pins none. */
        const val SUBAGENT_DEFAULT_MAX_STEPS: Int = 64
    }
}
