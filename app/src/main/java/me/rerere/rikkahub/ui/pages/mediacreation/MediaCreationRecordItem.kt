package me.rerere.rikkahub.ui.pages.mediacreation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.ImageAdd01
import me.rerere.hugeicons.stroke.ImageToVideo
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Refresh
import me.rerere.hugeicons.stroke.Share01
import me.rerere.hugeicons.stroke.Video01
import me.rerere.mediagen.model.ImageRole
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.MediaCreationAsset
import me.rerere.rikkahub.data.model.MediaCreationAssetType
import me.rerere.rikkahub.data.model.MediaCreationNode
import me.rerere.rikkahub.data.model.MediaCreationOutput
import me.rerere.rikkahub.data.model.MediaCreationRecord
import me.rerere.rikkahub.data.model.MediaCreationStatus
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.components.ui.VideoPlayerDialog
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.toMessageTimeString
import me.rerere.rikkahub.utils.writeClipboardText
import me.rerere.ui.components.RikkaConfirmDialog
import me.rerere.ui.components.Tooltip
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** 正在全屏查看的文件。 */
private data class MediaPreview(val file: File, val isVideo: Boolean)

/**
 * 时间线上的一项，显示它当前选中的版本：上面是这次生成的输入，下面是进度或产出，
 * 最后一行是可以对它做的操作，有多个版本时右下角可以切换。
 *
 * [editing] 表示输入区正在修改这一项。
 */
@Composable
internal fun MediaCreationRecordItem(
    node: MediaCreationNode,
    editing: Boolean,
    vm: MediaCreationVM,
    modifier: Modifier = Modifier,
) {
    val record = node.record
    var preview by remember { mutableStateOf<MediaPreview?>(null) }

    Card(
        modifier = modifier.fillMaxWidth(),
        // 内容离边 16dp，里面的小块圆角 12dp，和外圈同心
        shape = MaterialTheme.shapes.extraLarge,
        colors = CustomColors.cardColors,
        border = if (editing) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(
            // 最后一行按钮自带 8dp 的点击留白，底部补 8dp 后看起来和其它三边一样是 16dp
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RecordInput(
                record = record,
                resolve = vm::resolve,
                onPreview = { preview = it },
            )
            when {
                record.status.isActive -> RecordProgress(record)
                record.status == MediaCreationStatus.SUCCEEDED -> RecordOutputs(
                    record = record,
                    vm = vm,
                    onPreview = { preview = it },
                )

                else -> RecordFailure(record)
            }
            RecordActions(node = node, vm = vm)
        }
    }

    preview?.let { target ->
        if (target.isVideo) {
            VideoPlayerDialog(file = target.file, onDismissRequest = { preview = null })
        } else {
            ImagePreviewDialog(images = listOf(target.file.absolutePath), onDismissRequest = { preview = null })
        }
    }
}

