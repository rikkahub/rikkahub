package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

@Serializable
data class StLorebookEntryExtension(
    val group: String = "",
    val groupOverride: Boolean = false,
    val groupWeight: Int? = null,
    val useGroupScoring: Boolean = false,
    val sticky: Int? = null,
    val cooldown: Int? = null,
    val delay: Int? = null,
    val delayUntilRecursion: String = "",
    val triggers: List<String> = emptyList(),
    val outletName: String = "",
    val useProbability: Boolean? = null,
    val ignoreBudget: Boolean = false,
    val excludeRecursion: Boolean = false,
    val preventRecursion: Boolean = false,
    val vectorized: Boolean = false,
    val automationId: String = "",
    val extra: Map<String, String> = emptyMap(),
) {
    fun toMetadataMap(): Map<String, String> {
        return buildMap {
            putAll(extra)
            if (group.isNotBlank()) put(KEY_GROUP, group)
            if (groupOverride) put(KEY_GROUP_OVERRIDE, "true")
            groupWeight?.let { put(KEY_GROUP_WEIGHT, it.toString()) }
            if (useGroupScoring) put(KEY_USE_GROUP_SCORING, "true")
            sticky?.let { put(KEY_STICKY, it.toString()) }
            cooldown?.let { put(KEY_COOLDOWN, it.toString()) }
            delay?.let { put(KEY_DELAY, it.toString()) }
            if (delayUntilRecursion.isNotBlank()) put(KEY_DELAY_UNTIL_RECURSION, delayUntilRecursion)
            if (triggers.isNotEmpty()) {
                put(
                    KEY_TRIGGERS,
                    buildJsonArray {
                        triggers.forEach { add(JsonPrimitive(it)) }
                    }.toString(),
                )
            }
            if (outletName.isNotBlank()) put(KEY_OUTLET_NAME, outletName)
            useProbability?.let { put(KEY_USE_PROBABILITY, it.toString()) }
            if (ignoreBudget) put(KEY_IGNORE_BUDGET, "true")
            if (excludeRecursion) put(KEY_EXCLUDE_RECURSION, "true")
            if (preventRecursion) put(KEY_PREVENT_RECURSION, "true")
            if (vectorized) put(KEY_VECTORIZED, "true")
            if (automationId.isNotBlank()) put(KEY_AUTOMATION_ID, automationId)
        }
    }

    fun groupNames(): List<String> {
        return group
            .split(',')
            .mapNotNull { value -> value.trim().takeIf { it.isNotBlank() } }
    }

    fun recursionDelayLevel(): Int? {
        val rawValue = delayUntilRecursion.trim()
        if (rawValue.isEmpty() || rawValue.equals("false", ignoreCase = true) || rawValue == "0") {
            return null
        }
        if (rawValue.equals("true", ignoreCase = true)) {
            return 1
        }
        return rawValue.toIntOrNull()?.takeIf { it > 0 }
    }

    companion object {
        private val KNOWN_KEYS = setOf(
            KEY_GROUP,
            KEY_GROUP_OVERRIDE,
            KEY_GROUP_WEIGHT,
            KEY_USE_GROUP_SCORING,
            KEY_STICKY,
            KEY_COOLDOWN,
            KEY_DELAY,
            KEY_DELAY_UNTIL_RECURSION,
            KEY_TRIGGERS,
            KEY_OUTLET_NAME,
            KEY_USE_PROBABILITY,
            KEY_IGNORE_BUDGET,
            KEY_EXCLUDE_RECURSION,
            KEY_PREVENT_RECURSION,
            KEY_VECTORIZED,
            KEY_AUTOMATION_ID,
        )

        fun fromMetadata(metadata: Map<String, String>): StLorebookEntryExtension {
            return StLorebookEntryExtension(
                group = metadata[KEY_GROUP].orEmpty(),
                groupOverride = metadata.booleanValue(KEY_GROUP_OVERRIDE) == true,
                groupWeight = metadata.intValue(KEY_GROUP_WEIGHT),
                useGroupScoring = metadata.booleanValue(KEY_USE_GROUP_SCORING) == true,
                sticky = metadata.intValue(KEY_STICKY),
                cooldown = metadata.intValue(KEY_COOLDOWN),
                delay = metadata.intValue(KEY_DELAY),
                delayUntilRecursion = metadata[KEY_DELAY_UNTIL_RECURSION].orEmpty().trim(),
                triggers = metadata.listValue(KEY_TRIGGERS),
                outletName = metadata[KEY_OUTLET_NAME].orEmpty(),
                useProbability = metadata.booleanValue(KEY_USE_PROBABILITY),
                ignoreBudget = metadata.booleanValue(KEY_IGNORE_BUDGET) == true,
                excludeRecursion = metadata.booleanValue(KEY_EXCLUDE_RECURSION) == true,
                preventRecursion = metadata.booleanValue(KEY_PREVENT_RECURSION) == true,
                vectorized = metadata.booleanValue(KEY_VECTORIZED) == true,
                automationId = metadata[KEY_AUTOMATION_ID].orEmpty(),
                extra = metadata.filterKeys { it !in KNOWN_KEYS },
            )
        }
    }
}

fun PromptInjection.RegexInjection.stExtension(): StLorebookEntryExtension {
    return StLorebookEntryExtension.fromMetadata(stMetadata)
}

fun PromptInjection.RegexInjection.withStExtension(
    extension: StLorebookEntryExtension,
): PromptInjection.RegexInjection {
    return copy(stMetadata = extension.toMetadataMap())
}

inline fun PromptInjection.RegexInjection.updateStExtension(
    transform: (StLorebookEntryExtension) -> StLorebookEntryExtension,
): PromptInjection.RegexInjection {
    return withStExtension(transform(stExtension()))
}

private fun Map<String, String>.booleanValue(key: String): Boolean? {
    return this[key]?.trim()?.let { value ->
        when {
            value.equals("true", ignoreCase = true) || value == "1" -> true
            value.equals("false", ignoreCase = true) || value == "0" -> false
            else -> null
        }
    }
}

private fun Map<String, String>.intValue(key: String): Int? {
    return this[key]?.trim()?.toIntOrNull()
}

private fun Map<String, String>.listValue(key: String): List<String> {
    return Regex("[,\\n]")
        .split(this[key].orEmpty())
        .mapNotNull { value ->
            value
                .trim()
                .removePrefix("[")
                .removeSuffix("]")
                .removePrefix("\"")
                .removeSuffix("\"")
                .takeIf { it.isNotBlank() }
        }
        .distinct()
}

private const val KEY_GROUP = "group"
private const val KEY_GROUP_OVERRIDE = "group_override"
private const val KEY_GROUP_WEIGHT = "group_weight"
private const val KEY_USE_GROUP_SCORING = "use_group_scoring"
private const val KEY_STICKY = "sticky"
private const val KEY_COOLDOWN = "cooldown"
private const val KEY_DELAY = "delay"
private const val KEY_DELAY_UNTIL_RECURSION = "delay_until_recursion"
private const val KEY_TRIGGERS = "triggers"
private const val KEY_OUTLET_NAME = "outlet_name"
private const val KEY_USE_PROBABILITY = "useProbability"
private const val KEY_IGNORE_BUDGET = "ignore_budget"
private const val KEY_EXCLUDE_RECURSION = "exclude_recursion"
private const val KEY_PREVENT_RECURSION = "prevent_recursion"
private const val KEY_VECTORIZED = "vectorized"
private const val KEY_AUTOMATION_ID = "automation_id"
