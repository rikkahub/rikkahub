package me.rerere.rikkahub.ui.pages.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.rerere.rikkahub.service.ChatFailure
import kotlin.uuid.Uuid

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val solution: ChatErrorSolution? = null,
)

enum class ChatErrorSolution {
    CheckTitleModelSettings,
}

class ChatErrorPresenter {
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun present(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        _errors.update {
            it + ChatError(
                title = title,
                error = error,
                conversationId = conversationId,
                solution = solution
            )
        }
    }

    fun present(failure: ChatFailure, title: String? = null) {
        present(
            error = IllegalStateException(failure.message),
            conversationId = failure.conversationId,
            title = title,
        )
    }

    fun dismiss(id: Uuid) {
        _errors.update { errors -> errors.filterNot { it.id == id } }
    }

    fun clear() {
        _errors.value = emptyList()
    }
}
