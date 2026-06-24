package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Connect
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Edit02
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.SshHostEntity
import me.rerere.rikkahub.data.repository.SshHostRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.pages.setting.components.SshHostFormSheet
import me.rerere.rikkahub.ui.pages.setting.components.SshTestDialog
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingSshPage() {
    val repo: SshHostRepository = koinInject()
    var hosts by remember { mutableStateOf<List<SshHostEntity>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }

    var showAddSheet by remember { mutableStateOf(false) }
    var editingHost by remember { mutableStateOf<SshHostEntity?>(null) }
    var testingHost by remember { mutableStateOf<SshHostEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<SshHostEntity?>(null) }

    fun refresh() {
        scope.launch {
            hosts = repo.getAll()
            loaded = true
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val filteredHosts = remember(hosts, searchQuery) {
        if (searchQuery.isBlank()) hosts
        else hosts.filter { h ->
            h.name.contains(searchQuery, ignoreCase = true) ||
            h.host.contains(searchQuery, ignoreCase = true) ||
            h.user.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (showSearch) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text(stringResource(R.string.setting_ssh_search)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(stringResource(R.string.setting_ssh_title))
                    }
                },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { showSearch = !showSearch; if (!showSearch) searchQuery = "" }) {
                        Icon(HugeIcons.Search01, contentDescription = stringResource(R.string.setting_ssh_search))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddSheet = true }) {
                Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.setting_ssh_add))
            }
        }
    ) { padding ->
        if (loaded && filteredHosts.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    HugeIcons.ServerStack01,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.setting_ssh_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (loaded) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item { Spacer(Modifier.height(8.dp)) }
                items(filteredHosts, key = { it.name }) { host ->
                    SshHostItem(
                        host = host,
                        onEdit = { editingHost = host },
                        onTest = { testingHost = host },
                        onDelete = { deleteTarget = host },
                    )
                }
                item { Spacer(Modifier.height(80.dp)) } // FAB clearance
            }
        }
    }

    // Add sheet
    if (showAddSheet) {
        SshHostFormSheet(
            onDismiss = { showAddSheet = false },
            onSave = { entity ->
                scope.launch {
                    repo.upsert(entity)
                    refresh()
                }
            },
        )
    }

    // Edit sheet
    editingHost?.let { host ->
        SshHostFormSheet(
            existing = host,
            onDismiss = { editingHost = null },
            onSave = { entity ->
                scope.launch {
                    if (entity.name != host.name) {
                        repo.deleteByName(host.name)
                    }
                    repo.upsert(entity)
                    refresh()
                }
            },
        )
    }

    // Test dialog
    testingHost?.let { host ->
        SshTestDialog(
            host = host,
            onDismiss = { testingHost = null },
        )
    }

    // Delete confirmation
    deleteTarget?.let { host ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.setting_ssh_delete_confirm)) },
            text = {
                Text("${host.user}@${host.host}:${host.port} (${host.name})")
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        repo.deleteByName(host.name)
                        refresh()
                    }
                    deleteTarget = null
                }) {
                    Text(stringResource(R.string.setting_ssh_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun SshHostItem(
    host: SshHostEntity,
    onEdit: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            HugeIcons.ServerStack01,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = host.name,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "${host.user}@${host.host}:${host.port}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = buildAuthLabel(host),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        Row {
            IconButton(onClick = onTest) {
                Icon(
                    HugeIcons.Connect,
                    contentDescription = stringResource(R.string.setting_ssh_test),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onEdit) {
                Icon(
                    HugeIcons.Edit02,
                    contentDescription = stringResource(R.string.setting_ssh_edit),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    HugeIcons.Delete02,
                    contentDescription = stringResource(R.string.setting_ssh_delete),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

private fun buildAuthLabel(host: SshHostEntity): String {
    val parts = mutableListOf<String>()
    if (!host.password.isNullOrBlank()) parts.add("password")
    if (!host.privateKey.isNullOrBlank()) parts.add("private key")
    return if (parts.isEmpty()) "no auth configured" else parts.joinToString(" + ")
}
