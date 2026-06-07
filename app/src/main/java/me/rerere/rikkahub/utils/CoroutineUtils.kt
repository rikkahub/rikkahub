package me.rerere.rikkahub.utils

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

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

/**
 * Launches [block] on [viewModelScope] to drive a one-shot [SendChannel] of events instead of a UI
 * callback lambda. [block] sends its own success / known-failure events through the channel; this
 * wrapper only owns the EXCEPTIONAL path: a non-cancellation throwable that escapes [block] is turned
 * into a failure event via [onError] and sent, while cancellation is rethrown (per
 * [shouldRethrowVmError]) so a disposed screen tearing the VM down is never reported as a failed
 * operation.
 *
 * Root cause this addresses: VMs used to invoke a Composable-captured callback after long IO work,
 * which the screen may already have disposed (stale lambda). The first fix routed results through a
 * no-replay SharedFlow — but a SharedFlow(replay = 0) DROPS any event produced while no collector is
 * subscribed, and the UI collector lives in a `LaunchedEffect` that is torn down whenever the screen
 * leaves composition. A save/import finishing in that no-collector window had its terminal event
 * permanently lost (dialog never dismissed, toast never shown). extraBufferCapacity cannot help: that
 * buffer only keeps the PRODUCER from suspending, it is never replayed to a future subscriber. The
 * transport is therefore a buffered [SendChannel]: it RETAINS sent events until a consumer reads them,
 * so an event emitted during the no-collector window is still delivered to the next collector.
 */
fun <E> ViewModel.launchEmitting(
    events: SendChannel<E>,
    context: CoroutineContext = EmptyCoroutineContext,
    onError: (Throwable) -> E,
    block: suspend CoroutineScope.() -> Unit,
): Job = viewModelScope.launch(context) {
    runEmitting(events, onError, block)
}

/**
 * Pure, scope-free core of [launchEmitting] — kept separate so the cancellation-vs-error event
 * classification can be unit-tested on the JVM without coroutines-test or Android. Runs [block]
 * (which sends its own success / known-failure events into [events]); a non-cancellation throwable
 * becomes a failure event via [onError], cancellation is rethrown so structured-concurrency teardown
 * is never reported as a failed operation.
 */
suspend fun <E> CoroutineScope.runEmitting(
    events: SendChannel<E>,
    onError: (Throwable) -> E,
    block: suspend CoroutineScope.() -> Unit,
) {
    try {
        block()
    } catch (t: Throwable) {
        if (shouldRethrowVmError(t)) throw t
        events.send(onError(t))
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
