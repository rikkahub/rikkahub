package me.rerere.rikkahub.workflow.condition

import me.rerere.rikkahub.workflow.model.ConditionSpec
import me.rerere.rikkahub.workflow.model.WorkflowContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ConditionEvaluatorTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    private fun ctxAt(
        time: LocalTime,
        date: LocalDate = LocalDate.of(2026, 5, 7),
        battery: Int? = 50,
        charging: Boolean = false,
        wifiSsid: String? = null,
        foreground: String? = null,
        screenOn: Boolean = true,
        lat: Double? = null,
        lng: Double? = null,
    ): WorkflowContext {
        val zdt = ZonedDateTime.of(date, time, zone)
        return WorkflowContext(
            nowMs = zdt.toInstant().toEpochMilli(),
            batteryLevel = battery,
            isCharging = charging,
            wifiSsid = wifiSsid,
            foregroundPackage = foreground,
            screenOn = screenOn,
            latitude = lat,
            longitude = lng,
        )
    }

    @Test fun `time_between non-wrap window`() {
        val c = ConditionSpec.TimeBetween("09:00", "17:00")
        assertTrue(ConditionEvaluator.evaluate(c, ctxAt(LocalTime.of(12, 0)), zone))
        assertFalse(ConditionEvaluator.evaluate(c, ctxAt(LocalTime.of(8, 59)), zone))
        assertFalse(ConditionEvaluator.evaluate(c, ctxAt(LocalTime.of(17, 0)), zone))
    }

    @Test fun `time_between wraps past midnight`() {
        val c = ConditionSpec.TimeBetween("22:00", "06:00")
        assertTrue(ConditionEvaluator.evaluate(c, ctxAt(LocalTime.of(23, 30)), zone))
        assertTrue(ConditionEvaluator.evaluate(c, ctxAt(LocalTime.of(2, 0)), zone))
        assertFalse(ConditionEvaluator.evaluate(c, ctxAt(LocalTime.of(12, 0)), zone))
    }

    @Test fun `day_of_week_in true and false`() {
        // 2026-05-07 is a Thursday (ISO 4)
        val c = ConditionSpec.DayOfWeekIn(listOf(1, 2, 3, 4, 5))
        assertTrue(ConditionEvaluator.evaluate(c, ctxAt(LocalTime.of(12, 0), LocalDate.of(2026, 5, 7)), zone))
        // 2026-05-09 = Saturday (ISO 6)
        assertFalse(ConditionEvaluator.evaluate(c, ctxAt(LocalTime.of(12, 0), LocalDate.of(2026, 5, 9)), zone))
    }

    @Test fun `wifi_ssid_is matches exact`() {
        val c = ConditionSpec.WifiSsidIs("HomeWiFi")
        assertTrue(ConditionEvaluator.evaluate(c, ctxAt(LocalTime.of(12, 0), wifiSsid = "HomeWiFi"), zone))
        assertFalse(ConditionEvaluator.evaluate(c, ctxAt(LocalTime.of(12, 0), wifiSsid = "OtherWiFi"), zone))
        assertFalse(ConditionEvaluator.evaluate(c, ctxAt(LocalTime.of(12, 0), wifiSsid = null), zone))
    }

    @Test fun `wifi_ssid_in any of`() {
        val c = ConditionSpec.WifiSsidIn(listOf("Home", "Office"))
        assertTrue(ConditionEvaluator.evaluate(c, ctxAt(LocalTime.of(12, 0), wifiSsid = "Office"), zone))
        assertFalse(ConditionEvaluator.evaluate(c, ctxAt(LocalTime.of(12, 0), wifiSsid = "Cafe"), zone))
    }

    @Test fun `battery_above and battery_below`() {
        val above = ConditionSpec.BatteryAbove(50)
        assertTrue(ConditionEvaluator.evaluate(above, ctxAt(LocalTime.of(12, 0), battery = 60), zone))
        assertFalse(ConditionEvaluator.evaluate(above, ctxAt(LocalTime.of(12, 0), battery = 50), zone))
        val below = ConditionSpec.BatteryBelow(20)
        assertTrue(ConditionEvaluator.evaluate(below, ctxAt(LocalTime.of(12, 0), battery = 15), zone))
        assertFalse(ConditionEvaluator.evaluate(below, ctxAt(LocalTime.of(12, 0), battery = 20), zone))
    }

    @Test fun `is_charging and is_not_charging`() {
        assertTrue(ConditionEvaluator.evaluate(ConditionSpec.IsCharging, ctxAt(LocalTime.of(12, 0), charging = true), zone))
        assertFalse(ConditionEvaluator.evaluate(ConditionSpec.IsCharging, ctxAt(LocalTime.of(12, 0), charging = false), zone))
        assertTrue(ConditionEvaluator.evaluate(ConditionSpec.IsNotCharging, ctxAt(LocalTime.of(12, 0), charging = false), zone))
    }

    @Test fun `foreground_app_is matches package`() {
        val c = ConditionSpec.ForegroundAppIs("com.example")
        assertTrue(ConditionEvaluator.evaluate(c, ctxAt(LocalTime.of(12, 0), foreground = "com.example"), zone))
        assertFalse(ConditionEvaluator.evaluate(c, ctxAt(LocalTime.of(12, 0), foreground = "com.other"), zone))
        assertFalse(ConditionEvaluator.evaluate(c, ctxAt(LocalTime.of(12, 0), foreground = null), zone))
    }

    @Test fun `screen_is_on and screen_is_off`() {
        assertTrue(ConditionEvaluator.evaluate(ConditionSpec.ScreenIsOn, ctxAt(LocalTime.of(12, 0), screenOn = true), zone))
        assertFalse(ConditionEvaluator.evaluate(ConditionSpec.ScreenIsOff, ctxAt(LocalTime.of(12, 0), screenOn = true), zone))
    }

    @Test fun `evaluateAll fails fast on first failed condition`() {
        val r = ConditionEvaluator.evaluateAll(
            listOf(ConditionSpec.IsCharging, ConditionSpec.WifiSsidIs("Home")),
            ctxAt(LocalTime.of(12, 0), charging = false, wifiSsid = "Home"),
            zone,
        )
        assertTrue(r is ConditionEvaluator.Result.FailedAt)
        assertTrue((r as ConditionEvaluator.Result.FailedAt).index == 0)
    }

    @Test fun `evaluateAll passes when every condition holds`() {
        val r = ConditionEvaluator.evaluateAll(
            listOf(ConditionSpec.IsCharging, ConditionSpec.BatteryAbove(20)),
            ctxAt(LocalTime.of(12, 0), charging = true, battery = 80),
            zone,
        )
        assertTrue(r is ConditionEvaluator.Result.Pass)
    }

    @Test fun `time_after_sunset fails open without location`() {
        val c = ConditionSpec.TimeAfterSunset()
        assertTrue(ConditionEvaluator.evaluate(c, ctxAt(LocalTime.of(12, 0), lat = null, lng = null), zone))
    }

    @Test fun `needsLocation true only for sun conditions`() {
        assertTrue(ConditionEvaluator.needsLocation(listOf(ConditionSpec.TimeAfterSunset())))
        assertTrue(ConditionEvaluator.needsLocation(listOf(ConditionSpec.TimeBeforeSunrise())))
        assertFalse(ConditionEvaluator.needsLocation(listOf(ConditionSpec.IsCharging)))
        assertFalse(ConditionEvaluator.needsLocation(emptyList()))
    }
}
