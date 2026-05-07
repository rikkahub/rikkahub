package me.rerere.rikkahub.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.ChatService
import org.koin.compose.koinInject

/**
 * Bottom-anchored chat overlay rendered above the BrowserView's WebView. Two states:
 *
 *  - **Pill** (default): a small floating chat icon at the bottom-right corner. Semi-
 *    transparent so it doesn't compete with the page underneath. Tap to expand.
 *  - **Expanded**: a translucent card with the latest assistant reply (single-line preview,
 *    scrollable in-place when long), an "Generating…" indicator while a turn is in flight,
 *    and a single-line input + send button. Tap the X to collapse back to pill.
 *
 * The overlay always operates on the conversation that opened the browser. browser_open
 * threads the caller's conversation id into the intent extra; for Settings-launched
 * sessions where no conversation is associated, the overlay falls back to whatever
 * convId is currently active in [ChatService] sessions, and if none exists, hides itself
 * entirely (manual browsing path doesn't need an AI input — the user can ask a fresh
 * question from the in-app chat instead).
 *
 * Sends post directly via [ChatService.sendMessage] using the same path as the in-app
 * chat surface, so HARDLINE / approval / token accounting all apply unchanged. The user
 * watches the model drive the page in real time without ever leaving this Activity.
 */
@OptIn(ExperimentalUuidApi::class)
@Composable
fun BrowserMiniChat(
    conversationId: Uuid?,
    modifier: Modifier = Modifier,
) {
    if (conversationId == null) return

    val chatService = koinInject<ChatService>()
    val conversation by chatService.getConversationFlow(conversationId).collectAsStateWithLifecycle()
    val processingStatus by chatService
        .getProcessingStatusFlow(conversationId)
        .collectAsStateWithLifecycle()

    val isGenerating = processingStatus != null
    val latestAssistantText = remember(conversation) {
        conversation.currentMessages
            .lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.parts
            ?.filterIsInstance<UIMessagePart.Text>()
            ?.joinToString("\n") { it.text }
            ?.takeIf { it.isNotBlank() }
    }

    var expanded by remember { mutableStateOf(false) }

    Box(
        // imePadding lifts the entire overlay above the soft keyboard when it slides up;
        // navigationBarsPadding clears the gesture bar when the keyboard is hidden so the
        // pill / card never sits under the system handles. Combined modifiers run in
        // outer-first order — the IME inset wins when it's larger than the nav bar inset.
        modifier = modifier
            .imePadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.BottomEnd,
    ) {
        AnimatedVisibility(
            visible = !expanded,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
        ) {
            // Pill: floating action-button-sized circle bottom-right.
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                tonalElevation = 4.dp,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .padding(end = 16.dp, bottom = 16.dp)
                    .size(56.dp),
            ) {
                IconButton(
                    onClick = { expanded = true },
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.Sparkles,
                        contentDescription = stringResource(R.string.browser_mini_chat_open),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
        ) {
            BrowserMiniChatExpandedCard(
                latestAssistantText = latestAssistantText,
                isGenerating = isGenerating,
                onSend = { text ->
                    chatService.sendMessage(
                        conversationId = conversationId,
                        content = listOf(UIMessagePart.Text(text)),
                    )
                },
                onCollapse = { expanded = false },
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 12.dp)
                    .fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun BrowserMiniChatExpandedCard(
    latestAssistantText: String?,
    isGenerating: Boolean,
    onSend: (String) -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Header row: "Ask the AI" + collapse.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = HugeIcons.Sparkles,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.browser_mini_chat_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onCollapse) {
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = stringResource(R.string.browser_mini_chat_close),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // Latest assistant reply. Cap at 30% of screen-ish via heightIn(max=120dp);
            // user can scroll inside if it spills. While generating, show a tiny spinner +
            // "thinking" hint instead of clobbering the previous reply.
            if (latestAssistantText != null || isGenerating) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(modifier = Modifier.padding(10.dp)) {
                        if (isGenerating && latestAssistantText.isNullOrBlank()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.browser_mini_chat_generating),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        } else if (latestAssistantText != null) {
                            Text(
                                text = latestAssistantText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .heightIn(max = 120.dp)
                                    .verticalScroll(rememberScrollState()),
                            )
                        }
                    }
                }
            }

            // Input row.
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.browser_mini_chat_placeholder),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            val q = input.trim()
                            if (q.isNotEmpty() && !isGenerating) {
                                onSend(q)
                                input = ""
                                keyboard?.hide()
                            }
                        },
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                        disabledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                )
                FilledIconButton(
                    onClick = {
                        val q = input.trim()
                        if (q.isNotEmpty() && !isGenerating) {
                            onSend(q)
                            input = ""
                            keyboard?.hide()
                        }
                    },
                    enabled = input.isNotBlank() && !isGenerating,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(
                        imageVector = HugeIcons.Sparkles,
                        contentDescription = stringResource(R.string.browser_mini_chat_send),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