@Composable
private fun RecordInput(
    record: MediaCreationRecord,
    resolve: (String) -> File,
    onPreview: (MediaPreview) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (record.inputs.isNotEmpty()) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 只有区分角色的输入才标注，纯参考图不需要说明
                val showRoles = record.inputs.any { it.type == MediaCreationAssetType.IMAGE && it.role != ImageRole.REFERENCE }
                record.inputs.forEach { asset ->
                    InputThumbnail(
                        asset = asset,
                        file = resolve(asset.path),
                        showRole = showRoles,
                        onPreview = onPreview,
                    )
                }
            }
        }
        if (record.prompt.isNotBlank()) {
            var expanded by remember { mutableStateOf(false) }
            Text(
                text = record.prompt,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { expanded = !expanded },
            )
        }
        val time = remember(record.createAt) {
            record.createAt.atZone(ZoneId.systemDefault()).toLocalDateTime().toMessageTimeString()
        }
        Text(
            text = (listOf(record.modelId) + record.params.summary(record.kind) + time).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InputThumbnail(
    asset: MediaCreationAsset,
    file: File,
    showRole: Boolean,
    onPreview: (MediaPreview) -> Unit,
) {
    val isVideo = asset.type == MediaCreationAssetType.VIDEO
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onPreview(MediaPreview(file, isVideo)) },
    ) {
        MediaThumbnail(
            file = file,
            isVideo = isVideo,
            modifier = Modifier.fillMaxSize(),
            playIconSize = 8.dp,
        )
        if (showRole && !isVideo) {
            Text(
                text = asset.role.label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(vertical = 1.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun RecordProgress(record: MediaCreationRecord) {
    // 当前阶段已经持续的时间
    val elapsed by produceState(initialValue = elapsedSeconds(record.updateAt), record.updateAt) {
        while (true) {
            value = elapsedSeconds(record.updateAt)
            delay(1000)
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ContainedLoadingIndicator(modifier = Modifier.size(32.dp))
            Text(
                text = "${record.status.label}  %d:%02d".format(elapsed / 60, elapsed % 60),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun elapsedSeconds(since: Instant): Long = Duration.between(since, Instant.now()).seconds.coerceAtLeast(0)

@Composable
private fun RecordFailure(record: MediaCreationRecord) {
    val cancelled = record.status == MediaCreationStatus.CANCELLED
    Surface(
        color = if (cancelled) {
            MaterialTheme.colorScheme.surfaceContainerHighest
        } else {
            MaterialTheme.colorScheme.errorContainer
        },
        contentColor = if (cancelled) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onErrorContainer
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        SelectionContainer {
            Text(
                text = when {
                    !cancelled -> record.error ?: record.status.label
                    record.taskId != null -> stringResource(R.string.media_creation_page_cancelled_task_running)
                    else -> record.status.label
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

private val SingleOutputMaxWidth = 200.dp
private val OutputMaxHeight = 160.dp

// 多项产出时一行摆几格
private const val OUTPUT_COLUMNS = 3

@Composable
private fun RecordOutputs(
    record: MediaCreationRecord,
    vm: MediaCreationVM,
    onPreview: (MediaPreview) -> Unit,
) {
    val outputs = record.outputs
    if (outputs.size == 1) {
        // 不铺满整行：横图受宽度限制，竖图受高度限制，免得一条记录占掉大半屏
        OutputTile(
            output = outputs.single(),
            vm = vm,
            onPreview = onPreview,
            modifier = Modifier.sizeIn(maxWidth = SingleOutputMaxWidth, maxHeight = OutputMaxHeight),
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            outputs.chunked(OUTPUT_COLUMNS).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { output ->
                        // 每格宽度固定，太高的竖图在格子里按高度上限缩小
                        Box(modifier = Modifier.weight(1f)) {
                            OutputTile(
                                output = output,
                                vm = vm,
                                onPreview = onPreview,
                                modifier = Modifier.heightIn(max = OutputMaxHeight),
                            )
                        }
                    }
                    // 没摆满的一行用空格占位，每格宽度保持一致
                    repeat(OUTPUT_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private fun MediaCreationOutput.aspectRatio(): Float {
    val width = width ?: return 1f
    val height = height ?: return 1f
    if (width <= 0 || height <= 0) return 1f
    return (width.toFloat() / height).coerceIn(0.5f, 2.4f)
}

@Composable
private fun OutputTile(
    output: MediaCreationOutput,
    vm: MediaCreationVM,
    onPreview: (MediaPreview) -> Unit,
    modifier: Modifier = Modifier,
) {
    val file = remember(output.path) { vm.resolve(output.path) }
    Box(
        modifier = modifier
            .aspectRatio(output.aspectRatio())
            .clip(RoundedCornerShape(12.dp))
            .clickable { onPreview(MediaPreview(file, output.isVideo)) },
    ) {
        MediaThumbnail(
            file = file,
            isVideo = output.isVideo,
            poster = output.posterPath?.let(vm::resolve),
            modifier = Modifier.fillMaxSize(),
        )
        output.durationSeconds?.takeIf { output.isVideo }?.let { duration ->
            Text(
                text = "%d:%02d".format(duration.toInt() / 60, duration.toInt() % 60),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        OutputMenu(
            output = output,
            file = file,
            vm = vm,
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

/** 单项产出的操作：放回输入区、保存、分享。 */
@Composable
private fun OutputMenu(
    output: MediaCreationOutput,
    file: File,
    vm: MediaCreationVM,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }

    @Composable
    fun MenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
        DropdownMenuItem(
            text = { Text(text) },
            leadingIcon = { Icon(icon, contentDescription = null) },
            onClick = {
                expanded = false
                onClick()
            },
        )
    }

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .padding(6.dp)
                .size(28.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable { expanded = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = HugeIcons.MoreVertical,
                contentDescription = stringResource(R.string.more_options),
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (output.isVideo) {
                MenuItem(stringResource(R.string.media_creation_page_continue_video_long), HugeIcons.Video01) {
                    vm.continueVideo(output)
                }
                MenuItem(stringResource(R.string.media_creation_page_use_as_reference_video), HugeIcons.ImageAdd01) {
                    vm.useVideo(output)
                }
            } else {
                MenuItem(stringResource(R.string.media_creation_page_use_as_reference_image), HugeIcons.ImageAdd01) {
                    vm.useImage(output, ImageRole.REFERENCE)
                }
                MenuItem(stringResource(R.string.media_creation_page_use_as_first_frame), HugeIcons.ImageToVideo) {
                    vm.useImage(output, ImageRole.FIRST_FRAME)
                }
                MenuItem(stringResource(R.string.media_creation_page_use_as_last_frame), HugeIcons.ImageToVideo) {
                    vm.useImage(output, ImageRole.LAST_FRAME)
                }
            }
            MenuItem(stringResource(R.string.media_creation_page_save_to_gallery), HugeIcons.Download01) {
                scope.launch {
                    runCatching { saveMediaToGallery(context, file, output.mimeType) }
                        .onSuccess {
                            toaster.show(
                                context.getString(R.string.media_creation_page_saved_to_gallery),
                                type = ToastType.Success,
                            )
                        }
                        .onFailure {
                            toaster.show(
                                it.message ?: context.getString(R.string.media_creation_page_save_failed),
                                type = ToastType.Error,
                            )
                        }
                }
            }
            MenuItem(stringResource(R.string.share), HugeIcons.Share01) {
                runCatching { shareMedia(context, file, output.mimeType) }
                    .onFailure {
                        toaster.show(
                            it.message ?: context.getString(R.string.media_creation_page_share_failed),
                            type = ToastType.Error,
                        )
                    }
            }
        }
    }
}

@Composable
private fun RecordActions(
    node: MediaCreationNode,
    vm: MediaCreationVM,
) {
    val record = node.record
    val hasVersions = node.versionCount > 1
    val context = LocalContext.current
    val toaster = LocalToaster.current
    var confirmCancel by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    // 右边的版本切换和左边的最后一行对齐
    Row(verticalAlignment = Alignment.Bottom) {
        // 放不下时换行，保证每个操作都看得见
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                record.status.isActive -> ActionChip(stringResource(R.string.cancel), HugeIcons.Cancel01) {
                    // 已经提交给服务端的任务撤不回来，先说明清楚
                    if (record.taskId != null) confirmCancel = true else vm.cancel(record)
                }

                record.status == MediaCreationStatus.SUCCEEDED -> {
                    // 只有一项产出时把最常用的去向直接摆出来，多项产出走各自的菜单
                    record.outputs.singleOrNull()?.let { output ->
                        if (output.isVideo) {
                            ActionChip(
                                stringResource(R.string.media_creation_page_continue_video),
                                HugeIcons.Video01,
                            ) { vm.continueVideo(output) }
                        } else {
                            ActionChip(
                                stringResource(R.string.media_creation_page_use_as_reference),
                                HugeIcons.ImageAdd01,
                            ) { vm.useImage(output, ImageRole.REFERENCE) }
                            ActionChip(
                                stringResource(R.string.media_creation_page_make_video),
                                HugeIcons.ImageToVideo,
                            ) { vm.useImage(output, ImageRole.FIRST_FRAME) }
                        }
                    }
                    ActionChip(stringResource(R.string.media_creation_page_rerun), HugeIcons.Refresh) {
                        vm.rerun(record)
                    }
                }

                else -> {
                    ActionChip(
                        text = stringResource(
                            if (record.taskId != null) {
                                R.string.media_creation_page_resume
                            } else {
                                R.string.media_creation_page_retry
                            }
                        ),
                        icon = HugeIcons.Refresh,
                    ) { vm.retry(record) }
                    // 服务端的任务可能已经取不回来了（被清掉、结果地址失效），而这一点客户端判断不了：
                    // 留一个不依赖旧任务的出口，另起一个版本重新提交
                    if (record.taskId != null) {
                        ActionChip(stringResource(R.string.media_creation_page_rerun), HugeIcons.Refresh) {
                            vm.rerun(record)
                        }
                    }
                }
            }
            ActionChip(stringResource(R.string.media_creation_page_edit), HugeIcons.PencilEdit01) {
                vm.editFrom(record)
            }
            // 次要操作只放图标，跟在后面一起换行，不再收进弹出菜单
            if (record.prompt.isNotBlank()) {
                ActionIcon(stringResource(R.string.media_creation_page_copy_prompt), HugeIcons.Copy01) {
                    context.writeClipboardText(record.prompt)
                    toaster.show(
                        context.getString(R.string.media_creation_page_prompt_copied),
                        type = ToastType.Success,
                    )
                }
            }
            ActionIcon(
                text = stringResource(
                    if (hasVersions) R.string.media_creation_page_delete_version else R.string.delete
                ),
                icon = HugeIcons.Delete01,
                destructive = true,
            ) { confirmDelete = true }
        }
        if (hasVersions) {
            VersionSwitcher(node = node, onSwitch = { vm.switchVersion(node, it) })
        }
    }

    RikkaConfirmDialog(
        show = confirmCancel,
        title = stringResource(R.string.media_creation_page_stop_waiting_title),
        confirmText = stringResource(R.string.media_creation_page_stop_waiting),
        dismissText = stringResource(R.string.media_creation_page_keep_waiting),
        onConfirm = {
            confirmCancel = false
            vm.cancel(record)
        },
        onDismiss = { confirmCancel = false },
    ) {
        Text(stringResource(R.string.media_creation_page_stop_waiting_text))
    }

    RikkaConfirmDialog(
        show = confirmDelete,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            confirmDelete = false
            vm.delete(record)
        },
        onDismiss = { confirmDelete = false },
    ) {
        val active = record.status.isActive
        Text(
            when {
                hasVersions && active -> stringResource(
                    R.string.media_creation_page_delete_version_active_text,
                    node.versionCount - 1,
                )

                hasVersions -> stringResource(
                    R.string.media_creation_page_delete_version_text,
                    node.versionCount - 1,
                )

                active -> stringResource(R.string.media_creation_page_delete_record_active_text)
                else -> stringResource(R.string.media_creation_page_delete_record_text)
            }
        )
    }
}

/** 在一项的各个版本之间切换。 */
@Composable
private fun VersionSwitcher(
    node: MediaCreationNode,
    onSwitch: (offset: Int) -> Unit,
) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant

    @Composable
    fun Arrow(icon: ImageVector, description: String, offset: Int) {
        val enabled = node.versionIndex + offset in 0 until node.versionCount
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = color,
            modifier = Modifier
                .clip(CircleShape)
                .alpha(if (enabled) 1f else 0.5f)
                .clickable(enabled = enabled) { onSwitch(offset) }
                .padding(8.dp)
                .size(16.dp),
        )
    }

    Row(
        // 和左边的操作按钮同高
        modifier = Modifier.heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Arrow(HugeIcons.ArrowLeft01, stringResource(R.string.media_creation_page_previous_version), offset = -1)
        Text(
            text = "${node.versionIndex + 1}/${node.versionCount}",
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
        Arrow(HugeIcons.ArrowRight01, stringResource(R.string.media_creation_page_next_version), offset = 1)
    }
}

@Composable
private fun ActionChip(text: String, icon: ImageVector, onClick: () -> Unit) {
    val height = ButtonDefaults.ExtraSmallContainerHeight
    FilledTonalButton(
        onClick = onClick,
        // 按下时圆角收紧
        shapes = ButtonDefaults.shapesFor(height),
        modifier = Modifier.heightIn(min = height),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        contentPadding = ButtonDefaults.contentPaddingFor(height, hasStartIcon = true),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.iconSizeFor(height)))
        Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(height)))
        Text(text = text, style = ButtonDefaults.textStyleFor(height))
    }
}

/** 只有图标的操作，长按显示名称。 */
@Composable
private fun ActionIcon(
    text: String,
    icon: ImageVector,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    Tooltip(tooltip = { Text(text) }) {
        FilledTonalIconButton(
            onClick = onClick,
            // 按下时圆角收紧
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.size(
                IconButtonDefaults.extraSmallContainerSize(IconButtonDefaults.IconButtonWidthOption.Wide)
            ),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = if (destructive) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            ),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = text,
                modifier = Modifier.size(IconButtonDefaults.extraSmallIconSize),
            )
        }
    }
}
