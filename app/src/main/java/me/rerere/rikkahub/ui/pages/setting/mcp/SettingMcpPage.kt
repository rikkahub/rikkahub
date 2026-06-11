package me.rerere.rikkahub.ui.pages.setting.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.ai.runtime.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpStatus
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.pages.setting.SettingVM
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun SettingMcpPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val mcpConfigs = settings.mcpServers
    val creationState = useEditState<McpServerConfig> {
        vm.updateSettings(
            settings.copy(
                mcpServers = mcpConfigs + it
            )
        )
    }
    val editState = useEditState<McpServerConfig> { newConfig ->
        vm.updateSettings(
            settings.copy(
                mcpServers = mcpConfigs.map {
                    if (it.id == newConfig.id) {
                        newConfig
                    } else {
                        it
                    }
                }
            ))
    }
    var showImportDialog by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_mcp_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    IconButton(
                        onClick = {
                            showImportDialog = true
                        }
                    ) {
                        Icon(HugeIcons.FileImport, null)
                    }
                    IconButton(
                        onClick = {
                            creationState.open(McpServerConfig.StreamableHTTPServer())
                        }
                    ) {
                        Icon(HugeIcons.Add01, null)
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        val mcpManager = koinInject<McpManager>()
        val status by mcpManager.syncingStatus.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        val state = rememberPullToRefreshState()
        val loading = status.values.any { it == McpStatus.Connecting || it is McpStatus.Reconnecting }
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = {
                scope.launch {
                    mcpManager.syncAll()
                }
            },
            state = state,
            modifier = Modifier.padding(innerPadding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(16.dp)
            ) {
                items(mcpConfigs, key = { it.id }) { mcpConfig ->
                    McpServerItem(
                        item = mcpConfig,
                        onEdit = {
                            editState.open(mcpConfig)
                        },
                        onDelete = {
                            vm.updateSettings(
                                settings.copy(
                                    mcpServers = mcpConfigs.filter { it.id != mcpConfig.id }
                                )
                            )
                        },
                        modifier = Modifier.animateItem()
                    )
                }
            }

            if (mcpConfigs.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = stringResource(R.string.setting_mcp_page_no_mcp_servers_found))
                    Text(
                        text = stringResource(R.string.setting_mcp_page_add_one_to_get_started),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
    McpServerConfigModal(creationState)
    McpServerConfigModal(editState)
    if (showImportDialog) {
        McpImportModal(
            onDismiss = { showImportDialog = false },
            onImport = { newConfigs ->
                val existingIds = mcpConfigs.map { it.commonOptions.name }.toSet()
                val toAdd = newConfigs.filter { it.commonOptions.name !in existingIds }
                vm.updateSettings(settings.copy(mcpServers = mcpConfigs + toAdd))
                showImportDialog = false
            }
        )
    }
}
