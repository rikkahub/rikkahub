package me.rerere.rikkahub.data.permissions

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import me.rerere.rikkahub.service.RikkaAccessibilityService
import me.rerere.rikkahub.service.RikkaNotificationListenerService

/**
 * Auto-discovered inventory of every permission this app requires, grouped by how the
 * user grants it. Reads <uses-permission> entries at runtime via PackageManager so
 * future-added perms appear automatically — only the friendly-label lookup is hand-curated;
 * any unmapped perm falls back to humanizing the constant name.
 *
 * On top of <uses-permission>, two virtual rows surface service bindings the user must enable
 * via dedicated Android UIs (AccessibilityService, NotificationListenerService) — these are
 * not real permissions but behave the same from the user's standpoint.
 */
object PermissionInventory {

    enum class Group { ServicesAndIntegrations, SpecialAccess, Runtime, AutoGranted }

    enum class Status { GRANTED, DENIED, AUTO_GRANTED }

    sealed class GrantAction {
        /** No action required — install-time / signature-level / always granted. */
        object None : GrantAction()
        /** Request via ActivityResultContracts.RequestPermission. */
        data class Runtime(val permission: String) : GrantAction()
        /** Open this Intent, user toggles in system Settings. */
        data class SystemSettings(val intent: Intent) : GrantAction()
    }

    data class Row(
        val id: String,
        val label: String,
        val description: String,
        val status: Status,
        val group: Group,
        val grant: GrantAction,
    )

    fun build(context: Context): List<Row> {
        val rows = mutableListOf<Row>()
        rows += accessibilityServiceRow(context)
        rows += notificationListenerRow(context)

        val declared = readDeclaredPermissions(context)
        for (perm in declared) {
            rows += classify(context, perm) ?: continue
        }
        return rows.sortedWith(
            compareBy({ it.group.ordinal }, { if (it.status == Status.DENIED) 0 else 1 }, { it.label })
        )
    }

