package me.rerere.rikkahub.ui.components.ui

import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.ChatErrorSolution
import me.rerere.rikkahub.ui.context.LocalNavController
import kotlin.uuid.Uuid

@Composable
fun ErrorCardsDisplay(
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = errors.isNotEmpty(),
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            // 清除全部按钮（当有多个错误时显示）
            if (errors.size > 1) {
                Surface(
                    onClick = onClearAllErrors,
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = HugeIcons.Delete01,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            text = stringResource(R.string.chat_page_clear_all_errors),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            // 错误卡片列表
            errors.forEach { error ->
                ErrorCard(
                    error = error,
                    onDismiss = { onDismissError(error.id) },
                )
            }
        }
    }
}

@Composable
fun ErrorCard(
    error: ChatError,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboard.current
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val checkTitleModelSettings = stringResource(R.string.chat_page_check_title_model_settings)
    val linkColor = MaterialTheme.colorScheme.primary

    // 5 秒后自动消失
    LaunchedEffect(error.id) {
        delay(5000)
        onDismiss()
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (error.title != null) {
                    Text(
                        text = error.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = error.error.message ?: "Unknown error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                    overflow = TextOverflow.Ellipsis,
                )
                if (error.solution == ChatErrorSolution.CheckTitleModelSettings) {
                    Text(
                        text = buildAnnotatedString {
                            withLink(
                                LinkAnnotation.Clickable(
                                    tag = "check_title_model_settings",
                                    styles = TextLinkStyles(
                                        style = SpanStyle(
                                            color = linkColor,
                                            textDecoration = TextDecoration.Underline,
                                        )
                                    ),
                                    linkInteractionListener = {
                                        navController.navigate(Screen.SettingModels)
                                    },
                                )
                            ) {
                                append(checkTitleModelSettings)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(
                                clipData = ClipData.newPlainText("Error", error.error.message ?: "Unknown error")
                            )
                        )
                    }
                },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Copy01,
                    contentDescription = "Copy error message",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Cancel01,
                    contentDescription = stringResource(R.string.chat_page_dismiss_error),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
