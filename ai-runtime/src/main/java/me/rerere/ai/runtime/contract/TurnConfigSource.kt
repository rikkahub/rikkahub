package me.rerere.ai.runtime.contract

import kotlinx.coroutines.flow.StateFlow
import kotlin.uuid.Uuid

/**
 * Neutral port the runtime reads its per-turn config through (issue #243 §B). The app binds this
 * over its settings store at the composition root; the runtime never sees the persistence layer.
 */
interface TurnConfigSource {
    /** The latest config snapshot, observable for live-config turns. */
    val snapshot: StateFlow<TurnConfig>

    /** The assistant projection for [id], or null if no such assistant exists in the current snapshot. */
    fun assistant(id: Uuid): AssistantConfig?
}
