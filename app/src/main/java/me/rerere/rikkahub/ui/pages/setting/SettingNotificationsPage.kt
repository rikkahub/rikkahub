package me.rerere.rikkahub.ui.pages.setting

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.notifications.NotificationEntry
import me.rerere.rikkahub.data.notifications.NotificationListenerConfig
import me.rerere.rikkahub.data.notifications.NotificationListenerPreferences
import me.rerere.rikkahub.data.telegram.TelegramBotConfig
import me.rerere.rikkahub.data.telegram.TelegramBotPreferences
import me.rerere.rikkahub.service.RikkaNotificationListenerService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject

/**
 * Settings page for the notification listener subsystem. Shows live bound state, the
 * per-app whitelist editor, and a recent-activity log. Whitelist persists via
 * NotificationListenerPreferences. Mirrors the SettingTelegramPage layout.
 */
@Composable
fun SettingNotificationsPage() {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val listenerPrefs: NotificationListenerPreferences = koinInject()
    val telegramPrefs: TelegramBotPreferences = koinInject()

    val cfg by listenerPrefs.flow.collectAsStateWithLifecycle(initialValue = NotificationListenerConfig())
    val tg by telegramPrefs.flow.collectAsStateWithLifecycle(initialValue = TelegramBotConfig())

    // Live service handle: collected via StateFlow when the service is bound; falls back to
    // empty defaults otherwise. Recompositions key off the listener's bound flag.
    val svc = remember { RikkaNotificationListenerService.instance }
    val boundFlow: StateFlow<Boolean> = svc?.bound ?: remember { MutableStateFlow(false) }
    val bound by boundFlow.collectAsStateWithLifecycle()
    val recentFlow: StateFlow<List<NotificationEntry>> = svc?.recent ?: remember { MutableStateFlow(emptyList()) }
    val recent by recentFlow.collectAsStateWithLifecycle()

    var pickerOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_notifications)) },
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
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Status
            Text(
                text = stringResource(R.string.setting_page_notifications_status_section),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp)
            )
            CardGroup {
                item(
                    headlineContent = {
                        Text(
                            if (bound) stringResource(R.string.setting_page_notifications_status_connected)
                            else stringResource(R.string.setting_page_notifications_status_not_connected),
                            color = if (bound) Color(0xFF22C55E) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    supportingContent = if (!bound) ({
                        Text(stringResource(R.string.setting_page_notifications_status_help))
                    }) else null,
                )
                item(
                    onClick = {
                        context.startActivity(
                            me.rerere.rikkahub.data.ai.tools.local.PermissionHelper
                                .notificationListenerSettingsIntent()
                        )
                    },
                    headlineContent = {
                        Text(stringResource(R.string.setting_page_notifications_open_settings))
                    },
                )
                item(
                    headlineContent = {
                        val chatId = tg.defaultChatId
                        Text(
                            if (chatId != null && tg.enabled)
                                stringResource(R.string.setting_page_notifications_default_chat_set, chatId.toString())
                            else
                                stringResource(R.string.setting_page_notifications_default_chat_unset),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }

            // Whitelist
            Text(
                text = stringResource(R.string.setting_page_notifications_whitelist_section),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp)
            )
            CardGroup {
                item(
                    onClick = { pickerOpen = true },
                    headlineContent = {
                        Text(stringResource(R.string.setting_page_notifications_whitelist_label))
                    },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = whitelistSummary(cfg.whitelist.size),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                stringResource(R.string.setting_page_notifications_whitelist_help),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    trailingContent = {
                        TextButton(onClick = { pickerOpen = true }) {
                            Text(stringResource(R.string.setting_page_notifications_whitelist_manage))
                        }
                    },
                )
            }

            if (pickerOpen) {
                AppWhitelistPickerDialog(
                    selected = cfg.whitelist,
                    onDismiss = { pickerOpen = false },
                    onToggle = { pkg, on ->
                        scope.launch {
                            listenerPrefs.update { c ->
                                c.copy(
                                    whitelist = if (on) c.whitelist + pkg else c.whitelist - pkg
                                )
                            }
                        }
                    },
                )
            }

            // Recent activity
            Text(
                text = stringResource(R.string.setting_page_notifications_recent_section),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp)
            )
            if (recent.isEmpty()) {
                CardGroup {
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_notifications_recent_empty))
                        }
                    )
                }
            } else {
                CardGroup {
                    recent.asReversed().take(50).forEach { entry ->
                        item(
                            headlineContent = {
                                Text("${entry.label}: ${entry.title.ifBlank { "(no title)" }}")
                            },
                            supportingContent = {
                                Text(
                                    text = entry.text.take(120).ifBlank { "(no text)" } + " · " +
                                        formatRelativeTime(System.currentTimeMillis() - entry.postTimeMs),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun formatRelativeTime(deltaMs: Long): String {
    val s = deltaMs / 1000
    return when {
        s < 60 -> stringResource(R.string.setting_page_notifications_action_ago_seconds, s.toInt().coerceAtLeast(0))
        s < 3600 -> stringResource(R.string.setting_page_notifications_action_ago_minutes, (s / 60).toInt())
        else -> stringResource(R.string.setting_page_notifications_action_ago_hours, (s / 3600).toInt())
    }
}

@Composable
private fun whitelistSummary(count: Int): String = when (count) {
    0 -> stringResource(R.string.setting_page_notifications_whitelist_summary_zero)
    1 -> stringResource(R.string.setting_page_notifications_whitelist_summary_n, 1)
    else -> stringResource(R.string.setting_page_notifications_whitelist_summary_n_plural, count)
}

private data class InstalledAppRow(
    val packageName: String,
    val label: String,
)

/**
 * Modal app picker for the auto-route whitelist. Loads all installed user apps once via
 * PackageManager (off the main thread), shows them with their app icon, label, and package
 * name, plus a Switch per row that auto-saves on toggle. Search field at the top filters by
 * label or package; an optional filter chip narrows to currently-whitelisted only.
 *
 * The dialog NEVER lists the rikkahub package itself or com.android.systemui — both would
 * trigger the listener service's self-loop guard regardless of whether they're whitelisted.
 */
@Composable
private fun AppWhitelistPickerDialog(
    selected: Set<String>,
    onDismiss: () -> Unit,
    onToggle: (packageName: String, enabled: Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledAppRow>?>(null) }
    var query by remember { mutableStateOf("") }
    var showSelectedOnly by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadInstalledApps(ctx) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_page_notifications_whitelist_picker_title)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    singleLine = true,
                    placeholder = {
                        Text(stringResource(R.string.setting_page_notifications_whitelist_picker_search))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = CircleShape,
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                FilterChip(
                    selected = showSelectedOnly,
                    onClick = { showSelectedOnly = !showSelectedOnly },
                    label = {
                        Text(stringResource(R.string.setting_page_notifications_whitelist_picker_show_only_selected))
                    },
                )

                val list = apps
                if (list == null) {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("…", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    val needle = query.trim().lowercase()
                    val visible = list.filter { row ->
                        val matchesQuery = needle.isEmpty() ||
                            row.label.lowercase().contains(needle) ||
                            row.packageName.lowercase().contains(needle)
                        val matchesSelected = !showSelectedOnly || row.packageName in selected
                        matchesQuery && matchesSelected
                    }
                    if (visible.isEmpty()) {
                        Text(
                            stringResource(R.string.setting_page_notifications_whitelist_picker_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(visible, key = { it.packageName }) { row ->
                                AppWhitelistRow(
                                    row = row,
                                    checked = row.packageName in selected,
                                    onCheckedChange = { on -> onToggle(row.packageName, on) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.setting_page_notifications_whitelist_picker_done))
            }
        },
    )
}

@Composable
private fun AppWhitelistRow(
    row: InstalledAppRow,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    val icon = remember(row.packageName) {
        try { ctx.packageManager.getApplicationIcon(row.packageName) } catch (_: Throwable) { null }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Coil 3 accepts an Android Drawable as the model directly; no manual conversion.
        AsyncImage(
            model = icon,
            contentDescription = null,
            modifier = Modifier.size(36.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = row.label.ifBlank { row.packageName },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            Text(
                text = row.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * One-shot read of installed user apps. Sorts by display label. Excludes self-package and
 * com.android.systemui because the listener service drops their notifications regardless.
 * Includes both launcher apps and addon-style packages (no launcher) so users can whitelist
 * service-only apps if they want — same broad scope list_installed_apps uses with a filter.
 */
private fun loadInstalledApps(ctx: Context): List<InstalledAppRow> {
    val pm = ctx.packageManager
    @Suppress("DEPRECATION")
    val all = try { pm.getInstalledPackages(0) } catch (_: Throwable) { emptyList() }
    val rows = mutableListOf<InstalledAppRow>()
    for (info in all) {
        val pkg = info.packageName
        if (pkg == ctx.packageName) continue
        if (pkg == "com.android.systemui") continue
        val appInfo: ApplicationInfo = info.applicationInfo ?: continue
        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val isUpdated = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        if (isSystem && !isUpdated) continue
        val label = try { appInfo.loadLabel(pm).toString() } catch (_: Throwable) { pkg }
        rows.add(InstalledAppRow(packageName = pkg, label = label))
    }
    rows.sortBy { it.label.lowercase() }
    return rows
}
