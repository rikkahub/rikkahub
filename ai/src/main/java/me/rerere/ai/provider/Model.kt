package me.rerere.ai.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class Model(
    val modelId: String = "",
    val displayName: String = "",
    val id: Uuid = Uuid.random(),
    val type: ModelType = ModelType.CHAT,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val inputModalities: List<Modality> = listOf(Modality.TEXT),
    val outputModalities: List<Modality> = listOf(Modality.TEXT),
    /**
     * Capability contract used for request preflight. Null deliberately means legacy data and is
     * derived from [abilities] and modalities, so previously persisted models remain valid.
     */
    val capabilityProfile: ModelCapabilityProfile? = null,
    val abilities: List<ModelAbility> = emptyList(),
    val tools: Set<BuiltInTools> = emptySet(),
    val providerOverwrite: ProviderSetting? = null,
    /** Optional provider-advertised context window; null uses the governor's conservative default. */
    val contextWindowTokens: Int? = null,
)

@Serializable
enum class ModelType {
    CHAT,
    IMAGE,
    EMBEDDING,
}

@Serializable
enum class Modality {
    TEXT,
    IMAGE,
}

@Serializable
enum class ModelAbility {
    TOOL,
    REASONING,
}

/** How reliably a provider preserves function-call IDs across streamed chunks and follow-up turns. */
@Serializable
enum class ToolCallIdStability {
    UNKNOWN,
    STABLE,
    UNSTABLE,
}

/**
 * A provider/model contract. Null token limits mean that the adapter did not receive an
 * authoritative value; callers must not invent a limit.
 */
@Serializable
data class ModelCapabilityProfile(
    val contextWindowTokens: Int? = null,
    val maxOutputTokens: Int? = null,
    val toolCalling: Boolean = false,
    val parallelToolCalls: Boolean = false,
    val structuredOutputJsonSchema: Boolean = false,
    val streaming: Boolean = false,
    val reasoning: Boolean = false,
    val multimodalInput: Boolean = false,
    val multimodalOutput: Boolean = false,
    val providerNativeTools: Boolean = false,
    val nativeToolsCompatibleWithFunctionTools: Boolean = false,
    val toolCallIdStability: ToolCallIdStability = ToolCallIdStability.UNKNOWN,
) {
    fun validationError(): String? = when {
        contextWindowTokens != null && contextWindowTokens <= 0 -> "contextWindowTokens must be positive"
        maxOutputTokens != null && maxOutputTokens <= 0 -> "maxOutputTokens must be positive"
        contextWindowTokens != null && maxOutputTokens != null && maxOutputTokens > contextWindowTokens ->
            "maxOutputTokens cannot exceed contextWindowTokens"
        parallelToolCalls && !toolCalling -> "parallelToolCalls requires toolCalling"
        toolCallIdStability != ToolCallIdStability.UNKNOWN && !toolCalling ->
            "toolCallIdStability requires toolCalling"
        nativeToolsCompatibleWithFunctionTools && !providerNativeTools ->
            "nativeToolsCompatibleWithFunctionTools requires providerNativeTools"
        else -> null
    }

    companion object {
        fun fromLegacy(model: Model): ModelCapabilityProfile = ModelCapabilityProfile(
            contextWindowTokens = model.contextWindowTokens,
            toolCalling = ModelAbility.TOOL in model.abilities,
            reasoning = ModelAbility.REASONING in model.abilities,
            streaming = true,
            multimodalInput = Modality.IMAGE in model.inputModalities,
            multimodalOutput = Modality.IMAGE in model.outputModalities,
            providerNativeTools = model.tools.isNotEmpty(),
            // Legacy tool execution already relied on provider call IDs; retain that behavior.
            toolCallIdStability = if (ModelAbility.TOOL in model.abilities) {
                ToolCallIdStability.STABLE
            } else {
                ToolCallIdStability.UNKNOWN
            },
        )
    }
}

/** Returns an explicitly configured profile or the compatibility contract inferred from old data. */
fun Model.effectiveCapabilityProfile(): ModelCapabilityProfile =
    capabilityProfile ?: ModelCapabilityProfile.fromLegacy(this)

/** Read-only max-output API for context governors and request planners. */
val Model.effectiveMaxOutputTokens: Int?
    get() = effectiveCapabilityProfile().maxOutputTokens

/** Safe API for custom-model editors and importers. Invalid overrides are rejected at the boundary. */
fun Model.withCapabilityProfile(profile: ModelCapabilityProfile?): Model {
    require(profile?.validationError() == null) { profile?.validationError() ?: "Invalid capability profile" }
    return copy(capabilityProfile = profile)
}

// 模型(提供商)提供的内置工具选项
@Serializable
sealed class BuiltInTools {
    // https://ai.google.dev/gemini-api/docs/google-search?hl=zh-cn
    @Serializable
    @SerialName("search")
    data object Search : BuiltInTools()

    // https://ai.google.dev/gemini-api/docs/url-context?hl=zh-cn
    @Serializable
    @SerialName("url_context")
    data object UrlContext : BuiltInTools()

    @Serializable
    @SerialName("image_generation")
    data object ImageGeneration : BuiltInTools()
}



