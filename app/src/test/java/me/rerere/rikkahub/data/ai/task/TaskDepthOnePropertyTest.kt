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
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.subagent.SPAWN_TOOL_NAME
import me.rerere.rikkahub.data.ai.subagent.SubagentGenerate
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * TASK_DEPTH_ONE (SPEC.md M4): a spawned child's tool pool NEVER contains the exact spawn-tool
 * name `task` (the structural recursion guard, depth bounded at 1), while every name that merely
 * looks like it — `task_create`, `mcp__task`, `tasks`, `taskz` — survives.
 *
 * The property is pinned at the seam that actually drives a child run ([TaskCoordinator.run] via
 * its [SubagentGenerate] collaborator): whatever pool a parent hands the coordinator, the pool
 * the engine receives for the child is the input minus exactly `task`. This is the production
 * truth (not just the pure `filterToolsForSubagent` primitive, which is pinned separately): if a
 * future refactor of the coordinator forgot the strip, this test fails even though the primitive
 * test still passes.
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

    /** Names that are NOT the spawn tool but share a prefix/substring — must all survive. */
    private val lookalikes = listOf("task_create", "task_get", "task_list", "task_update", "mcp__task", "tasks", "taskz", "Task")

    @Test
    fun `the child pool never contains the exact spawn name and lookalikes survive`() {
        runBlocking {
            // Generate pools that interleave the reserved name, lookalikes, and arbitrary names.
            val arbPool: Arb<List<String>> = Arb.list(
                Arb.string(1..6).map { it },
                0..6,
            ).map { arbitrary ->
                (arbitrary + lookalikes + listOf(SPAWN_TOOL_NAME, SPAWN_TOOL_NAME)).shuffled()
            }

            checkAll(300, arbPool) { names ->
                val capturedTools = mutableListOf<Tool>()
                val generate: SubagentGenerate = { _, _, _, _, tools, _, _ ->
                    capturedTools.clear()
                    capturedTools.addAll(tools)
                    flowOf(GenerationChunk.Messages(listOf(assistantMsg("done"))))
                }
                val coordinator = TaskCoordinator(generate = generate)

                coordinator.run(
                    sub = Assistant(name = "Sub", chatModelId = subModel.id),
                    prompt = "go",
                    parentModelId = null,
                    settings = settingsWith(subModel),
                    tools = names.map { tool(it) },
                )

                val capturedNames = capturedTools.map { it.name }
                assertFalse(
                    "the exact spawn name must never reach a child (recursion guard): $capturedNames",
                    capturedNames.contains(SPAWN_TOOL_NAME),
                )
                // Every lookalike that was in the input pool survives untouched.
                lookalikes.forEach { look ->
                    assertTrue(
                        "lookalike '$look' must survive the depth-1 strip: $capturedNames",
                        capturedNames.contains(look),
                    )
                }
            }
        }
    }
}
