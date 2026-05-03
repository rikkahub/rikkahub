package me.rerere.rikkahub.data.ai.tools.local

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

object PermissionHelper {
    fun hasRuntime(ctx: Context, perms: List<String>): Boolean =
        perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    fun hasWriteSettings(ctx: Context): Boolean = Settings.System.canWrite(ctx)

    fun hasDndAccess(ctx: Context): Boolean =
        ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    fun writeSettingsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, "package:${ctx.packageName}".toUri())

    fun dndAccessIntent(ctx: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // ACTION_NOTIFICATION_POLICY_ACCESS_DETAIL_SETTINGS is @hide/@SystemApi; use literal action string.
            Intent("android.settings.NOTIFICATION_POLICY_ACCESS_DETAIL_SETTINGS")
                .setData("package:${ctx.packageName}".toUri())
        } else {
            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        }

    /**
     * True iff the app is exempted from Doze battery optimizations. Without this exemption,
     * OEM-aggressive ROMs (Xiaomi/OPPO/OnePlus/Vivo) cut network for foreground services when
     * the screen turns off, which makes the Telegram bot's long-poll go silent.
     */
    fun ignoresBatteryOptimizations(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(ctx.packageName) ?: false

    /**
     * Direct prompt to whitelist the app from Doze. Required-permission per Play Store policy
     * is `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; manifest declares it.
     */
    fun requestIgnoreBatteryOptimizationsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData("package:${ctx.packageName}".toUri())

    /** Fallback page for users who declined the prompt; shows the system-wide list. */
    fun batteryOptimizationsListIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}
