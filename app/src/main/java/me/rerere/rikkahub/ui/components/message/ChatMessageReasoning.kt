package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import me.rerere.ai.provider.Model
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.extractGeminiThinkingTitle
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

enum class ReasoningCardState(val expanded: Boolean) {
    Collapsed(false),
    Preview(true),
    Expanded(true),
}

@Composable
fun ChainOfThoughtScope.ChatMessageReasoningStep(
    reasoning: UIMessagePart.Reasoning,
    model: Model?,
    assistant: Assistant?,
    fadeHeight: Float = 64f,
) {
    var expandState by remember { mutableStateOf(ReasoningCardState.Collapsed) }
    val scrollState = rememberScrollState()
    val settings = LocalSettings.current
    val loading = reasoning.finishedAt == null

    LaunchedEffect(reasoning.reasoning, loading) {
        if (loading) {
            if (!expandState.expanded) expandState = ReasoningCardState.Preview
            scrollState.animateScrollTo(scrollState.maxValue)
        } else {
            if (expandState.expanded) {
                expandState = if (settings.displaySetting.autoCloseThinking) {
                    ReasoningCardState.Collapsed
                } else {
                    ReasoningCardState.Expanded
                }
            }
        }
    }

    var duration by remember(reasoning.finishedAt != null, reasoning.createdAt) {
        mutableStateOf(
            value = reasoning.finishedAt?.let { endTime ->
                endTime - reasoning.createdAt
            } ?: (Clock.System.now() - reasoning.createdAt)
        )
    }

    LaunchedEffect(loading) {
        if (loading) {
            while (isActive) {
                duration = (reasoning.finishedAt ?: Clock.System.now()) - reasoning.createdAt
                delay(50)
            }
        }
    }

    fun onExpandedChange(nextExpanded: Boolean) {
        expandState = if (loading) {
            if (nextExpanded) ReasoningCardState.Expanded else ReasoningCardState.Preview
        } else {
            if (nextExpanded) ReasoningCardState.Expanded else ReasoningCardState.Collapsed
        }
    }

    ControlledChainOfThoughtStep(
        expanded = expandState == ReasoningCardState.Expanded,
        onExpandedChange = ::onExpandedChange,
        icon = {
            Icon(
                painter = painterResource(R.drawable.deepthink),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
        },
        label = {
            if (loading && model != null && ModelRegistry.GEMINI_SERIES.match(model.modelId)) {
                GeminiReasoningTitle(reasoning = reasoning)
            } else {
                Text(
                    text = stringResource(R.string.deep_thinking_seconds, duration.toDouble(DurationUnit.SECONDS).toFloat()),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.shimmer(isLoading = loading),
                )
            }
        },
        extra = {
            if (loading && duration > 0.seconds) {
                Text(
                    text = duration.toString(DurationUnit.SECONDS, 1),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.shimmer(isLoading = loading),
                )
            }
        },
        contentVisible = expandState != ReasoningCardState.Collapsed,
        content = {
            val isPreview = expandState == ReasoningCardState.Preview
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { contentModifier ->
                        if (isPreview) {
                            contentModifier
                                .graphicsLayer { alpha = 0.99f }
                                .drawWithCache {
                                    val brush = Brush.verticalGradient(
                                        startY = 0f,
                                        endY = size.height,
                                        colorStops = arrayOf(
                                            0.0f to Color.Transparent,
                                            (fadeHeight / size.height) to Color.Black,
                                            (1 - fadeHeight / size.height) to Color.Black,
                                            1.0f to Color.Transparent
                                        )
                                    )
                                    onDrawWithContent {
                                        drawContent()
                                        drawRect(
                                            brush = brush,
                                            size = Size(size.width, size.height),
                                            blendMode = BlendMode.DstIn,
                                        )
                                    }
                                }
                                .heightIn(max = 100.dp)
                                .verticalScroll(scrollState)
                        } else {
                            contentModifier
                        }
                    }
            ) {
                SelectionContainer {
                    MarkdownBlock(
                        content = reasoning.reasoning.replaceRegexes(
                            assistant = assistant,
                            scope = AssistantAffectScope.ASSISTANT,
                            visual = true,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        },
    )
}

@Composable
private fun GeminiReasoningTitle(reasoning: UIMessagePart.Reasoning) {
    val title = reasoning.reasoning.extractGeminiThinkingTitle()
    if (title != null) {
        AnimatedContent(
            targetState = title,
            transitionSpec = {
                (slideInVertically { height -> height } + fadeIn()).togetherWith(
                    slideOutVertically { height -> -height } + fadeOut()
                )
            }
        ) {
            Text(
                text = it,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .shimmer(true),
            )
        }
    }
}
