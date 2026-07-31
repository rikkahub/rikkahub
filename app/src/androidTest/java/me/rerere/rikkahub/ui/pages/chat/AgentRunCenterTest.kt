package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import java.util.concurrent.atomic.AtomicInteger
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.agent.routing.AgentIntent
import me.rerere.rikkahub.data.ai.agent.routing.InputTrust
import me.rerere.rikkahub.data.db.entity.AgentRunEntity
import me.rerere.rikkahub.data.db.entity.ToolExecutionEntity
import me.rerere.rikkahub.data.model.AgentRunConfigSnapshot
import me.rerere.rikkahub.data.model.AgentRunStatus
import me.rerere.rikkahub.data.model.ToolExecutionStatus
import me.rerere.rikkahub.data.model.ToolExecutionSummary
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AgentRunCenterTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun progressRingRetainsDeterminateValueWhileCrossfadingToIndeterminate() {
        composeRule.mainClock.autoAdvance = false
        var progress by mutableStateOf<Float?>(0.75f)
        composeRule.setContent {
            MaterialTheme {
                AgentRunProgressRing(
                    progress = progress,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    size = 24.dp,
                    strokeWidth = 3.dp,
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(2_000)

        composeRule.runOnIdle { progress = null }
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo.Indeterminate,
            ),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        composeRule.onNode(
            SemanticsMatcher("determinate progress is retained above 70%") { node ->
                val key = SemanticsProperties.ProgressBarRangeInfo
                if (key !in node.config) {
                    false
                } else {
                    val rangeInfo = node.config[key]
                    rangeInfo != ProgressBarRangeInfo.Indeterminate && rangeInfo.current > 0.70f
                }
            },
            useUnmergedTree = true,
        ).assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(2_000)

        composeRule.onNode(
            SemanticsMatcher("outgoing determinate progress") { node ->
                val key = SemanticsProperties.ProgressBarRangeInfo
                key in node.config && node.config[key] != ProgressBarRangeInfo.Indeterminate
            },
            useUnmergedTree = true,
        ).assertDoesNotExist()
    }

    @Test
    fun systemBackReturnsToParentBeforeDismissingDetailSheet() {
        var currentState by mutableStateOf(
            detailState(
                canNavigateBack = true,
                runId = "child-run",
            )
        )
        val parentState = detailState(runId = "root-run")
        val navigationCount = AtomicInteger()
        val dismissalCount = AtomicInteger()

        composeRule.setContent {
            MaterialTheme {
                AgentRunDetailSheet(
                    state = currentState,
                    onDismiss = { dismissalCount.incrementAndGet() },
                    onOpenApproval = {},
                    onNavigateBack = {
                        navigationCount.incrementAndGet()
                        currentState = parentState
                    },
                )
            }
        }

        Espresso.pressBack()

        composeRule.waitUntil(timeoutMillis = 5_000) { navigationCount.get() == 1 }
        composeRule.runOnIdle {
            assertEquals(0, dismissalCount.get())
            assertEquals(parentState, currentState)
        }

        Espresso.pressBack()

        composeRule.waitUntil(timeoutMillis = 5_000) { dismissalCount.get() == 1 }
        assertEquals(1, navigationCount.get())
    }

    @Test
    fun activeCardExposesDeterminateStepProgressAndOpenAction() {
        composeRule.setContent {
            MaterialTheme {
                AgentRunActiveCard(
                    run = AgentRunPresentation(
                        runId = "run",
                        status = "运行中",
                        visualState = AgentRunVisualState.WORKING,
                        statusDescription = null,
                        model = "model",
                        routing = AgentRunRoutingPresentation(
                            kind = AgentRunRoutingKind.AUTO,
                            intent = AgentIntent.EXECUTE,
                        ),
                        runtimeVersion = null,
                        maxSteps = 4,
                        completedSteps = 1,
                        currentStep = "步骤 2 · tool",
                        waitingReason = null,
                        createdAt = 0,
                        duration = "1.00s",
                        failureCategory = null,
                        timeline = emptyList(),
                    ),
                    onOpen = {},
                )
            }
        }

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val routeLabel = context.getString(
            R.string.agent_routing_auto_label,
            context.getString(R.string.agent_intent_execute),
        )
        composeRule.onNodeWithText(routeLabel, substring = true).assertIsDisplayed()
        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.LiveRegion,
                LiveRegionMode.Polite,
            ),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(current = 0.25f, range = 0f..1f),
            )
        ).assertIsDisplayed()
        composeRule.onNodeWithText(routeLabel, substring = true).assertHasClickAction()
    }

    @Test
    fun activeCardRetainsCompleteRouteStatusFrameWhenRoutingChanges() {
        composeRule.mainClock.autoAdvance = false
        var run by mutableStateOf(runningPresentation())
        composeRule.setContent {
            MaterialTheme {
                AgentRunActiveCard(
                    run = run,
                    onOpen = {},
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(2_000)

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val autoRoute = context.getString(
            R.string.agent_routing_auto_label,
            context.getString(R.string.agent_intent_execute),
        )
        val unavailableRoute = context.getString(R.string.agent_routing_unavailable_label)
        val oldStatusLine = "$autoRoute · 运行中"
        val newStatusLine = "$unavailableRoute · 运行中"

        composeRule.runOnIdle {
            run = run.copy(
                routing = AgentRunRoutingPresentation(kind = AgentRunRoutingKind.UNAVAILABLE),
            )
        }
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithText(oldStatusLine).assertIsDisplayed()
        composeRule.onNodeWithText(newStatusLine).assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(2_000)

        composeRule.onNodeWithText(oldStatusLine).assertDoesNotExist()
        composeRule.onNodeWithText(newStatusLine).assertIsDisplayed()
    }

    @Test
    fun activeCardRetainsCompleteMetadataFrameDuringDirectionalProgressUpdate() {
        composeRule.mainClock.autoAdvance = false
        var run by mutableStateOf(
            runningPresentation().copy(
                status = "已完成",
                visualState = AgentRunVisualState.SUCCEEDED,
                duration = "1.00s",
            )
        )
        composeRule.setContent {
            MaterialTheme {
                AgentRunActiveCard(
                    run = run,
                    onOpen = {},
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(2_000)

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val oldMetadata = "model · 1/4 步 · ${context.getString(R.string.agent_run_elapsed, "1.00s")}"
        val newMetadata = "model · 2/4 步 · ${context.getString(R.string.agent_run_elapsed, "9.00s")}"
        composeRule.onNodeWithText(oldMetadata).assertIsDisplayed()

        composeRule.runOnIdle {
            run = run.copy(
                completedSteps = 2,
                duration = "9.00s",
            )
        }
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithText(oldMetadata).assertIsDisplayed()
        composeRule.onNodeWithText(newMetadata).assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(2_000)

        composeRule.onNodeWithText(oldMetadata).assertDoesNotExist()
        composeRule.onNodeWithText(newMetadata).assertIsDisplayed()
    }

    @Test
    fun activeCardDurationOnlyRefreshDoesNotStartMetadataTransition() {
        composeRule.mainClock.autoAdvance = false
        var run by mutableStateOf(
            runningPresentation().copy(
                status = "已完成",
                visualState = AgentRunVisualState.SUCCEEDED,
                duration = "1.00s",
            )
        )
        composeRule.setContent {
            MaterialTheme {
                AgentRunActiveCard(
                    run = run,
                    onOpen = {},
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(2_000)

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val oldMetadata = "model · 1/4 步 · ${context.getString(R.string.agent_run_elapsed, "1.00s")}"
        val newMetadata = "model · 1/4 步 · ${context.getString(R.string.agent_run_elapsed, "2.00s")}"
        composeRule.onNodeWithText(oldMetadata).assertIsDisplayed()

        composeRule.runOnIdle { run = run.copy(duration = "2.00s") }
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithText(oldMetadata).assertDoesNotExist()
        composeRule.onNodeWithText(newMetadata).assertIsDisplayed()
    }

    @Test
    fun activeCardRetainsDeterminateProgressWhileCrossfadingToIndeterminate() {
        composeRule.mainClock.autoAdvance = false
        var run by mutableStateOf(
            runningPresentation().copy(
                completedSteps = 3,
                maxSteps = 4,
            )
        )
        composeRule.setContent {
            MaterialTheme {
                AgentRunActiveCard(
                    run = run,
                    onOpen = {},
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(2_000)

        composeRule.runOnIdle { run = run.copy(maxSteps = null) }
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo.Indeterminate,
            ),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        composeRule.onNode(
            SemanticsMatcher("active card progress is retained above 70%") { node ->
                val key = SemanticsProperties.ProgressBarRangeInfo
                if (key !in node.config) {
                    false
                } else {
                    val rangeInfo = node.config[key]
                    rangeInfo != ProgressBarRangeInfo.Indeterminate && rangeInfo.current > 0.70f
                }
            },
            useUnmergedTree = true,
        ).assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(2_000)

        composeRule.onNode(
            SemanticsMatcher("outgoing active card determinate progress") { node ->
                val key = SemanticsProperties.ProgressBarRangeInfo
                key in node.config && node.config[key] != ProgressBarRangeInfo.Indeterminate
            },
            useUnmergedTree = true,
        ).assertDoesNotExist()
    }

    @Test
    fun activeCardRetainsDeterminateProgressSnapshotDuringTerminalExit() {
        composeRule.mainClock.autoAdvance = false
        var run by mutableStateOf(runningPresentation())
        composeRule.setContent {
            MaterialTheme {
                AgentRunActiveCard(
                    run = run,
                    onOpen = {},
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(2_000)

        composeRule.runOnIdle {
            run = run.copy(
                status = "已完成",
                visualState = AgentRunVisualState.SUCCEEDED,
                completedSteps = 4,
            )
        }
        composeRule.mainClock.advanceTimeBy(50)

        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(current = 0.25f, range = 0f..1f),
            )
        ).assertIsDisplayed()
        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(current = 1f, range = 0f..1f),
            )
        ).assertDoesNotExist()

        composeRule.mainClock.advanceTimeBy(2_000)

        composeRule.onNode(
            SemanticsMatcher("terminal active-card determinate progress") { node ->
                val key = SemanticsProperties.ProgressBarRangeInfo
                key in node.config && node.config[key] != ProgressBarRangeInfo.Indeterminate
            }
        ).assertDoesNotExist()
    }

    @Test
    fun activeCardRetainsDeterminateProgressDuringStoppingExit() {
        composeRule.mainClock.autoAdvance = false
        var isStopping by mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                AgentRunActiveCard(
                    run = runningPresentation(),
                    onOpen = {},
                    onStop = {},
                    isStopping = isStopping,
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(2_000)

        composeRule.runOnIdle { isStopping = true }
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(current = 0.25f, range = 0f..1f),
            )
        ).assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(2_000)

        composeRule.onNode(
            SemanticsMatcher("stopping active-card determinate progress") { node ->
                val key = SemanticsProperties.ProgressBarRangeInfo
                key in node.config && node.config[key] != ProgressBarRangeInfo.Indeterminate
            }
        ).assertDoesNotExist()
    }

    @Test
    fun activeCardFallsBackToIndeterminateProgressWithoutStepBudget() {
        composeRule.setContent {
            MaterialTheme {
                AgentRunActiveCard(
                    run = runningPresentation().copy(maxSteps = null),
                    onOpen = {},
                )
            }
        }

        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo.Indeterminate,
            )
        ).assertIsDisplayed()
    }

    @Test
    fun approvalCardShowsAttentionStateWithoutLoopingProgress() {
        composeRule.setContent {
            MaterialTheme {
                AgentRunActiveCard(
                    run = AgentRunPresentation(
                        runId = "run",
                        status = "等待审批",
                        visualState = AgentRunVisualState.NEEDS_ATTENTION,
                        statusDescription = null,
                        model = "model",
                        routing = AgentRunRoutingPresentation(
                            kind = AgentRunRoutingKind.AUTO,
                            intent = AgentIntent.EXECUTE,
                        ),
                        runtimeVersion = null,
                        maxSteps = 4,
                        completedSteps = 1,
                        currentStep = "步骤 2 · tool",
                        waitingReason = "此操作需要你的批准",
                        createdAt = 0,
                        duration = "1.00s",
                        failureCategory = null,
                        timeline = emptyList(),
                    ),
                    onOpen = {},
                )
            }
        }

        composeRule.onNodeWithText("此操作需要你的批准").assertIsDisplayed()
        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo.Indeterminate,
            )
        ).assertDoesNotExist()
    }

    @Test
    fun stopControlSwitchesToDuplicateSafeStoppingFeedback() {
        var stopClicks = 0
        val hapticFeedback = RecordingHapticFeedback()
        composeRule.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides hapticFeedback) {
                MaterialTheme {
                    var stopping by remember { mutableStateOf(false) }
                    AgentRunActiveCard(
                        run = runningPresentation(),
                        isStopping = stopping,
                        onStop = {
                            stopClicks += 1
                            stopping = true
                        },
                        onOpen = {},
                    )
                }
            }
        }

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.onNodeWithContentDescription(context.getString(R.string.stop))
            .assertIsDisplayed()
            .performClick()

        composeRule.waitForIdle()
        assertEquals(1, stopClicks)
        assertEquals(listOf(HapticFeedbackType.Confirm), hapticFeedback.feedbackTypes)
        composeRule.onNodeWithContentDescription(context.getString(R.string.stop)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(R.string.agent_run_stopping)).assertIsDisplayed()
    }

    @Test
    fun activeCardRetainsDisabledStopControlDuringNativeTerminalExit() {
        composeRule.mainClock.autoAdvance = false
        var run by mutableStateOf(runningPresentation())
        var stopAvailable by mutableStateOf(true)
        var stopClicks = 0
        composeRule.setContent {
            MaterialTheme {
                AgentRunActiveCard(
                    run = run,
                    onOpen = {},
                    onStop = if (stopAvailable) {
                        { stopClicks += 1 }
                    } else {
                        null
                    },
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(2_000)

        composeRule.runOnIdle {
            run = run.copy(
                status = "已完成",
                visualState = AgentRunVisualState.SUCCEEDED,
                currentStep = null,
            )
            stopAvailable = false
        }
        composeRule.mainClock.advanceTimeByFrame()

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.onNodeWithText("已完成", substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.stop))
            .assertIsDisplayed()
            .assertIsNotEnabled()
        assertEquals(0, stopClicks)

        composeRule.mainClock.advanceTimeBy(2_000)

        composeRule.onNodeWithContentDescription(context.getString(R.string.stop)).assertDoesNotExist()
    }

    @Test
    fun terminalAcknowledgementCardNeverExposesStaleStopAction() {
        var stopClicks = 0
        composeRule.setContent {
            MaterialTheme {
                AgentRunActiveCard(
                    run = runningPresentation().copy(
                        status = "已完成",
                        visualState = AgentRunVisualState.SUCCEEDED,
                        currentStep = null,
                    ),
                    onOpen = {},
                    onStop = { stopClicks += 1 },
                )
            }
        }

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.onNodeWithText("已完成", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("正在等待运行遥测").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(R.string.stop)).assertDoesNotExist()
        assertEquals(0, stopClicks)
    }

    @Test
    fun failedAcknowledgementCardShowsGuidanceInsteadOfStaleLiveActivity() {
        composeRule.setContent {
            MaterialTheme {
                AgentRunActiveCard(
                    run = runningPresentation().copy(
                        status = "失败",
                        visualState = AgentRunVisualState.FAILED,
                        statusDescription = "请在聊天中重新发起。",
                        currentStep = "过期步骤",
                        waitingReason = "过期等待原因",
                    ),
                    onOpen = {},
                )
            }
        }

        composeRule.onNodeWithText("请在聊天中重新发起。").assertIsDisplayed()
        composeRule.onNodeWithText("过期步骤").assertDoesNotExist()
        composeRule.onNodeWithText("过期等待原因").assertDoesNotExist()
    }

    @Test
    fun activeCardHostDoesNotReplayHistoricalTerminalRun() {
        composeRule.setContent {
            MaterialTheme {
                AgentRunActiveCardHost(
                    activeRun = null,
                    latestRun = runningPresentation().copy(
                        status = "已完成",
                        visualState = AgentRunVisualState.SUCCEEDED,
                        currentStep = null,
                    ),
                    stoppingRunId = null,
                    onOpen = {},
                    onStop = {},
                )
            }
        }

        composeRule.onNodeWithText("已完成", substring = true).assertDoesNotExist()
    }

    @Test
    fun activeCardHostTransitionsBetweenRunIdentitiesAndDisablesOutgoingCard() {
        composeRule.mainClock.autoAdvance = false
        var activeRun by mutableStateOf<AgentRunPresentation?>(
            runningPresentation().copy(
                runId = "run-a",
                model = "model-a",
            )
        )
        var latestRun by mutableStateOf(activeRun)
        val openedRunIds = mutableListOf<String>()
        composeRule.setContent {
            MaterialTheme {
                AgentRunActiveCardHost(
                    activeRun = activeRun,
                    latestRun = latestRun,
                    stoppingRunId = null,
                    onOpen = openedRunIds::add,
                    onStop = {},
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(2_000)

        composeRule.runOnIdle {
            val replacement = runningPresentation().copy(
                runId = "run-b",
                model = "model-b",
            )
            activeRun = replacement
            latestRun = replacement
        }
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithText("model-a", substring = true)
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeRule.onNodeWithText("model-b", substring = true)
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        assertEquals(listOf("run-b"), openedRunIds)

        composeRule.mainClock.advanceTimeBy(2_000)

        composeRule.onNodeWithText("model-a", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("model-b", substring = true).assertIsDisplayed()
    }

    @Test
    fun activeCardHostShowsTerminalStateBeforeNativeExit() {
        composeRule.mainClock.autoAdvance = false
        var activeRun by mutableStateOf<AgentRunPresentation?>(runningPresentation())
        var latestRun by mutableStateOf(activeRun)
        composeRule.setContent {
            MaterialTheme {
                AgentRunActiveCardHost(
                    activeRun = activeRun,
                    latestRun = latestRun,
                    stoppingRunId = null,
                    onOpen = {},
                    onStop = {},
                )
            }
        }
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.runOnIdle {
            val terminal = runningPresentation().copy(
                status = "已完成",
                visualState = AgentRunVisualState.SUCCEEDED,
                currentStep = "不应显示的过期步骤",
            )
            activeRun = null
            latestRun = terminal
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(400)

        composeRule.onNodeWithText("已完成", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("不应显示的过期步骤").assertDoesNotExist()
        composeRule.onNodeWithText("正在等待运行遥测").assertDoesNotExist()

        composeRule.mainClock.advanceTimeBy(2_000)
        composeRule.onNodeWithText("已完成", substring = true).assertDoesNotExist()
    }

    @Test
    fun runEntryTransitionsFromLiveProgressToTerminalStatus() {
        composeRule.mainClock.autoAdvance = false
        var openClicks = 0
        var latestRun by mutableStateOf(runningPresentation())
        composeRule.setContent {
            MaterialTheme {
                AgentRunEntry(
                    run = latestRun,
                    onOpen = { openClicks += 1 },
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(2_000)

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.onNodeWithContentDescription(context.getString(R.string.agent_run_entry_working))
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, openClicks)
        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(current = 0.25f, range = 0f..1f),
            )
        ).assertIsDisplayed()

        composeRule.runOnIdle {
            latestRun = latestRun.copy(
                status = "已完成",
                visualState = AgentRunVisualState.SUCCEEDED,
                completedSteps = 4,
            )
        }
        composeRule.mainClock.advanceTimeBy(50)

        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(current = 0.25f, range = 0f..1f),
            )
        ).assertIsDisplayed()
        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(current = 1f, range = 0f..1f),
            )
        ).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(R.string.agent_run_entry_succeeded))
            .assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(2_000)

        composeRule.onNode(
            SemanticsMatcher("terminal entry determinate progress") { node ->
                val key = SemanticsProperties.ProgressBarRangeInfo
                key in node.config && node.config[key] != ProgressBarRangeInfo.Indeterminate
            }
        ).assertDoesNotExist()
    }

    @Test
    fun runEntryFallsBackToIndeterminateProgressWithoutStepBudget() {
        composeRule.setContent {
            MaterialTheme {
                AgentRunEntry(
                    run = runningPresentation().copy(maxSteps = null),
                    onOpen = {},
                )
            }
        }

        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo.Indeterminate,
            )
        ).assertIsDisplayed()
    }

    @Test
    fun childRunCardTransitionsFromWorkingProgressToSucceededFinding() {
        var openClicks = 0
        var child by mutableStateOf(
            ChildRunPresentation(
                runId = "12345678",
                status = "运行中",
                visualState = AgentRunVisualState.WORKING,
                duration = "1.00s",
                findings = "",
            )
        )
        composeRule.setContent {
            MaterialTheme {
                AgentChildRunCard(
                    child = child,
                    onOpen = { openClicks += 1 },
                )
            }
        }

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.onNodeWithContentDescription(
            context.getString(
                R.string.agent_run_child_open_details,
                "12345678",
                "运行中",
            )
        ).assertIsDisplayed().performClick()
        assertEquals(1, openClicks)
        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo.Indeterminate,
            )
        ).assertIsDisplayed()

        composeRule.runOnIdle {
            child = child.copy(
                status = "已完成",
                visualState = AgentRunVisualState.SUCCEEDED,
                duration = "1.50s",
                findings = "Safe finding",
            )
        }
        composeRule.waitForIdle()

        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo.Indeterminate,
            )
        ).assertDoesNotExist()
        composeRule.onNodeWithText("Safe finding").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            context.getString(
                R.string.agent_run_child_open_details,
                "12345678",
                "已完成",
            )
        ).assertIsDisplayed()
    }

    @Test
    fun childRunCardRetainsFindingThroughNativeExit() {
        composeRule.mainClock.autoAdvance = false
        val finding = "退场期间仍应保留的子 Run 结论"
        var child by mutableStateOf(
            ChildRunPresentation(
                runId = "child-result",
                status = "已完成",
                visualState = AgentRunVisualState.SUCCEEDED,
                duration = "1.00s",
                findings = finding,
            )
        )
        composeRule.setContent {
            MaterialTheme {
                AgentChildRunCard(child = child, onOpen = {})
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText(finding).assertIsDisplayed()

        composeRule.runOnIdle { child = child.copy(findings = "") }
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithText(finding).assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(2_000)

        composeRule.onNodeWithText(finding).assertDoesNotExist()
    }

    @Test
    fun liveTimelineCardKeepsDetailsVisibleWhenItCompletes() {
        var item by mutableStateOf(
            timelineItem(
                status = "运行中",
                visualState = AgentRunVisualState.WORKING,
            )
        )
        composeRule.setContent {
            MaterialTheme {
                AgentRunTimelineCard(item = item)
            }
        }

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val safeSummary = context.getString(R.string.agent_run_timeline_summary, "Safe summary")
        composeRule.onNodeWithText(safeSummary).assertIsDisplayed()
        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo.Indeterminate,
            )
        ).assertIsDisplayed()

        composeRule.runOnIdle {
            item = item.copy(
                status = "已完成",
                visualState = AgentRunVisualState.SUCCEEDED,
                duration = "1.50s",
            )
        }
        composeRule.waitForIdle()

        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo.Indeterminate,
            )
        ).assertDoesNotExist()
        composeRule.onNodeWithText(safeSummary).assertIsDisplayed()
    }

    @Test
    fun timelineCardRetainsDetailSnapshotWhileRevokingExpansion() {
        composeRule.mainClock.autoAdvance = false
        var item by mutableStateOf(
            timelineItem(
                status = "运行中",
                visualState = AgentRunVisualState.WORKING,
            )
        )
        composeRule.setContent {
            MaterialTheme {
                AgentRunTimelineCard(item = item)
            }
        }
        composeRule.mainClock.advanceTimeByFrame()

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val safeSummary = context.getString(R.string.agent_run_timeline_summary, "Safe summary")
        val expandedDescription = context.getString(R.string.agent_run_timeline_expanded)
        composeRule.onNodeWithText(safeSummary).assertIsDisplayed()
        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                expandedDescription,
            )
        ).assertIsDisplayed().assertHasClickAction()

        composeRule.runOnIdle {
            item = item.copy(
                summary = null,
                outputSummary = null,
                failureCategory = null,
                approval = null,
                approvalReason = null,
            )
        }
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithText(safeSummary).assertIsDisplayed()
        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                expandedDescription,
            )
        ).assertDoesNotExist()

        composeRule.mainClock.advanceTimeBy(2_000)

        composeRule.onNodeWithText(safeSummary).assertDoesNotExist()
    }

    @Test
    fun completedTimelineCardStartsCompactAndExpandsAccessibly() {
        composeRule.setContent {
            MaterialTheme {
                AgentRunTimelineCard(
                    item = timelineItem(
                        status = "已完成",
                        visualState = AgentRunVisualState.SUCCEEDED,
                    )
                )
            }
        }

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val safeSummary = context.getString(R.string.agent_run_timeline_summary, "Safe summary")
        composeRule.onNodeWithText(safeSummary).assertDoesNotExist()
        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                context.getString(R.string.agent_run_timeline_collapsed),
            )
        ).assertIsDisplayed().assertHasClickAction().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(safeSummary).assertIsDisplayed()
        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                context.getString(R.string.agent_run_timeline_expanded),
            )
        ).assertIsDisplayed().assertHasClickAction()
    }

    @Test
    fun timelineCardAutoExpandsWhenAttentionIsNeeded() {
        var item by mutableStateOf(
            timelineItem(
                status = "等待中",
                visualState = AgentRunVisualState.PENDING,
            )
        )
        composeRule.setContent {
            MaterialTheme {
                AgentRunTimelineCard(item = item)
            }
        }

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val safeSummary = context.getString(R.string.agent_run_timeline_summary, "Safe summary")
        composeRule.onNodeWithText(safeSummary).assertDoesNotExist()
        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo.Indeterminate,
            )
        ).assertIsDisplayed()

        composeRule.runOnIdle {
            item = item.copy(
                status = "等待审批",
                visualState = AgentRunVisualState.NEEDS_ATTENTION,
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(safeSummary).assertIsDisplayed()
        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo.Indeterminate,
            )
        ).assertDoesNotExist()
        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                context.getString(R.string.agent_run_timeline_expanded),
            )
        ).assertIsDisplayed()
    }

    @Test
    fun newActivityButtonAnimatesCountAnnouncesAndInvokesLatestAction() {
        composeRule.mainClock.autoAdvance = false
        var clicks = 0
        var unseenCount by mutableIntStateOf(1)
        composeRule.setContent {
            MaterialTheme {
                AgentRunNewActivityButton(
                    unseenCount = unseenCount,
                    onClick = { clicks += 1 },
                )
            }
        }
        composeRule.mainClock.advanceTimeByFrame()

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val initialLabel = context.resources.getQuantityString(R.plurals.agent_run_new_activity, 1, 1)
        val targetLabel = context.resources.getQuantityString(R.plurals.agent_run_new_activity, 3, 3)
        composeRule.onNodeWithText(initialLabel, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.LiveRegion,
                LiveRegionMode.Polite,
            ),
            useUnmergedTree = true,
        ).assertIsDisplayed()

        composeRule.runOnIdle { unseenCount = 3 }
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithText(initialLabel, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText(targetLabel, useUnmergedTree = true).assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(2_000)

        composeRule.onNodeWithText(initialLabel, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText(targetLabel).assertIsDisplayed().performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun nestedCardsRenderDurationsFromOneSuppliedClock() {
        composeRule.setContent {
            MaterialTheme {
                Column {
                    AgentChildRunCard(
                        child = ChildRunPresentation(
                            runId = "child",
                            status = "running",
                            visualState = AgentRunVisualState.WORKING,
                            duration = "500ms",
                            findings = "",
                            durationStartedAt = 1_000,
                        ),
                        onOpen = {},
                        nowMillis = 3_500,
                    )
                    AgentRunTimelineCard(
                        item = timelineItem(
                            status = "running",
                            visualState = AgentRunVisualState.WORKING,
                        ).copy(durationStartedAt = 2_000),
                        nowMillis = 3_500,
                    )
                }
            }
        }

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.onNodeWithText(
            context.getString(R.string.agent_run_child_duration, "2.50s")
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.agent_run_timeline_duration, "1.50s")
        ).assertIsDisplayed()
    }

    @Test
    fun liveDurationTicksDoNotKeepPreviousTextInTransition() {
        composeRule.mainClock.autoAdvance = false
        var nowMillis by mutableLongStateOf(3_500)
        composeRule.setContent {
            MaterialTheme {
                Column {
                    AgentChildRunCard(
                        child = ChildRunPresentation(
                            runId = "child",
                            status = "running",
                            visualState = AgentRunVisualState.WORKING,
                            duration = "500ms",
                            findings = "",
                            durationStartedAt = 1_000,
                        ),
                        onOpen = {},
                        nowMillis = nowMillis,
                    )
                    AgentRunTimelineCard(
                        item = timelineItem(
                            status = "running",
                            visualState = AgentRunVisualState.WORKING,
                        ).copy(durationStartedAt = 250),
                        nowMillis = nowMillis,
                    )
                }
            }
        }

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val oldChildDuration = context.getString(R.string.agent_run_child_duration, "2.50s")
        val oldTimelineDuration = context.getString(R.string.agent_run_timeline_duration, "3.25s")
        composeRule.onNodeWithText(oldChildDuration).assertIsDisplayed()
        composeRule.onNodeWithText(oldTimelineDuration).assertIsDisplayed()

        composeRule.runOnIdle { nowMillis = 4_500 }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(oldChildDuration).assertDoesNotExist()
        composeRule.onNodeWithText(oldTimelineDuration).assertDoesNotExist()
        composeRule.onNodeWithText(
            context.getString(R.string.agent_run_child_duration, "3.50s")
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.agent_run_timeline_duration, "4.25s")
        ).assertIsDisplayed()
    }

    @Test
    fun detailPaneLoadingUsesNativeProgressFeedback() {
        composeRule.setContent {
            MaterialTheme {
                AgentRunDetailPane(state = AgentRunDetailState.Loading("run"))
            }
        }

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.onNodeWithText(context.getString(R.string.agent_run_detail_loading)).assertIsDisplayed()
        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo.Indeterminate,
            )
        ).assertIsDisplayed()
    }

    @Test
    fun nestedDetailHeaderExposesBackNavigation() {
        var backClicks = 0
        composeRule.setContent {
            MaterialTheme {
                AgentRunDetailPane(
                    state = detailState(canNavigateBack = true),
                    onNavigateBack = { backClicks += 1 },
                )
            }
        }

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.onNodeWithContentDescription(context.getString(R.string.agent_run_detail_back))
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, backClicks)
    }

    @Test
    fun nestedDetailNavigationRestoresExpandedParentTimelineState() {
        var state by mutableStateOf(
            detailState(
                runId = "root-run",
                withTimeline = true,
            )
        )
        composeRule.setContent {
            MaterialTheme {
                AgentRunDetailPane(state = state)
            }
        }

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val safeSummary = context.getString(R.string.agent_run_timeline_summary, "workspace / read")
        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                context.getString(R.string.agent_run_timeline_collapsed),
            )
        ).assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(safeSummary).assertIsDisplayed()

        composeRule.runOnIdle {
            state = detailState(runId = "child-run")
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(safeSummary).assertDoesNotExist()

        composeRule.runOnIdle {
            state = detailState(
                runId = "root-run",
                withTimeline = true,
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(safeSummary).assertIsDisplayed()
    }

    @Test
    fun detailPanePropagatesStopForRunningRootContent() {
        var stopClicks = 0
        composeRule.setContent {
            MaterialTheme {
                AgentRunDetailPane(
                    state = detailState(status = AgentRunStatus.RUNNING),
                    onStop = { stopClicks += 1 },
                )
            }
        }

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.onNodeWithContentDescription(context.getString(R.string.stop))
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, stopClicks)
    }

    @Test
    fun detailHeaderKeepsStopAvailableAndTransitionsToTerminalState() {
        var stopClicks = 0
        var presentation by mutableStateOf(
            runningPresentation().copy(currentStep = "Inspecting workspace")
        )
        var stopAvailable by mutableStateOf(true)
        var stopping by mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                AgentRunDetailHeader(
                    presentation = presentation,
                    canNavigateBack = false,
                    onNavigateBack = {},
                    onStop = if (stopAvailable) {
                        {
                            stopClicks += 1
                            stopping = true
                        }
                    } else {
                        null
                    },
                    isStopping = stopping,
                )
            }
        }

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.onNodeWithText("Inspecting workspace").assertIsDisplayed()
        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.LiveRegion,
                LiveRegionMode.Polite,
            ),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(current = 0.25f, range = 0f..1f),
            )
        ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.stop))
            .assertIsDisplayed()
            .performClick()
        composeRule.waitForIdle()

        assertEquals(1, stopClicks)
        composeRule.onNodeWithContentDescription(context.getString(R.string.stop)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(R.string.agent_run_stopping)).assertIsDisplayed()

        composeRule.runOnIdle {
            presentation = presentation.copy(
                status = "已完成",
                visualState = AgentRunVisualState.SUCCEEDED,
                currentStep = "不应显示的过期步骤",
                duration = "1.50s",
            )
            stopAvailable = false
            stopping = false
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(context.getString(R.string.stop)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(R.string.agent_run_stopping)).assertDoesNotExist()
        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo.Indeterminate,
            )
        ).assertDoesNotExist()
        composeRule.onNodeWithText("已完成", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("不应显示的过期步骤").assertDoesNotExist()
    }

    @Test
    fun detailHeaderRetainsOutgoingIdentityDuringNativeTerminalTransition() {
        composeRule.mainClock.autoAdvance = false
        var presentation by mutableStateOf(
            runningPresentation().copy(currentStep = "仍在处理")
        )
        composeRule.setContent {
            MaterialTheme {
                AgentRunDetailHeader(
                    presentation = presentation,
                    canNavigateBack = false,
                    onNavigateBack = {},
                    nowMillis = 1_000,
                )
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val oldIdentity = context.getString(
            R.string.agent_run_detail_identity,
            "run",
            "运行中",
            "1.00s",
        )
        val newIdentity = context.getString(
            R.string.agent_run_detail_identity,
            "run",
            "已完成",
            "9.00s",
        )
        composeRule.onNodeWithText(oldIdentity).assertIsDisplayed()
        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(current = 0.25f, range = 0f..1f),
            )
        ).assertIsDisplayed()

        composeRule.runOnIdle {
            presentation = presentation.copy(
                status = "已完成",
                visualState = AgentRunVisualState.SUCCEEDED,
                currentStep = "不应显示的过期步骤",
                duration = "9.00s",
                completedSteps = 4,
            )
        }
        composeRule.mainClock.advanceTimeBy(50)

        composeRule.onNodeWithText(oldIdentity).assertIsDisplayed()
        composeRule.onNodeWithText(newIdentity).assertIsDisplayed()
        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(current = 0.25f, range = 0f..1f),
            )
        ).assertIsDisplayed()
        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(current = 1f, range = 0f..1f),
            )
        ).assertDoesNotExist()

        composeRule.mainClock.advanceTimeBy(2_000)

        composeRule.onNodeWithText(oldIdentity).assertDoesNotExist()
        composeRule.onNodeWithText(newIdentity).assertIsDisplayed()
        composeRule.onNodeWithText("不应显示的过期步骤").assertDoesNotExist()
        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(current = 0.25f, range = 0f..1f),
            )
        ).assertDoesNotExist()
    }

    @Test
    fun detailHeaderRetainsProgressRingDuringStoppingPhaseTransition() {
        composeRule.mainClock.autoAdvance = false
        var isStopping by mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                AgentRunDetailHeader(
                    presentation = runningPresentation(),
                    canNavigateBack = false,
                    onNavigateBack = {},
                    onStop = {},
                    isStopping = isStopping,
                    nowMillis = 1_000,
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(2_000)

        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(current = 0.25f, range = 0f..1f),
            )
        ).assertIsDisplayed()

        composeRule.runOnIdle { isStopping = true }
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(current = 0.25f, range = 0f..1f),
            )
        ).assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(2_000)

        composeRule.onNode(
            SemanticsMatcher("stopped detail determinate progress") { node ->
                val key = SemanticsProperties.ProgressBarRangeInfo
                key in node.config && node.config[key] != ProgressBarRangeInfo.Indeterminate
            }
        ).assertDoesNotExist()
    }

    @Test
    fun detailHeaderDurationTickUpdatesWithoutStartingIdentityTransition() {
        composeRule.mainClock.autoAdvance = false
        var nowMillis by mutableLongStateOf(1_000)
        composeRule.setContent {
            MaterialTheme {
                AgentRunDetailHeader(
                    presentation = runningPresentation(),
                    canNavigateBack = false,
                    onNavigateBack = {},
                    nowMillis = nowMillis,
                )
            }
        }
        composeRule.mainClock.advanceTimeByFrame()

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val oldIdentity = context.getString(
            R.string.agent_run_detail_identity,
            "run",
            "运行中",
            "1.00s",
        )
        val newIdentity = context.getString(
            R.string.agent_run_detail_identity,
            "run",
            "运行中",
            "2.00s",
        )
        composeRule.onNodeWithText(oldIdentity).assertIsDisplayed()

        composeRule.runOnIdle { nowMillis = 2_000 }
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithText(oldIdentity).assertDoesNotExist()
        composeRule.onNodeWithText(newIdentity).assertIsDisplayed()
    }

    @Test
    fun detailHeaderRetainsFailureGuidanceThroughNativeExit() {
        composeRule.mainClock.autoAdvance = false
        val guidance = "保留到退场结束的失败说明"
        var presentation by mutableStateOf(
            runningPresentation().copy(
                status = "运行失败",
                visualState = AgentRunVisualState.FAILED,
                statusDescription = guidance,
            )
        )
        composeRule.setContent {
            MaterialTheme {
                AgentRunDetailHeader(
                    presentation = presentation,
                    canNavigateBack = false,
                    onNavigateBack = {},
                    nowMillis = 1_000,
                )
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText(guidance).assertIsDisplayed()

        composeRule.runOnIdle {
            presentation = presentation.copy(
                status = "已完成",
                visualState = AgentRunVisualState.SUCCEEDED,
                statusDescription = null,
            )
        }
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithText(guidance).assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(2_000)

        composeRule.onNodeWithText(guidance).assertDoesNotExist()
    }

    @Test
    fun detailHeaderRevokesApprovalInteractionBeforeNativeExitFinishes() {
        composeRule.mainClock.autoAdvance = false
        var approvalAvailable by mutableStateOf(true)
        var approvalClicks = 0
        composeRule.setContent {
            MaterialTheme {
                AgentRunDetailHeader(
                    presentation = runningPresentation(),
                    canNavigateBack = false,
                    onNavigateBack = {},
                    onOpenApproval = if (approvalAvailable) {
                        { approvalClicks += 1 }
                    } else {
                        null
                    },
                    nowMillis = 1_000,
                )
            }
        }
        composeRule.mainClock.advanceTimeByFrame()

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val approvalLabel = context.getString(R.string.agent_run_detail_open_approval)
        composeRule.onNodeWithText(approvalLabel)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        assertEquals(1, approvalClicks)

        composeRule.runOnIdle { approvalAvailable = false }
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithText(approvalLabel)
            .assertIsDisplayed()
            .assertHasNoClickAction()

        composeRule.mainClock.advanceTimeBy(2_000)

        composeRule.onNodeWithText(approvalLabel).assertDoesNotExist()
        assertEquals(1, approvalClicks)
    }

    @Test
    fun detailHeaderFallsBackToIndeterminateProgressWithoutStepBudget() {
        composeRule.setContent {
            MaterialTheme {
                AgentRunDetailHeader(
                    presentation = runningPresentation().copy(maxSteps = null),
                    canNavigateBack = false,
                    onNavigateBack = {},
                )
            }
        }

        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo.Indeterminate,
            )
        ).assertIsDisplayed()
    }

    @Test
    fun autoStatusIsInformationalAndHasNoClickAction() {
        composeRule.setContent {
            MaterialTheme { AgentAutoStatus(routing = null) }
        }

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.onNodeWithText(context.getString(R.string.agent_mode_auto))
            .assertIsDisplayed()
            .assertHasNoClickAction()
    }

    @Test
    fun autoStatusMapsEveryFrozenIntent() {
        composeRule.setContent {
            MaterialTheme {
                Column {
                    AgentIntent.entries.forEach { intent ->
                        AgentAutoStatus(
                            AgentRunRoutingPresentation(
                                kind = AgentRunRoutingKind.AUTO,
                                intent = intent,
                            )
                        )
                    }
                }
            }
        }

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        listOf(
            R.string.agent_intent_answer,
            R.string.agent_intent_explore,
            R.string.agent_intent_execute,
            R.string.agent_intent_clarify,
        ).forEach { intentLabel ->
            val expected = context.getString(
                R.string.agent_routing_auto_label,
                context.getString(intentLabel),
            )
            composeRule.onNodeWithText(expected).assertIsDisplayed()
        }
    }

    @Test
    fun legacySnapshotKeepsCompatibilityLabel() {
        composeRule.setContent {
            MaterialTheme {
                androidx.compose.material3.Text(
                    AgentRunRoutingPresentation(
                        kind = AgentRunRoutingKind.LEGACY,
                        legacyMode = "AGENT",
                    ).displayLabel()
                )
            }
        }

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.onNodeWithText(
            context.getString(R.string.agent_routing_legacy_label, "Agent")
        ).assertIsDisplayed()
    }

    @Test
    fun routingAuditShowsFrozenFactsAndTruncatedToolNames() {
        composeRule.setContent {
            MaterialTheme {
                AgentRunRoutingSection(
                    AgentRunRoutingPresentation(
                        kind = AgentRunRoutingKind.AUTO,
                        intent = AgentIntent.EXPLORE,
                        inputTrust = InputTrust.USER_DIRECT,
                        reasonCode = "explicit_exploration",
                        toolCount = 10,
                        visibleToolNames = listOf("ask_user", "workspace_read"),
                        toolNamesTruncated = true,
                        permissionDigest = "policy:v1",
                        policyVersion = "auto-intent-v1",
                    )
                )
            }
        }

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.onNodeWithText(
            context.getString(R.string.agent_routing_trust_user_direct)
        ).assertIsDisplayed()
        composeRule.onNodeWithText("explicit_exploration").assertIsDisplayed()
        composeRule.onNodeWithText("ask_user, workspace_read …").assertIsDisplayed()
        composeRule.onNodeWithText("policy:v1").assertIsDisplayed()
        composeRule.onNodeWithText("auto-intent-v1").assertIsDisplayed()
    }

    private fun runningPresentation() = AgentRunPresentation(
        runId = "run",
        status = "运行中",
        visualState = AgentRunVisualState.WORKING,
        statusDescription = null,
        model = "model",
        routing = AgentRunRoutingPresentation(
            kind = AgentRunRoutingKind.AUTO,
            intent = AgentIntent.EXECUTE,
        ),
        runtimeVersion = null,
        maxSteps = 4,
        completedSteps = 1,
        currentStep = "步骤 2 · tool",
        waitingReason = null,
        createdAt = 0,
        duration = "1.00s",
        failureCategory = null,
        timeline = emptyList(),
    )

    private fun timelineItem(
        status: String,
        visualState: AgentRunVisualState,
    ) = AgentRunTimelineItem(
        stableKey = "tool:timeline",
        sequence = 1,
        label = "工具 · workspace_read",
        status = status,
        visualState = visualState,
        duration = "1.00s",
        summary = "Safe summary",
        outputSummary = null,
        failureCategory = null,
        approval = null,
        approvalReason = null,
    )

    private fun detailState(
        canNavigateBack: Boolean = false,
        status: AgentRunStatus = AgentRunStatus.SUCCEEDED,
        runId: String = "child-run",
        withTimeline: Boolean = false,
    ) = AgentRunDetailState.Content(
        AgentRunDetail(
            run = AgentRunEntity(
                id = runId,
                conversationId = "conversation",
                assistantId = "assistant",
                parentRunId = if (runId == "root-run") null else "root-run",
                status = status.name,
                configSnapshotJson = JsonInstant.encodeToString(AgentRunConfigSnapshot()),
                createdAt = 0,
                updatedAt = 1_000,
            ),
            steps = emptyList(),
            tools = if (withTimeline) {
                listOf(
                    ToolExecutionEntity(
                        id = "$runId-tool",
                        runId = runId,
                        stepId = "orphan",
                        sequence = 0,
                        toolName = "workspace.read",
                        status = ToolExecutionStatus.SUCCEEDED.name,
                        summaryJson = JsonInstant.encodeToString(
                            ToolExecutionSummary(category = "workspace", operation = "read")
                        ),
                        createdAt = 0,
                        updatedAt = 1_000,
                    )
                )
            } else {
                emptyList()
            },
            approvals = emptyList(),
        ),
        canNavigateBack = canNavigateBack,
        navigationDepth = if (canNavigateBack) 2 else 1,
    )
}

private class RecordingHapticFeedback : HapticFeedback {
    val feedbackTypes = mutableListOf<HapticFeedbackType>()

    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        feedbackTypes += hapticFeedbackType
    }
}
