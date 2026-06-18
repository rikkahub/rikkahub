package me.rerere.rikkahub.ui.components.ui

import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

@Composable
fun FormSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var local by remember { mutableStateOf(checked) }
    LaunchedEffect(checked) {
        local = checked
    }
    Switch(
        checked = local,
        onCheckedChange = {
            local = it
            onCheckedChange(it)
        },
        enabled = enabled,
        modifier = modifier,
    )
}
