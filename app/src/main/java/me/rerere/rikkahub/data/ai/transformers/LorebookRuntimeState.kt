package me.rerere.rikkahub.data.ai.transformers

import me.rerere.rikkahub.data.model.PromptInjection
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

internal data class TimedLorebookEffect(
    val startMessageCount: Int,
    val endMessageCount: Int,
    val protected: Boolean = false,
)

internal data class LorebookTimedEffectsSnapshot(
    val stickyEntryIds: Set<Uuid> = emptySet(),
    val cooldownEntryIds: Set<Uuid> = emptySet(),
    val delayedEntryIds: Set<Uuid> = emptySet(),
)

class LorebookRuntimeState {
    private val stickyEffects = ConcurrentHashMap<Uuid, TimedLorebookEffect>()
    private val cooldownEffects = ConcurrentHashMap<Uuid, TimedLorebookEffect>()

    internal fun snapshot(
        entries: List<PromptInjection.RegexInjection>,
        messageCount: Int,
    ): LorebookTimedEffectsSnapshot {
        if (entries.isEmpty()) return LorebookTimedEffectsSnapshot()

        val entryMap = entries.associateBy { it.id }
        val stickyEntryIds = mutableSetOf<Uuid>()
        val cooldownEntryIds = mutableSetOf<Uuid>()

        collectActiveEffects(
            store = stickyEffects,
            entryMap = entryMap,
            messageCount = messageCount,
            activeIds = stickyEntryIds,
            onExpired = { entry ->
                val cooldown = entry.timedEffectValue("cooldown") ?: return@collectActiveEffects
                val effect = TimedLorebookEffect(
                    startMessageCount = messageCount,
                    endMessageCount = messageCount + cooldown,
                    protected = true,
                )
                cooldownEffects[entry.id] = effect
                cooldownEntryIds += entry.id
            },
            requiresConfiguration = { entry -> entry.timedEffectValue("sticky") != null },
        )
        collectActiveEffects(
            store = cooldownEffects,
            entryMap = entryMap,
            messageCount = messageCount,
            activeIds = cooldownEntryIds,
            onExpired = {},
            requiresConfiguration = { entry -> entry.timedEffectValue("cooldown") != null },
        )

        val delayedEntryIds = entries.asSequence()
            .filter { entry ->
                entry.timedEffectValue("delay")
                    ?.let { messageCount < it }
                    ?: false
            }
            .map { it.id }
            .toSet()

        return LorebookTimedEffectsSnapshot(
            stickyEntryIds = stickyEntryIds,
            cooldownEntryIds = cooldownEntryIds,
            delayedEntryIds = delayedEntryIds,
        )
    }

    internal fun recordActivatedEntries(
        entries: List<PromptInjection.RegexInjection>,
        messageCount: Int,
    ) {
        if (entries.isEmpty()) return
        entries.forEach { entry ->
            setEffectIfAbsent(
                store = stickyEffects,
                entry = entry,
                key = "sticky",
                messageCount = messageCount,
            )
            setEffectIfAbsent(
                store = cooldownEffects,
                entry = entry,
                key = "cooldown",
                messageCount = messageCount,
            )
        }
    }

    fun clear() {
        stickyEffects.clear()
        cooldownEffects.clear()
    }

    private inline fun collectActiveEffects(
        store: MutableMap<Uuid, TimedLorebookEffect>,
        entryMap: Map<Uuid, PromptInjection.RegexInjection>,
        messageCount: Int,
        activeIds: MutableSet<Uuid>,
        onExpired: (PromptInjection.RegexInjection) -> Unit,
        requiresConfiguration: (PromptInjection.RegexInjection) -> Boolean,
    ) {
        val iterator = store.entries.iterator()
        while (iterator.hasNext()) {
            val (entryId, effect) = iterator.next()
            val entry = entryMap[entryId]
            if (entry == null || !requiresConfiguration(entry)) {
                iterator.remove()
                continue
            }
            if (messageCount <= effect.startMessageCount && !effect.protected) {
                iterator.remove()
                continue
            }
            if (messageCount >= effect.endMessageCount) {
                iterator.remove()
                onExpired(entry)
                continue
            }
            activeIds += entryId
        }
    }

    private fun setEffectIfAbsent(
        store: MutableMap<Uuid, TimedLorebookEffect>,
        entry: PromptInjection.RegexInjection,
        key: String,
        messageCount: Int,
    ) {
        val duration = entry.timedEffectValue(key) ?: return
        store.putIfAbsent(
            entry.id,
            TimedLorebookEffect(
                startMessageCount = messageCount,
                endMessageCount = messageCount + duration,
            ),
        )
    }
}

private fun PromptInjection.RegexInjection.timedEffectValue(key: String): Int? {
    return stMetadata[key]
        ?.trim()
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
}
