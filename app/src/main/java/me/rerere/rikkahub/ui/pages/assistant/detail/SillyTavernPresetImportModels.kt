package me.rerere.rikkahub.ui.pages.assistant.detail

import kotlinx.serialization.Serializable

@Serializable
internal data class StPresetImport(
    val name: String = "",
    val temperature: Double? = null,
    val top_p: Double? = null,
    val openai_max_tokens: Int? = null,
    val frequency_penalty: Double? = null,
    val presence_penalty: Double? = null,
    val min_p: Double? = null,
    val top_k: Int? = null,
    val top_a: Double? = null,
    val repetition_penalty: Double? = null,
    val seed: Long? = null,
    val verbosity: String? = null,
    val enable_stop_string: Boolean? = null,
    val stop_string: String? = null,
    val stop_strings: List<String> = emptyList(),
    val names_behavior: Int? = null,
    val send_if_empty: String? = null,
    val assistant_prefill: String? = null,
    val assistant_impersonation: String? = null,
    val continue_prefill: Boolean? = null,
    val continue_postfix: String? = null,
    val new_chat_prompt: String? = null,
    val new_group_chat_prompt: String? = null,
    val new_example_chat_prompt: String? = null,
    val use_sysprompt: Boolean? = null,
    val squash_system_messages: Boolean? = null,
    val reasoning_effort: String? = null,
    val scenario_format: String? = null,
    val personality_format: String? = null,
    val wi_format: String? = null,
    val continue_nudge_prompt: String? = null,
    val group_nudge_prompt: String? = null,
    val impersonation_prompt: String? = null,
    val prompts: List<StPresetPromptImport> = emptyList(),
    val prompt_order: List<StPresetOrderList> = emptyList(),
) {
    val topP: Double?
        get() = top_p

    val openAIMaxTokens: Int?
        get() = openai_max_tokens

    val frequencyPenalty: Double?
        get() = frequency_penalty

    val presencePenalty: Double?
        get() = presence_penalty

    val minP: Double?
        get() = min_p

    val topK: Int?
        get() = top_k

    val topA: Double?
        get() = top_a

    val repetitionPenalty: Double?
        get() = repetition_penalty

    val enableStopString: Boolean?
        get() = enable_stop_string

    val stopString: String?
        get() = stop_string

    val stopStrings: List<String>
        get() = stop_strings

    val namesBehavior: Int?
        get() = names_behavior

    val sendIfEmpty: String?
        get() = send_if_empty

    val assistantPrefill: String?
        get() = assistant_prefill

    val assistantImpersonation: String?
        get() = assistant_impersonation

    val continuePrefill: Boolean?
        get() = continue_prefill

    val continuePostfix: String?
        get() = continue_postfix

    val newChatPrompt: String?
        get() = new_chat_prompt

    val newGroupChatPrompt: String?
        get() = new_group_chat_prompt

    val newExampleChatPrompt: String?
        get() = new_example_chat_prompt

    val useSystemPrompt: Boolean?
        get() = use_sysprompt

    val squashSystemMessages: Boolean?
        get() = squash_system_messages

    val reasoningEffort: String?
        get() = reasoning_effort

    val scenarioFormat: String?
        get() = scenario_format

    val personalityFormat: String?
        get() = personality_format

    val wiFormat: String?
        get() = wi_format

    val continueNudgePrompt: String?
        get() = continue_nudge_prompt

    val groupNudgePrompt: String?
        get() = group_nudge_prompt

    val impersonationPrompt: String?
        get() = impersonation_prompt

    val promptOrder: List<StPresetOrderList>
        get() = prompt_order
}

@Serializable
internal data class StPresetPromptImport(
    val identifier: String = "",
    val name: String? = null,
    val role: String? = null,
    val content: String? = null,
    val system_prompt: Boolean? = null,
    val marker: Boolean? = null,
    val enabled: Boolean? = null,
    val injection_position: Int? = null,
    val injection_depth: Int? = null,
    val injection_order: Int? = null,
    val injection_trigger: List<String>? = null,
    val forbid_overrides: Boolean? = null,
) {
    val systemPrompt: Boolean?
        get() = system_prompt

    val injectionPosition: Int?
        get() = injection_position

    val injectionDepth: Int?
        get() = injection_depth

    val injectionOrder: Int?
        get() = injection_order

    val injectionTriggers: List<String>
        get() = injection_trigger
            ?.mapNotNull { trigger ->
                trigger
                    .trim()
                    .lowercase()
                    .takeIf { it.isNotBlank() }
            }
            ?.distinct()
            ?: emptyList()

    val forbidOverrides: Boolean?
        get() = forbid_overrides
}

@Serializable
internal data class StPresetOrderList(
    val character_id: Long? = null,
    val order: List<StPresetOrderItem> = emptyList(),
) {
    val characterId: Long?
        get() = character_id
}

@Serializable
internal data class StPresetOrderItem(
    val identifier: String? = null,
    val enabled: Boolean = true,
)
