package me.rerere.rikkahub.ui.pages.setting.doctor

import android.Manifest
import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.AccessibilityServiceHandle
import me.rerere.rikkahub.data.ai.tools.local.NotificationListenerHandle
import me.rerere.rikkahub.data.ai.tools.local.PermissionHelper
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRunRepository
import me.rerere.rikkahub.data.telegram.TelegramBotPreferences
import me.rerere.rikkahub.service.TelegramBotService
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import me.rerere.rikkahub.browser.BrowserPreferences
import me.rerere.rikkahub.browser.BrowserToolDefaults
import java.net.InetAddress
import java.io.File

/**
 * Each row that depends on a system capability (a permission, an OS-level service binding,
 * Termux being installed) is "tool-aware": if no enabled tool needs the capability, the
 * row drops to INFO with a "not required" subtitle so the screen doesn't drown the user
 * in WARN noise about features they don't use.
 *
 * The map below records which [LocalToolOption] groups depend on which capability. The
 * answer comes from the tool registration code in `LocalTools.kt` — when a new tool is
 * added that needs a capability, also add its option here.
 */
private object Capability {
    val Notifications: Set<LocalToolOption> = setOf(
        LocalToolOption.Notification,        // post_notification tool
        LocalToolOption.TelegramBot,         // FGS notification
        LocalToolOption.CronJobs,            // CronJobWorker FGS notification
        LocalToolOption.Workflows,           // WorkflowTimeCronWorker FGS notification
    )
    val FineLocation: Set<LocalToolOption> = setOf(
        LocalToolOption.Location,            // get_location, geocode tools
        LocalToolOption.WifiInfo,            // SSID/BSSID on Android 10+
        LocalToolOption.Workflows,           // geofence_enter / geofence_exit triggers
    )
    val NotificationListener: Set<LocalToolOption> = setOf(
        LocalToolOption.NotificationListener,
        LocalToolOption.Workflows,           // notification_received trigger
    )
    val Accessibility: Set<LocalToolOption> = setOf(
        LocalToolOption.ScreenAutomation,    // take_screenshot, swipe, click_at, scroll, gesture
    )
    val Termux: Set<LocalToolOption> = setOf(
        LocalToolOption.Termux,
        LocalToolOption.SpeechToText,        // transcribe_audio_file uses Termux + whisper.cpp
        LocalToolOption.Ssh,                 // ssh_exec calls into termux ssh
    )
    val BatteryWhitelist: Set<LocalToolOption> = setOf(
        LocalToolOption.TelegramBot,         // long-poll loop
        LocalToolOption.CronJobs,            // worker fires
        LocalToolOption.Workflows,           // trigger receivers + cron worker
    )
    val AllFiles: Set<LocalToolOption> = setOf(
        LocalToolOption.Files,               // file_read / file_write to arbitrary paths
    )
    val Browser: Set<LocalToolOption> = setOf(
        LocalToolOption.Browser,             // 17 browser tools (in-app WebView)
    )
    // Phase 25 — Phase 3 second cut.
    val SendSms: Set<LocalToolOption> = setOf(
        LocalToolOption.SmsSend,
    )
    val Nfc: Set<LocalToolOption> = setOf(
        LocalToolOption.Nfc,
    )
}

/** Friendly name for the row's "needed by:" subtitle. */
private fun LocalToolOption.shortName(): String = when (this) {
    LocalToolOption.Location -> "Location"
    LocalToolOption.WifiInfo -> "WiFi info"
    LocalToolOption.NotificationListener -> "Notification listener"
    LocalToolOption.ScreenAutomation -> "Screen automation"
    LocalToolOption.Termux -> "Termux"
    LocalToolOption.SpeechToText -> "Speech-to-text"
    LocalToolOption.Ssh -> "SSH"
    LocalToolOption.TelegramBot -> "Telegram bot"
    LocalToolOption.CronJobs -> "Cron jobs"
    LocalToolOption.Workflows -> "Workflows"
    LocalToolOption.Notification -> "Notification"
    LocalToolOption.Files -> "Files"
    LocalToolOption.Browser -> "Browser"
    LocalToolOption.SmsSend -> "SMS send"
    LocalToolOption.Wallpaper -> "Wallpaper"
    LocalToolOption.Keystore -> "Keystore"
    LocalToolOption.Nfc -> "NFC"
    LocalToolOption.ExternalStorage -> "External storage"
    LocalToolOption.Archive -> "Archive (zip)"
    else -> this::class.simpleName ?: "?"
}

/**
 * Run every diagnostic check. Returns the flat list — the Doctor screen groups by
 * [DoctorCheck.category].
 *
 * Most checks are cheap (Settings.Secure reads, package manager queries, in-memory state)
 * but a few do I/O (DB integrity PRAGMA, DNS resolve). Run on Dispatchers.IO at the call
 * site; the function itself is suspending so individual probes can withTimeoutOrNull.
 *
 * Adding a new check: append to the appropriate `runXxxChecks` block. Each helper function
 * returns either a single check or a list. Keep checks short — one concern per row.
 */
