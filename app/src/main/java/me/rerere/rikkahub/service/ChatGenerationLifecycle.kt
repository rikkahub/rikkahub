package me.rerere.rikkahub.service

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus

internal suspend inline fun <T> withGuaranteedChatGenerationEnd(
    eventBus: AppEventBus,
    crossinline terminalEvent: () -> AppEvent.ChatGenerationEnded,
    block: suspend () -> T,
): T = try {
    block()
} finally {
    withContext(NonCancellable) {
        eventBus.emit(terminalEvent())
    }
}
