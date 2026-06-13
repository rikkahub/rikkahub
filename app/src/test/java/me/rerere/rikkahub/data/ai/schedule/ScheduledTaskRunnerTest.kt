package me.rerere.rikkahub.data.ai.schedule

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.runtime.GenerationChunk
import me.rerere.ai.runtime.contract.MisfirePolicy
import me.rerere.ai.runtime.contract.ScheduleKind
import me.rerere.ai.runtime.contract.ScheduleOwner
import me.rerere.ai.runtime.contract.ScheduleSnapshot
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.subagent.SubagentGenerate
import me.rerere.rikkahub.data.ai.task.TaskCoordinator
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ScheduleClaim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * JVM unit tests for [ScheduledTaskRunner] (SPEC.md M5 / task T8). The runner is the seam that
 * turns a winning [ScheduleClaim] into a real task run by REUSING [TaskCoordinator.run] — the same
 * lifecycle-tracked, budget/concurrency-gated machine a live `task` spawn drives — instead of
 * inventing a second runner. Driven by a FAKE engine seam ([SubagentGenerate]) and a real
 * [TaskCoordinator]: no Context / WorkManager / Room / network.
 *
 * The two contracts these tests pin (the T8 acceptance):
 *  - the runner reuses [TaskCoordinator.run] (the engine seam is invoked with the RESOLVED target
 *    assistant, the schedule's prompt, the current settings) and returns the run's TERMINAL run id;
 *  - a scheduled run has NO live approval surface, so approval-gated child tools auto-DENY through
 *    the existing `gateSubagentTools` empty-allowlist path — the real approval-gated tool never runs.
 */
class ScheduledTaskRunnerTest {

    private val targetModel = Model(modelId = "target-model", displayName = "Target", id = Uuid.random())

    private fun settingsWith(vararg models: Model): Settings = Settings(
        chatModelId = Uuid.random(),
        providers = listOf(ProviderSetting.OpenAI(models = models.toList())),
    )

    private fun assistantMsg(text: String): UIMessage =
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text)))

    /** Capture of the engine-seam arguments, so a passing test proves real reuse, not a stub call. */
    private class Captured {
        lateinit var assistant: Assistant
        lateinit var messages: List<UIMessage>
        lateinit var tools: List<Tool>
        var invoked: Boolean = false
    }

    private fun capturingGenerate(captured: Captured, emit: List<GenerationChunk>): SubagentGenerate =
        { _, _, messages, assistant, tools, _, _ ->
            captured.invoked = true
            captured.assistant = assistant
            captured.messages = messages
            captured.tools = tools
            flowOf(*emit.toTypedArray())
        }

    private fun snapshot(targetAssistantId: Uuid, prompt: String): ScheduleSnapshot = ScheduleSnapshot(
        id = Uuid.random(),
        targetAssistantId = targetAssistantId,
        prompt = prompt,
        owner = ScheduleOwner.USER,
        kind = ScheduleKind.ONE_SHOT,
        firstFireAt = 1_000L,
        nextFireAt = 1_000L,
        timeZoneId = "UTC",
        recurrenceSpec = null,
        misfirePolicy = MisfirePolicy.FIRE_ONCE_AND_COALESCE,
        enabled = false,
        lastFiredAt = 1_000L,
        lastTaskRunId = null,
        runningTaskRunId = Uuid.random(),
    )

    private fun runner(
        captured: Captured,
        target: Assistant,
        settings: Settings,
        emit: List<GenerationChunk>,
        childTools: List<Tool> = emptyList(),
    ): ScheduledTaskRunner = ScheduledTaskRunner(
        coordinator = TaskCoordinator(generate = capturingGenerate(captured, emit)),
        resolveAssistant = { id -> if (id == target.id) target else null },
        currentSettings = { settings },
        buildChildTools = { childTools },
    )

    @Test
    fun `firing a claim reuses TaskCoordinator_run with the resolved target and prompt`() = runBlocking {
        val captured = Captured()
        val target = Assistant(id = Uuid.random(), name = "Briefer", chatModelId = targetModel.id, spawnable = true)
        val claim = ScheduleClaim(runId = Uuid.random(), snapshot = snapshot(target.id, "morning briefing"))
        val runner = runner(captured, target, settingsWith(targetModel), listOf(GenerationChunk.Messages(listOf(assistantMsg("done")))))

        runner.run(claim, parentConversationId = Uuid.random())

        assertTrue("the runner must reuse TaskCoordinator.run -> the engine seam is invoked", captured.invoked)
        // The RESOLVED target assistant drove the run (memory forced off by the coordinator, same id).
        assertEquals(target.id, captured.assistant.id)
        // The schedule's prompt is the child's seed message.
        assertEquals("morning briefing", (captured.messages.last().parts.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `the run id returned is the claim's run id the coordinator persisted under`() = runBlocking {
        val captured = Captured()
        val target = Assistant(id = Uuid.random(), name = "Briefer", chatModelId = targetModel.id, spawnable = true)
        val claim = ScheduleClaim(runId = Uuid.random(), snapshot = snapshot(target.id, "go"))
        val runner = runner(captured, target, settingsWith(targetModel), listOf(GenerationChunk.Messages(listOf(assistantMsg("ok")))))

        val terminalRunId = runner.run(claim, parentConversationId = Uuid.random())

        assertEquals("the worker finishes the schedule against the run it just drove", claim.runId, terminalRunId)
    }

    @Test
    fun `approval-gated child tools auto-deny because a scheduled run has no live approval surface`() = runBlocking {
        val captured = Captured()
        val target = Assistant(id = Uuid.random(), name = "Briefer", chatModelId = targetModel.id, spawnable = true)
        var realToolRan = false
        val gatedTool = Tool(
            name = "delete_everything",
            description = "destructive",
            needsApproval = true,
            execute = { realToolRan = true; listOf(UIMessagePart.Text("DID THE DESTRUCTIVE THING")) },
        )
        val runner = runner(
            captured, target, settingsWith(targetModel),
            listOf(GenerationChunk.Messages(listOf(assistantMsg("ok")))),
            childTools = listOf(gatedTool),
        )

        runner.run(ScheduleClaim(runId = Uuid.random(), snapshot = snapshot(target.id, "go")), parentConversationId = Uuid.random())

        val childGated = captured.tools.single { it.name == "delete_everything" }
        // The child runtime never gates anything itself: the gate is the only decision point, so the
        // tool reaches the engine with needsApproval flipped off.
        assertFalse("a gated child tool must reach the engine with needsApproval=false", childGated.needsApproval)
        // Invoking it auto-denies (no live approval surface) WITHOUT executing the real destructive tool.
        val result = childGated.execute(JsonNull)
        assertFalse("the real approval-gated tool must NOT run on a scheduled fire", realToolRan)
        val text = (result.single() as UIMessagePart.Text).text
        assertTrue("an auto-denied call must surface a denial, was: $text", text.contains("denied"))
    }
}