class DoctorChecks(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val telegramPrefs: TelegramBotPreferences,
    private val workflowRepository: WorkflowRepository,
    private val scheduledJobRepository: ScheduledJobRepository,
    private val scheduledJobRunRepository: ScheduledJobRunRepository,
    private val conversationRepository: ConversationRepository,
    private val database: AppDatabase,
    // Pass 3: per-tool browser toggle store. Used by the browser write-tools-enabled INFO
    // row so the user can spot-check which side-effecting tools are currently switched on.
    // Optional + nullable so callers that don't construct this DoctorChecks via the DI
    // graph (a few legacy tests) keep compiling — the row is silently skipped when null.
    private val browserPreferences: BrowserPreferences? = null,
    // Phase 25 — SAF tree-grant store, backs the "granted directories" Doctor row.
    // Nullable + defaulted so legacy test paths that don't build the full DI graph compile.
    private val storageVolumeGrantStore: me.rerere.rikkahub.data.storage.StorageVolumeGrantStore? = null,
    // Surface the persisted LiteRT accelerator decision so the user can see whether their
    // local models actually engaged GPU/NPU or silently fell back to CPU.
    // Nullable + defaulted same as the others above for legacy test path compatibility.
    private val localRuntimePreferences: me.rerere.locallm.LocalRuntimePreferences? = null,
) {
    suspend fun runAll(): List<DoctorCheck> = withContext(Dispatchers.IO) {
        // Aggregate enabled tools across every assistant. A tool is "in use" if at least
        // one assistant has its LocalToolOption switched on. The Doctor uses this to
        // decide whether a missing capability is actually a problem worth flagging.
        val enabled: Set<LocalToolOption> = runCatching {
            settingsStore.settingsFlow.first().assistants.flatMap { it.localTools }.toSet()
        }.getOrDefault(emptySet())

        buildList {
            addAll(permissionChecks(enabled))
            addAll(serviceChecks(enabled))
            addAll(assistantChecks())
            addAll(databaseChecks(enabled))
            addAll(networkChecks())
            addAll(termuxChecks(enabled))
            addAll(browserChecks(enabled))
            addAll(maintenanceChecks())
            addAll(diagnosticsChecks(enabled))
        }
    }

    /**
     * Render the "needed by:" subtitle for a tool-aware row. If the requirement is currently
     * unsatisfied, list the enabled tools that demand it so the user knows why they should
     * care. Returns null when no enabled tool needs the capability — callers down-grade
     * severity to INFO in that case.
     */
    private fun requirersOf(cap: Set<LocalToolOption>, enabled: Set<LocalToolOption>): List<LocalToolOption> =
        cap.filter { it in enabled }

    // ----- Permissions ----------------------------------------------------------------

    private fun permissionChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> = buildList {
        add(
            capabilityRow(
                id = "perm.notifications",
                category = DoctorCategory.Permissions,
                label = "Post-notifications permission",
                cap = Capability.Notifications,
                enabled = enabled,
                granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    PermissionHelper.hasRuntime(context, listOf(Manifest.permission.POST_NOTIFICATIONS)),
                grantedDetail = "Granted.",
                missingDetail = "Required for foreground service notifications, tool approvals, and workflow alerts.",
                fix = FixAction.OpenAppRoute("Open app permissions", AppRouteKey.SettingPermissions),
            )
        )
        add(
            capabilityRow(
                id = "perm.location",
                category = DoctorCategory.Permissions,
                label = "Fine location permission",
                cap = Capability.FineLocation,
                enabled = enabled,
                granted = PermissionHelper.hasRuntime(context, listOf(Manifest.permission.ACCESS_FINE_LOCATION)),
                grantedDetail = "Granted.",
                missingDetail = "Needed for geofence triggers and reading WiFi SSID on Android 10+.",
                fix = FixAction.OpenAppRoute("Open app permissions", AppRouteKey.SettingPermissions),
            )
        )
        add(
            capabilityRow(
                id = "perm.battery_opt",
                category = DoctorCategory.Permissions,
                label = "Battery optimisation whitelist",
                cap = Capability.BatteryWhitelist,
                enabled = enabled,
                granted = PermissionHelper.ignoresBatteryOptimizations(context),
                grantedDetail = "App is whitelisted — background services run reliably.",
                missingDetail = "Doze can kill the Telegram bot, cron jobs, and workflows.",
                fix = FixAction.OpenIntent(
                    label = "Request whitelist",
                    intent = PermissionHelper.requestIgnoreBatteryOptimizationsIntent(context),
                ),
            )
        )
        add(
            capabilityRow(
                id = "perm.notification_listener",
                category = DoctorCategory.Permissions,
                label = "Notification Listener access",
                cap = Capability.NotificationListener,
                enabled = enabled,
                granted = PermissionHelper.hasNotificationListener(context),
                grantedDetail = "Granted — listener can read notifications.",
                missingDetail = "Not granted. The notification_received trigger and notification tools won't work.",
                fix = FixAction.OpenIntent(
                    label = "Open settings",
                    intent = PermissionHelper.notificationListenerSettingsIntent(),
                ),
            )
        )
        add(
            capabilityRow(
                id = "perm.accessibility",
                category = DoctorCategory.Permissions,
                label = "Accessibility Service",
                cap = Capability.Accessibility,
                enabled = enabled,
                granted = PermissionHelper.hasAccessibilityService(context),
                grantedDetail = "Enabled in system settings.",
                missingDetail = "Not enabled. take_screenshot, swipe, scroll, click_at, and gesture tools won't work.",
                fix = FixAction.OpenIntent(
                    label = "Open settings",
                    intent = PermissionHelper.accessibilitySettingsIntent(),
                ),
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(
                capabilityRow(
                    id = "perm.all_files",
                    category = DoctorCategory.Permissions,
                    label = "All-files access",
                    cap = Capability.AllFiles,
                    enabled = enabled,
                    granted = PermissionHelper.hasAllFilesAccess(context),
                    grantedDetail = "Granted — file_read / file_write tools can reach any path.",
                    missingDetail = "Not granted. File tools are restricted to scoped storage.",
                    fix = FixAction.OpenIntent(
                        label = "Open settings",
                        intent = PermissionHelper.allFilesAccessIntent(context),
                    ),
                )
            )
        }
        // Phase 25 — SEND_SMS runtime permission row for the send_sms tool.
        add(
            capabilityRow(
                id = "perm.send_sms",
                category = DoctorCategory.Permissions,
                label = "Send-SMS permission",
                cap = Capability.SendSms,
                enabled = enabled,
                granted = PermissionHelper.hasRuntime(context, listOf(Manifest.permission.SEND_SMS)),
                grantedDetail = "Granted.",
                missingDetail = "send_sms tool needs this to send messages.",
                fix = FixAction.OpenAppRoute("Open app permissions", AppRouteKey.SettingPermissions),
            )
        )
        // Phase 25 — NFC combined hardware + system-toggle row. Tri-state: no hardware
        // (INFO, no fix), hardware present but disabled (WARN, open NFC settings), on (OK).
        run {
            val adapter = android.nfc.NfcAdapter.getDefaultAdapter(context)
            val nfcNeeders = requirersOf(Capability.Nfc, enabled)
            when {
                adapter == null -> add(
                    DoctorCheck(
                        id = "perm.nfc_enabled",
                        category = DoctorCategory.Permissions,
                        label = "NFC",
                        detail = "Device has no NFC hardware.",
                        severity = Severity.INFO,
                    )
                )
                !adapter.isEnabled -> add(
                    DoctorCheck(
                        id = "perm.nfc_enabled",
                        category = DoctorCategory.Permissions,
                        label = "NFC",
                        detail = if (nfcNeeders.isEmpty())
                            "NFC is turned off in system settings. Not required by any enabled tool."
                        else
                            "NFC is turned off in system settings. Needed by: " +
                                nfcNeeders.joinToString(", ") { it.shortName() } + ".",
                        severity = if (nfcNeeders.isEmpty()) Severity.INFO else Severity.WARN,
                        fix = if (nfcNeeders.isEmpty()) null else FixAction.OpenIntent(
                            label = "Open NFC settings",
                            intent = android.content.Intent(android.provider.Settings.ACTION_NFC_SETTINGS)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        ),
                    )
                )
                else -> add(
                    DoctorCheck(
                        id = "perm.nfc_enabled",
                        category = DoctorCategory.Permissions,
                        label = "NFC",
                        detail = "NFC hardware present and enabled.",
                        severity = Severity.OK,
                    )
                )
            }
        }
    }

    /**
     * Build a capability-aware Doctor row.
     *   granted = true                                  -> Severity.OK
     *   granted = false AND no enabled tool needs cap   -> Severity.INFO ("not required")
     *   granted = false AND some enabled tool needs cap -> Severity.WARN ("needed by: …")
     *
     * The Fix button is offered only when granted=false AND at least one tool needs the
     * capability — we don't push the user to grant a permission they don't currently use.
     */
    private fun capabilityRow(
        id: String,
        category: DoctorCategory,
        label: String,
        cap: Set<LocalToolOption>,
        enabled: Set<LocalToolOption>,
        granted: Boolean,
        grantedDetail: String,
        missingDetail: String,
        fix: FixAction,
    ): DoctorCheck {
        val needers = requirersOf(cap, enabled)
        val severity = when {
            granted -> Severity.OK
            needers.isEmpty() -> Severity.INFO
            else -> Severity.WARN
        }
        val detail = when {
            granted -> grantedDetail
            needers.isEmpty() -> "Not required by any enabled tool."
            else -> "$missingDetail Needed by: ${needers.joinToString(", ") { it.shortName() }}."
        }
        return DoctorCheck(
            id = id,
            category = category,
            label = label,
            detail = detail,
            severity = severity,
            fix = if (!granted && needers.isNotEmpty()) fix else null,
        )
    }

    // ----- Background services ---------------------------------------------------------

    private suspend fun serviceChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> = buildList {
        val tg = telegramPrefs.current()
        // Telegram bot: token, enabled flag, FGS state should agree.
        if (tg.enabled) {
            add(
                DoctorCheck(
                    id = "service.telegram_token",
                    category = DoctorCategory.Services,
                    label = "Telegram bot token",
                    // Don't render any portion of the token — Telegram bot tokens are
                    // formatted "<bot_id>:<secret>" and even the first 6 chars reveal the
                    // bot id, which an attacker could use to enumerate bot endpoints.
                    detail = if (tg.token.isNotBlank()) "Token configured (${tg.token.length} chars, hidden)."
                    else "Telegram bot is enabled but no token is set — the service will fail at startup.",
                    severity = if (tg.token.isNotBlank()) Severity.OK else Severity.FAIL,
                    fix = if (tg.token.isBlank())
                        FixAction.OpenAppRoute("Open Telegram settings", AppRouteKey.SettingTelegram)
                    else null,
                )
            )
            add(
                DoctorCheck(
                    id = "service.telegram_running",
                    category = DoctorCategory.Services,
                    label = "Telegram bot foreground service",
                    detail = if (TelegramBotService.isRunning) "Service is running."
                    else "Service is stopped. Telegram messages won't reach the assistant. The watchdog will retry on the next 30-min health pass.",
                    severity = when {
                        TelegramBotService.isRunning -> Severity.OK
                        tg.token.isBlank() -> Severity.INFO  // token issue covers this
                        else -> Severity.FAIL
                    },
                )
            )
        } else {
            add(
                DoctorCheck(
                    id = "service.telegram_off",
                    category = DoctorCategory.Services,
                    label = "Telegram bot",
                    detail = "Disabled — that's fine if you don't use Telegram.",
                    severity = Severity.INFO,
                )
            )
        }
        // AccessibilityService binding — only flagged if a tool that needs it is enabled.
        val accNeeders = requirersOf(Capability.Accessibility, enabled)
        if (accNeeders.isNotEmpty()) {
            add(
                DoctorCheck(
                    id = "service.accessibility_bound",
                    category = DoctorCategory.Services,
                    label = "AccessibilityService bound",
                    detail = if (AccessibilityServiceHandle.isRunning())
                        "Service object is alive — ${accNeeders.joinToString(", ") { it.shortName() }} can run."
                    else if (PermissionHelper.hasAccessibilityService(context))
                        "Enabled in settings but not bound (Android killed the service or it hasn't started yet). Toggle it off and on again."
                    else
                        "Not enabled. Required by: ${accNeeders.joinToString(", ") { it.shortName() }}.",
                    severity = when {
                        AccessibilityServiceHandle.isRunning() -> Severity.OK
                        else -> Severity.WARN
                    },
                    fix = if (!AccessibilityServiceHandle.isRunning()) FixAction.OpenIntent(
                        label = "Open settings",
                        intent = PermissionHelper.accessibilitySettingsIntent(),
                    ) else null,
                )
            )
        }
        // NotificationListener binding — same logic.
        val nlNeeders = requirersOf(Capability.NotificationListener, enabled)
        if (nlNeeders.isNotEmpty()) {
            add(
                DoctorCheck(
                    id = "service.notification_listener_bound",
                    category = DoctorCategory.Services,
                    label = "NotificationListener bound",
                    detail = if (NotificationListenerHandle.isBound())
                        "Listener is bound — ${nlNeeders.joinToString(", ") { it.shortName() }} can run."
                    else if (PermissionHelper.hasNotificationListener(context))
                        "Granted but not currently bound. Toggle it off and on in settings."
                    else
                        "Not granted. Required by: ${nlNeeders.joinToString(", ") { it.shortName() }}.",
                    severity = when {
                        NotificationListenerHandle.isBound() -> Severity.OK
                        else -> Severity.WARN
                    },
                    fix = if (!NotificationListenerHandle.isBound()) FixAction.OpenIntent(
                        label = "Open settings",
                        intent = PermissionHelper.notificationListenerSettingsIntent(),
                    ) else null,
                )
            )
        }
    }

    // ----- Active assistant ------------------------------------------------------------

    /**
     * Informational section. All rows are [Severity.INFO] — these are status rows, not
     * problem rows. The single "default assistant" row surfaces the assistant that:
     *   - New Telegram conversations use (when no explicit assistantId is configured).
     *   - Cron jobs run as (their assistantId is locked at job creation time, but new jobs
     *     inherit from the Settings default).
     *   - New in-app chats default to.
     *
     * A WARN row fires when the global assistant list is empty — that's a sign the settings
     * store was corrupted or a migration wiped the assistants list.
     *
     * A separate row shows the Telegram-bot-configured override if one is set.
     */
    private suspend fun assistantChecks(): List<DoctorCheck> = buildList {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val assistants = settings.assistants
            val defaultAssistant = settings.getCurrentAssistant()

            // Row 1: default assistant name + id
            add(
                DoctorCheck(
                    id = "assistant.default",
                    category = DoctorCategory.AssistantInfo,
                    label = "Default assistant",
                    detail = if (assistants.isEmpty())
                        "No assistants configured — the app won't be able to start a conversation."
                    else
                        "\"${defaultAssistant.name.ifBlank { "(unnamed)" }}\" " +
                        "(id: ${defaultAssistant.id.toString().take(8)}…). " +
                        "Used for new chats, cron jobs, and Telegram when no override is set.",
                    severity = if (assistants.isEmpty()) Severity.WARN else Severity.INFO,
                    fix = FixAction.OpenAppRoute("Open Assistants", AppRouteKey.Assistant),
                )
            )

            // Row 2: total assistant count
            add(
                DoctorCheck(
                    id = "assistant.count",
                    category = DoctorCategory.AssistantInfo,
                    label = "Assistant count",
                    detail = "${assistants.size} assistant(s) configured.",
                    severity = Severity.INFO,
                    fix = FixAction.OpenAppRoute("Open Assistants", AppRouteKey.Assistant),
                )
            )

            // Row 3: Telegram-bot assistant override (if set)
            val tg = telegramPrefs.current()
            if (tg.enabled && tg.assistantId != null) {
                val tgAssistant = tg.assistantId.let { id ->
                    runCatching {
                        val uuid = kotlin.uuid.Uuid.parse(id)
                        assistants.find { it.id == uuid }
                    }.getOrNull()
                }
                add(
                    DoctorCheck(
                        id = "assistant.telegram_override",
                        category = DoctorCategory.AssistantInfo,
                        label = "Telegram bot assistant override",
                        detail = when {
                            tgAssistant != null ->
                                "Telegram inbound messages route to \"${tgAssistant.name.ifBlank { "(unnamed)" }}\" " +
                                "(id: ${tgAssistant.id.toString().take(8)}…) — overriding the global default."
                            else ->
                                "Telegram assistant override is set (id: ${tg.assistantId.take(8)}…) but no matching " +
                                "assistant was found. Messages will fall back to the global default."
                        },
                        severity = if (tgAssistant != null) Severity.INFO else Severity.WARN,
                        fix = if (tgAssistant == null)
                            FixAction.OpenAppRoute("Open Telegram settings", AppRouteKey.SettingTelegram)
                        else null,
                    )
                )
            }
        }
    }

    // ----- Database --------------------------------------------------------------------

    private suspend fun databaseChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> = buildList {
        // Migration version
        val version = runCatching { database.openHelper.readableDatabase.version }.getOrDefault(-1)
        add(
            DoctorCheck(
                id = "db.version",
                category = DoctorCategory.Database,
                label = "Database schema version",
                // Room refuses to open the DB unless the stored version matches the compiled schema;
                // if we got here, version is the live schema version (migrations ran successfully).
                detail = if (version > 0) "v$version — migrations completed, schema is consistent."
                else "Couldn't read DB version — Room may have failed to open the database.",
                severity = if (version > 0) Severity.OK else Severity.WARN,
            )
        )
        // Integrity check
        val integrity = runCatching {
            withTimeoutOrNull(5_000L) {
                database.openHelper.readableDatabase
                    .query("PRAGMA integrity_check;")
                    .use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }
        }.getOrNull()
        // Offer an AutoFix only when the corruption mentions message_fts — that's the one
        // we know how to repair (DROP + recreate + reindex from the messages table). For
        // any other integrity failure, surface the message and let the user decide; we
        // don't blanket-rebuild things we don't know are safe.
        val mentionsFts = integrity != null && integrity != "ok" && integrity.contains("message_fts", ignoreCase = true)
        add(
            DoctorCheck(
                id = "db.integrity",
                category = DoctorCategory.Database,
                label = "DB integrity_check",
                detail = when (integrity) {
                    null -> "Integrity check timed out or failed."
                    "ok" -> "PRAGMA integrity_check returned ok."
                    else -> "Integrity check returned: $integrity"
                },
                severity = if (integrity == "ok") Severity.OK else Severity.FAIL,
                fix = if (mentionsFts) FixAction.AutoFix(
                    label = "Rebuild search index",
                    run = {
                        runCatching {
                            val n = conversationRepository.repairAndRebuildIndexes()
                            AutoFixResult(ok = true, message = "Rebuilt message_fts from $n conversation(s).")
                        }.getOrElse {
                            AutoFixResult(
                                ok = false,
                                message = "Repair failed: ${it::class.simpleName}: ${it.message ?: "?"}",
                            )
                        }
                    },
                ) else null,
            )
        )
        // Workflows summary
        runCatching {
            val all = workflowRepository.observeAll().first()
            val enabled = all.count { it.entity.enabled }
            add(
                DoctorCheck(
                    id = "db.workflows",
                    category = DoctorCategory.Database,
                    label = "Workflows",
                    detail = "${all.size} total, $enabled enabled.",
                    severity = Severity.INFO,
                    fix = if (all.isNotEmpty())
                        FixAction.OpenAppRoute("Open Workflows", AppRouteKey.SettingWorkflows)
                    else null,
                )
            )
        }
        // Scheduled jobs summary
        runCatching {
            val all = scheduledJobRepository.getAll()
            val enabled = all.count { it.enabled }
            add(
                DoctorCheck(
                    id = "db.scheduled_jobs",
                    category = DoctorCategory.Database,
                    label = "Scheduled jobs",
                    detail = "${all.size} total, $enabled enabled.",
                    severity = Severity.INFO,
                    fix = if (all.isNotEmpty())
                        FixAction.OpenAppRoute("Open Scheduled jobs", AppRouteKey.SettingScheduledJobs)
                    else null,
                )
            )
        }
        // Stranded run rows (started but never finished — process killed mid-run)
        runCatching {
            val stranded = scheduledJobRunRepository.getStranded(System.currentTimeMillis() - 30 * 60_000L)
            add(
                DoctorCheck(
                    id = "db.stranded_runs",
                    category = DoctorCategory.Database,
                    label = "Stranded scheduled-job runs",
                    detail = if (stranded.isEmpty())
                        "None. Worker has been finishing all runs cleanly."
                    else
                        "${stranded.size} run(s) started > 30 min ago and never reported back. Likely process kill mid-run.",
                    severity = if (stranded.isEmpty()) Severity.OK else Severity.WARN,
                )
            )
        }
        // Phase 25 — SAF granted-directories live count for the ExternalStorage tool.
        // Reconciles against the OS persisted-permission list so revoked grants drop off.
        val store = storageVolumeGrantStore
        if (store != null) {
            runCatching {
                val externalStorageEnabled = enabled.contains(LocalToolOption.ExternalStorage)
                val grants = store.reconcile()
                add(
                    DoctorCheck(
                        id = "storage.granted_directories",
                        category = DoctorCategory.Database,
                        label = "Granted directories",
                        detail = when {
                            !externalStorageEnabled && grants.isEmpty() ->
                                "External Storage tool not enabled. Not required."
                            grants.isEmpty() ->
                                "No directories granted yet. Call grant_directory_access to add one."
                            else ->
                                "${grants.size} directory(ies) granted: " +
                                    grants.joinToString(", ") { it.displayName } + "."
                        },
                        severity = if (externalStorageEnabled && grants.isNotEmpty())
                            Severity.OK else Severity.INFO,
                    )
                )
            }
        }
    }

    // ----- Network & providers ---------------------------------------------------------

    private suspend fun networkChecks(): List<DoctorCheck> = buildList {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val provs = settings.providers
            val configured = provs.count { p ->
                when (p) {
                    is me.rerere.ai.provider.ProviderSetting.OpenAI -> p.apiKey.isNotBlank()
                    is me.rerere.ai.provider.ProviderSetting.Google -> p.apiKey.isNotBlank()
                    is me.rerere.ai.provider.ProviderSetting.Claude -> p.apiKey.isNotBlank()
                    is me.rerere.ai.provider.ProviderSetting.AICore -> p.enabled  // on-device, no API key
                    // Local provider (LiteRT): usable when enabled AND at least one model has
                    // been loaded/downloaded. A disabled provider with no models is the factory
                    // default — don't count it.
                    is me.rerere.ai.provider.ProviderSetting.LiteRtLocal -> p.enabled && p.models.isNotEmpty()
                }
            }
            add(
                DoctorCheck(
                    id = "net.providers",
                    category = DoctorCategory.Network,
                    label = "LLM providers configured",
                    detail = "$configured provider(s) configured (API key set, AICore enabled, or local model loaded) out of ${provs.size} total.",
                    severity = if (configured > 0) Severity.OK else Severity.WARN,
                    fix = FixAction.OpenAppRoute("Open Providers", AppRouteKey.SettingProvider),
                )
            )
        }
        // LiteRT accelerator status. The runtime's GPU -> CPU fallback is silent today:
        // if the device's OpenCL/OpenGL delegate fails to init (e.g. MLDrift's
        // "CreateSharedMemoryManager is not implemented" on some Adreno drivers), the
        // model loads on CPU and the user has no UI indication. LiteRtProvider now
        // persists the actually-chosen accelerator after every load; surface that here
        // so the user can confirm GPU is engaged.
        runCatching {
            val prefs = localRuntimePreferences
            if (prefs != null) {
                val accel = prefs.acceleratorFlow(me.rerere.locallm.LocalRuntime.LiteRT).first()
                val forceCpu = prefs.forceCpu(me.rerere.locallm.LocalRuntime.LiteRT)
                val detail = when {
                    accel == null -> "Not probed yet. The accelerator is decided on the first model load."
                    forceCpu && accel == "CPU" ->
                        "CPU (Try-GPU toggle off in Settings -> Local LiteRT). " +
                            "Flip it on to retry the device's GPU on the next load."
                    accel == "CPU" ->
                        "CPU (fallback: the GPU delegate failed to initialise on this device, " +
                            "likely an MLDrift issue. Tap 'Re-detect' in Settings -> Local LiteRT " +
                            "to retry with a fresh probe.)"
                    accel == "GPU" -> "GPU (OpenCL or OpenGL, picked by LiteRT's internal probe)."
                    accel == "QNN" || accel == "NPU" -> "NPU (Qualcomm QNN delegate)."
                    accel == "NNAPI" -> "NNAPI."
                    else -> "Backend label: $accel"
                }
                val severity = when {
                    accel == null -> Severity.INFO
                    accel == "CPU" && !forceCpu -> Severity.WARN  // unexpected fallback
                    else -> Severity.OK
                }
                add(
                    DoctorCheck(
                        id = "net.litert_accel",
                        category = DoctorCategory.Network,
                        label = "LiteRT accelerator",
                        detail = detail,
                        severity = severity,
                        fix = FixAction.OpenAppRoute(
                            "Open Local LiteRT",
                            AppRouteKey.SettingProvider,
                        ),
                    )
                )
            }
        }
        // DNS sanity — confirms the OkHttp clients aren't stuck on a stale resolver.
        val dnsOk = withTimeoutOrNull(2_500L) {
            runCatching { InetAddress.getByName("dns.google") != null }.getOrDefault(false)
        } == true
        add(
            DoctorCheck(
                id = "net.dns",
                category = DoctorCategory.Network,
                label = "DNS resolution",
                detail = if (dnsOk) "dns.google resolved within 2.5 s."
                else "DNS resolution failed or timed out. NetworkChangeMonitor evicts the OkHttp pool on network changes — if this stays red, check connectivity.",
                severity = if (dnsOk) Severity.OK else Severity.WARN,
            )
        )
    }

    // ----- Termux ----------------------------------------------------------------------

    private fun termuxChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> = buildList {
        val needers = requirersOf(Capability.Termux, enabled)
        // Skip the entire category when no Termux-using tool is enabled — keeps the
        // Doctor screen focused on what the user actually configured.
        if (needers.isEmpty()) return@buildList

        val pm = context.packageManager
        val termuxInstalled = runCatching { pm.getPackageInfo("com.termux", 0); true }.getOrDefault(false)
        add(
            DoctorCheck(
                id = "termux.installed",
                category = DoctorCategory.Termux,
                label = "Termux installed",
                detail = if (termuxInstalled) "com.termux is installed on this device."
                else "Termux not installed. Required by: ${needers.joinToString(", ") { it.shortName() }}.",
                severity = if (termuxInstalled) Severity.OK else Severity.WARN,
            )
        )
        if (termuxInstalled) {
            val runCommandPerm = runCatching {
                val perm = "com.termux.permission.RUN_COMMAND"
                context.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            add(
                DoctorCheck(
                    id = "termux.run_command",
                    category = DoctorCategory.Termux,
                    label = "Termux RUN_COMMAND permission",
                    detail = if (runCommandPerm) "Granted — RikkaHub can dispatch shell commands to Termux."
                    else "Not granted. Re-toggle the Termux row in Local Tools to see the post-grant dialog.",
                    severity = if (runCommandPerm) Severity.OK else Severity.WARN,
                )
            )
        }
    }

    // ----- Browser (Pass 3) ------------------------------------------------------------

    /**
     * Pass 3: Doctor rows for the in-app browser feature.
     *  - `browser.profile_dir_writable` — the WebView profile lives at
     *    `${filesDir}/browser-profile/`. The directory MUST exist + be writable for cookies
     *    to persist across app restarts. AutoFix re-creates it on demand.
     *  - `browser.write_tools_status` — informational live count of which write-tools the
     *    user has switched on. Lets a user spot-check at a glance whether `browser_type`
     *    is unintentionally enabled. INFO severity, no fix action.
     *
     * The category is [DoctorCategory.Permissions] per the spec ("Permissions / Services").
     * Both rows are emitted regardless of master Browser-toggle state, but their severity
     * downgrades to INFO when no assistant has [LocalToolOption.Browser] enabled (matches
     * the existing capability-aware pattern used throughout the file).
     */
    private fun browserChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> = buildList {
        val needers = requirersOf(Capability.Browser, enabled)
        val browserNeeded = needers.isNotEmpty()

        // Row 1: profile dir writable (with AutoFix to mkdirs).
        val profileDir = File(context.filesDir, "browser-profile")
        val exists = runCatching { profileDir.exists() && profileDir.isDirectory }.getOrDefault(false)
        val writable = exists && runCatching { profileDir.canWrite() }.getOrDefault(false)
        val ok = exists && writable
        add(
            DoctorCheck(
                id = "browser.profile_dir_writable",
                category = DoctorCategory.Permissions,
                label = "Browser profile directory",
                detail = when {
                    ok && browserNeeded -> "${profileDir.absolutePath} exists and is writable — cookies persist."
                    ok -> "${profileDir.absolutePath} exists. Not required by any enabled tool."
                    !exists && browserNeeded -> "Directory does not exist. Cookies and localStorage won't persist. Needed by: Browser."
                    !exists -> "Directory does not exist. Not required by any enabled tool."
                    !writable && browserNeeded -> "Directory exists but is not writable. Needed by: Browser."
                    else -> "Directory exists but is not writable."
                },
                severity = when {
                    ok -> Severity.OK
                    browserNeeded -> Severity.WARN
                    else -> Severity.INFO
                },
                fix = if (!ok && browserNeeded) FixAction.AutoFix(
                    label = "Create directory",
                    run = {
                        val created = runCatching { profileDir.mkdirs() }.getOrDefault(false)
                        val nowOk = profileDir.exists() && profileDir.canWrite()
                        AutoFixResult(
                            ok = nowOk,
                            message = if (nowOk) "Created ${profileDir.absolutePath}."
                            else if (created) "Directory created but still not writable — check storage permission."
                            else "mkdirs() returned false; underlying storage may be read-only.",
                        )
                    },
                ) else null,
            )
        )

        // Row 2: write-tools live count (INFO only). Skipped silently if BrowserPreferences
        // wasn't injected — the row is purely informational and the test harness paths
        // that don't construct prefs shouldn't fail.
        val prefs = browserPreferences
        if (prefs != null) {
            val snapshot = runCatching { prefs.snapshotBlocking() }.getOrDefault(BrowserToolDefaults.DEFAULT_ENABLED)
            val onWriteTools = BrowserToolDefaults.WRITE_TOOLS.filter { snapshot[it] == true }
            val detail = if (onWriteTools.isEmpty())
                "Live count of side-effecting browser tools enabled: 0. None of the write tools are switched on."
            else
                "Live count of side-effecting browser tools enabled: ${onWriteTools.size} (${onWriteTools.joinToString(", ") { it.removePrefix("browser_") }})."
            add(
                DoctorCheck(
                    id = "browser.write_tools_status",
                    category = DoctorCategory.Permissions,
                    label = "Browser write tools enabled",
                    detail = detail,
                    severity = Severity.INFO,
                )
            )
        }
    }

    // ----- Maintenance -----------------------------------------------------------------

    private fun maintenanceChecks(): List<DoctorCheck> = buildList {
        // Cache size on disk
        val cacheBytes = directorySize(context.cacheDir)
        add(
            DoctorCheck(
                id = "maint.cache_size",
                category = DoctorCategory.Maintenance,
                label = "App cache size",
                detail = "Cache is using ${humanBytes(cacheBytes)}. " +
                    if (cacheBytes > 200L * 1024 * 1024) "Consider clearing — over 200 MB." else "Within normal range.",
                severity = if (cacheBytes > 500L * 1024 * 1024) Severity.WARN else Severity.OK,
                fix = FixAction.AutoFix(
                    label = "Clear cache",
                    run = {
                        val freed = clearDirectoryContents(context.cacheDir)
                        AutoFixResult(ok = true, message = "Freed ${humanBytes(freed)}.")
                    },
                ),
            )
        )
    }

    // ----- Diagnostics summary ---------------------------------------------------------

    private fun diagnosticsChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> = listOf(
        DoctorCheck(
            id = "diag.app",
            category = DoctorCategory.Diagnostics,
            label = "App build",
            detail = "RikkaHub-agent ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) — debug=${BuildConfig.DEBUG}",
            severity = Severity.INFO,
        ),
        DoctorCheck(
            id = "diag.android",
            category = DoctorCategory.Diagnostics,
            label = "Android",
            detail = "API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE}) on ${Build.MANUFACTURER} ${Build.MODEL}",
            severity = Severity.INFO,
        ),
        DoctorCheck(
            id = "diag.runtime",
            category = DoctorCategory.Diagnostics,
            label = "Runtime",
            detail = run {
                val rt = Runtime.getRuntime()
                val freeMb = rt.freeMemory() / (1024 * 1024)
                val totalMb = rt.totalMemory() / (1024 * 1024)
                val maxMb = rt.maxMemory() / (1024 * 1024)
                "Heap: $freeMb MB free of $totalMb MB ($maxMb MB max)"
            },
            severity = Severity.INFO,
        ),
        DoctorCheck(
            id = "diag.enabled_tools",
            category = DoctorCategory.Diagnostics,
            label = "Enabled tools across assistants",
            detail = if (enabled.isEmpty()) "No local tools enabled — agentic features won't work."
            else "${enabled.size} tool group(s) enabled.",
            severity = if (enabled.isEmpty()) Severity.WARN else Severity.INFO,
        ),
    )

    private fun directorySize(dir: File): Long = runCatching {
        if (!dir.exists()) return@runCatching 0L
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    private fun clearDirectoryContents(dir: File): Long {
        var freed = 0L
        runCatching {
            dir.listFiles()?.forEach { f ->
                freed += directorySize(f)
                f.deleteRecursively()
            }
        }
        return freed
    }

    private fun humanBytes(bytes: Long): String {
        val mb = 1024.0 * 1024
        val gb = mb * 1024
        return when {
            bytes < mb -> "%.0f KB".format(bytes / 1024.0)
            bytes < gb -> "%.1f MB".format(bytes / mb)
            else -> "%.2f GB".format(bytes / gb)
        }
    }
}
