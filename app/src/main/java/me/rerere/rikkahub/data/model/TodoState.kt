package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
enum class TodoStatus {
    TODO,
    DOING,
    DONE
}

@Serializable
data class TodoItem(
    val id: String = Uuid.random().toString(),
    val title: String,
    val status: TodoStatus = TodoStatus.TODO,
    val note: String? = null
)

@Serializable
data class TodoState(
    val todos: List<TodoItem> = emptyList(),
    val isEnabled: Boolean = false
)
