package me.rerere.rikkahub.data.ai.task

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.runtime.GenerationChunk
import me.rerere.ai.runtime.contract.TurnConfig
import me.rerere.ai.runtime.subagent.resolveSubagentModel
import me.rerere.ai.runtime.task.TaskBudget
import me.rerere.ai.runtime.task.TaskBudgetCap
import me.rerere.ai.runtime.task.TaskBudgetUsage
import me.rerere.ai.runtime.task.TaskEvent
import me.rerere.ai.runtime.task.TaskSpec
import me.rerere.ai.runtime.task.TaskState
import me.rerere.ai.runtime.task.TaskStateReducer
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.runtime.toAssistantConfig
import me.rerere.rikkahub.data.ai.subagent.SPAWN_TOOL_NAME
import me.rerere.rikkahub.data.ai.subagent.SubagentGenerate
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * JVM unit tests for [TaskCoordinator] (SPEC.md M4), driven by a FAKE engine (the [SubagentGenerate]
 * collaborator) and an in-memory [TaskRunStore] fake — no Context / Provider / network / Room.
 *
 * The coordinator is the product replacement for `SubagentRunner`: it must keep that runner's
 * parity behaviors (memory OFF, model resolution, final-text extraction, spawn-tool strip,
 * maxSteps) AND additionally persist a task-run row, drive the [TaskStateReducer] to a terminal,
 * enforce the [TaskBudget] (steps/tokens/wall-time + per-parent 1 / global 1 concurrency), and
 * never bypass `generateText` (the engine seam is always invoked).
 */
class TaskCoordinatorTest {

    private val parentModel = Model(modelId = "parent-model", displayName = "Parent", id = Uuid.random())
    private val subModel = Model(modelId = "sub-model", displayName = "Sub", id = Uuid.random())

    private fun settingsWith(vararg models: Model): Settings = Settings(
        chatModelId = Uuid.random(),
        providers = listOf(ProviderSetting.OpenAI(models = models.toList())),
    )

    private fun tool(name: String): Tool = Tool(name = name, description = name, execute = { emptyList() })

