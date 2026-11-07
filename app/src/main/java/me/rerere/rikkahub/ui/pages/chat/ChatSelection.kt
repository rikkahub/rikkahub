package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.R
import me.rerere.ui.components.Tooltip
import kotlin.uuid.Uuid

/**
 * 消息多选（用于导出）的状态。放在外面是为了让顶栏菜单也能直接进入多选。
 */
@Stable
class ChatSelectionState {
    var selecting by mutableStateOf(false)
    val selectedItems = mutableStateListOf<Uuid>()

    fun start(ids: List<Uuid>) {
        selectedItems.clear()
        selectedItems.addAll(ids)
        selecting = true
    }

    fun cancel() {
        selecting = false
        selectedItems.clear()
    }

    fun isAllSelected(ids: List<Uuid>): Boolean =
        selectedItems.isNotEmpty() && ids.all { it in selectedItems }

    // 全部选中时清空，否则补齐成全选
    fun toggleAll(ids: List<Uuid>) {
        val allSelected = isAllSelected(ids)
        selectedItems.clear()
        if (!allSelected) {
            selectedItems.addAll(ids)
        }
    }
}

@Composable
fun rememberChatSelectionState(): ChatSelectionState = remember { ChatSelectionState() }

/**
 * 多选时浮在底部的工具栏：取消、全选/取消全选、确认。
 */
@Composable
internal fun ChatSelectionToolbar(
    state: ChatSelectionState,
    allIds: List<Uuid>,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = state.selecting,
        modifier = modifier,
        enter = slideInVertically(
            initialOffsetY = { it * 2 },
        ),
        exit = slideOutVertically(
            targetOffsetY = { it * 2 },
        ),
    ) {
        HorizontalFloatingToolbar(
            expanded = true,
        ) {
            Tooltip(
                tooltip = {
                    Text(stringResource(R.string.chat_page_clear_selection))
                }
            ) {
                IconButton(onClick = { state.cancel() }) {
                    Icon(HugeIcons.Cancel01, null)
                }
            }
            // 图标看不出是全选，直接写文字；全部选中时变成取消全选
            TextButton(onClick = { state.toggleAll(allIds) }) {
                Text(
                    stringResource(
                        if (state.isAllSelected(allIds)) R.string.chat_page_deselect_all
                        else R.string.chat_page_select_all
                    )
                )
            }
            Tooltip(
                tooltip = {
                    Text(stringResource(R.string.confirm))
                }
            ) {
                FilledIconButton(onClick = onConfirm) {
                    Icon(HugeIcons.Tick01, null)
                }
            }
        }
    }
}
