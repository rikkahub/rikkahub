package me.rerere.rikkahub.workflow.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Phase 12 — typed condition spec. Composed AND across the array (no OR groups in v1).
 * 14 variants per the locked spec. Pure functions of `(ctx, params) -> Bool`.
 */
@Serializable
sealed class ConditionSpec {

    /** "HH:mm" device local. Wraps past midnight if start > end (e.g. 22:00..06:00). */
    @Serializable
    @SerialName("time_between")
    data class TimeBetween(val start: String, val end: String) : ConditionSpec()

    /** Sun calculation uses last-known location if available; fails open (= true) without one. */
    @Serializable
    @SerialName("time_after_sunset")
    data class TimeAfterSunset(val offsetMinutes: Int = 0) : ConditionSpec()

    @Serializable
    @SerialName("time_before_sunrise")
    data class TimeBeforeSunrise(val offsetMinutes: Int = 0) : ConditionSpec()

    /** ISO 1..7 (1=Mon). Empty = always-true. */
    @Serializable
    @SerialName("day_of_week_in")
    data class DayOfWeekIn(val days: List<Int>) : ConditionSpec()

    @Serializable
    @SerialName("wifi_ssid_is")
    data class WifiSsidIs(val ssid: String) : ConditionSpec()

    @Serializable
    @SerialName("wifi_ssid_in")
    data class WifiSsidIn(val ssids: List<String>) : ConditionSpec()

    @Serializable
    @SerialName("battery_above")
    data class BatteryAbove(val percent: Int) : ConditionSpec()

    @Serializable
    @SerialName("battery_below")
    data class BatteryBelow(val percent: Int) : ConditionSpec()

    @Serializable @SerialName("is_charging") data object IsCharging : ConditionSpec()

    @Serializable @SerialName("is_not_charging") data object IsNotCharging : ConditionSpec()

    @Serializable
    @SerialName("foreground_app_is")
    data class ForegroundAppIs(val packageName: String) : ConditionSpec()

    @Serializable
    @SerialName("foreground_app_in")
    data class ForegroundAppIn(val packageNames: List<String>) : ConditionSpec()

    @Serializable @SerialName("screen_is_on") data object ScreenIsOn : ConditionSpec()

    @Serializable @SerialName("screen_is_off") data object ScreenIsOff : ConditionSpec()
}
