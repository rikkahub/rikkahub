package me.rerere.rikkahub.data.ai.tools

import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * Process-scoped registry of conversation ids that should run with auto-approval.
 *
 * Used by the cron worker: scheduled jobs are pre-authorised at SCHEDULE time (the
 * `schedule_job` tool prompts the user with an explicit "this job will run without
 * per-tool approval" warning) and run AUTOMATICALLY at FIRE time. Without this, every
 * cron tick that calls a side-effecting tool (termux_run_command, ssh_exec, etc.)
 * would flip to Pending and stall forever — there's no UI surface to grant approval
 * when the user is asleep / away.
 *
 * The HARDLINE floor still applies. `HardlineCommandGuard.checkTool` runs BEFORE the
 * auto-approval lookup in `GenerationHandler`, so a cron job's `rm -rf /` is still
 * blocked at the floor, headless mode or not.
 *
 * The set is in-memory and dies with the process; cron workers re-mark on every fire.
 * Conversation ids are unique per fire (CronJobWorker creates a fresh Uuid each run).
 */
object HeadlessConversations {

    private val ids: MutableSet<Uuid> = ConcurrentHashMap.newKeySet()

    fun mark(conversationId: Uuid) {
        ids.add(conversationId)
    }

    fun unmark(conversationId: Uuid) {
        ids.remove(conversationId)
    }

    fun isHeadless(conversationId: Uuid): Boolean = conversationId in ids
}
