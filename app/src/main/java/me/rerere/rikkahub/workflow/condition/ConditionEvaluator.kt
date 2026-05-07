package me.rerere.rikkahub.workflow.condition

import me.rerere.rikkahub.workflow.model.ConditionSpec
import me.rerere.rikkahub.workflow.model.WorkflowContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Phase 12 — pure-functional condition evaluator. Conditions are AND-combined; if any
 * returns false, the workflow fire is logged as `SKIPPED_CONDITIONS`.
 *
 * Time-based conditions evaluate against the device-local zone (per spec). Sunrise / sunset
 * conditions need a known location — if [ctx.latitude] / [ctx.longitude] are both null,
 * those conditions fail-open (return true) so a workflow with `time_after_sunset` doesn't
 * become silently un-fireable on a device without location permission. The user sees this
 * in the row's last-run status.
 */
object ConditionEvaluator {

    /** Returns [Result.Pass] if every condition passes, else [Result.FailedAt(idx, reason)]. */
    fun evaluateAll(
        conditions: List<ConditionSpec>,
        ctx: WorkflowContext,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Result {
        for ((idx, c) in conditions.withIndex()) {
            val ok = evaluate(c, ctx, zone)
            if (!ok) return Result.FailedAt(idx, summary(c))
        }
        return Result.Pass
    }

    /** True if [condition] holds against [ctx]. */
    fun evaluate(condition: ConditionSpec, ctx: WorkflowContext, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ctx.nowMs), zone)
        return when (condition) {
            is ConditionSpec.TimeBetween -> timeBetween(now.toLocalTime(), condition.start, condition.end)
            is ConditionSpec.TimeAfterSunset -> {
                if (ctx.latitude == null || ctx.longitude == null) true  // fail-open w/o location
                else SolarTimes.isAfterSunset(now, ctx.latitude, ctx.longitude, condition.offsetMinutes)
            }
            is ConditionSpec.TimeBeforeSunrise -> {
                if (ctx.latitude == null || ctx.longitude == null) true
                else SolarTimes.isBeforeSunrise(now, ctx.latitude, ctx.longitude, condition.offsetMinutes)
            }
            is ConditionSpec.DayOfWeekIn -> {
                if (condition.days.isEmpty()) true
                else now.dayOfWeek in condition.days.map { isoToDow(it) }.toSet()
            }
            is ConditionSpec.WifiSsidIs -> ctx.wifiSsid != null && ctx.wifiSsid.equals(condition.ssid, ignoreCase = false)
            is ConditionSpec.WifiSsidIn -> ctx.wifiSsid != null && condition.ssids.any { it == ctx.wifiSsid }
            is ConditionSpec.BatteryAbove -> ctx.batteryLevel != null && ctx.batteryLevel > condition.percent
            is ConditionSpec.BatteryBelow -> ctx.batteryLevel != null && ctx.batteryLevel < condition.percent
            is ConditionSpec.IsCharging -> ctx.isCharging
            is ConditionSpec.IsNotCharging -> !ctx.isCharging
            is ConditionSpec.ForegroundAppIs -> ctx.foregroundPackage == condition.packageName
            is ConditionSpec.ForegroundAppIn -> ctx.foregroundPackage != null && ctx.foregroundPackage in condition.packageNames
            is ConditionSpec.ScreenIsOn -> ctx.screenOn
            is ConditionSpec.ScreenIsOff -> !ctx.screenOn
        }
    }

    /** Whether any condition in [conditions] needs a location lookup. */
    fun needsLocation(conditions: List<ConditionSpec>): Boolean =
        conditions.any { it is ConditionSpec.TimeAfterSunset || it is ConditionSpec.TimeBeforeSunrise }

    /**
     * "HH:mm" between handler. If [start] > [end] (e.g. 22:00..06:00), wrap past midnight
     * — the window is `now >= start || now < end`.
     */
    fun timeBetween(now: LocalTime, startStr: String, endStr: String): Boolean {
        val start = parseHHmm(startStr) ?: return false
        val end = parseHHmm(endStr) ?: return false
        return if (start <= end) {
            !now.isBefore(start) && now.isBefore(end)
        } else {
            !now.isBefore(start) || now.isBefore(end)
        }
    }

    private fun parseHHmm(s: String): LocalTime? {
        val parts = s.split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return LocalTime.of(h, m)
    }

    private fun isoToDow(iso: Int): DayOfWeek = when (iso) {
        1 -> DayOfWeek.MONDAY; 2 -> DayOfWeek.TUESDAY; 3 -> DayOfWeek.WEDNESDAY
        4 -> DayOfWeek.THURSDAY; 5 -> DayOfWeek.FRIDAY; 6 -> DayOfWeek.SATURDAY
        else -> DayOfWeek.SUNDAY
    }

    private fun summary(c: ConditionSpec): String = when (c) {
        is ConditionSpec.TimeBetween -> "between ${c.start} and ${c.end}"
        is ConditionSpec.TimeAfterSunset -> "after sunset" + if (c.offsetMinutes != 0) " ${c.offsetMinutes}m" else ""
        is ConditionSpec.TimeBeforeSunrise -> "before sunrise" + if (c.offsetMinutes != 0) " ${c.offsetMinutes}m" else ""
        is ConditionSpec.DayOfWeekIn -> "day in ${c.days}"
        is ConditionSpec.WifiSsidIs -> "WiFi is ${c.ssid}"
        is ConditionSpec.WifiSsidIn -> "WiFi in ${c.ssids}"
        is ConditionSpec.BatteryAbove -> "battery > ${c.percent}%"
        is ConditionSpec.BatteryBelow -> "battery < ${c.percent}%"
        is ConditionSpec.IsCharging -> "charging"
        is ConditionSpec.IsNotCharging -> "not charging"
        is ConditionSpec.ForegroundAppIs -> "foreground app = ${c.packageName}"
        is ConditionSpec.ForegroundAppIn -> "foreground app in ${c.packageNames.size} pkgs"
        is ConditionSpec.ScreenIsOn -> "screen on"
        is ConditionSpec.ScreenIsOff -> "screen off"
    }

    sealed class Result {
        data object Pass : Result()
        data class FailedAt(val index: Int, val reason: String) : Result()
    }
}
