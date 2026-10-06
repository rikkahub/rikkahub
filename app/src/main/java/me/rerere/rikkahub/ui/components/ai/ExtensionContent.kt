package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.uuid.Uuid
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage

@Composable
fun ModeInjectionsContent(
    modeInjections: List<PromptInjection.ModeInjection>,
    selectedIds: Set<Uuid>,
    onToggle: (Uuid, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    colors: ListItemColors = ListItemDefaults.segmentedColors(),
    onManage: (() -> Unit)? = null,
) {
    ExtensionList(
        items = modeInjections,
        modifier = modifier,
        contentPadding = contentPadding,
        onManage = onManage,
    ) { injection, shapes ->
        ExtensionListItem(
            title = injection.name.ifBlank { stringResource(R.string.extension_content_unnamed) },
            checked = selectedIds.contains(injection.id),
            onCheckedChange = { checked -> onToggle(injection.id, checked) },
            shapes = shapes,
            colors = colors,
        )
    }
}

@Composable
fun LorebooksContent(
    lorebooks: List<Lorebook>,
    selectedIds: Set<Uuid>,
    onToggle: (Uuid, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    colors: ListItemColors = ListItemDefaults.segmentedColors(),
    onManage: (() -> Unit)? = null,
) {
    ExtensionList(
        items = lorebooks,
        modifier = modifier,
        contentPadding = contentPadding,
        onManage = onManage,
    ) { lorebook, shapes ->
        ExtensionListItem(
            title = lorebook.name.ifBlank { stringResource(R.string.extension_content_unnamed_lorebook) },
            description = lorebook.description,
            checked = selectedIds.contains(lorebook.id),
            onCheckedChange = { checked -> onToggle(lorebook.id, checked) },
            shapes = shapes,
            colors = colors,
        )
    }
}

@Composable
fun SkillsContent(
    skills: List<SkillMetadata>,
    enabledSkills: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    colors: ListItemColors = ListItemDefaults.segmentedColors(),
    onManage: (() -> Unit)? = null,
) {
    ExtensionList(
        items = skills,
        modifier = modifier,
        contentPadding = contentPadding,
        onManage = onManage,
        key = { it.skillDir.absolutePath },
    ) { skill, shapes ->
        ExtensionListItem(
            title = skill.name,
            description = skill.description,
            checked = enabledSkills.contains(skill.name),
            onCheckedChange = { checked -> onToggle(skill.name, checked) },
            shapes = shapes,
            colors = colors,
        )
    }
}

@Composable
fun QuickMessagesContent(
    quickMessages: List<QuickMessage>,
    selectedIds: Set<Uuid>,
    onToggle: (Uuid, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    colors: ListItemColors = ListItemDefaults.segmentedColors(),
    onManage: (() -> Unit)? = null,
) {
    ExtensionList(
        items = quickMessages,
        modifier = modifier,
        contentPadding = contentPadding,
        onManage = onManage,
        key = { it.id },
    ) { quickMessage, shapes ->
        ExtensionListItem(
            title = quickMessage.title.ifBlank { stringResource(R.string.extension_content_unnamed) },
            description = quickMessage.content,
            descriptionMaxLines = 2,
            checked = selectedIds.contains(quickMessage.id),
            onCheckedChange = { checked -> onToggle(quickMessage.id, checked) },
            shapes = shapes,
            colors = colors,
        )
    }
}

// 扩展项的分段列表，末尾可带一个跳转到管理页的按钮
@Composable
private fun <T> ExtensionList(
    items: List<T>,
    modifier: Modifier,
    contentPadding: PaddingValues,
    onManage: (() -> Unit)?,
    key: ((T) -> Any)? = null,
    itemContent: @Composable (item: T, shapes: ListItemShapes) -> Unit,
) {
    val itemKey: ((Int, T) -> Any)? = key?.let { keyOf -> { _, item -> keyOf(item) } }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        itemsIndexed(items = items, key = itemKey) { index, item ->
            itemContent(item, ListItemDefaults.segmentedShapes(index = index, count = items.size))
        }
        if (onManage != null) {
            item {
                ManageButton(onClick = onManage)
            }
        }
    }
}

@Composable
private fun ExtensionListItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    shapes: ListItemShapes,
    colors: ListItemColors,
    description: String = "",
    // 技能等的描述可能很长，完整内容在管理页看
    descriptionMaxLines: Int = 3,
) {
    SegmentedListItem(
        checked = checked,
        onCheckedChange = onCheckedChange,
        shapes = shapes,
        colors = colors,
        supportingContent = if (description.isNotBlank()) {
            {
                Text(
                    text = description,
                    maxLines = descriptionMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else null,
        trailingContent = {
            // 整行都能点击切换，开关只用来显示状态
            Switch(checked = checked, onCheckedChange = null)
        },
    ) {
        Text(title)
    }
}

@Composable
private fun ManageButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        FilledTonalButton(onClick = onClick) {
            Icon(
                imageVector = HugeIcons.Settings03,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.extension_content_manage))
        }
    }
}

@Composable
fun ExtensionEmptyState(
    message: String,
    buttonText: String? = null,
    onAction: (() -> Unit)? = null,
    icon: ImageVector = HugeIcons.Package,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialShapes.Cookie9Sided.toShape(),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (buttonText != null && onAction != null) {
            FilledTonalButton(onClick = onAction) {
                Icon(
                    imageVector = HugeIcons.Link01,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(buttonText)
            }
        }
    }
}
