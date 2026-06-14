package me.rerere.rikkahub.data.ai.task

import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.runtime.GenerationChunk
import me.rerere.ai.runtime.task.TaskEvent
import me.rerere.ai.runtime.task.TaskSpec
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.subagent.SPAWN_TOOL_MODEL_NAME
import me.rerere.rikkahub.data.ai.subagent.SPAWN_TOOL_NAME
import me.rerere.rikkahub.data.ai.subagent.SubagentGenerate
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.repository.TaskRunRepository
import me.rerere.rikkahub.data.repository.fakes.FakeBoardTransactions
import me.rerere.rikkahub.data.repository.fakes.FakeTaskRunDAO
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * TASK_DEPTH_ONE (SPEC.md M4 + M2 rename): a spawned child's tool pool NEVER contains EITHER exact
 * spawn-tool name — the advertised [SPAWN_TOOL_MODEL_NAME] (`agent`) NOR the legacy execution alias
 * [SPAWN_TOOL_NAME] (`task`) — the structural recursion guard (depth bounded at 1). Every name that
 * merely LOOKS like one of them — the board family `task_*`, the `mcp__`-prefixed variants, the
 * plurals `tasks`/`agents`, casings like `Task` — must survive untouched.
 *
 * The property is pinned at BOTH seams that drive a child run ([TaskCoordinator.run] and
 * [TaskCoordinator.resume] via their [SubagentGenerate] collaborator): whatever pool a parent hands
 * the coordinator, the pool the engine receives for the child is the input minus exactly the two
 * spawn names. This is the production truth (not just the pure `stripSpawnTools` primitive, which is
 * pinned separately): if a future refactor of the coordinator forgot the strip — or stripped only
 * one of the two names — this test fails even though the primitive test still passes.
 */
class TaskDepthOnePropertyTest {

    private val subModel = Model(modelId = "sub-model", displayName = "Sub", id = Uuid.random())

    private fun settingsWith(vararg models: Model): Settings = Settings(
        chatModelId = Uuid.random(),
        providers = listOf(ProviderSetting.OpenAI(models = models.toList())),
    )

    private fun tool(name: String): Tool =
        Tool(name = name, description = name, execute = { emptyList() })

    private fun assistantMsg(text: String): UIMessage =
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text)))

    /**
     * The exact names the depth-1 guard must STRIP from any child pool: the two — and only the two —
     * spawn names. Held as a list so the survival checks below can exclude exactly these.
     */
    private val strippedNames = listOf(SPAWN_TOOL_MODEL_NAME, SPAWN_TOOL_NAME)

    /**
     * Names that are NOT a spawn tool but share a prefix/substring/case/plural — the board family,
     * the `mcp__`-prefixed impersonators (raw MCP tools are `mcp__`-prefixed at the build site, so a
     * tool literally named `task`/`agent` becomes `mcp__task`/`mcp__agent` and must NOT be stripped),
     * the plurals, and a differently-cased `Task`. Every one of these must survive the strip.
     */
    private val lookalikes = listOf(
        "task_create", "task_get", "task_list", "task_update",
        "mcp__agent", "mcp__task", "agents", "tasks", "taskz", "Task",
    )

    /**
     * Pools that interleave arbitrary names, the lookalike set, and BOTH exact spawn names (each
     * present twice so a strip that drops only the first occurrence is also caught), all shuffled.
     */
    private val arbPool: Arb<List<String>> = Arb.list(
        Arb.string(1..6).map { it },
        0..6,
    ).map { arbitrary ->
        (arbitrary + lookalikes + strippedNames + strippedNames).shuffled()
    }

    private fun capturingGenerate(sink: MutableList<Tool>): SubagentGenerate =
        { _, _, _, _, tools, _, _ ->
            sink.clear()
            sink.addAll(tools)
            flowOf(GenerationChunk.Messages(listOf(assistantMsg("done"))))
        }

    private fun assertStripsOnlySpawnNames(inputPool: List<String>, childNames: List<String>) {
        // BOTH exact spawn names are gone from the child pool (recursion guard, both names).
        strippedNames.forEach { spawn ->
            assertFalse(
                "the exact spawn name '$spawn' must never reach a child (recursion guard): $childNames",
                childNames.contains(spawn),
            )
        }
        // Every lookalike that was in the input pool survives untouched — the strip removes ONLY the
        // two exact spawn names, never a name that merely resembles one.
        lookalikes.forEach { look ->
            assertTrue(
                "lookalike '$look' must survive the depth-1 strip: $childNames",
                childNames.contains(look),
            )
        }
        // Exhaustive ONLY-the-two check: every input name that is not one of the two spawn names
        // must still be present, so the guard can never drop an arbitrary (non-spawn) name either.
        inputPool.filterNot { it in strippedNames }.forEach { kept ->
            assertTrue(
                "non-spawn name '$kept' must survive the depth-1 strip: $childNames",
                childNames.contains(kept),
            )
        }
    }

    @Test
    fun `run strips only the two exact spawn names and lookalikes survive`() {
        runBlocking {
            checkAll(300, arbPool) { names ->
                val captured = mutableListOf<Tool>()
                val coordinator = TaskCoordinator(generate = capturingGenerate(captured))
                coordinator.run(
                    sub = Assistant(name = "Sub", chatModelId = subModel.id),
                    prompt = "go",
                    parentModelId = null,
                    settings = settingsWith(subModel),
                    tools = names.map { tool(it) },
                )
                assertStripsOnlySpawnNames(names, captured.map { it.name })
            }
        }
    }

    @Test
    fun `resume strips only the two exact spawn names and lookalikes survive`() {
        runBlocking {
            checkAll(300, arbPool) { names ->
                // A resume can only run from a persisted Interrupted row (claimResume gates it), so
                // seed a real repository (fold through the actual reducer) to Interrupted first.
                val store = TaskRunRepository(
                    dao = FakeTaskRunDAO(),
                    transactions = FakeBoardTransactions(),
                    now = { 1_000L },
                )
                val taskId = Uuid.random()
                store.create(
                    TaskSpec(
                        taskId = taskId,
                        parentConversationId = Uuid.random(),
                        parentToolCallId = "call",
                        agentTypeId = "agent",
                        prompt = "go",
                    )
                )
                store.applyEvent(taskId, TaskEvent.Enqueued)
                store.applyEvent(taskId, TaskEvent.SlotClaimed)
                store.applyEvent(taskId, TaskEvent.ChildProgressed)
                store.applyEvent(taskId, TaskEvent.ProcessInterrupted("did half"))

                val captured = mutableListOf<Tool>()
                val coordinator = TaskCoordinator(generate = capturingGenerate(captured), store = store)
                coordinator.resume(
                    taskId = taskId,
                    sub = Assistant(name = "Sub", chatModelId = subModel.id),
                    prompt = "go",
                    progressSummary = "did half",
                    parentModelId = null,
                    settings = settingsWith(subModel),
                    tools = names.map { tool(it) },
                )
                assertStripsOnlySpawnNames(names, captured.map { it.name })
            }
        }
    }
}
