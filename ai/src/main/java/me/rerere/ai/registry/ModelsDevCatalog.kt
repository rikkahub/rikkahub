package me.rerere.ai.registry

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.ModelAbility

/**
 * The models.dev catalog (https://models.dev — MIT) bundled as a snapshot at
 * `ai/src/main/resources/models_dev.json` (their provider-agnostic `models.json`). It is a
 * GAP-FILLER: [ModelRegistry]'s curated family matcher takes precedence (it encodes deliberate,
 * sometimes conditional values — e.g. Claude's base vs 1M-beta window), and models.dev supplies an
 * accurate value for the long tail of model ids the registry doesn't hardcode, instead of the
 * conservative 128k default. It never overrides a curated registry value.
 *
 * The snapshot is keyed `"provider/model"`; we index by the bare, lowercased model id so a rikkahub
 * model id (which is bare) resolves regardless of which provider serves it. Parsed once, lazily,
 * from the classloader resource — works on device and in JVM unit tests.
 *
 * To refresh: `curl -s https://models.dev/models.json -o ai/src/main/resources/models_dev.json`.
 */
object ModelsDevCatalog {
    /** The subset of a models.dev entry rikkahub consumes (gap-fill for the registry). */
    data class Spec(
        val contextWindow: Int?,
        val abilities: List<ModelAbility>,
    )

    private const val RESOURCE = "/models_dev.json"
    private val json = Json { ignoreUnknownKeys = true }

    private val byId: Map<String, Spec> by lazy { load() }

    /** Authoritative spec for [modelId] (bare or provider-prefixed), or null if not in the catalog. */
    fun lookup(modelId: String): Spec? = byId[normalize(modelId)]

    private fun normalize(id: String): String = id.substringAfterLast('/').lowercase().trim()

    private fun load(): Map<String, Spec> {
        val stream = ModelsDevCatalog::class.java.getResourceAsStream(RESOURCE) ?: return emptyMap()
        val root = runCatching {
            stream.use { json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject }
        }.getOrNull() ?: return emptyMap()

        val out = HashMap<String, Spec>(root.size)
        for ((key, value) in root) {
            val obj = value as? JsonObject ?: continue
            val bare = normalize(obj["id"]?.jsonPrimitive?.contentOrNull ?: key)
            // First writer wins: the same bare id served by multiple providers carries equivalent facts.
            if (bare in out) continue

            val abilities = buildList {
                if (obj["tool_call"]?.jsonPrimitive?.booleanOrNull == true) add(ModelAbility.TOOL)
                if (obj["reasoning"]?.jsonPrimitive?.booleanOrNull == true) add(ModelAbility.REASONING)
            }
            out[bare] = Spec(
                contextWindow = obj["limit"]?.jsonObject?.get("context")?.jsonPrimitive?.intOrNull,
                abilities = abilities,
            )
        }
        return out
    }
}
