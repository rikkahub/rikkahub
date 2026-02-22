package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.TriangleAlert
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Conversation

// 消息节点数量警告阈值
const val MESSAGE_NODE_WARNING_THRESHOLD = 768
const val LAST_ASSISTANT_INPUT_TOKEN_WARNING_THRESHOLD = 300_000

data class ConversationSizeInfo(
    val nodeCount: Int,
    val lastAssistantInputTokens: Int,
    val exceedNodeCountThreshold: Boolean,
    val exceedInputTokenThreshold: Boolean,
    val showWarning: Boolean
)

private val DefaultSizeInfo = ConversationSizeInfo(
    nodeCount = 0,
    lastAssistantInputTokens = 0,
    exceedNodeCountThreshold = false,
    exceedInputTokenThreshold = false,
    showWarning = false
)

@Composable
fun rememberConversationSizeInfo(conversation: Conversation): ConversationSizeInfo {
    return remember(conversation.messageNodes) {
        val nodeCount = conversation.messageNodes.size
        val lastAssistantInputTokens = conversation.messageNodes.asReversed()
            .map { it.currentMessage }
            .firstOrNull { it.role == MessageRole.ASSISTANT }
            ?.usage
            ?.promptTokens
            ?: 0
        val exceedNodeCountThreshold = nodeCount > MESSAGE_NODE_WARNING_THRESHOLD
        val exceedInputTokenThreshold = lastAssistantInputTokens > LAST_ASSISTANT_INPUT_TOKEN_WARNING_THRESHOLD
        ConversationSizeInfo(
            nodeCount = nodeCount,
            lastAssistantInputTokens = lastAssistantInputTokens,
            exceedNodeCountThreshold = exceedNodeCountThreshold,
            exceedInputTokenThreshold = exceedInputTokenThreshold,
            showWarning = exceedNodeCountThreshold && exceedInputTokenThreshold
        )
    }
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
                tint = MaterialTheme.colorScheme.tertiary
            )
        },
        title = {
            Text(text = stringResource(R.string.chat_size_dialog_title))
        },
        text = {
            Text(text = stringResource(R.string.chat_size_dialog_content, sizeInfo.nodeCount))
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.confirm))
            }
        }
    )
}
