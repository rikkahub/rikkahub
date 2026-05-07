package me.rerere.rikkahub.workflow.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Phase 12 — typed trigger spec. The LLM authors workflows by emitting JSON whose `trigger`
 * object carries `type` + trigger-specific `params`. kotlinx.serialization's polymorphic
 * sealed-class serializer maps `type` to the right variant; unknown `type` strings fail
 * validation with a clear error.
 *
 * 19 variants per the locked spec. Variants that need no params are `data object`s; those
 * with params are `data class`es with their own fields. The JSON shape on the wire is
 * `{"type": "wifi_connected", "ssid": "HomeWiFi"}` (flat) — see WorkflowJson for how this is
 * parsed using a discriminator + wrapper, since we want to keep the user-facing schema as
 * `{"type":"...","params":{...}}` for LLM ergonomics.
 */
@Serializable
sealed class TriggerSpec {
    /** Time-of-day or cron schedule. Reuses the scheduled-jobs WorkManager backend. */
    @Serializable
    @SerialName("time_cron")
    data class TimeCron(
        /** 5-field cron OR @every Ns — same dialect ScheduledJobs accepts. Optional if timeOfDay set. */
        val cron: String? = null,
        /** "HH:mm" (24h, device local). Mutually exclusive with cron. */
        val timeOfDay: String? = null,
        /** ISO 1..7 (1=Mon). Empty = every day. Only used with timeOfDay. */
        val daysOfWeek: List<Int> = emptyList(),
        /** Optional IANA timezone id; null = device local. */
        val timezone: String? = null,
    ) : TriggerSpec()

    @Serializable
    @SerialName("wifi_connected")
    data class WifiConnected(val ssid: String? = null) : TriggerSpec()

    @Serializable
    @SerialName("wifi_disconnected")
    data class WifiDisconnected(val ssid: String? = null) : TriggerSpec()

    @Serializable
    @SerialName("bluetooth_device_connected")
    data class BluetoothDeviceConnected(val deviceAddress: String? = null) : TriggerSpec()

    @Serializable
    @SerialName("bluetooth_device_disconnected")
    data class BluetoothDeviceDisconnected(val deviceAddress: String? = null) : TriggerSpec()

    @Serializable @SerialName("headphones_plugged") data object HeadphonesPlugged : TriggerSpec()

    @Serializable @SerialName("headphones_unplugged") data object HeadphonesUnplugged : TriggerSpec()

    @Serializable @SerialName("power_connected") data object PowerConnected : TriggerSpec()

    @Serializable @SerialName("power_disconnected") data object PowerDisconnected : TriggerSpec()

    /** Fires on transition: previous level was ≥threshold, current level is <threshold. */
    @Serializable
    @SerialName("battery_below")
    data class BatteryBelow(val thresholdPercent: Int) : TriggerSpec()

    /** Fires on transition: previous level was <threshold, current level is ≥threshold. */
    @Serializable
    @SerialName("battery_above")
    data class BatteryAbove(val thresholdPercent: Int) : TriggerSpec()

    @Serializable
    @SerialName("geofence_enter")
    data class GeofenceEnter(
        val lat: Double,
        val lng: Double,
        val radiusM: Int,
        val label: String? = null,
    ) : TriggerSpec()

    @Serializable
    @SerialName("geofence_exit")
    data class GeofenceExit(
        val lat: Double,
        val lng: Double,
        val radiusM: Int,
        val label: String? = null,
    ) : TriggerSpec()

    @Serializable
    @SerialName("app_launched")
    data class AppLaunched(val packageName: String) : TriggerSpec()

    @Serializable
    @SerialName("app_closed")
    data class AppClosed(val packageName: String) : TriggerSpec()

    @Serializable
    @SerialName("notification_received")
    data class NotificationReceived(
        val packageName: String? = null,
        val titleContains: String? = null,
        val textContains: String? = null,
    ) : TriggerSpec()

    @Serializable @SerialName("boot_completed") data object BootCompleted : TriggerSpec()

    @Serializable @SerialName("screen_on") data object ScreenOn : TriggerSpec()

    @Serializable @SerialName("screen_off") data object ScreenOff : TriggerSpec()

    /** Only via workflow_run() or the Settings "Run now" button. */
    @Serializable @SerialName("manual") data object Manual : TriggerSpec()
}
