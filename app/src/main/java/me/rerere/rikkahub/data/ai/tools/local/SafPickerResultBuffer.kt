package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/** Result of a SAF directory-tree pick launched via [ToolHostActivity]. */
sealed class SafPickerResult {
    /** User picked a tree; [contentUri] is the persistable tree URI. */
    data class Granted(val contentUri: String) : SafPickerResult()
    /** User cancelled the picker. */
    data object Cancelled : SafPickerResult()
    /** Picker failed or the provider rejected a persistable grant. */
    data class Error(val message: String) : SafPickerResult()
}

/**
 * Process-scoped buffer that bridges the `grant_directory_access` tool (which has app
 * context only) to the SAF tree picker hosted in [ToolHostActivity]. Mirrors
 * [BiometricResultBuffer] / [CameraResultBuffer].
 */
class SafPickerResultBuffer {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<SafPickerResult>>()

    fun register(requestId: String): CompletableDeferred<SafPickerResult> {
        val deferred = CompletableDeferred<SafPickerResult>()
        pending[requestId] = deferred
        return deferred
    }

    fun complete(requestId: String, result: SafPickerResult) {
        pending.remove(requestId)?.complete(result)
    }
}
