package me.rerere.rikkahub.data.sync.cloud

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * App-wide file transfer progress for UI (upload/download via Perry proxy).
 * Keeps the dialog visible across worker batches until [clear] is called.
 */
class UploadProgressTracker {
    data class State(
        val active: Boolean = false,
        val isDownload: Boolean = false,
        val displayName: String = "",
        val completed: Int = 0,
        val remaining: Int = 0,
        val bytesSent: Long = 0,
        val bytesTotal: Long = 0,
    ) {
        val total: Int get() = completed + remaining
        val index: Int get() = (completed + 1).coerceAtMost(total.coerceAtLeast(1))

        val fraction: Float?
            get() {
                if (total <= 0) return null
                // Prefer per-file byte progress when known; otherwise completed/total.
                val fileFrac = if (bytesTotal > 0L) {
                    (bytesSent.toFloat() / bytesTotal.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
                val overall = (completed + fileFrac) / total.toFloat()
                return overall.coerceIn(0f, 0.99f)
            }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun ensureActive(isDownload: Boolean = false) {
        _state.update {
            if (it.active) it else State(active = true, isDownload = isDownload)
        }
    }

    fun setQueue(remaining: Int, isDownload: Boolean) {
        _state.update {
            val keepCompleted = if (it.active && it.isDownload == isDownload) it.completed else 0
            State(
                // Stay active until clear(); batch boundaries must not hide the dialog.
                active = true,
                isDownload = isDownload,
                displayName = it.displayName,
                completed = keepCompleted,
                remaining = remaining.coerceAtLeast(0),
                bytesSent = 0,
                bytesTotal = 0,
            )
        }
    }

    fun beginFile(displayName: String, bytesTotal: Long, isDownload: Boolean = false) {
        _state.update {
            State(
                active = true,
                isDownload = isDownload,
                displayName = displayName,
                completed = it.completed,
                remaining = it.remaining.coerceAtLeast(1),
                bytesSent = 0,
                bytesTotal = bytesTotal.coerceAtLeast(0L),
            )
        }
    }

    fun updateBytes(bytesSent: Long, bytesTotal: Long = _state.value.bytesTotal) {
        _state.update {
            it.copy(
                active = true,
                bytesSent = bytesSent.coerceAtLeast(0L),
                bytesTotal = bytesTotal.coerceAtLeast(0L),
            )
        }
    }

    fun completeFile() {
        _state.update {
            val remaining = (it.remaining - 1).coerceAtLeast(0)
            it.copy(
                active = true,
                completed = it.completed + 1,
                remaining = remaining,
                bytesSent = 0,
                bytesTotal = 0,
            )
        }
    }

    fun clear() {
        _state.value = State()
    }
}
