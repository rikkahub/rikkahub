package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.effectiveUserName
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.utils.toLocalString

@Composable
fun ChatMessageUserAvatar(
    avatar: Avatar,
    nickname: String,
    modifier: Modifier = Modifier,
) {
    UIAvatar(
        name = nickname.ifEmpty { stringResource(R.string.user_default_name) },
        modifier = modifier.size(36.dp),
        value = avatar,
        loading = false,
    )
}

@Composable
fun ChatMessageAssistantAvatar(
    loading: Boolean,
    model: Model?,
    assistant: Assistant?,
    modifier: Modifier = Modifier,
) {
    val settings = LocalSettings.current.displaySetting
    if (!settings.showModelIcon) return

    when {
        assistant?.useAssistantAvatar == true -> {
            UIAvatar(
                name = assistant.name.ifEmpty { stringResource(R.string.assistant_page_default_assistant) },
                modifier = modifier.size(32.dp),
                value = assistant.avatar,
                loading = loading,
            )
        }

        model != null -> {
            AutoAIIcon(
                name = model.modelId,
                modifier = modifier.size(32.dp),
                loading = loading,
            )
        }
    }
}

@Composable
fun ChatMessageIdentityLabel(
    message: UIMessage,
    model: Model?,
    assistant: Assistant?,
    modifier: Modifier = Modifier,
) {
    val settings = LocalSettings.current
    val isUser = message.role == MessageRole.USER
    val showName = if (isUser) settings.displaySetting.showUserAvatar else settings.displaySetting.showModelName
    val showDate = settings.displaySetting.showDateBelowName

    if (!showName && !showDate) return

    val alignment = if (isUser) Alignment.End else Alignment.Start
    val labelText = when {
        isUser -> settings.effectiveUserName().ifBlank { stringResource(R.string.user_default_name) }
        assistant?.useAssistantAvatar == true -> assistant.name.ifEmpty {
            stringResource(R.string.assistant_page_default_assistant)
        }

        model != null -> model.displayName
        else -> null
    }

    Column(
        modifier = modifier,
        horizontalAlignment = alignment,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (showName && labelText != null) {
            Text(
                text = labelText,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                color = LocalContentColor.current.copy(alpha = 0.72f),
            )
        }

        if (showDate) {
            Text(
                text = message.createdAt.toJavaLocalDateTime().toLocalString(),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                color = LocalContentColor.current.copy(alpha = 0.52f),
            )
        }
    }
}
