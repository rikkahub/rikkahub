package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Serializes suspend jobs into a strict FIFO chain on [scope]. Generic: knows
 * nothing about conversations or persistence payloads. A failing job is contained
 * (logged by the caller's block, not here) and never blocks later jobs.
 */
class VoicePersistenceQueue(
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private val jobs = mutableSetOf<Job>()
    private var lastJob: Job? = null

    fun enqueue(block: suspend () -> Unit): Job {
        lateinit var job: Job
        synchronized(lock) {
            val previousJob = lastJob
            job = scope.launch(start = CoroutineStart.LAZY) {
                previousJob?.join()
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // Contained: a failing job must not break the FIFO chain; the caller's
                    // block owns its own logging.
                }
            }
            jobs += job
            lastJob = job
        }
        job.invokeOnCompletion {
            synchronized(lock) {
                jobs -= job
                if (lastJob === job) {
                    lastJob = null
                }
            }
        }
        job.start()
        return job
    }

    suspend fun await() {
        while (true) {
            val pending = synchronized(lock) {
                if (jobs.isEmpty()) {
                    return
                }
                jobs.toList()
            }
            pending.joinAll()
        }
    }
}
