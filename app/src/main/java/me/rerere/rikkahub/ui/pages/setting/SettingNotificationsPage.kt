package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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

    val cfg by listenerPrefs.flow.collectAsState(initial = NotificationListenerConfig())
    val tg by telegramPrefs.flow.collectAsState(initial = TelegramBotConfig())

    // Live service handle: collected via StateFlow when the service is bound; falls back to
    // empty defaults otherwise. Recompositions key off the listener's bound flag.
    val svc = remember { RikkaNotificationListenerService.instance }
    val boundFlow: StateFlow<Boolean> = svc?.bound ?: remember { MutableStateFlow(false) }
    val bound by boundFlow.collectAsState()
    val recentFlow: StateFlow<List<NotificationEntry>> = svc?.recent ?: remember { MutableStateFlow(emptyList()) }
    val recent by recentFlow.collectAsState()

    var whitelistText by remember(cfg.whitelist) {
        mutableStateOf(cfg.whitelist.sorted().joinToString(","))
    }

    val savedFmt = stringResource(R.string.setting_page_notifications_whitelist_saved_toast)

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
                    headlineContent = {
                        Text(stringResource(R.string.setting_page_notifications_whitelist_label))
                    },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                stringResource(R.string.setting_page_notifications_whitelist_help),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextField(
                                value = whitelistText,
                                onValueChange = { whitelistText = it },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                                singleLine = false,
                                modifier = Modifier.fillMaxSize(),
                                shape = CircleShape,
                                colors = TextFieldDefaults.colors(
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                ),
                            )
                            TextButton(onClick = {
                                scope.launch {
                                    val parsed = whitelistText.split(",")
                                        .map { it.trim() }
                                        .filter { it.isNotBlank() }
                                        .toSet()
                                    listenerPrefs.update { it.copy(whitelist = parsed) }
                                    toaster.show(String.format(savedFmt, parsed.size))
                                }
                            }) {
                                Text(stringResource(R.string.setting_page_notifications_whitelist_save))
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
