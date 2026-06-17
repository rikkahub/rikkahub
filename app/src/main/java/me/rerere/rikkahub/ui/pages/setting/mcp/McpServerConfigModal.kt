package me.rerere.rikkahub.ui.pages.setting.mcp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.ai.runtime.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpStatus
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.hooks.EditState
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.theme.extendColors
import org.koin.compose.koinInject

@Composable
internal fun McpServerConfigModal(state: EditState<McpServerConfig>) {
    val mcpManager = koinInject<McpManager>()
    val settingsStore = koinInject<SettingsStore>()

    state.EditStateContent { config, updateValue ->
        val pagerState = rememberPagerState { 2 }
        val scope = rememberCoroutineScope()

        // Live connection status for THIS server (keyed by id so the flow isn't restarted on every
        // field edit). Drives the Connect/Reconnect button below.
        val status by remember(config.id) { mcpManager.getStatus(config) }
            .collectAsStateWithLifecycle(initialValue = McpStatus.Idle)

        // sync() writes the discovered tools to SETTINGS by id, not to this draft. Merge the stored
        // tools into the draft REACTIVELY — whenever they change (i.e. after a connect/reconnect) —
        // preserving any enable/needsApproval the user has toggled in the draft. This is why the
        // Tools tab populates after connecting without reopening the sheet, and (unlike adopting a
        // click-time snapshot) it can never revert an edit made while the connection was in flight.
        val storedTools = settingsStore.settingsFlow.collectAsStateWithLifecycle().value
            .mcpServers.find { it.id == config.id }?.commonOptions?.tools
        LaunchedEffect(storedTools) {
            val stored = storedTools ?: return@LaunchedEffect
            val merged = stored.map { st ->
                config.commonOptions.tools.find { it.name == st.name }
                    ?.let { st.copy(enable = it.enable, needsApproval = it.needsApproval) }
                    ?: st
            }
            if (merged != config.commonOptions.tools) {
                updateValue(config.clone(commonOptions = config.commonOptions.copy(tools = merged)))
            }
        }

        // Persist the draft to settings WITHOUT dismissing the sheet, upserting by id (the first save
        // of a new server appends it, later saves update it). Awaited so a follow-up connect's sync()
        // can find the server by id and write its discovered tools back.
        suspend fun persist(cfg: McpServerConfig) {
            settingsStore.update { s ->
                val exists = s.mcpServers.any { it.id == cfg.id }
                s.copy(
                    mcpServers = if (exists) {
                        s.mcpServers.map { if (it.id == cfg.id) cfg else it }
                    } else {
                        s.mcpServers + cfg
                    }
                )
            }
        }

        ModalBottomSheet(
            onDismissRequest = { state.dismiss() },
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SecondaryTabRow(
                    selectedTabIndex = pagerState.currentPage,
                    containerColor = Color.Transparent
                ) {
                    Tab(
                        selected = pagerState.currentPage == 0,
                        onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                        text = { Text(stringResource(R.string.setting_mcp_page_basic_settings)) }
                    )
                    Tab(
                        selected = pagerState.currentPage == 1,
                        onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                        text = { Text(stringResource(R.string.setting_mcp_page_tools)) }
                    )
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) { page ->
                    when (page) {
                        0 -> McpCommonOptionsConfigure(config = config, update = updateValue)
                        1 -> McpToolsConfigure(config = config, update = updateValue)
                    }
                }

                val nameValid = config.commonOptions.name.isNotBlank()
                val enabled = config.commonOptions.enable
                val busy = status is McpStatus.Connecting || status is McpStatus.Reconnecting

                ConnectionStatusRow(status = status, disabled = !enabled)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Connect when never tried; Reconnect once it has been (connected/errored). Gated
                    // on `enable` because the auto-connect manager only keeps ENABLED servers and would
                    // tear down a manually-connected disabled one on the next settings emission.
                    TextButton(
                        enabled = nameValid && enabled && !busy,
                        onClick = {
                            scope.launch {
                                persist(config)
                                // Persisting an enabled server makes the manager auto-connect it when
                                // it is not yet tracked (Idle) — one connect. When it is already
                                // tracked (status != Idle), the auto-connect won't re-add the same id,
                                // so trigger the reconnect explicitly. Either branch runs exactly one
                                // connect for this id, so there is no persist-and-addClient double.
                                if (status !is McpStatus.Idle) {
                                    mcpManager.reconnect(config)
                                }
                                // Tools land via the reactive merge above once sync() writes them.
                            }
                        }
                    ) {
                        Text(if (status is McpStatus.Idle) "Connect" else "Reconnect")
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { state.dismiss() }) {
                            Text("Close")
                        }
                        // Save persists but keeps the sheet open, so the user can switch to the Tools
                        // tab and review/configure the discovered tools before leaving.
                        TextButton(
                            enabled = nameValid,
                            onClick = { scope.launch { persist(config) } }
                        ) {
                            Text(stringResource(R.string.setting_mcp_page_save))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatusRow(status: McpStatus, disabled: Boolean) {
    // A disabled (not-enabled) server can't be connected (see the Connect gate), so say why rather
    // than just greying the button — but a live status (connecting/connected/error) still wins.
    val showDisabledHint = disabled && (status is McpStatus.Idle)
    val color = when {
        showDisabledHint -> MaterialTheme.colorScheme.outline
        status is McpStatus.Connected -> MaterialTheme.extendColors.green6
        status is McpStatus.Error -> MaterialTheme.extendColors.red6
        status is McpStatus.Connecting || status is McpStatus.Reconnecting -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    val text = when {
        showDisabledHint -> "Disabled — enable the server to connect"
        status is McpStatus.Connected -> "Connected"
        status is McpStatus.Connecting -> "Connecting…"
        status is McpStatus.Reconnecting -> "Reconnecting ${status.attempt}/${status.maxAttempts}…"
        status is McpStatus.Error -> status.message
        else -> "Not connected"
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (status is McpStatus.Connecting || status is McpStatus.Reconnecting) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp))
        }
    }
}
