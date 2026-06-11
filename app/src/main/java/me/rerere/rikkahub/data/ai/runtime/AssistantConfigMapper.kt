package me.rerere.rikkahub.data.ai.runtime

import me.rerere.ai.runtime.contract.AssistantConfig
import me.rerere.ai.runtime.contract.AssistantRegexRule
import me.rerere.ai.runtime.contract.AssistantRegexScope
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex

/**
 * Maps the app [Assistant] onto the neutral [AssistantConfig] the `:ai-runtime` contract uses (issue
 * #243 slice 3). Pure 1:1 field copy — no semantic translation; the runtime never sees the app type.
 *
 * The `localTools: List<LocalToolOption>` allowlist is projected to stable string ids via the
 * sealed-type `@SerialName` keys ([localToolId]); [localToolOptionOf] is the inverse the app catalog
 * uses to recover the real options for `LocalTools.getTools`, so the projection round-trips and the
 * mapping is not lossy.
 */
fun Assistant.toAssistantConfig(): AssistantConfig = AssistantConfig(
    id = id,
    chatModelId = chatModelId,
    systemPrompt = systemPrompt,
    streamOutput = streamOutput,
    enableMemory = enableMemory,
    useGlobalMemory = useGlobalMemory,
    enableRecentChatsReference = enableRecentChatsReference,
    messageTemplate = messageTemplate,
    regexes = regexes.map { it.toRule() },
    reasoningLevel = reasoningLevel,
    maxTokens = maxTokens,
    customHeaders = customHeaders,
    customBodies = customBodies,
    mcpServers = mcpServers,
    localToolIds = localTools.map { it.localToolId() },
    enabledSkills = enabledSkills,
    modeInjectionIds = modeInjectionIds,
    lorebookIds = lorebookIds,
    knowledgeBaseId = knowledgeBaseId,
    description = description,
    spawnable = spawnable,
    subagentMaxSteps = maxSteps,
)

private fun AssistantRegex.toRule(): AssistantRegexRule = AssistantRegexRule(
    id = id,
    name = name,
    enabled = enabled,
    findRegex = findRegex,
    replaceString = replaceString,
    affectingScope = affectingScope.mapTo(mutableSetOf()) { it.toNeutral() },
    visualOnly = visualOnly,
)

private fun AssistantAffectScope.toNeutral(): AssistantRegexScope = when (this) {
    AssistantAffectScope.USER -> AssistantRegexScope.User
    AssistantAffectScope.ASSISTANT -> AssistantRegexScope.Assistant
}

/** Stable string id for a local-tool option (its persisted `@SerialName`). */
fun LocalToolOption.localToolId(): String = when (this) {
    LocalToolOption.JavascriptEngine -> "javascript_engine"
    LocalToolOption.TimeInfo -> "time_info"
    LocalToolOption.Clipboard -> "clipboard"
    LocalToolOption.Tts -> "tts"
    LocalToolOption.AskUser -> "ask_user"
}

/** Inverse of [localToolId]; null for an unknown id (defensive — a future option not yet mapped). */
fun localToolOptionOf(id: String): LocalToolOption? = when (id) {
    "javascript_engine" -> LocalToolOption.JavascriptEngine
    "time_info" -> LocalToolOption.TimeInfo
    "clipboard" -> LocalToolOption.Clipboard
    "tts" -> LocalToolOption.Tts
    "ask_user" -> LocalToolOption.AskUser
    else -> null
}
