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
// 保留原始手势检测 只服务收藏模型拖拽

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope

fun interface DragGestureDetector {
    suspend fun PointerInputScope.detect(
        onDragStart: (Offset) -> Unit,
        onDragEnd: () -> Unit,
        onDragCancel: () -> Unit,
        onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit
    )

    data object Press : DragGestureDetector {
        override suspend fun PointerInputScope.detect(
            onDragStart: (Offset) -> Unit,
            onDragEnd: () -> Unit,
            onDragCancel: () -> Unit,
            onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit
        ) {
            detectDragGestures(onDragStart, onDragEnd, onDragCancel, onDrag)
        }
    }

    data object LongPress : DragGestureDetector {
        override suspend fun PointerInputScope.detect(
            onDragStart: (Offset) -> Unit,
            onDragEnd: () -> Unit,
            onDragCancel: () -> Unit,
            onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit
        ) {
            detectDragGesturesAfterLongPress(onDragStart, onDragEnd, onDragCancel, onDrag)
        }
    }
}
