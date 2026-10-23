package me.rerere.ai.registry

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility
import java.io.InputStream
import java.io.OutputStream

class ModelInfo(
    val inputModalities: Set<Modality>,
    val outputModalities: Set<Modality>,
    val abilities: Set<ModelAbility>,
    val contextLength: Int?,
)

/**
 * models.dev (https://models.dev/api.json) 的模型数据。
 *
 * 同一个模型会出现在多个提供商下，id 写法也各不相同（如 `claude-sonnet-4-5`、`anthropic/claude-sonnet-4.5`），
 * 这里把每条记录按完整 id、去掉前缀后的名称、canonical id 建索引，同一个 key 下的多条记录按多数投票合并。
 */
class ModelsDevCatalog private constructor(
    private val providers: Map<String, ModelsDevProvider>
) {
    private val index: Map<String, ModelInfo> = buildIndex(providers)

    val size: Int get() = index.size

    fun find(modelId: String): ModelInfo? {
        val id = modelId.trim().lowercase()
        if (id.isEmpty()) return null
        val name = id.substringAfterLast('/')
        // OpenRouter 等用 `:free`、`:thinking` 之类的后缀区分同一模型的不同渠道
        return index[id]
            ?: index[id.substringBefore(':')]
            ?: index[name]
            ?: index[name.substringBefore(':')]
    }

    /** 写出只含匹配所需字段的 JSON，格式仍是 models.dev api.json 的子集，可再用 [decode] 读回 */
    @OptIn(ExperimentalSerializationApi::class)
    fun encodeTo(output: OutputStream) {
        json.encodeToStream(providers, output)
    }

    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        fun decode(input: InputStream): ModelsDevCatalog {
            val providers = json.decodeFromStream<Map<String, ModelsDevProvider>>(input)
            return ModelsDevCatalog(providers.mapValues { (_, provider) -> provider.slim() })
        }
    }
}

private val json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

@Serializable
private data class ModelsDevProvider(
    val models: Map<String, ModelsDevModel> = emptyMap()
) {
    fun slim() = copy(models = models.mapValues { (_, model) -> model.slim() })
}

@Serializable
private data class ModelsDevModel(
    val modalities: ModelsDevModalities = ModelsDevModalities(),
    @SerialName("tool_call")
    val toolCall: Boolean = false,
    val reasoning: Boolean = false,
    val limit: ModelsDevLimit? = null,
    @SerialName("canonical_model_id")
    val canonicalModelId: String? = null,
) {
    fun slim() = copy(
        modalities = ModelsDevModalities(
            input = modalities.input.filter { it in USED_MODALITIES },
            output = modalities.output.filter { it in USED_MODALITIES },
        ),
        limit = limit?.takeIf { it.context != null },
    )
}

@Serializable
private data class ModelsDevModalities(
    val input: List<String> = listOf("text"),
    val output: List<String> = listOf("text"),
)

@Serializable
private data class ModelsDevLimit(
    val context: Int? = null,
)

private val USED_MODALITIES = setOf("text", "image")

private class Votes {
    var total = 0
    var imageInput = 0
    var imageOutput = 0
    var tool = 0
    var reasoning = 0
    val contexts = HashMap<Int, Int>()

    fun add(model: ModelsDevModel) {
        total += 1
        if ("image" in model.modalities.input) imageInput += 1
        if ("image" in model.modalities.output) imageOutput += 1
        if (model.toolCall) tool += 1
        if (model.reasoning) reasoning += 1
        model.limit?.context?.takeIf { it > 0 }?.let { contexts.merge(it, 1, Int::plus) }
    }

    // 各家数据出错多是漏标能力，所以票数持平时算支持
    private fun majority(count: Int) = count * 2 >= total

    fun toModelInfo() = ModelInfo(
        inputModalities = if (majority(imageInput)) setOf(Modality.TEXT, Modality.IMAGE) else setOf(Modality.TEXT),
        outputModalities = if (majority(imageOutput)) setOf(Modality.TEXT, Modality.IMAGE) else setOf(Modality.TEXT),
        abilities = buildSet {
            if (majority(tool)) add(ModelAbility.TOOL)
            if (majority(reasoning)) add(ModelAbility.REASONING)
        },
        contextLength = contexts.maxWithOrNull(compareBy({ it.value }, { it.key }))?.key,
    )
}

private fun buildIndex(providers: Map<String, ModelsDevProvider>): Map<String, ModelInfo> {
    val votes = HashMap<String, Votes>()
    for (provider in providers.values) {
        for ((id, model) in provider.models) {
            val keys = buildSet {
                val lower = id.lowercase()
                add(lower)
                add(lower.substringAfterLast('/'))
                model.canonicalModelId?.lowercase()?.let {
                    add(it)
                    add(it.substringAfterLast('/'))
                }
            }
            for (key in keys) {
                if (key.isNotEmpty()) votes.getOrPut(key, ::Votes).add(model)
            }
        }
    }
    return votes.mapValues { (_, it) -> it.toModelInfo() }
}
