package me.rerere.rikkahub.workflow.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Phase 12 — typed condition spec. Composed AND across the array (no OR groups in v1).
 * 14 variants per the locked spec. Pure functions of `(ctx, params) -> Bool`.
 *
 * Every variant carries an optional `invert` flag (default false). When true, the
 * evaluator negates that single condition's boolean result — so "WiFi is NOT HomeWiFi"
 * or "NOT charging at night" become expressible without new variants. The negation is
 * applied generically at the top of [me.rerere.rikkahub.workflow.condition.ConditionEvaluator.evaluate],
 * so it works uniformly for all condition types including the time/sun/day variants.
 *
 * `invert` is additive and forward-compatible: a stored definition that predates this
 * field decodes with `invert = false` (the old positive-only behavior), and the
 * snake_case naming strategy maps it to `invert` on the wire either way.
 */
@Serializable
sealed class ConditionSpec {

    /** When true, the evaluator negates this condition's result. Default false = unchanged. */
    abstract val invert: Boolean

    /** "HH:mm" device local. Wraps past midnight if start > end (e.g. 22:00..06:00). */
    @Serializable
    @SerialName("time_between")
    data class TimeBetween(
        val start: String,
        val end: String,
        override val invert: Boolean = false,
    ) : ConditionSpec()

    /** Sun calculation uses last-known location if available; fails open (= true) without one. */
    @Serializable
    @SerialName("time_after_sunset")
    data class TimeAfterSunset(
        val offsetMinutes: Int = 0,
        override val invert: Boolean = false,
    ) : ConditionSpec()

    @Serializable
    @SerialName("time_before_sunrise")
    data class TimeBeforeSunrise(
        val offsetMinutes: Int = 0,
        override val invert: Boolean = false,
    ) : ConditionSpec()

    /** ISO 1..7 (1=Mon). Empty = always-true. */
    @Serializable
    @SerialName("day_of_week_in")
    data class DayOfWeekIn(
        val days: List<Int>,
        override val invert: Boolean = false,
    ) : ConditionSpec()

    @Serializable
    @SerialName("wifi_ssid_is")
    data class WifiSsidIs(
        val ssid: String,
        override val invert: Boolean = false,
    ) : ConditionSpec()

    @Serializable
    @SerialName("wifi_ssid_in")
    data class WifiSsidIn(
        val ssids: List<String>,
        override val invert: Boolean = false,
    ) : ConditionSpec()

    @Serializable
    @SerialName("battery_above")
    data class BatteryAbove(
        val percent: Int,
        override val invert: Boolean = false,
    ) : ConditionSpec()

    @Serializable
    @SerialName("battery_below")
    data class BatteryBelow(
        val percent: Int,
        override val invert: Boolean = false,
    ) : ConditionSpec()

    @Serializable
    @SerialName("is_charging")
    data class IsCharging(override val invert: Boolean = false) : ConditionSpec()

    @Serializable
    @SerialName("is_not_charging")
    data class IsNotCharging(override val invert: Boolean = false) : ConditionSpec()

    @Serializable
    @SerialName("foreground_app_is")
    data class ForegroundAppIs(
        val packageName: String,
        override val invert: Boolean = false,
    ) : ConditionSpec()

    @Serializable
    @SerialName("foreground_app_in")
    data class ForegroundAppIn(
        val packageNames: List<String>,
        override val invert: Boolean = false,
    ) : ConditionSpec()

    @Serializable
    @SerialName("screen_is_on")
    data class ScreenIsOn(override val invert: Boolean = false) : ConditionSpec()

    @Serializable
    @SerialName("screen_is_off")
    data class ScreenIsOff(override val invert: Boolean = false) : ConditionSpec()
}
