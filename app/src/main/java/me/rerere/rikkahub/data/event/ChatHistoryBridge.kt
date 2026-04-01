package me.rerere.rikkahub.data.event

import android.os.Handler
import android.os.Looper
import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.utils.JsonInstant

@Serializable
data class ChatHistorySnapshot(
    val conversationId: String = "",
    val userName: String = "User",
    val assistantName: String = "Assistant",
    val messages: List<ChatHistoryMessageSnapshot> = emptyList(),
)

@Serializable
data class ChatHistoryMessageSnapshot(
    val index: Int,
    val nodeId: String,
    val messageId: String,
    val role: String,
    val name: String,
    val text: String,
    val swipeIndex: Int,
    val swipes: List<String> = emptyList(),
)

fun Conversation.toChatHistorySnapshot(
    userName: String = "User",
    assistantName: String = "Assistant",
): ChatHistorySnapshot {
    val resolvedUserName = userName.ifBlank { "User" }
    val resolvedAssistantName = assistantName.ifBlank { "Assistant" }

    return ChatHistorySnapshot(
        conversationId = id.toString(),
        userName = resolvedUserName,
        assistantName = resolvedAssistantName,
        messages = messageNodes.mapIndexedNotNull { index, node ->
            val currentMessage = node.messages.getOrNull(node.selectIndex)
                ?: node.messages.lastOrNull()
                ?: return@mapIndexedNotNull null
            ChatHistoryMessageSnapshot(
                index = index,
                nodeId = node.id.toString(),
                messageId = currentMessage.id.toString(),
                role = currentMessage.role.name.lowercase(),
                name = currentMessage.role.toHistoryName(
                    userName = resolvedUserName,
                    assistantName = resolvedAssistantName,
                ),
                text = currentMessage.toText(),
                swipeIndex = node.selectIndex.coerceIn(0, node.messages.lastIndex),
                swipes = node.messages.map(UIMessage::toText),
            )
        },
    )
}

fun ChatHistorySnapshot.toJsonString(): String {
    return JsonInstant.encodeToString(ChatHistorySnapshot.serializer(), this)
}

fun List<UIMessagePart>.replaceLastTextPart(text: String): List<UIMessagePart> {
    val lastTextIndex = indexOfLast { it is UIMessagePart.Text }
    if (lastTextIndex == -1) {
        return if (text.isBlank()) {
            this
        } else {
            this + UIMessagePart.Text(text)
        }
    }

    return mapIndexed { index, part ->
        if (index == lastTextIndex && part is UIMessagePart.Text) {
            part.copy(text = text)
        } else {
            part
        }
    }
}

class ChatHistoryBridge {
    interface Delegate {
        fun editMessage(nodeId: String, text: String)
        fun deleteMessage(nodeId: String)
        fun selectMessageNode(nodeId: String, selectIndex: Int)
        fun regenerateMessage(nodeId: String, regenerateAssistantMessage: Boolean)
        fun continueMessage(nodeId: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var delegate: Delegate? = null

    @Volatile
    private var snapshotJson: String = ChatHistorySnapshot().toJsonString()

    fun register(delegate: Delegate) {
        this.delegate = delegate
    }

    fun unregister(delegate: Delegate) {
        if (this.delegate === delegate) {
            this.delegate = null
        }
    }

    fun updateSnapshot(snapshot: ChatHistorySnapshot) {
        snapshotJson = snapshot.toJsonString()
    }

    fun getSnapshotJson(): String = snapshotJson

    fun editMessage(nodeId: String, text: String) {
        if (nodeId.isBlank()) return
        postToMain {
            delegate?.editMessage(nodeId, text)
        }
    }

    fun deleteMessage(nodeId: String) {
        if (nodeId.isBlank()) return
        postToMain {
            delegate?.deleteMessage(nodeId)
        }
    }

    fun selectMessageNode(nodeId: String, selectIndex: Int) {
        if (nodeId.isBlank()) return
        postToMain {
            delegate?.selectMessageNode(nodeId, selectIndex)
        }
    }

    fun regenerateMessage(nodeId: String, regenerateAssistantMessage: Boolean = true) {
        if (nodeId.isBlank()) return
        postToMain {
            delegate?.regenerateMessage(nodeId, regenerateAssistantMessage)
        }
    }

    fun continueMessage(nodeId: String) {
        if (nodeId.isBlank()) return
        postToMain {
            delegate?.continueMessage(nodeId)
        }
    }

    private fun postToMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }
}

private fun MessageRole.toHistoryName(
    userName: String,
    assistantName: String,
): String {
    return when (this) {
        MessageRole.USER -> userName
        MessageRole.ASSISTANT -> assistantName
        MessageRole.SYSTEM -> "System"
        MessageRole.TOOL -> "Tool"
    }
}
