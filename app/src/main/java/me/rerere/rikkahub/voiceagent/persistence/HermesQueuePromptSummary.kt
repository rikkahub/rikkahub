package me.rerere.rikkahub.voiceagent.persistence

import me.rerere.rikkahub.voiceagent.hermes.HermesQueueRecord
import me.rerere.rikkahub.voiceagent.hermes.HermesQueueSnapshot
import me.rerere.rikkahub.voiceagent.hermes.HermesQueueStatus

/**
 * LLM prompt copy for the durable Hermes queue status. Lives in the prompt layer:
 * the queue model must not know how it is narrated to the model.
 */
internal fun HermesQueueSnapshot.toStatusQuestionPromptSummary(): String {
    if (active.isEmpty() && unannouncedTerminal.isEmpty()) return ""

    val completedWaiting = unannouncedTerminal.countStatus(HermesQueueStatus.Complete)
    val failedWaiting = unannouncedTerminal.countStatus(HermesQueueStatus.Failed)
    val expiredWaiting = unannouncedTerminal.countStatus(HermesQueueStatus.Expired)
    val canceledWaiting = unannouncedTerminal.countStatus(HermesQueueStatus.Canceled)

    return buildString {
        appendLine("Durable Hermes queue status:")
        active.forEach { record ->
            appendLine("- Still ${record.status.wireName}: ${record.prompt}")
        }
        if (unannouncedTerminal.isNotEmpty()) {
            appendLine(
                "- Unannounced terminal results: " +
                    "completed=$completedWaiting, " +
                    "failed=$failedWaiting, " +
                    "expired=$expiredWaiting, " +
                    "canceled=$canceledWaiting"
            )
        }
        append(
            "When the user asks about Hermes status, answer only from this durable queue status. " +
                "Do not invent completed results. Do not describe terminal result contents unless a later " +
                "Hermes completion follow-up turn provides them."
        )
    }.trim()
}

private fun List<HermesQueueRecord>.countStatus(status: HermesQueueStatus): Int =
    count { it.status == status }
