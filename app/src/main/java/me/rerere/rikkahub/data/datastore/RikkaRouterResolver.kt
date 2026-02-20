package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.model.RikkaRouterGroup
import kotlin.uuid.Uuid

val RIKKA_ROUTER_PROVIDER_ID: Uuid = Uuid.parse("5f8ce857-8157-46ac-b8e7-d70f2107a7f8")
const val RIKKA_ROUTER_PROVIDER_NAME = "rikkarouter"

data class RikkaRouterCandidate(
    val provider: ProviderSetting,
    val model: Model,
)

fun Settings.getRikkaRouterModels(includeDisabledGroups: Boolean = true): List<Model> {
    val groups = if (includeDisabledGroups) {
        rikkaRouter.groups
    } else {
        rikkaRouter.groups.filter { it.enabled }
    }
    return groups.map { group ->
        val candidates = resolveRikkaRouterCandidates(group)
        val abilities = candidates.flatMap { it.model.abilities }.distinct()
        val inputModalities = candidates.flatMap { it.model.inputModalities }.distinct().ifEmpty { listOf(Modality.TEXT) }
        val outputModalities = candidates.flatMap { it.model.outputModalities }.distinct().ifEmpty { listOf(Modality.TEXT) }
        Model(
            id = group.id,
            modelId = "rikkarouter/${group.name.ifBlank { group.id.toString() }}",
            displayName = group.name.ifBlank { "rikkarouter" },
            type = ModelType.CHAT,
            abilities = abilities,
            inputModalities = inputModalities,
            outputModalities = outputModalities,
        )
    }
}

fun Settings.findRikkaRouterGroupByModelId(modelId: Uuid): RikkaRouterGroup? {
    return rikkaRouter.groups.firstOrNull { it.id == modelId }
}

fun Settings.resolveRikkaRouterCandidatesByModelId(modelId: Uuid): List<RikkaRouterCandidate> {
    val group = findRikkaRouterGroupByModelId(modelId) ?: return emptyList()
    return resolveRikkaRouterCandidates(group)
}

fun Settings.resolveRikkaRouterCandidates(group: RikkaRouterGroup): List<RikkaRouterCandidate> {
    if (!rikkaRouter.enabled || !group.enabled) return emptyList()

    val modelProviderMap = buildMap<Uuid, Pair<ProviderSetting, Model>> {
        providers.forEach { provider ->
            provider.models.forEach { model ->
                put(model.id, provider to model)
            }
        }
    }

    val orderedMembers = group.members
        .filter { it.enabled }
        .sortedWith(
            compareBy(
                { it.modelId != group.primaryModelId },
                { it.order }
            )
        )

    return orderedMembers
        .mapNotNull { member ->
            val pair = modelProviderMap[member.modelId] ?: return@mapNotNull null
            val provider = pair.first
            val model = pair.second
            if (!provider.enabled || model.type != ModelType.CHAT) return@mapNotNull null
            RikkaRouterCandidate(provider = provider, model = model)
        }
        .distinctBy { "${it.provider.id}:${it.model.id}" }
}

fun Settings.findRikkaRouterMatches(groupName: String): List<Pair<ProviderSetting, Model>> {
    val target = normalizeModelKey(groupName)
    if (target.isBlank()) return emptyList()
    return buildList {
        providers.forEach { provider ->
            provider.models
                .asSequence()
                .filter { it.type == ModelType.CHAT }
                .filter { model ->
                    val display = normalizeModelKey(model.displayName)
                    val modelId = normalizeModelKey(model.modelId)
                    (display.isNotBlank() && (display.contains(target) || target.contains(display))) ||
                        modelId.contains(target) || target.contains(modelId)
                }
                .forEach { model -> add(provider to model) }
        }
    }.distinctBy { (provider, model) -> "${provider.id}:${model.id}" }
}

fun Settings.buildRikkaRouterVirtualProvider(includeDisabledGroups: Boolean = false): ProviderSetting.OpenAI {
    return ProviderSetting.OpenAI(
        id = RIKKA_ROUTER_PROVIDER_ID,
        enabled = rikkaRouter.enabled,
        name = RIKKA_ROUTER_PROVIDER_NAME,
        models = getRikkaRouterModels(includeDisabledGroups = includeDisabledGroups),
        apiKey = "",
        baseUrl = "",
        builtIn = true,
        description = {},
        shortDescription = {},
    )
}

private fun normalizeModelKey(text: String): String {
    return text
        .trim()
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "")
}
