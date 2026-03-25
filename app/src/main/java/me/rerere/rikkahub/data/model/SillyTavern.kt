package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole

@Serializable
data class SillyTavernPromptTemplate(
    val sourceName: String = "",
    val scenarioFormat: String = "{{scenario}}",
    val personalityFormat: String = "{{personality}}",
    val wiFormat: String = "{0}",
    val mainPrompt: String = "",
    val newChatPrompt: String = "",
    val newGroupChatPrompt: String = "",
    val newExampleChatPrompt: String = "",
    val continueNudgePrompt: String = "",
    val groupNudgePrompt: String = "",
    val impersonationPrompt: String = "",
    val assistantPrefill: String = "",
    val assistantImpersonation: String = "",
    val continuePrefill: Boolean = false,
    val continuePostfix: String = "",
    val sendIfEmpty: String = "",
    val namesBehavior: Int? = null,
    val useSystemPrompt: Boolean = false,
    val squashSystemMessages: Boolean = false,
    val prompts: List<SillyTavernPromptItem> = emptyList(),
    val promptOrder: List<SillyTavernPromptOrderItem> = emptyList(),
    val orderedPromptIds: List<String> = emptyList(),
)

@Serializable
data class SillyTavernPromptItem(
    val identifier: String = "",
    val name: String = "",
    val role: MessageRole = MessageRole.SYSTEM,
    val content: String = "",
    val systemPrompt: Boolean = true,
    val marker: Boolean = false,
    val enabled: Boolean = true,
    val injectionPosition: StPromptInjectionPosition = StPromptInjectionPosition.RELATIVE,
    val injectionDepth: Int = 4,
    val injectionOrder: Int = 100,
    val injectionTriggers: List<String> = emptyList(),
    val forbidOverrides: Boolean = false,
)

@Serializable
data class SillyTavernPromptOrderItem(
    val identifier: String = "",
    val enabled: Boolean = true,
)

@Serializable
enum class StPromptInjectionPosition {
    @SerialName("relative")
    RELATIVE,

    @SerialName("absolute")
    ABSOLUTE,
}

@Serializable
data class SillyTavernCharacterData(
    val sourceName: String = "",
    val name: String = "",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val systemPromptOverride: String = "",
    val postHistoryInstructions: String = "",
    val firstMessage: String = "",
    val exampleMessagesRaw: String = "",
    val alternateGreetings: List<String> = emptyList(),
    val creatorNotes: String = "",
    val depthPrompt: StDepthPrompt? = null,
)

@Serializable
data class StDepthPrompt(
    val prompt: String = "",
    val depth: Int = 4,
    val role: MessageRole = MessageRole.SYSTEM,
)

@Serializable
data class LorebookTriggerContext(
    val recentMessagesText: String = "",
    val characterDescription: String = "",
    val characterPersonality: String = "",
    val personaDescription: String = "",
    val scenario: String = "",
    val creatorNotes: String = "",
    val characterDepthPrompt: String = "",
)

fun SillyTavernPromptTemplate.findPrompt(identifier: String): SillyTavernPromptItem? {
    return prompts.find { it.identifier == identifier }
}

fun SillyTavernPromptTemplate.findPromptOrder(identifier: String): SillyTavernPromptOrderItem? {
    return resolvePromptOrder().find { it.identifier == identifier }
}

fun SillyTavernPromptTemplate.hasExplicitPromptOrder(): Boolean {
    return promptOrder.isNotEmpty() || orderedPromptIds.isNotEmpty()
}

fun SillyTavernPromptTemplate.resolvePromptOrder(): List<SillyTavernPromptOrderItem> {
    if (promptOrder.isNotEmpty()) {
        return promptOrder
            .filter { it.identifier.isNotBlank() }
            .distinctBy { it.identifier }
    }
    if (orderedPromptIds.isNotEmpty()) {
        return orderedPromptIds
            .filter { it.isNotBlank() }
            .distinct()
            .map { identifier ->
                SillyTavernPromptOrderItem(
                    identifier = identifier,
                    enabled = findPrompt(identifier)?.enabled ?: true,
                )
            }
    }
    return prompts
        .filter { it.identifier.isNotBlank() }
        .distinctBy { it.identifier }
        .map { prompt ->
            SillyTavernPromptOrderItem(
                identifier = prompt.identifier,
                enabled = prompt.enabled,
            )
        }
}

fun SillyTavernPromptTemplate.withPromptOrder(order: List<SillyTavernPromptOrderItem>): SillyTavernPromptTemplate {
    val normalized = order
        .filter { it.identifier.isNotBlank() }
        .distinctBy { it.identifier }
    return copy(
        promptOrder = normalized,
        orderedPromptIds = normalized.map { it.identifier },
    )
}

fun SillyTavernPromptItem.matchesGenerationType(generationType: String): Boolean {
    val normalizedType = generationType.trim().lowercase().ifBlank { "normal" }
    val normalizedTriggers = injectionTriggers
        .mapNotNull { trigger ->
            trigger.trim().lowercase().takeIf { it.isNotBlank() }
        }
        .distinct()
    return normalizedTriggers.isEmpty() || normalizedTriggers.contains(normalizedType)
}
