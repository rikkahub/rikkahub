package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.asr.ASRState
import me.rerere.rikkahub.R
import me.rerere.asr.ASRStatus
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Voice

private enum class AsrDisplayState {
    Idle, Connecting, Active
}

@Composable
internal fun AsrButton(
    state: ASRState,
    onClick: () -> Unit,
) {
    val isIdle = state.status == ASRStatus.Idle
    val targetContainerColor = when (state.status) {
        ASRStatus.Idle -> Color.Transparent
        ASRStatus.Connecting -> MaterialTheme.colorScheme.secondaryContainer
        ASRStatus.Listening -> MaterialTheme.colorScheme.primaryContainer
        ASRStatus.Stopping -> MaterialTheme.colorScheme.tertiaryContainer
        ASRStatus.Error -> MaterialTheme.colorScheme.errorContainer
    }
    val targetContentColor = when (state.status) {
        ASRStatus.Idle -> LocalContentColor.current
        ASRStatus.Connecting -> MaterialTheme.colorScheme.onSecondaryContainer
        ASRStatus.Listening -> MaterialTheme.colorScheme.onPrimaryContainer
        ASRStatus.Stopping -> MaterialTheme.colorScheme.onTertiaryContainer
        ASRStatus.Error -> MaterialTheme.colorScheme.onErrorContainer
    }
    val containerColor by animateColorAsState(
        targetValue = targetContainerColor,
        animationSpec = tween(300),
        label = "asr_container"
    )
    val contentColor by animateColorAsState(
        targetValue = targetContentColor,
        animationSpec = tween(300),
        label = "asr_content"
    )

    val displayState = when (state.status) {
        ASRStatus.Idle, ASRStatus.Error -> AsrDisplayState.Idle
        ASRStatus.Connecting -> AsrDisplayState.Connecting
        ASRStatus.Listening, ASRStatus.Stopping -> AsrDisplayState.Active
    }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .height(36.dp)
            .widthIn(min = 36.dp)
            .animateContentSize(animationSpec = MotionScheme.expressive().defaultSpatialSpec()),
        shape = CircleShape,
        tonalElevation = if (isIdle) 0.dp else 2.dp,
        color = containerColor,
    ) {
        AnimatedContent(
            targetState = displayState,
            transitionSpec = {
                (fadeIn(tween(200)) togetherWith fadeOut(tween(200)))
                    .using(SizeTransform(clip = false))
            },
            label = "asr_content_switch"
        ) { display ->
            when (display) {
                AsrDisplayState.Idle -> {
                    Box(
                        modifier = Modifier.size(36.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = HugeIcons.Voice,
                            contentDescription = stringResource(R.string.asr_button_content_description),
                            tint = contentColor
                        )
                    }
                }

                AsrDisplayState.Connecting -> {
                    Box(
                        modifier = Modifier.size(36.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val infiniteTransition = rememberInfiniteTransition(label = "asr_pulse")
                        val scale by infiniteTransition.animateFloat(
                            initialValue = 0.8f,
                            targetValue = 1.2f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(600, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "asr_pulse_scale"
                        )
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .scale(scale)
                                .clip(CircleShape)
                                .background(contentColor)
                        )
                    }
                }

                AsrDisplayState.Active -> {
                    Row(
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AudioLevelDots(
                            amplitudes = state.amplitudes,
                            color = contentColor,
                        )
                        Text(
                            text = stringResource(R.string.asr_button_stop),
                            color = contentColor,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioLevelDots(
    amplitudes: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val recent = amplitudes.takeLast(3)
    val values = when {
        recent.size >= 3 -> recent
        recent.size == 2 -> listOf(recent[0] * 0.6f, recent[1], recent[1] * 0.8f)
        recent.size == 1 -> listOf(recent[0] * 0.5f, recent[0], recent[0] * 0.7f)
        else -> listOf(0f, 0f, 0f)
    }

    val barWidth = 3.5.dp
    val minHeight = 4.dp
    val maxHeight = 16.dp

    Row(
        modifier = modifier.height(maxHeight),
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        values.forEachIndexed { index, amp ->
            val animatedAmp by animateFloatAsState(
                targetValue = amp.coerceIn(0f, 1f),
                animationSpec = spring(
                    dampingRatio = 0.6f,
                    stiffness = 400f,
                ),
                label = "bar_$index"
            )
            val barHeight = minHeight + (maxHeight - minHeight) * animatedAmp
            Box(
                modifier = Modifier
                    .width(barWidth)
                    .height(barHeight)
                    .clip(RoundedCornerShape(barWidth / 2))
                    .background(color)
            )
        }
    }
}