    private fun readDeclaredPermissions(context: Context): List<String> {
        val pm = context.packageManager
        val info: PackageInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            return emptyList()
        }
        return info.requestedPermissions?.toList() ?: emptyList()
    }

    private fun classify(context: Context, perm: String): Row? {
        val pm = context.packageManager
        val pkgUri: Uri = ("package:" + context.packageName).toUri()

        // Special-access permissions — each has its own canWrite / canDrawOverlays / etc check
        // and a deep-link Intent.
        when (perm) {
            Manifest.permission.SYSTEM_ALERT_WINDOW -> {
                val granted = Settings.canDrawOverlays(context)
                return Row(
                    id = perm,
                    label = "Display over other apps",
                    description = "Lets RikkaHub draw the \"agent is working\" overlay while automation is in progress.",
                    status = if (granted) Status.GRANTED else Status.DENIED,
                    group = Group.SpecialAccess,
                    grant = GrantAction.SystemSettings(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri)
                    ),
                )
            }
            Manifest.permission.WRITE_SETTINGS -> {
                val granted = Settings.System.canWrite(context)
                return Row(
                    id = perm,
                    label = "Modify system settings",
                    description = "Lets the agent change brightness via set_brightness.",
                    status = if (granted) Status.GRANTED else Status.DENIED,
                    group = Group.SpecialAccess,
                    grant = GrantAction.SystemSettings(
                        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri)
                    ),
                )
            }
            Manifest.permission.ACCESS_NOTIFICATION_POLICY -> {
                val nm = context.getSystemService(NotificationManager::class.java)
                val granted = nm?.isNotificationPolicyAccessGranted == true
                return Row(
                    id = perm,
                    label = "Do Not Disturb access",
                    description = "Lets the agent change ringer mode and per-stream volume.",
                    status = if (granted) Status.GRANTED else Status.DENIED,
                    group = Group.SpecialAccess,
                    grant = GrantAction.SystemSettings(
                        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    ),
                )
            }
            Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS -> {
                val pwm = context.getSystemService(PowerManager::class.java)
                val granted = pwm?.isIgnoringBatteryOptimizations(context.packageName) == true
                return Row(
                    id = perm,
                    label = "Ignore battery optimizations",
                    description = "Keeps the Telegram bot foreground service responsive when the screen is off.",
                    status = if (granted) Status.GRANTED else Status.DENIED,
                    group = Group.SpecialAccess,
                    // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS pops a system dialog asking
                    // for the exemption directly — better UX than the long settings list.
                    grant = GrantAction.SystemSettings(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri)
                    ),
                )
            }
            Manifest.permission.POST_NOTIFICATIONS -> {
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val granted = ContextCompat.checkSelfPermission(context, perm) ==
                        PackageManager.PERMISSION_GRANTED
                    Row(
                        id = perm,
                        label = "Post notifications",
                        description = "Required so the bot foreground service and TTS / progress notifications can show.",
                        status = if (granted) Status.GRANTED else Status.DENIED,
                        group = Group.Runtime,
                        grant = GrantAction.Runtime(perm),
                    )
                } else {
                    autoRow(perm, "Post notifications")
                }
            }
        }

        // Generic classification: ask PackageManager about the protection level. Dangerous =>
        // runtime grant. Anything else (normal, signature, signatureOrSystem) is auto-granted
        // at install time and only listed for transparency.
        val info: PermissionInfo? = try {
            pm.getPermissionInfo(perm, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

        if (info == null) {
            // Unknown to this device — typically a custom perm declared by an app that isn't
            // installed (e.g. com.termux.permission.RUN_COMMAND when Termux isn't installed).
            // Best we can do is check checkSelfPermission and offer no grant flow.
            val granted = ContextCompat.checkSelfPermission(context, perm) ==
                PackageManager.PERMISSION_GRANTED
            return Row(
                id = perm,
                label = humanize(perm),
                description = "Custom permission. Owner app may not be installed yet.",
                status = if (granted) Status.GRANTED else Status.DENIED,
                group = Group.Runtime,
                grant = GrantAction.Runtime(perm),
            )
        }

        val protectionBase = info.protection
        val isDangerous = protectionBase == PermissionInfo.PROTECTION_DANGEROUS
        val granted = ContextCompat.checkSelfPermission(context, perm) ==
            PackageManager.PERMISSION_GRANTED

        return if (isDangerous) {
            Row(
                id = perm,
                label = labelOrHumanize(perm),
                description = describeRuntime(perm),
                status = if (granted) Status.GRANTED else Status.DENIED,
                group = Group.Runtime,
                grant = GrantAction.Runtime(perm),
            )
        } else {
            autoRow(perm, labelOrHumanize(perm))
        }
    }

    private fun autoRow(perm: String, label: String) = Row(
        id = perm,
        label = label,
        description = "Auto-granted at install (no user action needed).",
        status = Status.AUTO_GRANTED,
        group = Group.AutoGranted,
        grant = GrantAction.None,
    )

    private fun accessibilityServiceRow(context: Context): Row {
        val component = ComponentName(context, RikkaAccessibilityService::class.java)
            .flattenToString()
        val enabled = (Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: "").split(":").any { it.equals(component, ignoreCase = true) }
        return Row(
            id = "rikkahub.SERVICE_ACCESSIBILITY",
            label = "Screen automation (Accessibility)",
            description = "Required for tap, swipe, click_node, screenshot, read_window_tree, set_text and other UI-driving tools.",
            status = if (enabled) Status.GRANTED else Status.DENIED,
            group = Group.ServicesAndIntegrations,
            grant = GrantAction.SystemSettings(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ),
        )
    }

    private fun notificationListenerRow(context: Context): Row {
        val component = ComponentName(context, RikkaNotificationListenerService::class.java)
            .flattenToString()
        val enabled = (Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ) ?: "").split(":").any { it.equals(component, ignoreCase = true) }
        return Row(
            id = "rikkahub.SERVICE_NOTIFICATION_LISTENER",
            label = "Notification access",
            description = "Lets the agent read incoming notifications and auto-forward whitelisted apps to Telegram.",
            status = if (enabled) Status.GRANTED else Status.DENIED,
            group = Group.ServicesAndIntegrations,
            grant = GrantAction.SystemSettings(
                Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ),
        )
    }

    // -- Friendly labels for every dangerous permission we currently request ------------------

    private val LABELS = mapOf(
        Manifest.permission.CAMERA to "Camera",
        Manifest.permission.RECORD_AUDIO to "Microphone",
        Manifest.permission.READ_PHONE_STATE to "Phone state",
        Manifest.permission.ACCESS_FINE_LOCATION to "Precise location",
        Manifest.permission.ACCESS_COARSE_LOCATION to "Approximate location",
        Manifest.permission.READ_CONTACTS to "Contacts",
        Manifest.permission.READ_CALL_LOG to "Call log",
        Manifest.permission.READ_SMS to "SMS",
        Manifest.permission.POST_NOTIFICATIONS to "Post notifications",
        "com.termux.permission.RUN_COMMAND" to "Termux RUN_COMMAND",
    )

    private val DESCRIPTIONS = mapOf(
        Manifest.permission.CAMERA to "Used by take_photo to capture a still image.",
        Manifest.permission.RECORD_AUDIO to "Used by record_audio and speech_to_text.",
        Manifest.permission.READ_PHONE_STATE to "Used by get_telephony_info (SIM operator, signal).",
        Manifest.permission.ACCESS_FINE_LOCATION to "Used by get_location and get_wifi_info.",
        Manifest.permission.ACCESS_COARSE_LOCATION to "Approximate location fallback for get_location.",
        Manifest.permission.READ_CONTACTS to "Used by search_contacts and list_contacts.",
        Manifest.permission.READ_CALL_LOG to "Used by list_call_log.",
        Manifest.permission.READ_SMS to "Used by list_sms_inbox and search_sms.",
        "com.termux.permission.RUN_COMMAND" to "Lets RikkaHub start commands inside Termux for the termux_run_command tool.",
    )

    private fun labelOrHumanize(perm: String) = LABELS[perm] ?: humanize(perm)
    private fun describeRuntime(perm: String) =
        DESCRIPTIONS[perm] ?: "Runtime permission required by one or more enabled tools."

    private fun humanize(perm: String): String {
        val tail = perm.substringAfterLast('.')
        return tail.lowercase().split('_').joinToString(" ") {
            it.replaceFirstChar { c -> c.uppercase() }
        }
    }
}

private fun String.toUri(): Uri = Uri.parse(this)
