package com.hefengfan.hffchat.ui.hooks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Remembers the current Lifecycle.State of the application's LifecycleOwner
 * (usually the Activity or Fragment hosting the Compose UI).
 *
 * The returned State object will update whenever the lifecycle state changes
 * (e.g., from STARTED to RESUMED).
 *
 * @return A State object holding the current Lifecycle.State.
 */
@Composable
fun rememberAppLifecycleState(): State<Lifecycle.State> {
    // 1. 获取当前的 LifecycleOwner。
    // LocalLifecycleOwner 是一个 CompositionLocal，提供了当前组合上下文的 LifecycleOwner。
    val lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
    // 2. 使用 remember 创建一个 MutableState，用于存储当前的生命周期状态。
    // 初始化状态为当前的生命周期状态。
    val lifecycleState = remember { mutableStateOf(lifecycleOwner.lifecycle.currentState) }
    // 3. 使用 DisposableEffect 添加和移除一个 LifecycleObserver。
    // DisposableEffect 适用于需要在 Composable 进入或离开组合时执行副作用和清理操作的场景。
    // key 设置为 lifecycleOwner.lifecycle，确保当 lifecycle 对象本身改变时（极少见）效果能正确处理。
    DisposableEffect(lifecycleOwner.lifecycle) {
        // 创建一个 LifecycleEventObserver。
        // 当任何生命周期事件发生时，onStateChanged 会被调用。
        val observer = LifecycleEventObserver { _, _ ->
            // 在生命周期事件发生后，更新 lifecycleState 的值到当前的生命周期状态。
            // 这会触发使用 lifecycleState 的 Composable 进行重组。
            lifecycleState.value = lifecycleOwner.lifecycle.currentState
        }
        // 将观察者添加到生命周期。
        lifecycleOwner.lifecycle.addObserver(observer)
        // onDispose 块会在 Composable 离开组合时被调用，用于清理资源。
        onDispose {
            // 移除观察者，防止内存泄漏。
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    // 4. 返回存储生命周期状态的 State 对象。
    return lifecycleState
}
