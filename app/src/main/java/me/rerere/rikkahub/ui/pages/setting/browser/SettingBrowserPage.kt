package me.rerere.rikkahub.ui.pages.setting.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import me.rerere.rikkahub.R
import me.rerere.rikkahub.browser.BrowserActivity
import me.rerere.rikkahub.browser.BrowserToolDefaults
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

/**
 * Settings → Browser. Three sections:
 *
 *  1. Browser — "Open browser" (launches BrowserActivity at about:blank for one-time
 *     manual use like signing into a site before the AI takes over) + "Clear browsing
 *     data" (wipes WebView profile dir + cookies; does NOT clear per-tool toggles —
 *     those are user config, not browsing data).
 *  2. Tools enabled — 17 individually-togglable browser tools. Read tools default ON,
 *     write tools default OFF, loop-control ON. Per the spec, the per-tool granularity
 *     is intentional — the AI controlling a real browser is the highest-trust surface
 *     in the app, so the user must be able to grant only what they trust.
 *  3. Defaults & limits — search engine (forward-compat dropdown, no-op in v1),
 *     per-tool timeout, single-task timeout. The two timeouts are editable (GitHub issue
 *     #4): values are clamped into a generous-but-bounded range in BrowserPreferences.
 */
@Composable
fun SettingBrowserPage(
    vm: SettingBrowserViewModel = koinViewModel(),
) {
    val ctx = LocalContext.current
    val toaster = LocalToaster.current
    val toolStates by vm.toolStates.collectAsStateWithLifecycle()
    val perToolTimeoutMs by vm.perToolTimeoutMs.collectAsStateWithLifecycle()
    val singleTaskTimeoutMs by vm.singleTaskTimeoutMs.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var showClearConfirm by remember { mutableStateOf(false) }
    val cleared = stringResource(R.string.setting_browser_clear_data_done)

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.setting_browser_clear_data_confirm_title)) },
            text = { Text(stringResource(R.string.setting_browser_clear_data_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    vm.clearBrowsingData(ctx) {
                        toaster.show(cleared, type = ToastType.Success)
                    }
                }) {
                    Text(stringResource(R.string.setting_browser_clear_data_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_browser_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Section: Browser
            CardGroup(
                title = { Text(stringResource(R.string.setting_browser_section_browser)) },
            ) {
                item(
                    onClick = {
                        ctx.startActivity(BrowserActivity.intent(ctx, "about:blank"))
                    },
                    headlineContent = { Text(stringResource(R.string.setting_browser_open)) },
                    supportingContent = { Text(stringResource(R.string.setting_browser_open_desc)) },
                )
                item(
                    onClick = { showClearConfirm = true },
                    headlineContent = { Text(stringResource(R.string.setting_browser_clear_data)) },
                    supportingContent = { Text(stringResource(R.string.setting_browser_clear_data_desc)) },
                )
            }

            // Section: Tools enabled — three sub-CardGroups grouped by category, each with
            // a small heading above. Mirrors AssistantLocalToolPage's category-divider pattern.
            Text(
                text = stringResource(R.string.setting_browser_tools_enabled_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
            )

            ToolCategorySection(
                heading = stringResource(R.string.setting_browser_category_read),
                tools = BrowserToolDefaults.READ_TOOLS.toList()
                    .filter { it in BrowserToolDefaults.ALL_TOOLS }
                    // Stable display order — preserves the spec's table sequence.
                    .sortedBy { BrowserToolDefaults.ALL_TOOLS.indexOf(it) },
                toolStates = toolStates,
                onToggle = vm::setToolEnabled,
            )
            ToolCategorySection(
                heading = stringResource(R.string.setting_browser_category_write),
                tools = BrowserToolDefaults.WRITE_TOOLS.toList()
                    .sortedBy { BrowserToolDefaults.ALL_TOOLS.indexOf(it) },
                toolStates = toolStates,
                onToggle = vm::setToolEnabled,
            )
            ToolCategorySection(
                heading = stringResource(R.string.setting_browser_category_loop_control),
                tools = BrowserToolDefaults.LOOP_CONTROL_TOOLS.toList()
                    .sortedBy { BrowserToolDefaults.ALL_TOOLS.indexOf(it) },
                toolStates = toolStates,
                onToggle = vm::setToolEnabled,
            )

            // Section: Defaults & limits
            CardGroup(
                title = { Text(stringResource(R.string.setting_browser_section_defaults)) },
            ) {
                item(
                    headlineContent = { Text(stringResource(R.string.setting_browser_search_engine)) },
                    supportingContent = { Text(stringResource(R.string.setting_browser_search_engine_desc)) },
                )
                // Per-tool timeout — editable, expressed in seconds. Clamped to 10 s..10 min
                // in BrowserPreferences before persist (GitHub issue #4).
                item(
                    headlineContent = { Text(stringResource(R.string.setting_browser_per_tool_timeout)) },
                    supportingContent = { Text(stringResource(R.string.setting_browser_per_tool_timeout_desc)) },
                    trailingContent = {
                        TimeoutInput(
                            currentValue = perToolTimeoutMs / 1_000L,
                            unitLabel = stringResource(R.string.setting_browser_per_tool_timeout_unit),
                            onCommit = vm::setPerToolTimeoutSeconds,
                        )
                    },
                )
                // Single-task timeout — editable, expressed in minutes. Clamped to
                // 1 min..60 min in BrowserPreferences before persist.
                item(
                    headlineContent = { Text(stringResource(R.string.setting_browser_single_task_timeout)) },
                    supportingContent = { Text(stringResource(R.string.setting_browser_single_task_timeout_desc)) },
                    trailingContent = {
                        TimeoutInput(
                            currentValue = singleTaskTimeoutMs / 60_000L,
                            unitLabel = stringResource(R.string.setting_browser_single_task_timeout_unit),
                            onCommit = vm::setSingleTaskTimeoutMinutes,
                        )
                    },
                )
            }
        }
    }
}

/**
 * Compact numeric input for a timeout row's trailing slot. [currentValue] is the persisted
 * value in display units (seconds or minutes); editing is buffered in local state and
 * committed on focus loss. The persisted value is clamped in [BrowserPreferences], so an
 * out-of-range entry snaps back to the nearest bound — the StateFlow round-trip refreshes
 * [currentValue] and the buffer follows it.
 */
@Composable
private fun TimeoutInput(
    currentValue: Long,
    unitLabel: String,
    onCommit: (Long) -> Unit,
) {
    // Local edit buffer. Re-seeds whenever the persisted value changes (including the
    // clamp-corrected value flowing back after a commit), so the field never goes stale.
    var text by remember(currentValue) { mutableStateOf(currentValue.toString()) }

    OutlinedTextField(
        value = text,
        onValueChange = { new -> text = new.filter { it.isDigit() }.take(4) },
        singleLine = true,
        suffix = { Text(unitLabel, style = MaterialTheme.typography.bodySmall) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .width(132.dp)
            .onFocusChanged { focus ->
                if (!focus.isFocused) {
                    val parsed = text.toLongOrNull()
                    if (parsed != null && parsed != currentValue) {
                        onCommit(parsed)
                    } else {
                        // Empty / unchanged — restore the canonical display value.
                        text = currentValue.toString()
                    }
                }
            },
    )
}

@Composable
private fun ToolCategorySection(
    heading: String,
    tools: List<String>,
    toolStates: Map<String, Boolean>,
    onToggle: (String, Boolean) -> Unit,
) {
    Text(
        text = heading,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
    )
    CardGroup {
        tools.forEach { toolName ->
            val checked = toolStates[toolName] ?: BrowserToolDefaults.DEFAULT_ENABLED[toolName] ?: false
            item(
                headlineContent = { Text(toolDisplayTitle(toolName)) },
                supportingContent = { Text(toolDisplayDesc(toolName)) },
                trailingContent = {
                    Switch(
                        checked = checked,
                        onCheckedChange = { onToggle(toolName, it) },
                    )
                },
            )
        }
    }
}

@Composable
private fun toolDisplayTitle(toolName: String): String = when (toolName) {
    BrowserToolDefaults.OPEN -> stringResource(R.string.setting_browser_tool_open_title)
    BrowserToolDefaults.CURRENT_URL -> stringResource(R.string.setting_browser_tool_current_url_title)
    BrowserToolDefaults.SCREENSHOT -> stringResource(R.string.setting_browser_tool_screenshot_title)
    BrowserToolDefaults.GET_TEXT -> stringResource(R.string.setting_browser_tool_get_text_title)
    BrowserToolDefaults.GET_DOM -> stringResource(R.string.setting_browser_tool_get_dom_title)
    BrowserToolDefaults.GET_LINKS -> stringResource(R.string.setting_browser_tool_get_links_title)
    BrowserToolDefaults.BACK -> stringResource(R.string.setting_browser_tool_back_title)
    BrowserToolDefaults.FORWARD -> stringResource(R.string.setting_browser_tool_forward_title)
    BrowserToolDefaults.WAIT_FOR -> stringResource(R.string.setting_browser_tool_wait_for_title)
    BrowserToolDefaults.CLICK -> stringResource(R.string.setting_browser_tool_click_title)
    BrowserToolDefaults.TYPE -> stringResource(R.string.setting_browser_tool_type_title)
    BrowserToolDefaults.SCROLL -> stringResource(R.string.setting_browser_tool_scroll_title)
    BrowserToolDefaults.SUBMIT -> stringResource(R.string.setting_browser_tool_submit_title)
    BrowserToolDefaults.SELECT -> stringResource(R.string.setting_browser_tool_select_title)
    BrowserToolDefaults.PRESS_KEY -> stringResource(R.string.setting_browser_tool_press_key_title)
    BrowserToolDefaults.EVAL_JS -> stringResource(R.string.setting_browser_tool_eval_js_title)
    BrowserToolDefaults.DONE -> stringResource(R.string.setting_browser_tool_done_title)
    else -> toolName
}

@Composable
private fun toolDisplayDesc(toolName: String): String = when (toolName) {
    BrowserToolDefaults.OPEN -> stringResource(R.string.setting_browser_tool_open_desc)
    BrowserToolDefaults.CURRENT_URL -> stringResource(R.string.setting_browser_tool_current_url_desc)
    BrowserToolDefaults.SCREENSHOT -> stringResource(R.string.setting_browser_tool_screenshot_desc)
    BrowserToolDefaults.GET_TEXT -> stringResource(R.string.setting_browser_tool_get_text_desc)
    BrowserToolDefaults.GET_DOM -> stringResource(R.string.setting_browser_tool_get_dom_desc)
    BrowserToolDefaults.GET_LINKS -> stringResource(R.string.setting_browser_tool_get_links_desc)
    BrowserToolDefaults.BACK -> stringResource(R.string.setting_browser_tool_back_desc)
    BrowserToolDefaults.FORWARD -> stringResource(R.string.setting_browser_tool_forward_desc)
    BrowserToolDefaults.WAIT_FOR -> stringResource(R.string.setting_browser_tool_wait_for_desc)
    BrowserToolDefaults.CLICK -> stringResource(R.string.setting_browser_tool_click_desc)
    BrowserToolDefaults.TYPE -> stringResource(R.string.setting_browser_tool_type_desc)
    BrowserToolDefaults.SCROLL -> stringResource(R.string.setting_browser_tool_scroll_desc)
    BrowserToolDefaults.SUBMIT -> stringResource(R.string.setting_browser_tool_submit_desc)
    BrowserToolDefaults.SELECT -> stringResource(R.string.setting_browser_tool_select_desc)
    BrowserToolDefaults.PRESS_KEY -> stringResource(R.string.setting_browser_tool_press_key_desc)
    BrowserToolDefaults.EVAL_JS -> stringResource(R.string.setting_browser_tool_eval_js_desc)
    BrowserToolDefaults.DONE -> stringResource(R.string.setting_browser_tool_done_desc)
    else -> ""
}