    private fun assistantMsg(text: String): UIMessage =
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text)))

    /** Capture of the engine-seam arguments, for parity assertions against SubagentRunner. */
    private class Captured {
        lateinit var assistant: Assistant
        lateinit var model: Model
        lateinit var tools: List<Tool>
        var maxSteps: Int = -1
        var invoked: Boolean = false
    }

    private fun capturingGenerate(captured: Captured, emit: List<GenerationChunk>): SubagentGenerate =
        { _, model, _, assistant, tools, maxSteps, _ ->
            captured.invoked = true
            captured.assistant = assistant
            captured.model = model
            captured.tools = tools
            captured.maxSteps = maxSteps
            flowOf(*emit.toTypedArray())
        }

    /**
     * In-memory [TaskRunStore]: folds events through the real reducer (so the test exercises the
     * SAME legality the repository enforces), records cumulative usage, and exposes the event log
     * + state for assertions.
     */
    private class FakeStore(private val budget: () -> TaskBudget = { TaskBudget() }) : TaskRunStore {
        val states = ConcurrentHashMap<Uuid, TaskState>()
        val events = ConcurrentHashMap<Uuid, MutableList<TaskEvent>>()
        val usage = ConcurrentHashMap<Uuid, TaskBudgetUsage>()
        val created = mutableListOf<TaskSpec>()
        val summaries = ConcurrentHashMap<Uuid, MutableList<String>>()

        override suspend fun create(spec: TaskSpec): TaskState {
            created += spec
            states[spec.taskId] = TaskState.Created
            events[spec.taskId] = mutableListOf()
            usage[spec.taskId] = TaskBudgetUsage()
            summaries[spec.taskId] = mutableListOf()
            return TaskState.Created
        }

        override suspend fun applyEvent(taskId: Uuid, event: TaskEvent): TaskState? {
            val current = states[taskId] ?: return null
            events.getValue(taskId) += event
            val next = TaskStateReducer.reduce(current, event)
            states[taskId] = next
            return next
        }

        override suspend fun claimResume(taskId: Uuid): Boolean {
            // Atomic CAS on the in-memory state map, mirroring the repository's transactional claim.
            var won = false
            states.compute(taskId) { _, current ->
                if (current is TaskState.Interrupted) {
                    won = true
                    TaskState.Resuming
                } else {
                    current
                }
            }
            return won
        }

        override suspend fun appendEventSummary(taskId: Uuid, summary: String, kind: String): Long? {
            summaries.getOrPut(taskId) { mutableListOf() } += summary
            return summaries.getValue(taskId).size.toLong()
        }

        override suspend fun recordUsage(taskId: Uuid, reported: TaskBudgetUsage, budget: TaskBudget) =
            usage.compute(taskId) { _, prev -> (prev ?: TaskBudgetUsage()).record(reported) }
                .let { budget.firstBreach(it!!) }
    }

    private fun coordinator(
        generate: SubagentGenerate,
        store: TaskRunStore = FakeStore(),
        budget: TaskBudget = TaskBudget(),
        clock: () -> Duration = { Duration.ZERO },
    ) = TaskCoordinator(
        generate = generate,
        store = store,
        defaultBudget = budget,
        monotonicNow = clock,
    )

    // --- parity with SubagentRunner ------------------------------------------------------------

    @Test
    fun `child forces memory OFF even when the sub has it ON`() {
        val captured = Captured()
        val sub = Assistant(
            name = "Researcher", chatModelId = subModel.id,
            enableMemory = true, useGlobalMemory = true, enableRecentChatsReference = true,
        )
        val coordinator = coordinator(capturingGenerate(captured, listOf(GenerationChunk.Messages(listOf(assistantMsg("done"))))))

        runBlocking { coordinator.run(sub = sub, prompt = "go", parentModelId = parentModel.id, settings = settingsWith(subModel)) }

        assertTrue("engine seam (generateText) must always be invoked — never bypassed", captured.invoked)
        assertFalse("child must not write/read parent memory", captured.assistant.enableMemory)
        assertFalse("child must not inject recent-chats", captured.assistant.enableRecentChatsReference)
    }

    @Test
    fun `model resolves via resolveSubagentModel and inherits the parent when sub pins none`() {
        val captured = Captured()
        val sub = Assistant(name = "Sub", chatModelId = null)
        val settings = settingsWith(parentModel)
        val coordinator = coordinator(capturingGenerate(captured, listOf(GenerationChunk.Messages(listOf(assistantMsg("ok"))))))

        runBlocking { coordinator.run(sub = sub, prompt = "go", parentModelId = parentModel.id, settings = settings) }

        val turn = TurnConfig(defaultModelId = settings.chatModelId, providers = emptyList(), assistants = emptyList())
        assertEquals(resolveSubagentModel(sub.toAssistantConfig(), parentModel.id, turn), captured.model.id)
    }

    @Test
    fun `run returns extractFinalAssistantText of the terminal messages`() {
        val captured = Captured()
        val sub = Assistant(name = "Sub", chatModelId = subModel.id)
        val emit = listOf(
            GenerationChunk.Messages(listOf(assistantMsg("partial"))),
            GenerationChunk.Messages(listOf(UIMessage.user("go"), assistantMsg("final answer"))),
        )
        val coordinator = coordinator(capturingGenerate(captured, emit))

        val result = runBlocking { coordinator.run(sub = sub, prompt = "go", parentModelId = null, settings = settingsWith(subModel)) }

        assertEquals("final answer", result.text)
    }

    @Test
    fun `run threads the child tool pool to the engine and strips the spawn tool`() {
        val captured = Captured()
        val sub = Assistant(name = "Sub", chatModelId = subModel.id)
        val coordinator = coordinator(capturingGenerate(captured, listOf(GenerationChunk.Messages(listOf(assistantMsg("x"))))))

        runBlocking {
            coordinator.run(
                sub = sub, prompt = "go", parentModelId = null, settings = settingsWith(subModel),
                tools = listOf(tool("mcp__search"), tool(SPAWN_TOOL_NAME), tool("use_skill")),
            )
        }

        assertEquals(listOf("mcp__search", "use_skill"), captured.tools.map { it.name })
    }

    @Test
    fun `maxSteps defaults to the budget ceiling and is overridable by the sub`() {
        val captured = Captured()
        val coordinator = coordinator(capturingGenerate(captured, listOf(GenerationChunk.Messages(listOf(assistantMsg("x"))))))

        runBlocking {
            coordinator.run(sub = Assistant(name = "Sub", chatModelId = subModel.id, maxSteps = null), prompt = "go", parentModelId = null, settings = settingsWith(subModel))
        }
        assertEquals(TaskBudget.DEFAULT_MAX_STEPS, captured.maxSteps)

        val captured2 = Captured()
        val coordinator2 = coordinator(capturingGenerate(captured2, listOf(GenerationChunk.Messages(listOf(assistantMsg("x"))))))
        runBlocking {
            coordinator2.run(sub = Assistant(name = "Sub", chatModelId = subModel.id, maxSteps = 7), prompt = "go", parentModelId = null, settings = settingsWith(subModel))
        }
        assertEquals(7, captured2.maxSteps)
    }

    // --- task-run row + reducer events ---------------------------------------------------------

    @Test
    fun `a successful run creates a task row and drives the reducer to Succeeded`() {
        val store = FakeStore()
        val sub = Assistant(name = "Sub", chatModelId = subModel.id)
        val coordinator = coordinator(
            capturingGenerate(Captured(), listOf(GenerationChunk.Messages(listOf(assistantMsg("final answer"))))),
            store = store,
        )

        val result = runBlocking { coordinator.run(sub = sub, prompt = "go", parentModelId = null, settings = settingsWith(subModel)) }

        assertEquals(1, store.created.size)
        val taskId = store.created.single().taskId
        // The lifecycle reached Succeeded carrying the final answer.
        assertEquals(TaskState.Succeeded("final answer"), store.states[taskId])
        assertEquals("final answer", result.text)
        assertEquals(TaskState.Succeeded("final answer"), result.state)
        // The legal happy-path event sequence was emitted in order (Enqueued -> SlotClaimed ->
        // ChildProgressed -> FinalResult).
        val kinds = store.events.getValue(taskId).map { it::class }
        assertTrue("Enqueued must precede SlotClaimed", kinds.indexOf(TaskEvent.Enqueued::class) < kinds.indexOf(TaskEvent.SlotClaimed::class))
        assertTrue("a final result must terminate the run", store.events.getValue(taskId).any { it is TaskEvent.FinalResult })
    }

    @Test
    fun `child progress is persisted as an event summary so recovery resumes from real progress`() {
        // Review finding #3: the coordinator records state + usage but never persisted a progress
        // summary, so recovery (recoverInterruptedRuns reads the LAST event summary) always saw an
        // empty history and re-spawned interrupted runs as a fresh prompt — repeating side effects.
        // A run that progresses must append the child's latest assistant text as a progress summary.
        val store = FakeStore()
        val sub = Assistant(name = "Sub", chatModelId = subModel.id)
        val emit = listOf(
            GenerationChunk.Messages(listOf(assistantMsg("looked up the weather"))),
            GenerationChunk.Messages(listOf(assistantMsg("looked up the weather"), assistantMsg("booked the flight"))),
        )
        val coordinator = coordinator(capturingGenerate(Captured(), emit), store = store)

        runBlocking { coordinator.run(sub = sub, prompt = "go", parentModelId = null, settings = settingsWith(subModel)) }

        val taskId = store.created.single().taskId
        val persisted = store.summaries.getValue(taskId)
        assertTrue("each progress chunk must persist a non-empty progress summary", persisted.isNotEmpty())
        assertTrue(
            "the last persisted summary must reflect the child's latest progress so resume injects it; was: $persisted",
            persisted.last().contains("booked the flight"),
        )
    }

    @Test
    fun `a provider failure drives the reducer to Failed and surfaces the error as output`() {
        val store = FakeStore()
        val failing: SubagentGenerate = { _, _, _, _, _, _, _ ->
            flow<GenerationChunk> { throw IllegalStateException("boom") }
        }
        val coordinator = coordinator(failing, store = store)

        val result = runBlocking {
            coordinator.run(sub = Assistant(name = "Sub", chatModelId = subModel.id), prompt = "go", parentModelId = null, settings = settingsWith(subModel))
        }

        val taskId = store.created.single().taskId
        assertTrue("a provider failure must terminate as Failed", store.states[taskId] is TaskState.Failed)
        // The tool output is a structured error string, not a thrown exception (existing tool-error-as-output behavior).
        assertTrue("the error must surface in the returned output: ${result.text}", result.text.contains("boom"))
        assertTrue("the terminal state must be Failed", result.state is TaskState.Failed)
    }

    // --- budget enforcement --------------------------------------------------------------------

    @Test
    fun `exceeding the step budget drives the reducer to BudgetExhausted`() {
        val store = FakeStore()
        // Steps are counted from the assistant turns the child produced; a transcript with more
        // assistant messages than the step cap is a step-budget breach.
        val reporting: SubagentGenerate = { _, _, _, _, _, _, _ ->
            val turns = (1..5).map { assistantMsg("turn $it") }
            flowOf(GenerationChunk.Messages(turns))
        }
        val coordinator = coordinator(reporting, store = store, budget = TaskBudget(maxSteps = 2))

        runBlocking {
            coordinator.run(sub = Assistant(name = "Sub", chatModelId = subModel.id), prompt = "go", parentModelId = null, settings = settingsWith(subModel))
        }

        val taskId = store.created.single().taskId
        assertTrue("a step-cap breach must terminate as BudgetExhausted", store.states[taskId] is TaskState.BudgetExhausted)
    }

    @Test
    fun `a budget breach stops collecting the child flow instead of letting it keep running`() {
        // The child flow emits a breaching chunk and then KEEPS emitting. A budget breach must
        // cancel the collection so the child stops producing chunks / running tools after the cap —
        // `return@collect` only skips one chunk, leaving the child to run on (review finding #3).
        val store = FakeStore()
        val emitted = AtomicInteger(0)
        val collected = AtomicInteger(0)
        val running: SubagentGenerate = { _, _, _, _, _, _, _ ->
            flow {
                // First chunk already breaches the 1-step cap (one assistant turn => 1 step, with a
                // cap of 0 the first fold breaches). Then the child tries to keep going.
                repeat(5) { i ->
                    emitted.incrementAndGet()
                    emit(GenerationChunk.Messages(listOf(assistantMsg("turn $i"))))
                }
            }
        }
        val coordinator = coordinator(running, store = store, budget = TaskBudget(maxSteps = 0))

        runBlocking {
            // Wrap the seam so we observe how many chunks the coordinator actually consumed.
            val countingCoordinator = coordinator(
                generate = { s, m, msgs, a, t, ms, ps ->
                    kotlinx.coroutines.flow.flow {
                        running(s, m, msgs, a, t, ms, ps).collect {
                            collected.incrementAndGet()
                            emit(it)
                        }
                    }
                },
                store = store,
                budget = TaskBudget(maxSteps = 0),
            )
            countingCoordinator.run(sub = Assistant(name = "Sub", chatModelId = subModel.id), prompt = "go", parentModelId = null, settings = settingsWith(subModel))
        }

        val taskId = store.created.single().taskId
        assertTrue("a step-cap breach must terminate as BudgetExhausted", store.states[taskId] is TaskState.BudgetExhausted)
        assertEquals("collection must stop at the first breaching chunk, not drain the child flow", 1, collected.get())
    }

    @Test
    fun `exceeding the wall-time budget drives the reducer to BudgetExhausted`() {
        val store = FakeStore()
        // The clock jumps 11 minutes between startedAt and the first usage fold, past the default
        // 10-minute wall-time cap. A frozen ZERO clock (the DI bug, review finding #1) would leave
        // elapsed = 0 and never breach — this test fails if the wall-time fold is not wired.
        val ticks = ArrayDeque(listOf(Duration.ZERO, 11.minutes))
        val steppingClock = { ticks.removeFirst() }
        val reporting: SubagentGenerate = { _, _, _, _, _, _, _ ->
            flowOf(GenerationChunk.Messages(listOf(assistantMsg("turn"))))
        }
        val coordinator = coordinator(reporting, store = store, clock = steppingClock)

        runBlocking {
            coordinator.run(sub = Assistant(name = "Sub", chatModelId = subModel.id), prompt = "go", parentModelId = null, settings = settingsWith(subModel))
        }

        val taskId = store.created.single().taskId
        val terminal = store.states[taskId]
        assertTrue("a wall-time breach must terminate as BudgetExhausted, was $terminal", terminal is TaskState.BudgetExhausted)
        assertEquals(TaskBudgetCap.WallTime, (terminal as TaskState.BudgetExhausted).breach.cap)
    }

    // --- concurrency gating --------------------------------------------------------------------

    @Test
    fun `global concurrency 2 caps simultaneous in-flight children and queues the rest`() {
        val maxConcurrent = AtomicInteger(0)
        val peak = AtomicInteger(0)
        // Three gates the test releases one at a time; each child blocks on its gate while "running".
        val gates = List(3) { CompletableDeferred<Unit>() }
        val started = List(3) { CompletableDeferred<Unit>() }
        val counter = AtomicInteger(0)

        val gatedGenerate: SubagentGenerate = { _, _, _, _, _, _, _ ->
            val idx = counter.getAndIncrement()
            flow {
                val live = maxConcurrent.incrementAndGet()
                peak.updateAndGet { maxOf(it, live) }
                started[idx].complete(Unit)
                gates[idx].await()
                maxConcurrent.decrementAndGet()
                emit(GenerationChunk.Messages(listOf(assistantMsg("done $idx"))))
            }
        }
        // Global cap 2, per-parent cap is irrelevant here (each run is its own parent toolcall).
        val coordinator = coordinator(gatedGenerate, budget = TaskBudget(globalConcurrency = 2, perParentConcurrency = 2))

        runBlocking {
            coroutineScope {
                val jobs = (0..2).map {
                    launch {
                        coordinator.run(sub = Assistant(name = "Sub", chatModelId = subModel.id), prompt = "go$it", parentModelId = null, settings = settingsWith(subModel))
                    }
                }
                // Exactly two children may be live at once; release them so the third can proceed.
                started[0].await(); started[1].await()
                assertEquals("only 2 children may run concurrently", 2, peak.get())
                gates[0].complete(Unit)
                started[2].await() // the third only starts after a slot frees
                gates[1].complete(Unit); gates[2].complete(Unit)
                jobs.forEach { it.join() }
            }
        }
        assertEquals("the global concurrency cap of 2 was never exceeded", 2, peak.get())
    }

    @Test
    fun `per-parent mutex entries are evicted so the registry stays bounded`() {
        // Every spawn uses a UNIQUE parentToolCallId — the exact hot-path shape that leaked one
        // permanent map entry per spawn for the life of the process-singleton coordinator (review
        // finding #3). After all runs complete the registry must be empty, not N-deep.
        val coordinator = coordinator(capturingGenerate(Captured(), listOf(GenerationChunk.Messages(listOf(assistantMsg("ok"))))))

        runBlocking {
            repeat(50) { i ->
                coordinator.run(
                    sub = Assistant(name = "Sub", chatModelId = subModel.id),
                    prompt = "go$i",
                    parentModelId = null,
                    settings = settingsWith(subModel),
                    parentToolCallId = "call-$i",
                )
            }
            assertEquals("every per-parent mutex entry must be evicted once its run completes", 0, coordinator.parentMutexCount())
        }
    }

    @Test
    fun `siblings on the same parent still serialize and the shared entry is evicted after both`() {
        // Eviction must NOT break per-parent cap = 1: two siblings on the SAME parentToolCallId must
        // still mutually exclude (share one Mutex), and the single shared entry must be gone only
        // after BOTH depart.
        val live = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val gates = List(2) { CompletableDeferred<Unit>() }
        val started = List(2) { CompletableDeferred<Unit>() }
        val counter = AtomicInteger(0)
        val gatedGenerate: SubagentGenerate = { _, _, _, _, _, _, _ ->
            val idx = counter.getAndIncrement()
            flow {
                peak.updateAndGet { maxOf(it, live.incrementAndGet()) }
                started[idx].complete(Unit)
                gates[idx].await()
                live.decrementAndGet()
                emit(GenerationChunk.Messages(listOf(assistantMsg("done $idx"))))
            }
        }
        // Per-parent cap 1, global wide enough that ONLY the per-parent mutex serializes them.
        val coordinator = coordinator(gatedGenerate, budget = TaskBudget(globalConcurrency = 4, perParentConcurrency = 1))

        runBlocking {
            coroutineScope {
                val jobs = (0..1).map {
                    launch {
                        coordinator.run(
                            sub = Assistant(name = "Sub", chatModelId = subModel.id),
                            prompt = "go$it",
                            parentModelId = null,
                            settings = settingsWith(subModel),
                            parentToolCallId = "shared-call",
                        )
                    }
                }
                // Only one sibling may be live at a time on the shared parent.
                started[0].await()
                assertEquals("the shared parent must hold exactly one live entry", 1, coordinator.parentMutexCount())
                gates[0].complete(Unit)
                started[1].await()
                gates[1].complete(Unit)
                jobs.forEach { it.join() }
            }
            assertEquals("per-parent cap 1 must serialize siblings on the same parent", 1, peak.get())
            assertEquals("the shared entry is evicted only after both siblings depart", 0, coordinator.parentMutexCount())
        }
    }
}
