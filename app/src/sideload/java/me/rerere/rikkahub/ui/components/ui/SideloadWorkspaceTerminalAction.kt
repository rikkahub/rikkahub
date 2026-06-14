package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Console

@Composable
internal fun SideloadWorkspaceTerminalAction(
    workspaceId: String?,
    onOpenTerminal: (String) -> Unit,
) {
    OutlinedButton(
        enabled = workspaceId != null,
        onClick = { workspaceId?.let(onOpenTerminal) },
    ) {
        Icon(
            imageVector = HugeIcons.Console,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Text("Open terminal")
    }
}
