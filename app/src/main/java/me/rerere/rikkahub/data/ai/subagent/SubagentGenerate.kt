package me.rerere.rikkahub.data.ai.subagent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.runtime.GenerationChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant

/**
 * The shape of the agentic engine a subagent run drives. This is the abstraction the spawn path
 * depends on (DIP); the concrete is [GenerationHandler.generateText], injected at the composition
 * root. Keeping it a function type (not the concrete handler) is what makes
 * [me.rerere.rikkahub.data.ai.task.TaskCoordinator] JVM-unit-testable with a fake flow — no
 * Context / Provider / network required, and crucially the child still runs through
 * `generateText`, so the PreToolUse hook dispatch `GenerationHandler` wires in is preserved (never
 * bypassed).
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
