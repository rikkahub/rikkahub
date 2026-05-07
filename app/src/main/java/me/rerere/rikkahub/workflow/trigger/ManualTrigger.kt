package me.rerere.rikkahub.workflow.trigger

import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowDefinition

/**
 * Phase 12 — manual trigger. No receiver, no schedule. Fires only via `workflow_run` tool
 * or the Settings "Run now" button — both call straight into
 * [me.rerere.rikkahub.workflow.execution.WorkflowEngine.fireNow].
 *
 * Lives as a family for symmetry — its sole job is to declare ownership of [TriggerSpec.Manual]
 * so [TriggerRegistry] doesn't think it's an unhandled type.
 */
internal class ManualTriggerFamily : WorkflowTriggerFamily {
    override val name = "manual"
    override fun handles(spec: TriggerSpec): Boolean = spec is TriggerSpec.Manual
    override suspend fun sync(matching: List<WorkflowDefinition>, callback: TriggerFireCallback) = Unit
    override suspend fun shutdown() = Unit
}
