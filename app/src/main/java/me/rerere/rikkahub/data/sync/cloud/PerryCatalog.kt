package me.rerere.rikkahub.data.sync.cloud

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.registry.ModelRegistry
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.uuid.Uuid

@Serializable
data class CatalogProviderDto(
    val id: String,
    val name: String = "",
    @SerialName("base_url") val baseUrl: String = "",
)

@Serializable
data class CatalogModelDto(
    @SerialName("provider_id") val providerId: String,
    val model: String,
    val name: String = "",
    @SerialName("model_uuid") val modelUuid: String = "",
)

@Serializable
data class CatalogModelEntryDto(
    val id: String,
    @SerialName("model_uuid") val modelUuid: String = "",
)

@Serializable
data class CatalogProviderModelsDto(
    @SerialName("provider_id") val providerId: String,
    val name: String = "",
    val models: List<String> = emptyList(),
    @SerialName("model_entries") val modelEntries: List<CatalogModelEntryDto> = emptyList(),
    val status: String = "ok",
    val error: String? = null,
)

object PerryCatalog {
    const val PATH_MARKER = "/v1/ai/"

    fun isPerryGateway(provider: ProviderSetting): Boolean {
        val base = when (provider) {
            is ProviderSetting.OpenAI -> provider.baseUrl
            else -> return false
        }
        return base.contains(PATH_MARKER)
    }

    fun monelProviderIdFromBaseUrl(baseUrl: String): String? {
        val idx = baseUrl.indexOf(PATH_MARKER)
        if (idx < 0) return null
        val rest = baseUrl.substring(idx + PATH_MARKER.length).trim('/')
        val id = rest.substringBefore('/')
        return id.takeIf { it.isNotBlank() }
    }

    fun providerUuid(monelProviderId: String): Uuid {
        return stableUuid("perry-provider:$monelProviderId")
    }

    fun modelUuid(raw: String, monelProviderId: String, modelId: String): Uuid {
        if (raw.isNotBlank()) {
            runCatching { Uuid.parse(raw) }.getOrNull()?.let { return it }
        }
        return stableUuid("perry-model:$monelProviderId:$modelId")
    }

    fun toOpenAIProvider(
        dto: CatalogProviderDto,
        perryBaseUrlForProvider: String,
        deviceToken: String,
        existing: ProviderSetting.OpenAI? = null,
    ): ProviderSetting.OpenAI {
        val id = existing?.id ?: providerUuid(dto.id)
        val name = existing?.name?.takeIf { it.isNotBlank() && !it.startsWith("Perry /") }
            ?: "Perry / ${dto.name.ifBlank { dto.id }}"
        return ProviderSetting.OpenAI(
            id = id,
            enabled = existing?.enabled ?: true,
            name = name,
            models = existing?.models ?: emptyList(),
            apiKey = deviceToken,
            baseUrl = perryBaseUrlForProvider,
            chatCompletionsPath = "/chat/completions",
            useResponseApi = false,
            includeHistoryReasoning = existing?.includeHistoryReasoning ?: true,
            balanceOption = existing?.balanceOption ?: me.rerere.ai.provider.BalanceOption(),
        )
    }

    fun toBrowseModels(
        monelProviderId: String,
        entries: List<CatalogModelEntryDto>,
        fallbackIds: List<String> = emptyList(),
    ): List<Model> {
        val fromEntries = entries.mapNotNull { entry ->
            val modelId = entry.id.trim()
            if (modelId.isEmpty()) return@mapNotNull null
            Model(
                id = modelUuid(entry.modelUuid, monelProviderId, modelId),
                modelId = modelId,
                displayName = modelId,
                inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(modelId),
                outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(modelId),
                abilities = ModelRegistry.MODEL_ABILITIES.getData(modelId),
            )
        }
        if (fromEntries.isNotEmpty()) return fromEntries.sortedBy { it.modelId }
        return fallbackIds.mapNotNull { modelId ->
            val id = modelId.trim()
            if (id.isEmpty()) return@mapNotNull null
            Model(
                id = modelUuid("", monelProviderId, id),
                modelId = id,
                displayName = id,
                inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(id),
                outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(id),
                abilities = ModelRegistry.MODEL_ABILITIES.getData(id),
            )
        }.sortedBy { it.modelId }
    }

    fun toBrowseModelsFromFlat(
        monelProviderId: String,
        models: List<CatalogModelDto>,
    ): List<Model> {
        return models
            .filter { it.providerId == monelProviderId }
            .map { dto ->
                val modelId = dto.model
                Model(
                    id = modelUuid(dto.modelUuid, monelProviderId, modelId),
                    modelId = modelId,
                    displayName = dto.name.ifBlank { modelId },
                    inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(modelId),
                    outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(modelId),
                    abilities = ModelRegistry.MODEL_ABILITIES.getData(modelId),
                )
            }
            .sortedBy { it.modelId }
    }

    fun mergeProviders(
        existing: List<ProviderSetting>,
        imported: List<ProviderSetting.OpenAI>,
    ): List<ProviderSetting> {
        val byId = existing.associateBy { it.id }.toMutableMap()
        val order = existing.map { it.id }.toMutableList()
        for (incoming in imported) {
            val prev = byId[incoming.id]
            if (prev is ProviderSetting.OpenAI) {
                byId[incoming.id] = incoming.copy(
                    models = prev.models,
                    enabled = prev.enabled,
                    name = if (prev.name.isNotBlank()) prev.name else incoming.name,
                    includeHistoryReasoning = prev.includeHistoryReasoning,
                    balanceOption = prev.balanceOption,
                )
            } else if (prev == null) {
                byId[incoming.id] = incoming
                order.add(0, incoming.id)
            }
        }
        return order.mapNotNull { byId[it] } + byId.keys.filter { it !in order.toSet() }.mapNotNull { byId[it] }
    }

    fun refreshPerryCredentials(
        providers: List<ProviderSetting>,
        resolveBaseUrl: (monelProviderId: String) -> String,
        deviceToken: String,
    ): List<ProviderSetting> {
        if (deviceToken.isBlank()) return providers
        return providers.map { provider ->
            if (provider !is ProviderSetting.OpenAI || !isPerryGateway(provider)) return@map provider
            val monelId = monelProviderIdFromBaseUrl(provider.baseUrl) ?: return@map provider
            provider.copy(
                baseUrl = resolveBaseUrl(monelId),
                apiKey = deviceToken,
                useResponseApi = false,
            )
        }
    }

    private fun stableUuid(seed: String): Uuid {
        return Uuid.parse(UUID.nameUUIDFromBytes(seed.toByteArray(StandardCharsets.UTF_8)).toString())
    }
}
