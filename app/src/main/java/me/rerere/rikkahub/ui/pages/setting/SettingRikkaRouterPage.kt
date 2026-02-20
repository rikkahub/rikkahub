package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Boxes
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Star
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.findRikkaRouterMatches
import me.rerere.rikkahub.data.model.RikkaRouterGroup
import me.rerere.rikkahub.data.model.RikkaRouterMember
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

@Composable
fun SettingRikkaRouterPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val router = settings.rikkaRouter

    var showAddDialog by remember { mutableStateOf(false) }
    var newGroupName by remember { mutableStateOf("") }

    fun updateGroup(group: RikkaRouterGroup) {
        vm.updateSettings(
            settings.copy(
                rikkaRouter = router.copy(
                    groups = router.groups.map {
                        if (it.id == group.id) group else it
                    }
                )
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("rikkarouter") },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(
                        onClick = { showAddDialog = true }
                    ) {
                        Icon(Lucide.Plus, contentDescription = "Add")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "启用 rikkarouter",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = router.enabled,
                        onCheckedChange = {
                            vm.updateSettings(
                                settings.copy(
                                    rikkaRouter = router.copy(enabled = it)
                                )
                            )
                        }
                    )
                }
            }

            if (router.groups.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("暂无模型")
                        Button(onClick = { showAddDialog = true }) {
                            Text("添加模型")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(router.groups, key = { it.id }) { group ->
                        RikkaRouterGroupCard(
                            group = group,
                            matches = settings.findRikkaRouterMatches(group.name),
                            onToggleEnabled = { enabled ->
                                updateGroup(group.copy(enabled = enabled))
                            },
                            onDeleteGroup = {
                                vm.updateSettings(
                                    settings.copy(
                                        rikkaRouter = router.copy(
                                            groups = router.groups.filterNot { it.id == group.id }
                                        )
                                    )
                                )
                            },
                            onToggleMember = { provider, model ->
                                val existed = group.members.any { it.modelId == model.id }
                                val newMembers = if (existed) {
                                    group.members.filterNot { it.modelId == model.id }
                                } else {
                                    val nextOrder = (group.members.maxOfOrNull { it.order } ?: -1) + 1
                                    group.members + RikkaRouterMember(
                                        modelId = model.id,
                                        enabled = true,
                                        order = nextOrder
                                    )
                                }
                                val primary = if (group.primaryModelId == model.id && existed) {
                                    null
                                } else {
                                    group.primaryModelId
                                }
                                updateGroup(group.copy(members = newMembers, primaryModelId = primary))
                            },
                            onSetPrimary = { modelId ->
                                updateGroup(group.copy(primaryModelId = modelId))
                            }
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("添加常用模型") },
            text = {
                OutlinedTextField(
                    value = newGroupName,
                    onValueChange = { newGroupName = it },
                    label = { Text("模型显示名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newGroupName.trim()
                        if (name.isBlank()) return@TextButton
                        vm.updateSettings(
                            settings.copy(
                                rikkaRouter = router.copy(
                                    groups = listOf(
                                        RikkaRouterGroup(
                                            id = Uuid.random(),
                                            name = name,
                                        )
                                    ) + router.groups
                                )
                            )
                        )
                        newGroupName = ""
                        showAddDialog = false
                    }
                ) {
                    Text("添加")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAddDialog = false
                        newGroupName = ""
                    }
                ) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun RikkaRouterGroupCard(
    group: RikkaRouterGroup,
    matches: List<Pair<ProviderSetting, Model>>,
    onToggleEnabled: (Boolean) -> Unit,
    onDeleteGroup: () -> Unit,
    onToggleMember: (ProviderSetting, Model) -> Unit,
    onSetPrimary: (Uuid) -> Unit,
) {
    var expanded by rememberSaveable(group.id) { mutableStateOf(false) }
    val selected = group.members.associateBy { it.modelId }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Lucide.Boxes, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = group.enabled,
                    onCheckedChange = onToggleEnabled
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "已挂载 ${group.members.size} 个模型",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { expanded = !expanded }
                ) {
                    Icon(if (expanded) Lucide.ChevronUp else Lucide.ChevronDown, contentDescription = null)
                }
                IconButton(
                    onClick = onDeleteGroup
                ) {
                    Icon(Lucide.Trash2, contentDescription = null)
                }
            }

            if (expanded) {
                if (matches.isEmpty()) {
                    Text(
                        text = "没有匹配到可挂载模型",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    matches.forEach { (provider, model) ->
                        val selectedMember = selected[model.id]
                        val isSelected = selectedMember != null
                        val isPrimary = group.primaryModelId == model.id

                        Card(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    AutoAIIcon(
                                        name = model.modelId,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = model.displayName.ifBlank { model.modelId },
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = provider.name,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(
                                        onClick = { onToggleMember(provider, model) }
                                    ) {
                                        Icon(
                                            if (isSelected) Lucide.X else Lucide.Plus,
                                            contentDescription = null
                                        )
                                    }
                                }

                                if (isSelected) {
                                    TextButton(
                                        onClick = { onSetPrimary(model.id) }
                                    ) {
                                        Icon(
                                            Lucide.Star,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = if (isPrimary) "当前主力" else "设为主力",
                                            modifier = Modifier.padding(start = 4.dp)
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
