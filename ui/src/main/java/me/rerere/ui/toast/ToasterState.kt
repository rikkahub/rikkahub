package me.rerere.ui.toast

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

enum class ToastType {
    Normal,
    Success,
    Info,
    Warning,
    Error,
}

@Stable
class ToasterState internal constructor(
    private val scope: CoroutineScope,
    private val maxVisibleToasts: Int,
) {
    private val nextId = AtomicLong()

    internal val items = mutableStateListOf<ToastItem>()

    fun show(
        message: String,
        type: ToastType = ToastType.Normal,
        duration: Duration = DefaultDuration,
    ) {
        enqueue(message, type, duration)
    }

    /** 显示一条进行中的提示（如「正在保存」），不会自动消失，操作结束后调用返回句柄的 [ToastHandle.dismiss] 收起。 */
    fun showProgress(message: String): ToastHandle {
        return ToastHandle(this, enqueue(message, ToastType.Normal, Duration.INFINITE))
    }

    private fun enqueue(message: String, type: ToastType, duration: Duration): ToastItem {
        val item = ToastItem(id = nextId.getAndIncrement(), message = message, type = type)
        items.add(item)
        // 超出可见数量时让最早的先退出
        items.filter { it.visibleState.targetState }
            .dropLast(maxVisibleToasts)
            .forEach { dismiss(it) }
        item.job = scope.launch {
            delay(duration)
            dismiss(item)
        }
        return item
    }

    internal fun dismiss(item: ToastItem) {
        item.job?.cancel()
        item.visibleState.targetState = false
    }

    internal fun remove(item: ToastItem) {
        items.remove(item)
    }

    companion object {
        val DefaultDuration = 4.seconds
    }
}

/** [ToasterState.showProgress] 返回的句柄，用来在操作结束后收起这条提示。 */
class ToastHandle internal constructor(
    private val state: ToasterState,
    private val item: ToastItem,
) {
    fun dismiss() = state.dismiss(item)
}

internal class ToastItem(
    val id: Long,
    val message: String,
    val type: ToastType,
) {
    val visibleState = MutableTransitionState(false).apply { targetState = true }
    var job: Job? = null
}

@Composable
fun rememberToasterState(maxVisibleToasts: Int = 3): ToasterState {
    val scope = rememberCoroutineScope()
    return remember(scope, maxVisibleToasts) { ToasterState(scope, maxVisibleToasts) }
}
