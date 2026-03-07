/*
 * Copyright 2023 Calvin Liang
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.rerere.rikkahub.ui.components.ai.modelreorderable

// Forked from https://github.com/Calvin-LL/Reorderable tree v3.0.0 on 2026-03-07
// 其他页面仍走三方库 这里只 vendored 收藏模型边缘自动滚动

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun rememberScroller(
    scrollableState: ScrollableState,
    pixelPerSecond: Float,
): Scroller {
    val scope = rememberCoroutineScope()
    val pixelPerSecondUpdated = rememberUpdatedState(pixelPerSecond)

    return remember(scrollableState, scope) {
        Scroller(
            scrollableState = scrollableState,
            scope = scope,
            pixelPerSecondProvider = { pixelPerSecondUpdated.value },
        )
    }
}

@Stable
class Scroller internal constructor(
    private val scrollableState: ScrollableState,
    private val scope: CoroutineScope,
    private val pixelPerSecondProvider: () -> Float,
) {
    companion object {
        private const val MaxScrollDuration = 100L
        private const val ZeroScrollWaitDuration = 100L
    }

    internal enum class Direction {
        BACKWARD,
        FORWARD;

        val opposite: Direction
            get() = when (this) {
                BACKWARD -> FORWARD
                FORWARD -> BACKWARD
            }
    }

    private data class ScrollInfo(
        val direction: Direction,
        val speedMultiplier: Float,
        val maxScrollDistanceProvider: () -> Float,
        val onScroll: suspend () -> Unit,
    ) {
        companion object {
            val Null = ScrollInfo(Direction.FORWARD, 0f, { 0f }, {})
        }
    }

    private var programmaticScrollJob: Job? = null
    val isScrolling: Boolean
        get() = programmaticScrollJob?.isActive == true

    private val scrollInfoChannel = Channel<ScrollInfo>(Channel.CONFLATED)

    internal fun start(
        direction: Direction,
        speedMultiplier: Float = 1f,
        maxScrollDistanceProvider: () -> Float = { Float.MAX_VALUE },
        onScroll: suspend () -> Unit = {},
    ): Boolean {
        if (!canScroll(direction)) return false

        if (programmaticScrollJob == null) {
            programmaticScrollJob = scope.launch {
                scrollLoop()
            }
        }

        scrollInfoChannel.trySend(
            ScrollInfo(
                direction = direction,
                speedMultiplier = speedMultiplier,
                maxScrollDistanceProvider = maxScrollDistanceProvider,
                onScroll = onScroll,
            )
        )
        return true
    }

    private suspend fun scrollLoop() {
        var scrollInfo: ScrollInfo? = null

        while (true) {
            scrollInfo = scrollInfoChannel.tryReceive().getOrNull() ?: scrollInfo
            if (scrollInfo == null || scrollInfo == ScrollInfo.Null) break

            val (direction, speedMultiplier, maxScrollDistanceProvider, onScroll) = scrollInfo
            val pixelPerSecond = pixelPerSecondProvider() * speedMultiplier
            val pixelPerMs = pixelPerSecond / 1000f

            onScroll()

            if (!canScroll(direction)) break

            val maxScrollDistance = maxScrollDistanceProvider()
            if (maxScrollDistance <= 0f) {
                delay(ZeroScrollWaitDuration)
                continue
            }

            val maxScrollDistanceDuration = maxScrollDistance / pixelPerMs
            val duration = maxScrollDistanceDuration.toLong().coerceIn(1L, MaxScrollDuration)
            val scrollDistance = maxScrollDistance * (duration / maxScrollDistanceDuration)
            val diff = when (direction) {
                Scroller.Direction.BACKWARD -> -scrollDistance
                Scroller.Direction.FORWARD -> scrollDistance
            }

            scrollableState.animateScrollBy(
                diff,
                tween(durationMillis = duration.toInt(), easing = LinearEasing)
            )
        }
    }

    private fun canScroll(direction: Direction): Boolean {
        return when (direction) {
            Direction.BACKWARD -> scrollableState.canScrollBackward
            Direction.FORWARD -> scrollableState.canScrollForward
        }
    }

    internal suspend fun stop() {
        scrollInfoChannel.send(ScrollInfo.Null)
        programmaticScrollJob?.cancelAndJoin()
        programmaticScrollJob = null
    }

    internal fun tryStop() {
        scope.launch { stop() }
    }
}
