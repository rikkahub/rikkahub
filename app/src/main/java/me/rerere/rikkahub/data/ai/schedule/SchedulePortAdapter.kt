package me.rerere.rikkahub.data.ai.schedule

import me.rerere.ai.runtime.contract.ScheduleDraft
import me.rerere.ai.runtime.contract.ScheduleMutationResult
import me.rerere.ai.runtime.contract.ScheduleOwner
import me.rerere.ai.runtime.contract.ScheduleSnapshot
import me.rerere.ai.runtime.contract.TaskSchedulePort
import me.rerere.rikkahub.data.repository.TaskScheduleRepository
import kotlin.uuid.Uuid

/**
 * Binds the neutral [TaskSchedulePort] to ONE conversation's schedules on the [TaskScheduleRepository]
 * — the SAME repository path the schedule UI uses (SPEC.md M4/T7), so every legality gate (target
 * spawnable, per-conversation/per-owner caps, minimum interval, prompt bound, conversation scoping)
 * is enforced once, caller-agnostically, never tool-handler-only.
 *
 * The conversation scope and the [ScheduleOwner] class are closed over HERE, exactly as
 * [me.rerere.rikkahub.data.ai.task.BoardPortAdapter] closes over the board's conversation + actor:
 * tools built on the port (`buildScheduleTools`) never see a conversation id or an owner, so a
 * schedule tool physically cannot reach another conversation's schedules or author one under the
 * wrong owner. The adapter is constructed per generation (like the tool catalog itself), so the
 * binding is always the current conversation — never [me.rerere.rikkahub.data.ai.task.TaskCoordinator]'s
 * `Uuid.random()` parent default. The agent path binds [ScheduleOwner.AGENT]; the UI path binds
 * [ScheduleOwner.USER], so the per-owner cap isolates the two quotas (spec assumption 4).
 */
class SchedulePortAdapter(
    private val repository: TaskScheduleRepository,
    private val conversationId: Uuid,
    private val owner: ScheduleOwner,
) : TaskSchedulePort {

    override suspend fun create(draft: ScheduleDraft): ScheduleMutationResult =
        repository.create(conversationId, owner, draft)

    override suspend fun list(): List<ScheduleSnapshot> =
        repository.list(conversationId)

    override suspend fun delete(id: Uuid): ScheduleMutationResult =
        repository.delete(conversationId, id)
}
