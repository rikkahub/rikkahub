package me.rerere.rikkahub.ui.pages.setting.scheduledjobs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.data.db.entity.ScheduledJobRunEntity
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRunRepository
import me.rerere.rikkahub.service.CronJobScheduler

/**
 * Backs the Settings → Scheduled Jobs list + detail screens. Mirrors the responsibilities
 * the cron-job *tools* expose to the LLM (`list_jobs`, `pause_job`, `resume_job`,
 * `trigger_job_now`, `delete_job`, `get_job_history`) so the UI can drive every operation
 * the assistant can — without going through the LLM.
 *
 * State changes go through [ScheduledJobRepository] AND [CronJobScheduler] in the same
 * order the tools use, so the WorkManager schedule and the DB row stay in lock-step.
 */
class ScheduledJobsViewModel(
    private val repository: ScheduledJobRepository,
    private val runRepository: ScheduledJobRunRepository,
    private val scheduler: CronJobScheduler,
) : ViewModel() {

    val jobs: StateFlow<List<ScheduledJobEntity>> =
        repository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val job = repository.getById(id) ?: return@launch
            val updated = job.copy(enabled = enabled)
            repository.update(updated)
            if (enabled) scheduler.schedule(updated) else scheduler.cancel(id)
        }
    }

    fun delete(id: String, onDone: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            scheduler.cancel(id)
            runRepository.deleteAllForJob(id)
            repository.deleteById(id)
            onDone()
        }
    }

    /** Manually fire — same path as the LLM's trigger_job_now tool. */
    suspend fun runNow(id: String): RunNowOutcome {
        val job = repository.getById(id) ?: return RunNowOutcome.NotFound
        if (!job.enabled) return RunNowOutcome.Disabled
        scheduler.triggerNow(id)
        return RunNowOutcome.Fired
    }

    suspend fun history(id: String, limit: Int = 20): List<ScheduledJobRunEntity> =
        runRepository.getRecent(id, limit)

    suspend fun get(id: String): ScheduledJobEntity? = repository.getById(id)

    enum class RunNowOutcome { Fired, NotFound, Disabled }
}
