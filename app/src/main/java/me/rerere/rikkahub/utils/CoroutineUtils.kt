package me.rerere.rikkahub.utils

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private const val TAG = "CoroutineUtils"

/**
 * Decides what a ViewModel coroutine should do with a throwable that escaped user-action/repo work.
 * CancellationException (and its subclasses, e.g. TimeoutCancellationException) MUST be rethrown so
 * structured-concurrency cancellation is never swallowed; every other Throwable is a recoverable
 * operation error that should be reported to local UI state instead of crashing the app.
 */
fun shouldRethrowVmError(t: Throwable): Boolean = t is CancellationException

/**
 * Launches [block] on [viewModelScope] with a terminal guard: any recoverable throwable that escapes
 * the block is routed to [onError] (report to local UI state) instead of propagating to the scope's
 * uncaught-exception handler — which, after CrashHandler.install, marks the process crashed and forces
 * safe-mode. Cancellation still propagates via [shouldRethrowVmError] so structured concurrency holds.
 */
fun ViewModel.launchVm(
    onError: (Throwable) -> Unit,
    block: suspend CoroutineScope.() -> Unit,
): Job = viewModelScope.launch {
    try {
        block()
    } catch (t: Throwable) {
        if (shouldRethrowVmError(t)) throw t
        onError(t)
    }
}

fun <T> Flow<T>.toMutableStateFlow(
    scope: CoroutineScope,
    initial: T
): MutableStateFlow<T> {
    val stateFlow = MutableStateFlow(initial)
    scope.launch {
        runCatching {
            this@toMutableStateFlow.collect { value ->
                stateFlow.value = value
            }
        }.onFailure {
            it.printStackTrace()
            Log.e(TAG, "Error while collecting flow: ${it.message}", it)

            Runtime.getRuntime().halt(1)
        }
    }
    return stateFlow
}
