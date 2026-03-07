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
// 只保留收藏模型拖拽把 pointer 事件转成 reorder 状态的这一层

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch

internal fun Modifier.draggable(
    key1: Any?,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    dragGestureDetector: DragGestureDetector = DragGestureDetector.Press,
    onDragStarted: (Offset) -> Unit = {},
    onDragStopped: () -> Unit = {},
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
) = composed {
    val coroutineScope = rememberCoroutineScope()
    var dragInteractionStart by remember { mutableStateOf<DragInteraction.Start?>(null) }
    var dragStarted by remember { mutableStateOf(false) }

    DisposableEffect(key1) {
        onDispose {
            if (dragStarted) {
                dragInteractionStart?.also {
                    coroutineScope.launch {
                        interactionSource?.emit(DragInteraction.Cancel(it))
                    }
                }

                onDragStopped()
                dragStarted = false
            }
        }
    }

    pointerInput(key1, enabled) {
        if (!enabled) return@pointerInput

        with(dragGestureDetector) {
            detect(
                onDragStart = {
                    dragStarted = true
                    dragInteractionStart = DragInteraction.Start().also {
                        coroutineScope.launch { interactionSource?.emit(it) }
                    }
                    onDragStarted(it)
                },
                onDragEnd = {
                    dragInteractionStart?.also {
                        coroutineScope.launch { interactionSource?.emit(DragInteraction.Stop(it)) }
                    }
                    if (dragStarted) onDragStopped()
                    dragStarted = false
                },
                onDragCancel = {
                    dragInteractionStart?.also {
                        coroutineScope.launch { interactionSource?.emit(DragInteraction.Cancel(it)) }
                    }
                    if (dragStarted) onDragStopped()
                    dragStarted = false
                },
                onDrag = onDrag,
            )
        }
    }
}
