package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.TriangleAlert
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant

// 对话大小警告阈值
const val CONVERSATION_SIZE_WARNING_THRESHOLD = 20 * 1024 * 1024 // 20MB
const val CONVERSATION_SIZE_DANGER_THRESHOLD = 24 * 1024 * 1024 // 24MB

/**
 * 计算消息节点序列化后的大小（字节）
 */
fun calculateConversationSize(messageNodes: List<MessageNode>): Int {
    return try {
        JsonInstant.encodeToString(messageNodes).length
    } catch (e: Exception) {
        0
    }
}

data class ConversationSizeInfo(
    val size: Int,
    val showWarning: Boolean,
    val isDanger: Boolean,
    val sizeMB: Float
)

fun checkConversationSize(messageNodes: List<MessageNode>): ConversationSizeInfo {
    val size = calculateConversationSize(messageNodes)
    return ConversationSizeInfo(
        size = size,
        showWarning = size > CONVERSATION_SIZE_WARNING_THRESHOLD,
        isDanger = size > CONVERSATION_SIZE_DANGER_THRESHOLD,
        sizeMB = size / (1024f * 1024f)
    )
}

private val DefaultSizeInfo = ConversationSizeInfo(
    size = 0,
    showWarning = false,
    isDanger = false,
    sizeMB = 0f
)

@Composable
fun rememberConversationSizeInfo(conversation: Conversation): ConversationSizeInfo {
    return produceState(
        initialValue = DefaultSizeInfo,
        key1 = conversation.messageNodes
    ) {
        value = withContext(Dispatchers.Default) {
            checkConversationSize(conversation.messageNodes)
        }
    }.value
}

@Composable
fun ConversationSizeWarningDialog(
    sizeInfo: ConversationSizeInfo,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Lucide.TriangleAlert,
                contentDescription = null,
                tint = if (sizeInfo.isDanger) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.tertiary
                }
            )
        },
        title = {
            Text(text = stringResource(R.string.chat_size_dialog_title))
        },
        text = {
            Text(text = stringResource(R.string.chat_size_dialog_content).format(sizeInfo.sizeMB))
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.confirm))
            }
        }
    )
}
