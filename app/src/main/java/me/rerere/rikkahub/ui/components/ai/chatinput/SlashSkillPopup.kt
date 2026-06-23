package me.rerere.rikkahub.ui.components.ai.chatinput

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.Repeat
import me.rerere.hugeicons.stroke.Target01

/** Render-time icon for a slash row (#364 slice 4): the built-in autonomy commands get a distinct glyph. */
private fun SlashItem.icon(): ImageVector = when (this) {
    is SlashItem.Builtin -> when (name) {
        "goal" -> HugeIcons.Target01
        "loop" -> HugeIcons.Repeat
        else -> HugeIcons.Puzzle
    }
    is SlashItem.Skill -> HugeIcons.Puzzle
}

/**
 * The "/" slash-command popup (user request): typing a leading "/" in the chat input surfaces this
 * card ABOVE the input, listing the reserved native commands (`/goal`, `/loop`) and the available
 * skills (filtered by the text after "/"). Picking a built-in drops the command into the input; picking
 * a skill enables it on the current assistant so the agent can invoke it via `use_skill`. "Manage"
 * opens the Skills screen. Rendered as a sibling above the TextField (not a floating Popup) so it
 * naturally sits over the composer the way the concept shows.
 */
@Composable
internal fun SlashSkillPopup(
    items: List<SlashItem>,
    onSelect: (SlashItem) -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Commands",
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(onClick = onManage) {
                    Text("Manage")
                }
            }
            if (items.isEmpty()) {
                Text(
                    text = "No matching commands",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                    items(items, key = { it.key }) { item ->
                        Surface(
                            onClick = { onSelect(item) },
                            color = Color.Transparent,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = item.icon(),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (item.description.isNotBlank()) {
                                        Text(
                                            text = item.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
