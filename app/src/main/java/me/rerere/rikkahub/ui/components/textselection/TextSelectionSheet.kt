package me.rerere.rikkahub.ui.components.textselection

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.TextSelectionAction
import me.rerere.rikkahub.ui.activity.TextSelectionState
import me.rerere.rikkahub.ui.activity.TextSelectionVM
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.icons.Lucide
import me.rerere.rikkahub.utils.writeClipboardText

@Composable
fun TextSelectionSheet(
    viewModel: TextSelectionVM,
    onDismiss: () -> Unit,
    onContinueInApp: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val actions = remember(settings.textSelectionConfig.actions) {
        settings.textSelectionConfig.actions.filter { it.enabled }
    }

    var isVisible by remember { mutableStateOf(false) }
    val backgroundAlpha by animateFloatAsState(
        targetValue = if (isVisible) 0.5f else 0f,
        label = "backgroundAlpha"
    )

    LaunchedEffect(Unit) {
        isVisible = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = backgroundAlpha))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visible = isVisible,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = spring(dampingRatio = 0.85f, stiffness = 320f)
            ) + fadeIn(),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = spring(dampingRatio = 0.85f, stiffness = 360f)
            ) + fadeOut()
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(16.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    ),
                shape = RoundedCornerShape(28.dp),
                tonalElevation = 6.dp,
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                when (val state = viewModel.state) {
                    is TextSelectionState.ActionSelection -> {
                        ActionSelectionContent(
                            selectedText = viewModel.selectedText,
                            actions = actions,
                            onActionSelected = viewModel::onActionSelected
                        )
                    }

                    is TextSelectionState.CustomPrompt -> {
                        CustomPromptContent(
                            prompt = viewModel.customPrompt,
                            onPromptChange = viewModel::updateCustomPrompt,
                            onBack = viewModel::backToActionSelection,
                            onSubmit = viewModel::submitCustomPrompt,
                        )
                    }

                    is TextSelectionState.Loading -> {
                        LoadingContent()
                    }

                    is TextSelectionState.Result -> {
                        ResultContent(
                            responseText = state.responseText,
                            isStreaming = state.isStreaming,
                            isReasoning = state.isReasoning,
                            isTranslate = viewModel.isTranslateAction(),
                            onBack = viewModel::backToActionSelection,
                            onStop = viewModel::cancelGeneration,
                            onContinueInApp = onContinueInApp,
                        )
                    }

                    is TextSelectionState.Error -> {
                        ErrorContent(
                            message = state.message,
                            onBack = viewModel::backToActionSelection,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionSelectionContent(
    selectedText: String,
    actions: List<TextSelectionAction>,
    onActionSelected: (TextSelectionAction) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = androidx.compose.ui.res.stringResource(R.string.text_selection_menu_label),
            style = MaterialTheme.typography.titleMedium,
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Text(
                text = selectedText,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (actions.isEmpty()) {
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.text_selection_no_actions_enabled),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            actions.chunked(2).forEach { rowActions ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowActions.forEach { action ->
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 56.dp),
                            shape = RoundedCornerShape(12.dp),
                            tonalElevation = 2.dp,
                            onClick = { onActionSelected(action) },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = iconForAction(action.icon),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = action.name,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    if (rowActions.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomPromptContent(
    prompt: String,
    onPromptChange: (String) -> Unit,
    onBack: () -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(30.dp)
            ) {
                Icon(Lucide.ArrowLeft, null, modifier = Modifier.size(18.dp))
            }
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.text_selection_ask),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        OutlinedTextField(
            value = prompt,
            onValueChange = onPromptChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(androidx.compose.ui.res.stringResource(R.string.text_selection_custom_placeholder))
            },
            shape = RoundedCornerShape(14.dp),
            minLines = 2,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                onClick = onSubmit,
                enabled = prompt.isNotBlank(),
            ) {
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.send),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun LoadingContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp))
        Text(
            text = androidx.compose.ui.res.stringResource(R.string.text_selection_generating),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ResultContent(
    responseText: String,
    isStreaming: Boolean,
    isReasoning: Boolean,
    isTranslate: Boolean,
    onBack: () -> Unit,
    onStop: () -> Unit,
    onContinueInApp: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val toaster = LocalToaster.current
    val copiedText = androidx.compose.ui.res.stringResource(R.string.copied)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(30.dp)) {
                    Icon(Lucide.ArrowLeft, null, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(6.dp))
                if (isStreaming) {
                    Text(
                        text = if (isReasoning) {
                            androidx.compose.ui.res.stringResource(R.string.reasoning_medium)
                        } else {
                            androidx.compose.ui.res.stringResource(R.string.text_selection_generating)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (isStreaming) {
                IconButton(onClick = onStop, modifier = Modifier.size(30.dp)) {
                    Icon(
                        imageVector = Lucide.CircleStop,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp, max = 320.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Box(
                modifier = Modifier
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (responseText.isBlank()) {
                    Text(
                        text = "...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    MarkdownBlock(content = responseText, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (!isStreaming && responseText.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    onClick = {
                        context.writeClipboardText(responseText)
                        toaster.show(copiedText, type = ToastType.Success)
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Lucide.Copy, null, modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(androidx.compose.ui.res.stringResource(R.string.text_selection_copy))
                    }
                }

                if (!isTranslate) {
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        onClick = onContinueInApp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Lucide.ExternalLink, null, modifier = Modifier.size(17.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = androidx.compose.ui.res.stringResource(R.string.text_selection_continue_chat),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            onClick = onBack
        ) {
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.back),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

private fun iconForAction(icon: String) = when (icon.lowercase()) {
    "translate" -> Lucide.Languages
    "lightbulb" -> Lucide.Lightbulb
    "summarize" -> Lucide.BookOpenText
    "ask" -> Lucide.Sparkles
    else -> Lucide.Sparkles
}
