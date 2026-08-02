package me.rerere.rikkahub.ui.components.message

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ToolApprovalActionsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun idleActionsExposeApproveAndDenyControls() {
        composeRule.setContent {
            MaterialTheme {
                ToolApprovalActions(
                    isSubmitting = false,
                    onDeny = {},
                    onApprove = {},
                )
            }
        }

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.onNodeWithContentDescription(context.getString(R.string.chat_message_tool_deny))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.chat_message_tool_approve))
            .assertIsDisplayed()
    }

    @Test
    fun submittingTransitionRevokesOutgoingControlsWithProgressFeedback() {
        composeRule.mainClock.autoAdvance = false
        val hapticFeedback = RecordingHapticFeedback()
        var approveClicks = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides hapticFeedback) {
                MaterialTheme {
                    var isSubmitting by remember { mutableStateOf(false) }
                    ToolApprovalActions(
                        isSubmitting = isSubmitting,
                        onDeny = {},
                        onApprove = {
                            approveClicks += 1
                            isSubmitting = true
                        },
                    )
                }
            }
        }
        composeRule.mainClock.advanceTimeByFrame()

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.onNodeWithContentDescription(context.getString(R.string.chat_message_tool_approve))
            .performClick()
        composeRule.mainClock.advanceTimeByFrame()

        assertEquals(1, approveClicks)
        assertEquals(listOf(HapticFeedbackType.Confirm), hapticFeedback.feedbackTypes)
        composeRule.onNodeWithContentDescription(context.getString(R.string.chat_message_tool_deny))
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(context.getString(R.string.chat_message_tool_approve))
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(context.getString(R.string.chat_message_tool_approval_submitting))
            .assertIsDisplayed()
        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.LiveRegion,
                LiveRegionMode.Polite,
            ),
            useUnmergedTree = true,
        ).assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(2_000)

        composeRule.onNodeWithContentDescription(context.getString(R.string.chat_message_tool_deny))
            .assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(R.string.chat_message_tool_approve))
            .assertDoesNotExist()
    }

    @Test
    fun approvalActionsUseSemanticHapticFeedback() {
        val hapticFeedback = RecordingHapticFeedback()
        var approveClicks = 0
        var denyClicks = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides hapticFeedback) {
                MaterialTheme {
                    ToolApprovalActions(
                        isSubmitting = false,
                        onDeny = { denyClicks += 1 },
                        onApprove = { approveClicks += 1 },
                    )
                }
            }
        }

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.onNodeWithContentDescription(context.getString(R.string.chat_message_tool_approve))
            .performClick()
        composeRule.onNodeWithContentDescription(context.getString(R.string.chat_message_tool_deny))
            .performClick()

        assertEquals(1, approveClicks)
        assertEquals(1, denyClicks)
        assertEquals(
            listOf(HapticFeedbackType.Confirm, HapticFeedbackType.ContextClick),
            hapticFeedback.feedbackTypes,
        )
    }

    @Test
    fun answerSubmitButtonLocksImmediatelyWithNativeProgressFeedback() {
        val hapticFeedback = RecordingHapticFeedback()
        var submitClicks = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides hapticFeedback) {
                MaterialTheme {
                    var isSubmitting by remember { mutableStateOf(false) }
                    ToolAnswerSubmitButton(
                        enabled = true,
                        isSubmitting = isSubmitting,
                        onSubmit = {
                            submitClicks += 1
                            isSubmitting = true
                            true
                        },
                    )
                }
            }
        }

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.onNodeWithText(context.getString(R.string.chat_message_tool_submit))
            .assertIsDisplayed()
            .performClick()
        composeRule.waitForIdle()

        assertEquals(1, submitClicks)
        assertEquals(listOf(HapticFeedbackType.Confirm), hapticFeedback.feedbackTypes)
        composeRule.onNodeWithText(context.getString(R.string.chat_message_tool_submit)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.chat_message_tool_answer_submitting)
        ).assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.LiveRegion,
                LiveRegionMode.Polite,
            ),
            useUnmergedTree = true,
        ).assertIsDisplayed()
    }

    @Test
    fun askUserCompletionLocksRetainedEditorDuringDirectionalExit() {
        composeRule.mainClock.autoAdvance = false
        lateinit var responseFrame: MutableState<AskUserResponseFrame>
        var editorClicks = 0
        composeRule.setContent {
            MaterialTheme {
                responseFrame = remember {
                    mutableStateOf(AskUserResponseFrame(mode = AskUserResponseMode.Editing))
                }
                AskUserResponseTransition(
                    targetFrame = responseFrame.value,
                    isSubmitting = false,
                ) { frame, interactionEnabled ->
                    when (frame.mode) {
                        AskUserResponseMode.Editing -> TextButton(
                            enabled = interactionEnabled,
                            onClick = { editorClicks += 1 },
                        ) {
                            Text("Editable answer")
                        }
                        AskUserResponseMode.Answered -> Text(frame.answer.orEmpty())
                        AskUserResponseMode.ReadOnly -> Unit
                    }
                }
            }
        }
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.runOnIdle {
            responseFrame.value = AskUserResponseFrame(
                mode = AskUserResponseMode.Answered,
                answer = "Saved answer",
            )
        }
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithText("Editable answer")
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeRule.onNodeWithText("Saved answer").assertIsDisplayed()
        assertEquals(0, editorClicks)

        composeRule.mainClock.advanceTimeBy(2_000)

        composeRule.onNodeWithText("Editable answer").assertDoesNotExist()
        composeRule.onNodeWithText("Saved answer").assertIsDisplayed()
    }

    @Test
    fun toolStatusMessageOwnsReplacementAndNullableExitFrames() {
        composeRule.mainClock.autoAdvance = false
        lateinit var message: MutableState<String?>
        composeRule.setContent {
            MaterialTheme {
                message = remember { mutableStateOf("First error") }
                ToolStatusMessage(message = message.value)
            }
        }
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.runOnIdle { message.value = "Second error" }
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithText("First error").assertIsDisplayed()
        composeRule.onNodeWithText("Second error").assertIsDisplayed()
        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.LiveRegion,
                LiveRegionMode.Polite,
            ),
            useUnmergedTree = true,
        ).assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(2_000)
        composeRule.onNodeWithText("First error").assertDoesNotExist()

        composeRule.runOnIdle { message.value = null }
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithText("Second error").assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(2_000)
        composeRule.onNodeWithText("Second error").assertDoesNotExist()
    }

    @Test
    fun askUserDraftSurvivesApprovalRenewalAndStateRestoration() {
        val restorationTester = StateRestorationTester(composeRule)
        var tool by mutableStateOf(
            UIMessagePart.Tool(
                toolCallId = "ask-call",
                toolName = "ask_user",
                input = "{\"questions\":[{\"id\":\"choice\"}]}",
                toolExecutionId = "execution-1",
                approvalId = "approval-1",
            )
        )
        lateinit var draftState: MutableState<AskUserAnswerDraft>

        restorationTester.setContent {
            draftState = rememberAskUserAnswerDraft(tool)
            Text(draftState.value.answers["choice"].orEmpty())
        }

        composeRule.runOnIdle {
            draftState.value = draftState.value.withAnswer("choice", "保留这份答案")
        }
        composeRule.onNodeWithText("保留这份答案").assertIsDisplayed()

        composeRule.runOnIdle {
            tool = tool.copy(
                approvalId = "approval-2",
                approvalStatusMessage = "approval renewed",
            )
        }
        composeRule.onNodeWithText("保留这份答案").assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("保留这份答案").assertIsDisplayed()
    }
}

private class RecordingHapticFeedback : HapticFeedback {
    val feedbackTypes = mutableListOf<HapticFeedbackType>()

    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        feedbackTypes += hapticFeedbackType
    }
}
