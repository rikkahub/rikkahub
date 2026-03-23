package me.rerere.rikkahub.ui.hooks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 创建一个防抖函数包装器
 *
 * @param delayMillis 延迟时间（毫秒）
 * @param function 要执行的函数
 * @return 包装后的防抖函数
 */
@Composable
fun <T> useDebounce(
    delayMillis: Long = 300,
    function: (T) -> Unit
): (T) -> Unit {
    val scope = rememberCoroutineScope()
    val debounceJob = remember { mutableStateOf<Job?>(null) }

    return remember {
        { param: T ->
            debounceJob.value?.cancel()
            debounceJob.value = scope.launch {
                delay(delayMillis)
                function(param)
            }
        }
    }
}

/**
 * 创建一个节流函数包装器
 *
 * @param intervalMillis 间隔时间（毫秒）
 * @param function 要执行的函数
 * @return 包装后的节流函数
 */
@Composable
fun <T> useThrottle(
    intervalMillis: Long = 300,
    function: (T) -> Unit
): (T) -> Unit {
    val scope = rememberCoroutineScope()
    val isThrottling = remember { AtomicBoolean(false) }
    val latestParam = remember { mutableStateOf<T?>(null) }

    return remember {
        { param: T ->
            latestParam.value = param

            if (!isThrottling.getAndSet(true)) {
                function(param)

                scope.launch {
                    delay(intervalMillis)
                    isThrottling.set(false)

                    // 如果在节流期间有新的参数，则在节流结束后执行一次
                    latestParam.value?.let { latestValue ->
                        // 重置参数
                        latestParam.value = null
                        // 用最新的参数再次调用节流函数
                        function(latestValue)
                    }
                }
            }
        }
    }
}

@Composable
fun rememberDebouncedTextState(
    value: String,
    debounceMillis: Long = 400,
    onDebouncedValueChange: (String) -> Unit,
): MutableState<String> {
    val state = rememberSaveable { mutableStateOf(value) }
    val latestValue by rememberUpdatedState(value)
    val latestOnDebouncedValueChange by rememberUpdatedState(onDebouncedValueChange)

    LaunchedEffect(value) {
        if (state.value != value) {
            state.value = value
        }
    }

    LaunchedEffect(state.value) {
        if (state.value == latestValue) return@LaunchedEffect
        delay(debounceMillis)
        if (state.value != latestValue) {
            latestOnDebouncedValueChange(state.value)
        }
    }

    return state
}
