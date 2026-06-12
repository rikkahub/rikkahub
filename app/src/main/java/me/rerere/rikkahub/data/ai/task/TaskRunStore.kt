package me.rerere.rikkahub.data.ai.task

import me.rerere.ai.runtime.task.TaskBudget
import me.rerere.ai.runtime.task.TaskBudgetBreach
import me.rerere.ai.runtime.task.TaskBudgetUsage
import me.rerere.ai.runtime.task.TaskEvent
import me.rerere.ai.runtime.task.TaskSpec
import me.rerere.ai.runtime.task.TaskState
import kotlin.uuid.Uuid

/**
 * The narrow persistence seam [TaskCoordinator] depends on (DIP). The concrete is
 * `TaskRunRepository` (Room-backed), bound at the composition root; tests inject an in-memory fake.
 *
 * Keeping this an interface — rather than the concrete repository — is what makes the coordinator
 * JVM-unit-testable without Room: every method here is exactly the repository surface the
 * coordinator drives, no wider.
 */
interface TaskRunStore {
    suspend fun create(spec: TaskSpec): TaskState

    suspend fun applyEvent(taskId: Uuid, event: TaskEvent): TaskState?

    /**
     * Atomically take the single `Interrupted -> Resuming` edge for [taskId] (decision #3: one
     * active handle per task). Returns true ONLY when THIS caller drove a real transition out of
     * [TaskState.Interrupted]; false when the run does not exist, is not interrupted, or was
     * already moved to `Resuming`/`Running` by a concurrent resume.
     *
     * This is a compare-and-set, NOT [applyEvent]: folding `ResumeRequested` through the reducer
     * returns `Resuming` BOTH when the caller won the edge AND when the state was already
     * `Resuming` and the fold no-op'd — the two are indistinguishable from the returned state. A
     * resume that inferred its win from that ambiguous result could spawn a second handle during
     * the `Resuming` window (review finding #2). This method reports win/loss directly, so the
     * loser is rejected before it spawns anything.
     */
    suspend fun claimResume(taskId: Uuid): Boolean

    suspend fun appendEventSummary(
        taskId: Uuid,
        summary: String,
        kind: String = "progress",
    ): Long?

    suspend fun recordUsage(
        taskId: Uuid,
        reported: TaskBudgetUsage,
        budget: TaskBudget = TaskBudget(),
    ): TaskBudgetBreach?
}

/**
 * A no-op store for runs that need no persistence (e.g. a unit-test path that only asserts the
 * returned text). Every method is inert: create returns [TaskState.Created], events are dropped,
 * usage never breaches. Used as the [TaskCoordinator] default so the simplest constructor stays
 * usable without wiring a repository.
 */
object NoopTaskRunStore : TaskRunStore {
    override suspend fun create(spec: TaskSpec): TaskState = TaskState.Created
    override suspend fun applyEvent(taskId: Uuid, event: TaskEvent): TaskState? = null
    override suspend fun claimResume(taskId: Uuid): Boolean = false
    override suspend fun appendEventSummary(taskId: Uuid, summary: String, kind: String): Long? = null
    override suspend fun recordUsage(taskId: Uuid, reported: TaskBudgetUsage, budget: TaskBudget): TaskBudgetBreach? = null
}
