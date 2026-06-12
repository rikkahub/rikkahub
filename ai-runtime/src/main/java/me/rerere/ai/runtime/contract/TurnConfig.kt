package me.rerere.ai.runtime.contract

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.runtime.hooks.HookConfig
import kotlin.uuid.Uuid

/**
 * Neutral, app-free snapshot of the per-turn settings the chat-turn runtime needs (issue #243 §B).
 *
 * This is a runtime *snapshot*, not a persisted record: the app maps its `Settings` onto this shape
 * at the composition root, so the runtime never imports the app's persistence types. Deliberately
 * NOT `@Serializable` — nothing in the runtime serializes a [TurnConfig]; serialization fixtures are
 * a later slice's concern (§NON-GOALS).
 */
data class TurnConfig(
    val defaultModelId: Uuid,
    val providers: List<ProviderSetting>,
    val assistants: List<AssistantConfig>,
    val maxSteps: Int = 256,
)

/**
 * Neutral projection of the app `Assistant` carrying exactly the fields the runtime turn needs
 * (issue #243 §B). Mapped from `Assistant` by the app-side adapter; the runtime never references the
 * app type. Mirrors the app field names so the mapping is a 1:1 copy with no semantic translation.
 */
data class AssistantConfig(
    val id: Uuid,
    val chatModelId: Uuid?,
    val systemPrompt: String,
    val streamOutput: Boolean,
    val enableMemory: Boolean,
    val useGlobalMemory: Boolean,
    val enableRecentChatsReference: Boolean,
    val messageTemplate: String,
    val regexes: List<AssistantRegexRule>,
    val reasoningLevel: ReasoningLevel,
    // 1:1 mirror of the app `Assistant` turn-shaping fields the chat-turn loop reads (issue #243 §C
    // step 9). Plain primitives, no app/Android type, so the §E P1 boundary stays clean.
    val allowConversationSystemPrompt: Boolean,
    val temperature: Float?,
    val topP: Float?,
    val contextMessageSize: Int,
    val maxTokens: Int?,
    val customHeaders: List<CustomHeader>,
    val customBodies: List<CustomBody>,
    val mcpServers: Set<Uuid>,
    val localToolIds: List<String>,
    val enabledSkills: Set<String>,
    val modeInjectionIds: Set<Uuid>,
    val lorebookIds: Set<Uuid>,
    val knowledgeBaseId: Uuid?,
    val description: String,
    val spawnable: Boolean,
    val subagentMaxSteps: Int?,
    // Additive + defaulted (#200 v1): assistants mapped before hooks existed carry the empty,
    // untrusted config, which dispatch treats as passthrough.
    val hooks: HookConfig = HookConfig(),
)

/**
 * Neutral mirror of the app `AssistantRegex` rule. The runtime applies these transforms without
 * importing the app model; `affectingScope` carries the neutral [AssistantRegexScope] tokens.
 */
data class AssistantRegexRule(
    val id: Uuid,
    val name: String,
    val enabled: Boolean,
    val findRegex: String,
    val replaceString: String,
    val affectingScope: Set<AssistantRegexScope>,
    val visualOnly: Boolean,
)

/** Neutral mirror of the app `AssistantAffectScope`. */
enum class AssistantRegexScope { User, Assistant }
