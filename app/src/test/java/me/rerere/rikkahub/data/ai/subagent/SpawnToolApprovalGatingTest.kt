package me.rerere.rikkahub.data.ai.subagent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.runtime.GenerationChunk
import me.rerere.ai.runtime.task.TaskApprovalDecision
import me.rerere.ai.runtime.task.TaskApprovalRequest
import me.rerere.ai.runtime.task.TaskBudget
import me.rerere.ai.runtime.task.TaskBudgetBreach
import me.rerere.ai.runtime.task.TaskBudgetUsage
import me.rerere.ai.runtime.task.TaskEvent
import me.rerere.ai.runtime.task.TaskSpec
import me.rerere.ai.runtime.task.TaskState
import me.rerere.ai.runtime.task.TaskToolPolicy
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.task.ExecutionHandleRegistry
import me.rerere.rikkahub.data.ai.task.ParentApprovalSurface
import me.rerere.rikkahub.data.ai.task.TaskApprovalRouter
import me.rerere.rikkahub.data.ai.task.TaskCoordinator
import me.rerere.rikkahub.data.ai.task.TaskRunStore
import me.rerere.rikkahub.data.ai.task.deniedChildToolResult
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * End-to-end Gap A wiring through the REAL spawn path: [buildSpawnTool] -> [gateSubagentTools]
 * -> [TaskApprovalRouter] -> a fake [ParentApprovalSurface], with the child driven by a fake
 * engine. Pins the maintainer decision #2 contract at the seam production uses:
 *
 *  - `Assistant.subagentApprovalAllowlist` is EFFECTIVE: an allowlisted needsApproval tool
 *    reaches the parent surface (namespaced under the run's task id) and executes on approval.
 *  - A non-allowlisted needsApproval tool auto-denies — never executes, never reaches the
 *    surface — and the reason lands in the task summary.
 *  - The child pool carries no needsApproval=true tool (the child runtime never gates itself).
 */
class SpawnToolApprovalGatingTest {

    private val subModel = Model(modelId = "sub-model", displayName = "Sub", id = Uuid.random())
    private val settings = Settings(
        chatModelId = Uuid.random(),
        providers = listOf(ProviderSetting.OpenAI(models = listOf(subModel))),
    )
    private val conversationId = Uuid.random()

    /** Records created specs + summaries; events fold nowhere (state legality is pinned elsewhere). */
    private class RecordingStore : TaskRunStore {
        val created = mutableListOf<TaskSpec>()
        val summaries = ConcurrentHashMap<Uuid, MutableList<Pair<String, String>>>()
        override suspend fun create(spec: TaskSpec): TaskState {
            created += spec
            return TaskState.Created
        }
        override suspend fun applyEvent(taskId: Uuid, event: TaskEvent): TaskState? = null
        override suspend fun claimResume(taskId: Uuid): Boolean = false
        override suspend fun appendEventSummary(taskId: Uuid, summary: String, kind: String): Long? {
            summaries.getOrPut(taskId) { mutableListOf() } += kind to summary
            return summaries.getValue(taskId).size.toLong()
        }
        override suspend fun recordUsage(taskId: Uuid, reported: TaskBudgetUsage, budget: TaskBudget): TaskBudgetBreach? = null
    }

    private class RecordingSurface(private val decide: TaskApprovalDecision) : ParentApprovalSurface {
        val forwarded = mutableListOf<Pair<String, TaskApprovalRequest>>()
        override suspend fun requestApproval(namespacedToolCallId: String, request: TaskApprovalRequest): TaskApprovalDecision {
            forwarded += namespacedToolCallId to request
            return decide
        }
    }

    @Test
    fun `the per-assistant allowlist is effective end-to-end through the spawn path`(): Unit = runBlocking {
        val sub = Assistant(
            name = "Researcher",
            chatModelId = subModel.id,
            spawnable = true,
            subagentApprovalAllowlist = listOf("ask_user"),
        )
        val store = RecordingStore()
        val surface = RecordingSurface(TaskApprovalDecision.Approved)
        val executed = ConcurrentHashMap.newKeySet<String>()
        val childOutputs = ConcurrentHashMap<String, String>()

        fun approvalTool(name: String): Tool = Tool(
            name = name,
            description = name,
            needsApproval = true,
            execute = {
                executed += name
                listOf(UIMessagePart.Text("$name ran"))
            },
        )

        val spawn = buildSpawnTool(
            spawnableAssistants = listOf(sub),
            coordinator = TaskCoordinator(
                generate = { _, _, _, _, tools, _, _ ->
                    flow {
                        assertTrue(
                            "the child pool must carry no needsApproval=true tool",
                            tools.none { it.needsApproval },
                        )
                        listOf("ask_user", "mcp__danger").forEach { name ->
                            val output = tools.single { it.name == name }.execute(buildJsonObject { put("p", name) })
                            childOutputs[name] = (output.single() as UIMessagePart.Text).text
                        }
                        emit(GenerationChunk.Messages(listOf(UIMessage.assistant("done"))))
                    }
                },
                store = store,
            ),
            parentModelId = null,
            settings = settings,
            registry = ExecutionHandleRegistry(),
            buildSubagentTools = { _, _ -> listOf(approvalTool("ask_user"), approvalTool("mcp__danger")) },
            releaseOrphanedClaims = {},
            approvalGateFor = { spawned ->
                TaskApprovalRouter(
                    policyFor = { TaskToolPolicy(approvalForwardAllowlist = spawned.subagentApprovalAllowlist.toSet()) },
                    surface = surface,
                    store = store,
                )
            },
            processingStatus = MutableStateFlow(null),
            progressLabel = { "running $it" },
            parentConversationId = conversationId,
        )

        spawn.execute(buildJsonObject {
            put("subagent", sub.name)
            put("prompt", "use your tools")
        })

        // Allowlisted: forwarded exactly once, namespaced under THIS run's task id, then executed.
        val taskId = store.created.single().taskId
        val (namespacedId, request) = surface.forwarded.single()
        assertEquals("ask_user", request.toolName)
        assertTrue(
            "the forwarded approval must be namespaced under the run's task id",
            namespacedId.startsWith("$taskId${TaskApprovalRouter.NAMESPACE_SEPARATOR}"),
        )
        assertEquals(setOf("ask_user"), executed)
        assertEquals("ask_user ran", childOutputs["ask_user"])

        // Non-allowlisted: auto-denied — denial result to the child, reason in the task summary.
        assertTrue(
            "the child resumes with the denial result, was: " + childOutputs["mcp__danger"],
            childOutputs["mcp__danger"]!!.contains("was denied and did not run"),
        )
        assertTrue(
            "the auto-deny reason must land in the task summary",
            store.summaries[taskId].orEmpty().any { (kind, text) ->
                kind == TaskApprovalRouter.SUMMARY_KIND_APPROVAL_DENIED && text.contains("mcp__danger")
            },
        )
    }
}
