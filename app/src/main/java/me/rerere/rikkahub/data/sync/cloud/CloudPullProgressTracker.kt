package me.rerere.rikkahub.data.sync.cloud

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Process-scoped state for non-blocking server pull feedback. */
class CloudPullProgressTracker {
    data class State(
        val conversationPullCount: Int = 0,
        val dismissedForSession: Boolean = false,
    ) {
        val isPullingConversations: Boolean get() = conversationPullCount > 0
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun beginConversationPull() {
        _state.update { it.copy(conversationPullCount = it.conversationPullCount + 1) }
    }

    fun endConversationPull() {
        _state.update {
            it.copy(conversationPullCount = (it.conversationPullCount - 1).coerceAtLeast(0))
        }
    }

    fun dismissForSession() {
        _state.update { it.copy(dismissedForSession = true) }
    }
}
