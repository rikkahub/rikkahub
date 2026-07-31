package me.rerere.rikkahub.ui.pages.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Cpu
import me.rerere.hugeicons.stroke.StopCircle
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.agent.routing.AgentIntent
import me.rerere.rikkahub.data.ai.agent.routing.InputTrust
import me.rerere.rikkahub.data.db.entity.AgentApprovalEntity

private const val AGENT_RUN_TERMINAL_GRACE_MILLIS = 150L
private const val AGENT_RUN_TERMINAL_ACKNOWLEDGEMENT_MILLIS = 650L

private fun agentRunTransientContentTransform(
    initialHasContent: Boolean,
    targetHasContent: Boolean,
): ContentTransform = when {
    !initialHasContent && targetHasContent -> {
        (expandVertically(expandFrom = Alignment.Top) + fadeIn()) togetherWith fadeOut()
    }
    initialHasContent && !targetHasContent -> {
        fadeIn() togetherWith (shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut())
    }
    else -> fadeIn() togetherWith fadeOut()
}

internal fun AgentRunVisualState.showsActiveProgress(): Boolean = when (this) {
    AgentRunVisualState.PENDING,
    AgentRunVisualState.WORKING,
    -> true
    AgentRunVisualState.NEEDS_ATTENTION,
    AgentRunVisualState.SUCCEEDED,
    AgentRunVisualState.FAILED,
    AgentRunVisualState.STOPPED,
    -> false
}

internal fun agentRunStepProgress(
    completedSteps: Int,
    maxSteps: Int?,
): Float? = maxSteps
    ?.takeIf { it > 0 }
    ?.let { stepBudget ->
        (completedSteps.toFloat() / stepBudget).coerceIn(0f, 1f)
    }

internal fun agentRunActivityText(
    visualState: AgentRunVisualState,
    waitingReason: String?,
    currentStep: String?,
    statusDescription: String?,
    isStopping: Boolean,
    stoppingLabel: String,
    waitingTelemetryLabel: String,
): String? {
    fun String?.takeIfNotBlank(): String? = this?.takeIf(String::isNotBlank)

    if (isStopping) return stoppingLabel
    if (visualState.isLive()) {
        return waitingReason.takeIfNotBlank()
            ?: currentStep.takeIfNotBlank()
            ?: waitingTelemetryLabel
    }
    return statusDescription.takeIfNotBlank()
}

@Composable
private fun rememberAgentRunProgressTarget(progress: Float?): Float {
    var lastDeterminateProgress by remember {
        mutableFloatStateOf(progress?.coerceIn(0f, 1f) ?: 0f)
    }
    val progressTarget = progress?.coerceIn(0f, 1f) ?: lastDeterminateProgress
    SideEffect {
        if (progress != null) {
            lastDeterminateProgress = progressTarget
        }
    }
    return progressTarget
}

@Composable
internal fun AgentRunProgressRing(
    progress: Float?,
    color: Color,
    trackColor: Color,
    size: Dp,
    strokeWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val progressTarget = rememberAgentRunProgressTarget(progress)
    val animatedProgress by animateFloatAsState(
        targetValue = progressTarget,
        animationSpec = MotionScheme.expressive().defaultSpatialSpec(),
        label = "agent_run_ring_progress",
    )

    AnimatedContent(
        targetState = progress != null,
        modifier = modifier.size(size),
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "agent_run_ring_mode",
    ) { isDeterminate ->
        if (isDeterminate) {
            CircularProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.size(size),
                color = color,
                trackColor = trackColor,
                strokeWidth = strokeWidth,
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(size),
                color = color,
                trackColor = trackColor,
                strokeWidth = strokeWidth,
            )
        }
    }
}

