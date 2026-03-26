package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LorebookGlobalSettings(
    val scanDepth: Int = 4,
    val minActivations: Int = 0,
    val minActivationsDepthMax: Int = 0,
    val budgetPercent: Int = 25,
    val includeNames: Boolean = true,
    val recursiveScanning: Boolean = false,
    val overflowAlert: Boolean = false,
    val caseSensitive: Boolean = false,
    val matchWholeWords: Boolean = false,
    val useGroupScoring: Boolean = false,
    val characterStrategy: WorldInfoCharacterStrategy = WorldInfoCharacterStrategy.CHARACTER_FIRST,
    val budgetCap: Int = 0,
    val maxRecursionSteps: Int = 0,
) {
    fun normalized(): LorebookGlobalSettings {
        return copy(
            scanDepth = scanDepth.coerceAtLeast(0),
            minActivations = minActivations.coerceAtLeast(0),
            minActivationsDepthMax = minActivationsDepthMax.coerceAtLeast(0),
            budgetPercent = budgetPercent.coerceIn(0, 100),
            budgetCap = budgetCap.coerceAtLeast(0),
            maxRecursionSteps = maxRecursionSteps.coerceAtLeast(0),
        )
    }
}

@Serializable
enum class WorldInfoCharacterStrategy {
    @SerialName("evenly")
    EVENLY,

    @SerialName("character_first")
    CHARACTER_FIRST,

    @SerialName("global_first")
    GLOBAL_FIRST,
}
