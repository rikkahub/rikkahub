package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import com.dokar.sonner.ToastType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.AccessibilityServiceHandle
import me.rerere.rikkahub.data.ai.tools.local.PermissionHelper
import me.rerere.rikkahub.service.ActionLogEntry
import me.rerere.rikkahub.service.RikkaAccessibilityService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import java.io.File
import java.io.FileOutputStream

/**
 * Settings page for the screen-automation AccessibilityService.
 * Shows live running status, last 50 actions, deep-link to system settings, and a
 * diagnostic-screenshot button so the user can verify capture works without going through
 * the LLM.
 */
@Composable
fun SettingAccessibilityPage() {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // Re-read the live service singleton on every recomposition; when it appears or
    // disappears, the running flag and lastActions flow recollect themselves.
    val svc = remember(AccessibilityServiceHandle.isRunning()) {
        RikkaAccessibilityService.instance
    }

    // Pull StateFlows from the live service if present; otherwise show empty defaults.
    val runningFlow: StateFlow<Boolean> = svc?.running
        ?: remember { MutableStateFlow(false) }
    val running by runningFlow.collectAsState()

    val actionsFlow: StateFlow<List<ActionLogEntry>> = svc?.lastActions
        ?: remember { MutableStateFlow(emptyList()) }
    val actions by actionsFlow.collectAsState()

    val captureOkFmt = stringResource(R.string.setting_page_accessibility_capture_ok_toast)
    val captureFailFmt = stringResource(R.string.setting_page_accessibility_capture_fail_toast)

    // Re-check overlay permission on resume so the row updates immediately after the user
    // returns from the system settings deep-link.
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_accessibility)) },
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
            // Status card
            Text(
                text = stringResource(R.string.setting_page_accessibility_status_section),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp)
            )
            CardGroup {
                if (running) {
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_accessibility_status_running))
                        }
                    )
                } else {
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_accessibility_status_not_running))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_page_accessibility_status_help))
                        }
                    )
                }
                item(
                    onClick = {
                        context.startActivity(
                            PermissionHelper.accessibilitySettingsIntent()
                        )
                    },
                    headlineContent = {
                        Text(stringResource(R.string.setting_page_accessibility_open_settings))
                    },
                )
            }

            // Activity overlay card
            Text(
                text = stringResource(R.string.setting_page_accessibility_overlay_section),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp)
            )
            CardGroup {
                if (overlayGranted) {
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_accessibility_overlay_granted))
                        }
                    )
                } else {
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_accessibility_overlay_not_granted))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_page_accessibility_overlay_help))
                        }
                    )
                    item(
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        },
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_accessibility_overlay_open_settings))
                        },
                    )
                }
            }

            // Diagnostics card
            Text(
                text = stringResource(R.string.setting_page_accessibility_diagnostics_section),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp)
            )
            CardGroup {
                item(
                    onClick = {
                        scope.launch {
                            val live = RikkaAccessibilityService.instance
                            if (live == null) {
                                toaster.show(
                                    String.format(captureFailFmt, "service not active"),
                                    type = ToastType.Error,
                                )
                                return@launch
                            }
                            val result = live.captureScreenshot(0)
                            when (result) {
                                is RikkaAccessibilityService.ScreenshotOutcome.Success -> {
                                    val cacheDir = File(context.cacheDir, "screenshots")
                                        .apply { mkdirs() }
                                    val ts = System.currentTimeMillis()
                                    val file = File(cacheDir, "diag-$ts.png")
                                    try {
                                        FileOutputStream(file).use { os ->
                                            result.bitmap.compress(
                                                android.graphics.Bitmap.CompressFormat.PNG, 100, os
                                            )
                                        }
                                    } finally {
                                        result.bitmap.recycle()
                                    }
                                    toaster.show(String.format(captureOkFmt, file.name))
                                }
                                is RikkaAccessibilityService.ScreenshotOutcome.Failure -> {
                                    toaster.show(
                                        String.format(captureFailFmt, result.reason),
                                        type = ToastType.Error,
                                    )
                                }
                            }
                        }
                    },
                    headlineContent = {
                        Text(stringResource(R.string.setting_page_accessibility_capture_diag))
                    },
                )
            }

            // Recent actions card
            Text(
                text = stringResource(R.string.setting_page_accessibility_recent_section),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp)
            )
            if (actions.isEmpty()) {
                CardGroup {
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_accessibility_no_actions))
                        }
                    )
                }
            } else {
                CardGroup {
                    actions.asReversed().forEach { entry ->
                        item(
                            headlineContent = { Text("${entry.type}: ${entry.paramsSummary}") },
                            supportingContent = {
                                Text(formatRelativeTime(System.currentTimeMillis() - entry.timestampMs))
                            },
                            trailingContent = {
                                Text(
                                    text = if (entry.success) "OK" else "FAIL",
                                    color = if (entry.success)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error
                                )
                            }
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
        s < 60 -> stringResource(R.string.setting_page_accessibility_action_ago_seconds, s.toInt().coerceAtLeast(0))
        s < 3600 -> stringResource(R.string.setting_page_accessibility_action_ago_minutes, (s / 60).toInt())
        else -> stringResource(R.string.setting_page_accessibility_action_ago_hours, (s / 3600).toInt())
    }
}
