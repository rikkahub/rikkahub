package me.rerere.rikkahub.ui.components.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import me.rerere.rikkahub.R
import org.junit.Rule
import org.junit.Test

class ChainOfThoughtTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun chainLevelControlAnnouncesExpansionState() {
        composeRule.setContent {
            MaterialTheme {
                ChainOfThought(
                    steps = listOf(1, 2, 3),
                    stepKey = { step -> step },
                    collapsedVisibleCount = 1,
                ) { step ->
                    ChainOfThoughtStep(label = { Text("Step $step") })
                }
            }
        }

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val collapsed = context.getString(R.string.chain_of_thought_collapsed)
        val expanded = context.getString(R.string.chain_of_thought_expanded)
        composeRule.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, collapsed)
        ).assertIsDisplayed().assertHasClickAction().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(context.getString(R.string.chain_of_thought_collapse)).assertIsDisplayed()
        composeRule.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expanded)
        ).assertIsDisplayed().assertHasClickAction()
    }

    @Test
    fun individualStepAnnouncesExpansionStateAndRevealsDetails() {
        composeRule.setContent {
            MaterialTheme {
                ChainOfThought(
                    steps = listOf(Unit),
                    stepKey = { Unit },
                ) {
                    ChainOfThoughtStep(
                        label = { Text("Tool step") },
                        content = { Text("Tool details") },
                    )
                }
            }
        }

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.onNodeWithText("Tool details").assertDoesNotExist()
        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                context.getString(R.string.chain_of_thought_collapsed),
            )
        ).assertIsDisplayed().assertHasClickAction().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Tool details").assertIsDisplayed()
        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                context.getString(R.string.chain_of_thought_expanded),
            )
        ).assertIsDisplayed().assertHasClickAction()
    }

    @Test
    fun prependingStepKeepsExpansionStateWithItsStableIdentity() {
        lateinit var steps: MutableState<List<TestStep>>
        composeRule.setContent {
            MaterialTheme {
                steps = remember {
                    mutableStateOf(listOf(TestStep("B"), TestStep("C")))
                }
                ChainOfThought(
                    steps = steps.value,
                    stepKey = TestStep::id,
                    collapsedVisibleCount = 10,
                ) { step ->
                    ChainOfThoughtStep(
                        label = { Text("Step ${step.id}") },
                        content = { Text("Details ${step.id}") },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Step B").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Details B").assertIsDisplayed()

        composeRule.runOnIdle {
            steps.value = listOf(TestStep("A")) + steps.value
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Details A").assertDoesNotExist()
        composeRule.onNodeWithText("Details B").assertIsDisplayed()
        composeRule.onNodeWithText("Details C").assertDoesNotExist()
    }
}

private data class TestStep(val id: String)