@Composable
private fun rememberAgentRunClock(
    key: String,
    enabled: Boolean,
): Long {
    var nowMillis by remember(key) { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(key, enabled) {
        while (enabled) {
            nowMillis = System.currentTimeMillis()
            delay(1_000L - (nowMillis % 1_000L))
        }
    }

    return nowMillis
}

@Composable
private fun rememberLiveDurationLabel(
    presentation: AgentRunPresentation,
    enabled: Boolean = true,
): String {
    val nowMillis = rememberAgentRunClock(
        key = presentation.runId,
        enabled = enabled && presentation.visualState.isLive(),
    )
    return liveDurationLabel(presentation, nowMillis)
}

private data class AgentRunMetadataKey(
    val model: String?,
    val completedSteps: Int,
    val maxSteps: Int?,
)

private data class AgentRunMetadataFrame(
    val model: String?,
    val completedSteps: Int,
    val maxSteps: Int?,
    val duration: String,
) {
    val contentKey = AgentRunMetadataKey(model, completedSteps, maxSteps)
}

private data class AgentRunStatusIdentity(
    val routingLabel: String,
    val status: String,
)

private data class AgentRunDetailIdentity(
    val shortRunId: String,
    val status: String,
    val duration: String,
)

private data class AgentRunVisualProgressKey(
    val visualState: AgentRunVisualState,
    val showsProgress: Boolean,
)

private data class AgentRunVisualProgressFrame(
    val visualState: AgentRunVisualState,
    val showsProgress: Boolean,
    val progress: Float?,
) {
    val contentKey = AgentRunVisualProgressKey(visualState, showsProgress)
}

private enum class AgentRunLinearProgressPhase {
    HIDDEN,
    DETERMINATE,
    INDETERMINATE,
}

private data class AgentRunLinearProgressFrame(
    val phase: AgentRunLinearProgressPhase,
    val progress: Float?,
)

@Composable
fun AgentRunActiveCard(
    run: AgentRunPresentation,
    onOpen: () -> Unit,
    onStop: (() -> Unit)? = null,
    isStopping: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val hapticFeedback = LocalHapticFeedback.current
    val motionScheme = MotionScheme.expressive()
    val routingLabel = run.routing.displayLabel()
    val statusIdentity = AgentRunStatusIdentity(routingLabel, run.status)
    val liveDuration = rememberLiveDurationLabel(run)
    val targetContainerColor = when (run.visualState) {
        AgentRunVisualState.PENDING -> MaterialTheme.colorScheme.secondaryContainer
        AgentRunVisualState.WORKING -> MaterialTheme.colorScheme.primaryContainer
        AgentRunVisualState.NEEDS_ATTENTION -> MaterialTheme.colorScheme.tertiaryContainer
        AgentRunVisualState.SUCCEEDED -> MaterialTheme.colorScheme.primaryContainer
        AgentRunVisualState.FAILED -> MaterialTheme.colorScheme.errorContainer
        AgentRunVisualState.STOPPED -> MaterialTheme.colorScheme.surfaceVariant
    }
    val targetContentColor = when (run.visualState) {
        AgentRunVisualState.PENDING -> MaterialTheme.colorScheme.onSecondaryContainer
        AgentRunVisualState.WORKING -> MaterialTheme.colorScheme.onPrimaryContainer
        AgentRunVisualState.NEEDS_ATTENTION -> MaterialTheme.colorScheme.onTertiaryContainer
        AgentRunVisualState.SUCCEEDED -> MaterialTheme.colorScheme.onPrimaryContainer
        AgentRunVisualState.FAILED -> MaterialTheme.colorScheme.onErrorContainer
        AgentRunVisualState.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val containerColor by animateColorAsState(targetContainerColor, label = "agent_run_container")
    val contentColor by animateColorAsState(targetContentColor, label = "agent_run_content")
    val stepProgress = agentRunStepProgress(run.completedSteps, run.maxSteps)
    val metadataFrame = AgentRunMetadataFrame(
        model = run.model,
        completedSteps = run.completedSteps,
        maxSteps = run.maxSteps,
        duration = liveDuration,
    )
    val showProgress = !isStopping && run.visualState.showsActiveProgress()
    val progressPhase = when {
        !showProgress -> AgentRunLinearProgressPhase.HIDDEN
        stepProgress != null -> AgentRunLinearProgressPhase.DETERMINATE
        else -> AgentRunLinearProgressPhase.INDETERMINATE
    }
    val progressFrame = AgentRunLinearProgressFrame(
        phase = progressPhase,
        progress = stepProgress.takeIf { progressPhase == AgentRunLinearProgressPhase.DETERMINATE },
    )
    val stopAction = onStop.takeIf { run.visualState.isLive() }
    val stoppingLabel = stringResource(R.string.agent_run_stopping)
    val activityText = agentRunActivityText(
        visualState = run.visualState,
        waitingReason = run.waitingReason,
        currentStep = run.currentStep,
        statusDescription = run.statusDescription,
        isStopping = isStopping,
        stoppingLabel = stoppingLabel,
        waitingTelemetryLabel = "正在等待运行遥测",
    )

    Card(
        onClick = onOpen,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(animationSpec = MotionScheme.expressive().defaultSpatialSpec()),
        ) {
            Row(
                modifier = Modifier.padding(start = 16.dp, top = 10.dp, end = 12.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AnimatedContent(
                    targetState = run.visualState,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "agent_run_icon",
                ) { visualState ->
                    Icon(
                        imageVector = visualState.icon(),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Column(
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    ) {
                        AnimatedContent(
                            targetState = statusIdentity,
                            transitionSpec = {
                                (fadeIn() + slideInVertically { it / 3 }) togetherWith
                                    (fadeOut() + slideOutVertically { -it / 3 })
                            },
                            label = "agent_run_status",
                        ) { statusFrame ->
                            Text(
                                text = "${statusFrame.routingLabel} · ${statusFrame.status}",
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                        AnimatedContent(
                            targetState = activityText,
                            transitionSpec = {
                                (fadeIn() + slideInVertically { it / 3 }) togetherWith
                                    (fadeOut() + slideOutVertically { -it / 3 })
                            },
                            label = "agent_run_activity",
                        ) { text ->
                            if (text != null) {
                                Text(
                                    text = text,
                                    modifier = Modifier.padding(top = 2.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    AnimatedContent(
                        targetState = metadataFrame,
                        contentKey = AgentRunMetadataFrame::contentKey,
                        modifier = Modifier.padding(top = 2.dp),
                        transitionSpec = {
                            when (agentRunCountMotion(initialState.completedSteps, targetState.completedSteps)) {
                                AgentRunCountMotion.INCREASE -> {
                                    (fadeIn() + slideInVertically(
                                        animationSpec = motionScheme.defaultSpatialSpec(),
                                        initialOffsetY = { it / 2 },
                                    )) togetherWith (fadeOut() + slideOutVertically(
                                        animationSpec = motionScheme.defaultSpatialSpec(),
                                        targetOffsetY = { -it / 2 },
                                    ))
                                }
                                AgentRunCountMotion.DECREASE -> {
                                    (fadeIn() + slideInVertically(
                                        animationSpec = motionScheme.defaultSpatialSpec(),
                                        initialOffsetY = { -it / 2 },
                                    )) togetherWith (fadeOut() + slideOutVertically(
                                        animationSpec = motionScheme.defaultSpatialSpec(),
                                        targetOffsetY = { it / 2 },
                                    ))
                                }
                                AgentRunCountMotion.STEADY -> fadeIn() togetherWith fadeOut()
                            }
                        },
                        label = "agent_run_metadata",
                    ) { metadataFrame ->
                        val metadata = listOfNotNull(
                            metadataFrame.model,
                            "${metadataFrame.completedSteps}/${metadataFrame.maxSteps ?: "?"} 步",
                            stringResource(R.string.agent_run_elapsed, metadataFrame.duration),
                        ).joinToString(" · ").ifBlank { "运行配置尚未写入" }
                        Text(
                            text = metadata,
                            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                            color = contentColor.copy(alpha = 0.72f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                AnimatedVisibility(
                    visible = stopAction != null || isStopping,
                    enter = expandHorizontally(expandFrom = Alignment.End) + fadeIn(),
                    exit = shrinkHorizontally(shrinkTowards = Alignment.End) + fadeOut(),
                ) {
                    IconButton(
                        onClick = {
                            stopAction?.let { stop ->
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                                stop()
                            }
                        },
                        enabled = stopAction != null && !isStopping,
                    ) {
                        AnimatedContent(
                            targetState = isStopping,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "agent_run_stop",
                        ) { stopping ->
                            if (stopping) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .semantics { contentDescription = stoppingLabel },
                                    color = contentColor,
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    imageVector = HugeIcons.StopCircle,
                                    contentDescription = stringResource(R.string.stop),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
            AnimatedContent(
                targetState = progressFrame,
                contentKey = AgentRunLinearProgressFrame::phase,
                modifier = Modifier.fillMaxWidth(),
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "agent_run_progress_phase",
            ) { frame ->
                val progressModifier = Modifier.fillMaxWidth().height(3.dp)
                when (frame.phase) {
                    AgentRunLinearProgressPhase.HIDDEN -> Box(Modifier.fillMaxWidth())
                    AgentRunLinearProgressPhase.DETERMINATE -> {
                        val progressTarget = rememberAgentRunProgressTarget(frame.progress)
                        val animatedProgress by animateFloatAsState(
                            targetValue = progressTarget,
                            animationSpec = motionScheme.defaultSpatialSpec(),
                            label = "agent_run_step_progress",
                        )
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = progressModifier,
                            color = contentColor,
                            trackColor = contentColor.copy(alpha = 0.16f),
                        )
                    }
                    AgentRunLinearProgressPhase.INDETERMINATE -> {
                        LinearProgressIndicator(
                            modifier = progressModifier,
                            color = contentColor,
                            trackColor = contentColor.copy(alpha = 0.16f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun AgentRunActiveCardHost(
    activeRun: AgentRunPresentation?,
    latestRun: AgentRunPresentation?,
    stoppingRunId: String?,
    onOpen: (String) -> Unit,
    onStop: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motionScheme = MaterialTheme.motionScheme
    var retainedRun by remember { mutableStateOf(activeRun) }
    var eligibleRunId by remember { mutableStateOf(activeRun?.runId) }
    var visible by remember { mutableStateOf(activeRun != null) }
    var retainedStoppingRunId by remember { mutableStateOf(stoppingRunId) }

    LaunchedEffect(stoppingRunId) {
        if (stoppingRunId != null) retainedStoppingRunId = stoppingRunId
    }
    LaunchedEffect(activeRun, latestRun) {
        val transition = agentRunCardTransition(
            activePresentation = activeRun,
            retainedPresentation = retainedRun,
            latestPresentation = latestRun,
            eligibleRunId = eligibleRunId,
        )
        retainedRun = transition.presentation
        when (transition.stage) {
            AgentRunCardStage.ACTIVE -> {
                eligibleRunId = activeRun?.runId
                visible = true
            }
            AgentRunCardStage.AWAITING_TERMINAL -> {
                delay(AGENT_RUN_TERMINAL_GRACE_MILLIS)
                visible = false
                eligibleRunId = null
            }
            AgentRunCardStage.TERMINAL -> {
                retainedStoppingRunId = null
                visible = true
                delay(AGENT_RUN_TERMINAL_ACKNOWLEDGEMENT_MILLIS)
                visible = false
                eligibleRunId = null
            }
            AgentRunCardStage.HIDDEN -> {
                visible = false
                eligibleRunId = null
            }
        }
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
    ) {
        val currentRunId = retainedRun?.runId
        AnimatedContent(
            targetState = retainedRun,
            transitionSpec = {
                (fadeIn() + slideInVertically(
                    animationSpec = motionScheme.defaultSpatialSpec(),
                    initialOffsetY = { it / 8 },
                )) togetherWith (fadeOut() + slideOutVertically(
                    animationSpec = motionScheme.defaultSpatialSpec(),
                    targetOffsetY = { -it / 8 },
                ))
            },
            contentKey = { it?.runId },
            label = "agent_active_run_identity",
        ) { run ->
            run?.let {
                val isStopping = run.visualState.isLive() && (
                    stoppingRunId == run.runId ||
                        activeRun == null && retainedStoppingRunId == run.runId
                )
                AgentRunActiveCard(
                    run = run,
                    onOpen = { onOpen(run.runId) },
                    onStop = if (activeRun?.runId == run.runId) {
                        { onStop(run.runId) }
                    } else {
                        null
                    },
                    isStopping = isStopping,
                    enabled = run.runId == currentRunId,
                )
            }
        }
    }
}

@Composable
internal fun AgentRunEntry(
    run: AgentRunPresentation,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visualState = run.visualState
    val targetTint = visualState.entryTint()
    val tint by animateColorAsState(targetTint, label = "agent_run_entry_tint")
    val description = visualState.entryDescription()
    val stepProgress = agentRunStepProgress(run.completedSteps, run.maxSteps)
    val stateFrame = AgentRunVisualProgressFrame(
        visualState = visualState,
        showsProgress = visualState.showsActiveProgress(),
        progress = stepProgress,
    )

    IconButton(
        onClick = onOpen,
        modifier = modifier.semantics { contentDescription = description },
    ) {
        AnimatedContent(
            targetState = stateFrame,
            contentKey = AgentRunVisualProgressFrame::contentKey,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "agent_run_entry_state",
        ) { frame ->
            Box(
                modifier = Modifier.size(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (frame.showsProgress) {
                    AgentRunProgressRing(
                        progress = frame.progress,
                        color = tint,
                        trackColor = tint.copy(alpha = 0.16f),
                        size = 28.dp,
                        strokeWidth = 2.dp,
                    )
                }
                Icon(
                    imageVector = frame.visualState.icon(),
                    contentDescription = null,
                    modifier = Modifier.size(if (frame.showsProgress) 14.dp else 20.dp),
                    tint = tint,
                )
            }
        }
    }
}

@Composable
internal fun AgentChildRunCard(
    child: ChildRunPresentation,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    nowMillis: Long? = null,
) {
    val targetTint = child.visualState.entryTint()
    val tint by animateColorAsState(targetTint, label = "agent_child_run_tint")
    val shortRunId = child.runId.takeLast(8)
    val openDescription = stringResource(
        R.string.agent_run_child_open_details,
        shortRunId,
        child.status,
    )
    val duration = nowMillis?.let { now ->
        liveDurationLabel(
            duration = child.duration,
            durationStartedAt = child.durationStartedAt,
            visualState = child.visualState,
            nowMillis = now,
        )
    } ?: child.duration

    Card(
        onClick = onOpen,
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = openDescription },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.animateContentSize(
                animationSpec = MotionScheme.expressive().defaultSpatialSpec(),
            ),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AnimatedContent(
                    targetState = child.visualState,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "agent_child_run_state",
                ) { state ->
                    val active = state.showsActiveProgress()
                    Box(
                        modifier = Modifier.size(28.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (active) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                color = tint,
                                trackColor = tint.copy(alpha = 0.16f),
                                strokeWidth = 2.dp,
                            )
                        }
                        Icon(
                            imageVector = state.icon(),
                            contentDescription = null,
                            modifier = Modifier.size(if (active) 14.dp else 20.dp),
                            tint = tint,
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text("Run $shortRunId", style = MaterialTheme.typography.titleSmall)
                    AnimatedContent(
                        targetState = child.status,
                        transitionSpec = {
                            (fadeIn() + slideInVertically { it / 3 }) togetherWith
                                (fadeOut() + slideOutVertically { -it / 3 })
                        },
                        label = "agent_child_run_status",
                    ) { status ->
                        Text(
                            text = status,
                            style = MaterialTheme.typography.labelMedium,
                            color = tint,
                        )
                    }
                    Text(
                        text = stringResource(R.string.agent_run_child_duration, duration),
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = HugeIcons.ArrowRight01,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedContent(
                targetState = child.findings.takeIf(String::isNotBlank),
                transitionSpec = {
                    agentRunTransientContentTransform(initialState != null, targetState != null)
                },
                label = "agent_child_run_finding",
            ) { finding ->
                if (finding != null) {
                    Text(
                        text = finding,
                        modifier = Modifier.padding(start = 52.dp, end = 40.dp, bottom = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentRunVisualState.entryTint(): Color = when (this) {
    AgentRunVisualState.PENDING -> MaterialTheme.colorScheme.secondary
    AgentRunVisualState.WORKING,
    AgentRunVisualState.SUCCEEDED,
    -> MaterialTheme.colorScheme.primary
    AgentRunVisualState.NEEDS_ATTENTION -> MaterialTheme.colorScheme.tertiary
    AgentRunVisualState.FAILED -> MaterialTheme.colorScheme.error
    AgentRunVisualState.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun AgentRunVisualState.entryDescription(): String = stringResource(
    when (this) {
        AgentRunVisualState.PENDING -> R.string.agent_run_entry_pending
        AgentRunVisualState.WORKING -> R.string.agent_run_entry_working
        AgentRunVisualState.NEEDS_ATTENTION -> R.string.agent_run_entry_attention
        AgentRunVisualState.SUCCEEDED -> R.string.agent_run_entry_succeeded
        AgentRunVisualState.FAILED -> R.string.agent_run_entry_failed
        AgentRunVisualState.STOPPED -> R.string.agent_run_entry_stopped
    }
)

private fun AgentRunVisualState.icon() = when (this) {
    AgentRunVisualState.PENDING -> HugeIcons.Clock02
    AgentRunVisualState.WORKING -> HugeIcons.Cpu
    AgentRunVisualState.NEEDS_ATTENTION,
    AgentRunVisualState.FAILED,
    -> HugeIcons.AlertCircle
    AgentRunVisualState.SUCCEEDED -> HugeIcons.Tick01
    AgentRunVisualState.STOPPED -> HugeIcons.Cancel01
}

@Composable
fun AgentRunDetailSheet(
    state: AgentRunDetailState,
    onDismiss: () -> Unit,
    onOpenApproval: (AgentApprovalEntity) -> Unit,
    onOpenChildRun: (String) -> Unit = {},
    onNavigateBack: () -> Unit = {},
    onStop: (() -> Unit)? = null,
    isStopping: Boolean = false,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        AgentRunDetailPane(
            state = state,
            onNavigateBack = onNavigateBack,
            onOpenApproval = onOpenApproval,
            onOpenChildRun = onOpenChildRun,
            onStop = onStop,
            isStopping = isStopping,
        )
        BackHandler(
            enabled = agentRunDetailBackBehavior(state) == AgentRunDetailBackBehavior.NAVIGATE_PARENT,
            onBack = onNavigateBack,
        )
    }
}

internal fun AgentRunDetailState.animationKey(): String = when (this) {
    AgentRunDetailState.Closed -> "closed"
    is AgentRunDetailState.Loading -> "loading:$runId"
    is AgentRunDetailState.Missing -> "missing:$runId"
    is AgentRunDetailState.Content -> "content:$runId"
}

internal enum class AgentRunDetailMotion {
    PHASE,
    FORWARD,
    BACK,
}

internal enum class AgentRunDetailBackBehavior {
    DISMISS_SHEET,
    NAVIGATE_PARENT,
}

internal fun agentRunDetailBackBehavior(state: AgentRunDetailState): AgentRunDetailBackBehavior =
    if (state.canNavigateBack) {
        AgentRunDetailBackBehavior.NAVIGATE_PARENT
    } else {
        AgentRunDetailBackBehavior.DISMISS_SHEET
    }

internal fun agentRunDetailMotion(
    initialState: AgentRunDetailState,
    targetState: AgentRunDetailState,
): AgentRunDetailMotion {
    if (initialState.runId == null || targetState.runId == null || initialState.runId == targetState.runId) {
        return AgentRunDetailMotion.PHASE
    }
    return when {
        targetState.navigationDepth > initialState.navigationDepth -> AgentRunDetailMotion.FORWARD
        targetState.navigationDepth < initialState.navigationDepth -> AgentRunDetailMotion.BACK
        else -> AgentRunDetailMotion.PHASE
    }
}

internal enum class AgentRunCountMotion {
    INCREASE,
    DECREASE,
    STEADY,
}

internal fun agentRunCountMotion(initialCount: Int, targetCount: Int): AgentRunCountMotion = when {
    targetCount > initialCount -> AgentRunCountMotion.INCREASE
    targetCount < initialCount -> AgentRunCountMotion.DECREASE
    else -> AgentRunCountMotion.STEADY
}

internal data class AgentTimelineUpdate(
    val currentKeys: Set<String>,
    val addedCount: Int,
    val unseenCount: Int,
    val shouldScrollToLatest: Boolean,
)

internal fun agentRunActivityKeys(
    childRunIds: List<String>,
    timelineKeys: List<String>,
): List<String> = buildList(childRunIds.size + timelineKeys.size) {
    childRunIds.forEach { runId -> add("child:$runId") }
    timelineKeys.forEach { timelineKey -> add("timeline:$timelineKey") }
}

internal fun agentTimelineUpdate(
    previousKeys: Set<String>,
    currentKeys: List<String>,
    currentUnseenCount: Int,
    followingLatest: Boolean,
): AgentTimelineUpdate {
    val currentKeySet = currentKeys.toSet()
    val addedCount = (currentKeySet - previousKeys).size
    return AgentTimelineUpdate(
        currentKeys = currentKeySet,
        addedCount = addedCount,
        unseenCount = if (followingLatest) 0 else currentUnseenCount.coerceAtLeast(0) + addedCount,
        shouldScrollToLatest = followingLatest && addedCount > 0,
    )
}

internal fun agentRunDetailLastItemIndex(
    childCount: Int,
    timelineCount: Int,
): Int {
    val childItems = childCount.coerceAtLeast(0).takeIf { it > 0 }?.plus(1) ?: 0
    val timelineItems = timelineCount.coerceAtLeast(1)
    return 4 + childItems + timelineItems
}

@Composable
internal fun AgentRunDetailPane(
    state: AgentRunDetailState,
    onNavigateBack: () -> Unit = {},
    onOpenApproval: (AgentApprovalEntity) -> Unit = {},
    onOpenChildRun: (String) -> Unit = {},
    onStop: (() -> Unit)? = null,
    isStopping: Boolean = false,
) {
    val detailStateHolder = rememberSaveableStateHolder()
    val motionScheme = MaterialTheme.motionScheme
    val forwardDirection = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1

    AnimatedContent(
        targetState = state,
        modifier = Modifier.fillMaxWidth(),
        transitionSpec = {
            when (agentRunDetailMotion(initialState, targetState)) {
                AgentRunDetailMotion.PHASE -> {
                    (fadeIn() + slideInVertically(
                        animationSpec = motionScheme.defaultSpatialSpec(),
                        initialOffsetY = { it / 10 },
                    )) togetherWith (fadeOut() + slideOutVertically(
                        animationSpec = motionScheme.defaultSpatialSpec(),
                        targetOffsetY = { -it / 10 },
                    ))
                }
                AgentRunDetailMotion.FORWARD -> {
                    (fadeIn() + slideInHorizontally(
                        animationSpec = motionScheme.defaultSpatialSpec(),
                        initialOffsetX = { forwardDirection * it / 5 },
                    )) togetherWith (fadeOut() + slideOutHorizontally(
                        animationSpec = motionScheme.defaultSpatialSpec(),
                        targetOffsetX = { -forwardDirection * it / 5 },
                    ))
                }
                AgentRunDetailMotion.BACK -> {
                    (fadeIn() + slideInHorizontally(
                        animationSpec = motionScheme.defaultSpatialSpec(),
                        initialOffsetX = { -forwardDirection * it / 5 },
                    )) togetherWith (fadeOut() + slideOutHorizontally(
                        animationSpec = motionScheme.defaultSpatialSpec(),
                        targetOffsetX = { forwardDirection * it / 5 },
                    ))
                }
            }
        },
        contentKey = { it.animationKey() },
        label = "agent_run_detail_state",
    ) { detailState ->
        when (detailState) {
            AgentRunDetailState.Closed -> AgentRunDetailLoading(
                canNavigateBack = false,
                onNavigateBack = onNavigateBack,
            )
            is AgentRunDetailState.Loading -> AgentRunDetailLoading(
                canNavigateBack = detailState.canNavigateBack,
                onNavigateBack = onNavigateBack,
            )
            is AgentRunDetailState.Missing -> AgentRunDetailMissing(
                canNavigateBack = detailState.canNavigateBack,
                onNavigateBack = onNavigateBack,
            )
            is AgentRunDetailState.Content -> detailStateHolder.SaveableStateProvider(detailState.runId) {
                AgentRunDetailContent(
                    detail = detailState.detail,
                    canNavigateBack = detailState.canNavigateBack,
                    onNavigateBack = onNavigateBack,
                    onOpenApproval = onOpenApproval,
                    onOpenChildRun = onOpenChildRun,
                    onStop = onStop,
                    isStopping = isStopping,
                )
            }
        }
    }
}

@Composable
private fun AgentRunDetailLoading(
    canNavigateBack: Boolean,
    onNavigateBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AgentRunDetailBackAction(canNavigateBack, onNavigateBack)
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        Text(
            text = stringResource(R.string.agent_run_detail_loading),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun AgentRunDetailMissing(
    canNavigateBack: Boolean,
    onNavigateBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AgentRunDetailBackAction(canNavigateBack, onNavigateBack)
        Icon(
            imageVector = HugeIcons.AlertCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            text = stringResource(R.string.agent_run_detail_missing),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun AgentRunDetailBackAction(
    visible: Boolean,
    onNavigateBack: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
        exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut(),
    ) {
        IconButton(onClick = onNavigateBack) {
            Icon(
                imageVector = HugeIcons.ArrowLeft01,
                contentDescription = stringResource(R.string.agent_run_detail_back),
            )
        }
    }
}

@Composable
internal fun AgentRunDetailHeader(
    presentation: AgentRunPresentation,
    canNavigateBack: Boolean,
    onNavigateBack: () -> Unit,
    onOpenApproval: (() -> Unit)? = null,
    onStop: (() -> Unit)? = null,
    isStopping: Boolean = false,
    nowMillis: Long? = null,
) {
    val hapticFeedback = LocalHapticFeedback.current
    val targetTint = presentation.visualState.entryTint()
    val tint by animateColorAsState(targetTint, label = "agent_detail_header_tint")
    val ownLiveDuration = rememberLiveDurationLabel(
        presentation = presentation,
        enabled = nowMillis == null,
    )
    val liveDuration = nowMillis?.let { liveNow ->
        liveDurationLabel(presentation, liveNow)
    } ?: ownLiveDuration
    val identity = AgentRunDetailIdentity(
        shortRunId = presentation.runId.takeLast(8),
        status = presentation.status,
        duration = liveDuration,
    )
    val stoppingLabel = stringResource(R.string.agent_run_stopping)
    val activity = agentRunActivityText(
        visualState = presentation.visualState,
        waitingReason = presentation.waitingReason,
        currentStep = presentation.currentStep,
        statusDescription = null,
        isStopping = isStopping,
        stoppingLabel = stoppingLabel,
        waitingTelemetryLabel = "正在等待运行遥测",
    )
    val allowStateProgress = !isStopping
    val stepProgress = agentRunStepProgress(presentation.completedSteps, presentation.maxSteps)
    val stateFrame = AgentRunVisualProgressFrame(
        visualState = presentation.visualState,
        showsProgress = allowStateProgress && presentation.visualState.showsActiveProgress(),
        progress = stepProgress,
    )
    val stopAction = onStop.takeIf {
        presentation.visualState == AgentRunVisualState.PENDING ||
            presentation.visualState == AgentRunVisualState.WORKING ||
            presentation.visualState == AgentRunVisualState.NEEDS_ATTENTION
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = MotionScheme.expressive().defaultSpatialSpec()),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AgentRunDetailBackAction(canNavigateBack, onNavigateBack)
            AnimatedContent(
                targetState = stateFrame,
                contentKey = AgentRunVisualProgressFrame::contentKey,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "agent_detail_header_state",
            ) { frame ->
                Box(
                    modifier = Modifier.size(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (frame.showsProgress) {
                        AgentRunProgressRing(
                            progress = frame.progress,
                            color = tint,
                            trackColor = tint.copy(alpha = 0.16f),
                            size = 32.dp,
                            strokeWidth = 2.dp,
                        )
                    }
                    Icon(
                        imageVector = frame.visualState.icon(),
                        contentDescription = null,
                        modifier = Modifier.size(if (frame.showsProgress) 16.dp else 22.dp),
                        tint = tint,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.agent_run_detail_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                AnimatedContent(
                    targetState = identity,
                    contentKey = { frame -> frame.shortRunId to frame.status },
                    transitionSpec = {
                        (fadeIn() + slideInVertically { it / 3 }) togetherWith
                            (fadeOut() + slideOutVertically { -it / 3 })
                    },
                    label = "agent_detail_header_identity",
                ) { frame ->
                    Text(
                        text = stringResource(
                            R.string.agent_run_detail_identity,
                            frame.shortRunId,
                            frame.status,
                            frame.duration,
                        ),
                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                        color = tint,
                    )
                }
                AnimatedContent(
                    targetState = activity,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    transitionSpec = {
                        (fadeIn() + slideInVertically { it / 3 }) togetherWith
                            (fadeOut() + slideOutVertically { -it / 3 })
                    },
                    label = "agent_detail_header_activity",
                ) { text ->
                    if (text != null) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = stopAction != null || isStopping,
                enter = expandHorizontally(expandFrom = Alignment.End) + fadeIn(),
                exit = shrinkHorizontally(shrinkTowards = Alignment.End) + fadeOut(),
            ) {
                IconButton(
                    onClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                        stopAction?.invoke()
                    },
                    enabled = stopAction != null && !isStopping,
                ) {
                    AnimatedContent(
                        targetState = isStopping,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "agent_detail_header_stop",
                    ) { stopping ->
                        if (stopping) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(22.dp)
                                    .semantics { contentDescription = stoppingLabel },
                                color = tint,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = HugeIcons.StopCircle,
                                contentDescription = stringResource(R.string.stop),
                                modifier = Modifier.size(22.dp),
                                tint = tint,
                            )
                        }
                    }
                }
            }
        }
        AnimatedContent(
            targetState = presentation.statusDescription,
            transitionSpec = {
                agentRunTransientContentTransform(initialState != null, targetState != null)
            },
            label = "agent_detail_header_description",
        ) { description ->
            if (description != null) {
                Text(
                    text = description,
                    modifier = Modifier.padding(start = 54.dp, top = 8.dp, end = 24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        AnimatedVisibility(
            visible = onOpenApproval != null,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        ) {
            TextButton(
                onClick = { onOpenApproval?.invoke() },
                enabled = onOpenApproval != null,
                modifier = Modifier.padding(start = 42.dp, end = 16.dp),
            ) {
                Text(stringResource(R.string.agent_run_detail_open_approval))
                Icon(HugeIcons.ArrowRight01, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
internal fun AgentRunNewActivityButton(
    unseenCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motionScheme = MotionScheme.expressive()
    ExtendedFloatingActionButton(
        onClick = onClick,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        icon = {
            Icon(
                imageVector = HugeIcons.ArrowDown01,
                contentDescription = null,
            )
        },
        text = {
            AnimatedContent(
                targetState = unseenCount.coerceAtLeast(1),
                transitionSpec = {
                    when (agentRunCountMotion(initialState, targetState)) {
                        AgentRunCountMotion.INCREASE -> {
                            (fadeIn() + slideInVertically(
                                animationSpec = motionScheme.defaultSpatialSpec(),
                                initialOffsetY = { it / 2 },
                            )) togetherWith (fadeOut() + slideOutVertically(
                                animationSpec = motionScheme.defaultSpatialSpec(),
                                targetOffsetY = { -it / 2 },
                            ))
                        }
                        AgentRunCountMotion.DECREASE -> {
                            (fadeIn() + slideInVertically(
                                animationSpec = motionScheme.defaultSpatialSpec(),
                                initialOffsetY = { -it / 2 },
                            )) togetherWith (fadeOut() + slideOutVertically(
                                animationSpec = motionScheme.defaultSpatialSpec(),
                                targetOffsetY = { it / 2 },
                            ))
                        }
                        AgentRunCountMotion.STEADY -> fadeIn() togetherWith fadeOut()
                    }
                },
                label = "agent_run_unseen_count",
            ) { count ->
                Text(
                    pluralStringResource(
                        R.plurals.agent_run_new_activity,
                        count,
                        count,
                    )
                )
            }
        },
    )
}

@Composable
private fun AgentRunDetailContent(
    detail: AgentRunDetail,
    canNavigateBack: Boolean,
    onNavigateBack: () -> Unit,
    onOpenApproval: (AgentApprovalEntity) -> Unit,
    onOpenChildRun: (String) -> Unit,
    onStop: (() -> Unit)?,
    isStopping: Boolean,
) {
    val presentation = remember(detail) { detail.toPresentation() }
    val pendingApproval = detail.approvals.firstOrNull { it.status == "PENDING" }
    val detailNowMillis = rememberAgentRunClock(
        key = "${presentation.runId}:detail",
        enabled = presentation.visualState.isLive(),
    )
    AgentRunDetailHeader(
        presentation = presentation,
        canNavigateBack = canNavigateBack,
        onNavigateBack = onNavigateBack,
        onOpenApproval = pendingApproval?.let { approval -> { onOpenApproval(approval) } },
        onStop = onStop,
        isStopping = isStopping,
        nowMillis = detailNowMillis,
    )
}

@Suppress("unused")
@Composable
private fun AgentRunDetailTelemetryContent(
    detail: AgentRunDetail,
    canNavigateBack: Boolean,
    onNavigateBack: () -> Unit,
    onOpenApproval: (AgentApprovalEntity) -> Unit,
    onOpenChildRun: (String) -> Unit,
    onStop: (() -> Unit)?,
    isStopping: Boolean,
) {
    val presentation = remember(detail) { detail.toPresentation() }
    val pendingApproval = detail.approvals.firstOrNull { it.status == "PENDING" }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val activityKeys = remember(presentation.children, presentation.timeline) {
        agentRunActivityKeys(
            childRunIds = presentation.children.map(ChildRunPresentation::runId),
            timelineKeys = presentation.timeline.map(AgentRunTimelineItem::stableKey),
        )
    }
    val hasLiveDuration = presentation.visualState.isLive() ||
        presentation.children.any { it.visualState.isLive() } ||
        presentation.timeline.any { it.visualState.isLive() }
    val detailNowMillis = rememberAgentRunClock(
        key = "${presentation.runId}:detail",
        enabled = hasLiveDuration,
    )
    var knownActivityKeys by remember(presentation.runId) { mutableStateOf(activityKeys.toSet()) }
    var followingLatest by remember(presentation.runId) { mutableStateOf(false) }
    var hasObservedLayout by remember(presentation.runId) { mutableStateOf(false) }
    var autoScrollCount by remember(presentation.runId) { mutableIntStateOf(0) }
    var unseenCount by remember(presentation.runId) { mutableIntStateOf(0) }
    var displayedUnseenCount by remember(presentation.runId) { mutableIntStateOf(0) }

    suspend fun animateToLatest() {
        autoScrollCount += 1
        try {
            listState.animateScrollToItem(
                index = agentRunDetailLastItemIndex(
                    childCount = presentation.children.size,
                    timelineCount = presentation.timeline.size,
                ),
            )
        } finally {
            autoScrollCount = (autoScrollCount - 1).coerceAtLeast(0)
        }
    }

    LaunchedEffect(presentation.runId, listState) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val nearLatest = totalItems > 0 && lastVisibleIndex >= (totalItems - 2).coerceAtLeast(0)
            Triple(totalItems, listState.isScrollInProgress, nearLatest)
        }.collect { (totalItems, isScrolling, nearLatest) ->
            if (totalItems == 0) return@collect
            when {
                !hasObservedLayout -> {
                    followingLatest = nearLatest
                    hasObservedLayout = true
                }
                nearLatest -> {
                    followingLatest = true
                    unseenCount = 0
                }
                isScrolling && autoScrollCount == 0 -> followingLatest = false
            }
        }
    }

    LaunchedEffect(activityKeys) {
        val update = agentTimelineUpdate(
            previousKeys = knownActivityKeys,
            currentKeys = activityKeys,
            currentUnseenCount = unseenCount,
            followingLatest = followingLatest,
        )
        knownActivityKeys = update.currentKeys
        unseenCount = update.unseenCount
        if (update.unseenCount > 0) displayedUnseenCount = update.unseenCount
        if (update.shouldScrollToLatest) {
            withFrameNanos { }
            animateToLatest()
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                AgentRunDetailHeader(
                    presentation = presentation,
                    canNavigateBack = canNavigateBack,
                    onNavigateBack = onNavigateBack,
                    onOpenApproval = pendingApproval?.let { approval ->
                        { onOpenApproval(approval) }
                    },
                    onStop = onStop,
                    isStopping = isStopping,
                    nowMillis = detailNowMillis,
                )
            }
            item {
                AgentRunSection("基本信息") {
                    AgentRunInfo("模型", presentation.model ?: "未记录")
                    AgentRunInfo(
                        stringResource(R.string.agent_routing_label),
                        presentation.routing.displayLabel(),
                    )
                    AgentRunInfo(
                        "步骤",
                        presentation.maxSteps?.let { "${presentation.completedSteps}/$it" }
                            ?: "${presentation.completedSteps}",
                    )
                    presentation.runtimeVersion?.let { AgentRunInfo("运行时", it) }
                    presentation.waitingReason?.let { AgentRunInfo("等待原因", it) }
                    presentation.failureCategory?.let { AgentRunInfo("失败分类", it) }
                }
            }
            item { AgentRunRoutingSection(presentation.routing) }
            if (presentation.children.isNotEmpty()) {
                item(key = "child-runs-header") {
                    Text(
                        text = stringResource(R.string.agent_run_children_section),
                        modifier = Modifier.padding(horizontal = 24.dp).animateItem(),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                items(presentation.children, key = { it.runId }) { child ->
                    AgentChildRunCard(
                        child = child,
                        onOpen = { onOpenChildRun(child.runId) },
                        modifier = Modifier.padding(horizontal = 16.dp).animateItem(),
                        nowMillis = detailNowMillis,
                    )
                }
            }
            item {
                Text(
                    stringResource(R.string.agent_run_timeline_section),
                    modifier = Modifier.padding(horizontal = 24.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (presentation.timeline.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.agent_run_timeline_empty),
                        modifier = Modifier.padding(horizontal = 24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(presentation.timeline, key = AgentRunTimelineItem::stableKey) { item ->
                    AgentRunTimelineCard(
                        item = item,
                        modifier = Modifier.animateItem(),
                        nowMillis = detailNowMillis,
                    )
                }
            }
            item { androidx.compose.foundation.layout.Spacer(Modifier.height(88.dp)) }
        }

        AnimatedVisibility(
            visible = unseenCount > 0,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            AgentRunNewActivityButton(
                unseenCount = displayedUnseenCount,
                onClick = {
                    followingLatest = true
                    unseenCount = 0
                    coroutineScope.launch {
                        animateToLatest()
                    }
                },
            )
        }
    }
}

@Composable
internal fun AgentRunRoutingPresentation.displayLabel(): String = when (kind) {
    AgentRunRoutingKind.AUTO -> stringResource(
        R.string.agent_routing_auto_label,
        intent.intentLabel(),
    )
    AgentRunRoutingKind.LEGACY -> stringResource(
        R.string.agent_routing_legacy_label,
        legacyMode.safeLegacyModeLabel(),
    )
    AgentRunRoutingKind.UNAVAILABLE -> stringResource(R.string.agent_routing_unavailable_label)
}

@Composable
private fun AgentIntent?.intentLabel(): String = when (this) {
    AgentIntent.ANSWER -> stringResource(R.string.agent_intent_answer)
    AgentIntent.EXPLORE -> stringResource(R.string.agent_intent_explore)
    AgentIntent.EXECUTE -> stringResource(R.string.agent_intent_execute)
    AgentIntent.CLARIFY -> stringResource(R.string.agent_intent_clarify)
    null -> stringResource(R.string.agent_intent_unknown)
}

private fun String?.safeLegacyModeLabel(): String = when (this) {
    "CHAT" -> "Chat"
    "PLAN" -> "Plan"
    "AGENT" -> "Agent"
    else -> "Legacy"
}

@Composable
internal fun AgentRunRoutingSection(routing: AgentRunRoutingPresentation) {
    AgentRunSection(stringResource(R.string.agent_routing_section)) {
        when (routing.kind) {
            AgentRunRoutingKind.AUTO -> {
                AgentRunInfo(
                    stringResource(R.string.agent_routing_trust),
                    when (routing.inputTrust) {
                        InputTrust.USER_DIRECT -> stringResource(R.string.agent_routing_trust_user_direct)
                        InputTrust.DERIVED_UNTRUSTED -> stringResource(R.string.agent_routing_trust_derived)
                        null -> stringResource(R.string.agent_routing_unknown)
                    },
                )
                AgentRunInfo(
                    stringResource(R.string.agent_routing_reason),
                    routing.reasonCode ?: stringResource(R.string.agent_routing_reason_unknown),
                )
                AgentRunInfo(
                    stringResource(R.string.agent_routing_tool_count),
                    routing.toolCount.toString(),
                )
                val noToolsLabel = stringResource(R.string.agent_routing_tools_none)
                val toolNames = routing.visibleToolNames.joinToString(", ")
                    .ifBlank { noToolsLabel }
                    .let { if (routing.toolNamesTruncated) "$it …" else it }
                AgentRunInfo(stringResource(R.string.agent_routing_tools), toolNames)
                AgentRunInfo(
                    stringResource(R.string.agent_routing_permission_digest),
                    routing.permissionDigest ?: stringResource(R.string.agent_routing_unknown),
                )
                AgentRunInfo(
                    stringResource(R.string.agent_routing_policy_version),
                    routing.policyVersion ?: stringResource(R.string.agent_routing_unknown),
                )
            }

            AgentRunRoutingKind.LEGACY -> AgentRunInfo(
                stringResource(R.string.agent_routing_legacy),
                stringResource(R.string.agent_routing_legacy_description),
            )

            AgentRunRoutingKind.UNAVAILABLE -> AgentRunInfo(
                stringResource(R.string.agent_routing_degraded),
                when (routing.degradedReason) {
                    AgentRunRoutingDegradedReason.MALFORMED -> stringResource(R.string.agent_routing_degraded_malformed)
                    AgentRunRoutingDegradedReason.TOO_LARGE -> stringResource(R.string.agent_routing_degraded_too_large)
                    AgentRunRoutingDegradedReason.UNSUPPORTED -> stringResource(
                        R.string.agent_routing_degraded_unsupported
                    )
                    AgentRunRoutingDegradedReason.INVALID,
                    null,
                    -> stringResource(R.string.agent_routing_degraded_invalid)
                },
            )
        }
    }
}

@Composable
private fun AgentRunSection(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun AgentRunInfo(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private data class AgentRunTimelineDetails(
    val summary: String?,
    val outputSummary: String?,
    val failureCategory: String?,
    val approval: String?,
    val approvalReason: String?,
)

private fun AgentRunTimelineItem.timelineDetails(): AgentRunTimelineDetails? = AgentRunTimelineDetails(
    summary = summary,
    outputSummary = outputSummary,
    failureCategory = failureCategory,
    approval = approval,
    approvalReason = approvalReason,
).takeIf { details ->
    details.summary != null || details.outputSummary != null || details.failureCategory != null ||
        details.approval != null || details.approvalReason != null
}

@Composable
internal fun AgentRunTimelineCard(
    item: AgentRunTimelineItem,
    modifier: Modifier = Modifier,
    nowMillis: Long? = null,
) {
    val details = item.timelineDetails()
    val hasDetails = details != null
    var expanded by rememberSaveable(item.stableKey) {
        mutableStateOf(false)
    }
    val expandLabel = stringResource(R.string.agent_run_timeline_expand)
    val collapseLabel = stringResource(R.string.agent_run_timeline_collapse)
    val expandedLabel = stringResource(R.string.agent_run_timeline_expanded)
    val collapsedLabel = stringResource(R.string.agent_run_timeline_collapsed)
    val targetTint = item.visualState.entryTint()
    val tint by animateColorAsState(targetTint, label = "agent_timeline_tint")
    val duration = nowMillis?.let { now ->
        liveDurationLabel(
            duration = item.duration,
            durationStartedAt = item.durationStartedAt,
            visualState = item.visualState,
            nowMillis = now,
        )
    } ?: item.duration
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = MotionScheme.expressive().defaultSpatialSpec(),
        label = "agent_timeline_arrow",
    )
    val interactionModifier = if (hasDetails) {
        Modifier
            .clickable(
                onClickLabel = if (expanded) collapseLabel else expandLabel,
                onClick = { expanded = !expanded },
            )
            .semantics {
                stateDescription = if (expanded) expandedLabel else collapsedLabel
            }
    } else {
        Modifier
    }

    Card(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .then(interactionModifier)
            .animateContentSize(animationSpec = MotionScheme.expressive().defaultSpatialSpec()),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AnimatedContent(
                    targetState = item.visualState,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "agent_timeline_state",
                ) { state ->
                    val active = state.showsActiveProgress()
                    Box(
                        modifier = Modifier.size(28.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (active) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                color = tint,
                                trackColor = tint.copy(alpha = 0.16f),
                                strokeWidth = 2.dp,
                            )
                        }
                        Icon(
                            imageVector = state.icon(),
                            contentDescription = null,
                            modifier = Modifier.size(
                                if (active) 14.dp else 20.dp
                            ),
                            tint = tint,
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    AnimatedContent(
                        targetState = item.status,
                        transitionSpec = {
                            (fadeIn() + slideInVertically { it / 3 }) togetherWith
                                (fadeOut() + slideOutVertically { -it / 3 })
                        },
                        label = "agent_timeline_status",
                    ) { status ->
                        Text(
                            text = status,
                            style = MaterialTheme.typography.labelMedium,
                            color = tint,
                        )
                    }
                    Text(
                        text = stringResource(R.string.agent_run_timeline_duration, duration),
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AnimatedVisibility(
                    visible = hasDetails,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Icon(
                        imageVector = HugeIcons.ArrowRight01,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp).rotate(arrowRotation),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            AnimatedContent(
                targetState = details.takeIf { expanded },
                transitionSpec = {
                    agentRunTransientContentTransform(initialState != null, targetState != null)
                },
                label = "agent_timeline_details",
            ) { visibleDetails ->
                if (visibleDetails != null) {
                    Column(
                        modifier = Modifier.padding(start = 50.dp, end = 40.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        visibleDetails.summary?.let {
                            Text(
                                stringResource(R.string.agent_run_timeline_summary, it),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        visibleDetails.outputSummary?.let {
                            Text(
                                stringResource(R.string.agent_run_timeline_output_summary, it),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        visibleDetails.failureCategory?.let {
                            Text(
                                text = stringResource(R.string.agent_run_timeline_failure, it),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        visibleDetails.approval?.let {
                            Text(
                                stringResource(R.string.agent_run_timeline_approval, it),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        visibleDetails.approvalReason?.let {
                            Text(
                                stringResource(R.string.agent_run_timeline_approval_reason, it),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}
