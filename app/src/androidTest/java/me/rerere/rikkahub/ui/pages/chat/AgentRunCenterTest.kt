package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.agent.routing.AgentIntent
import me.rerere.rikkahub.data.ai.agent.routing.InputTrust
import org.junit.Rule
import org.junit.Test

class AgentRunCenterTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun activeCardExposesStatusAndOpenAction() {
        composeRule.setContent {
            MaterialTheme {
                AgentRunActiveCard(
                    run = AgentRunPresentation(
                        runId = "run",
                        status = "运行中",
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
}
