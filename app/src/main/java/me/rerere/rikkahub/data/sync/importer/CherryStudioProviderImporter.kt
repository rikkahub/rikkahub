package me.rerere.rikkahub.data.sync.importer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.registry.ModelRegistry
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import java.util.zip.ZipFile

object CherryStudioProviderImporter {
    fun importProviders(file: File): List<ProviderSetting> {
        val dataJson = ZipFile(file).use { zip ->
            val entry = zip.getEntry("data.json")
                ?: throw IllegalArgumentException("Invalid Cherry Studio backup: data.json not found")
            zip.getInputStream(entry).bufferedReader().use { it.readText() }
        }

        val root = JsonInstant.parseToJsonElement(dataJson).jsonObject
        val persistedRaw = root["localStorage"]
            ?.jsonObject
            ?.get("persist:cherry-studio")
            ?.jsonPrimitive
            ?.contentOrNull
            ?: throw IllegalArgumentException("Invalid Cherry Studio backup: persist data missing")
        val persisted = JsonInstant.parseToJsonElement(persistedRaw).jsonObject

        val llmRaw = persisted["llm"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("Invalid Cherry Studio backup: llm settings missing")
        val llm = JsonInstant.parseToJsonElement(llmRaw).jsonObject

        return llm["providers"]?.jsonArray
            ?.mapNotNull { it.jsonObjectOrNull?.let(::parseProvider) }
            ?.distinctBy { importedProviderKey(it) }
            .orEmpty()
    }

    private fun parseProvider(provider: JsonObject): ProviderSetting? {
        val apiKey = provider["apiKey"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (apiKey.isBlank()) return null

        val type = provider["type"]?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
        val name = provider["name"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: "Cherry Studio"
        val apiHost = provider["apiHost"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val enabled = provider["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
        val models = parseModels(provider["models"]?.jsonArray)

        return when (type) {
            "anthropic" -> ProviderSetting.Claude(
                name = name,
                enabled = enabled,
                baseUrl = normalizeBaseUrl(
                    apiHost = apiHost,
                    suffix = "/v1",
                    fallback = ProviderSetting.Claude().baseUrl
                ),
                apiKey = apiKey,
                models = models,
            )

            "gemini", "vertexai" -> ProviderSetting.Google(
                name = name,
                enabled = enabled,
                baseUrl = normalizeBaseUrl(
                    apiHost = apiHost,
                    suffix = "/v1beta",
                    fallback = ProviderSetting.Google().baseUrl
                ),
                apiKey = apiKey,
                models = models,
            )

            else -> {
                val useResponseApi = type == "openai-response" || provider["models"]?.jsonArray?.any {
                    it.jsonObjectOrNull?.get("endpoint_type")?.jsonPrimitive?.contentOrNull == "openai-response"
                } == true
                ProviderSetting.OpenAI(
                    name = name,
                    enabled = enabled,
                    baseUrl = normalizeBaseUrl(
                        apiHost = apiHost,
                        suffix = "/v1",
                        fallback = ProviderSetting.OpenAI().baseUrl
                    ),
                    apiKey = apiKey,
                    models = models,
                    useResponseApi = useResponseApi,
                )
            }
        }
    }

    private fun parseModels(models: JsonArray?): List<Model> {
        if (models == null) return emptyList()
        return models.mapNotNull { modelElement ->
            val model = modelElement.jsonObjectOrNull ?: return@mapNotNull null
            val modelId = model["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (modelId.isBlank()) return@mapNotNull null

            val displayName = model["name"]?.jsonPrimitive?.contentOrNull?.ifBlank { modelId } ?: modelId
            Model(
                modelId = modelId,
                displayName = displayName,
                inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(modelId),
                outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(modelId),
                abilities = ModelRegistry.MODEL_ABILITIES.getData(modelId),
            )
        }
    }

    private fun normalizeBaseUrl(apiHost: String, suffix: String, fallback: String): String {
        val normalizedHost = apiHost.trim().trimEnd('/')
        if (normalizedHost.isBlank()) return fallback
        return if (normalizedHost.endsWith(suffix)) normalizedHost else "$normalizedHost$suffix"
    }

    private fun importedProviderKey(provider: ProviderSetting): String {
        return when (provider) {
            is ProviderSetting.OpenAI -> "openai|${provider.baseUrl}|${provider.apiKey}"
            is ProviderSetting.Google -> "google|${provider.baseUrl}|${provider.apiKey}"
            is ProviderSetting.Claude -> "claude|${provider.baseUrl}|${provider.apiKey}"
        }
    }
}
