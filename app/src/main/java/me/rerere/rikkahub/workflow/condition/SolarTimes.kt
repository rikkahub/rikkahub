package me.rerere.rikkahub.workflow.condition

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Phase 12 — sunrise / sunset computation, NOAA solar position formula inline (no
 * network calls, no extra deps). Accuracy is ±2 minutes for the latitude band where
 * Earth's atmosphere matters; the formula degenerates at high latitudes when the sun
 * doesn't rise/set on the given day, which is signalled by the helpers returning null.
 *
 * References:
 *   - NOAA Solar Calculator (Spencer-Fourier eqs)
 *   - https://gml.noaa.gov/grad/solcalc/calcdetails.html
 *
 * The formula is purely arithmetic — no JVM-only Math edge cases. Pure-JVM tests in
 * SolarTimesTest pin two known locations to ±2 min.
 */
object SolarTimes {

    /** Solar zenith angle for "civil" sunrise/sunset; standard refraction-corrected value. */
    private const val ZENITH_DEG = 90.833

    /**
     * Sunrise time on [date] at [(lat, lng)] in [zone]. Null if the sun doesn't rise
     * on this date (high-latitude polar day/night). Returns the local clock time.
     */
    fun sunriseAt(date: LocalDate, lat: Double, lng: Double, zone: ZoneId): LocalTime? =
        compute(date, lat, lng, zone, sunrise = true)

    /** Sunset time on [date] at [(lat, lng)] in [zone]. Null if no sunset on this date. */
    fun sunsetAt(date: LocalDate, lat: Double, lng: Double, zone: ZoneId): LocalTime? =
        compute(date, lat, lng, zone, sunrise = false)

    /** True if [now] is after sunset (with [offsetMinutes] applied) for [date]. */
    fun isAfterSunset(now: ZonedDateTime, lat: Double, lng: Double, offsetMinutes: Int): Boolean {
        val sunset = sunsetAt(now.toLocalDate(), lat, lng, now.zone) ?: return true  // polar day → "always after sunset"
        val cutoff = now.toLocalDate().atTime(sunset).plusMinutes(offsetMinutes.toLong()).atZone(now.zone)
        return !now.isBefore(cutoff)
    }

    /** True if [now] is before sunrise (with [offsetMinutes] applied) for [date]. */
    fun isBeforeSunrise(now: ZonedDateTime, lat: Double, lng: Double, offsetMinutes: Int): Boolean {
        val sunrise = sunriseAt(now.toLocalDate(), lat, lng, now.zone) ?: return true  // polar night → "always before sunrise"
        val cutoff = now.toLocalDate().atTime(sunrise).plusMinutes(offsetMinutes.toLong()).atZone(now.zone)
        return now.isBefore(cutoff)
    }

    private fun compute(date: LocalDate, lat: Double, lng: Double, zone: ZoneId, sunrise: Boolean): LocalTime? {
        // Day of the year, 1..366.
        val n = date.dayOfYear

        // 1. Convert longitude to hour value, approximate time.
        val lngHour = lng / 15.0
        val tApprox = if (sunrise) n + ((6 - lngHour) / 24.0) else n + ((18 - lngHour) / 24.0)

        // 2. Sun's mean anomaly.
        val m = (0.9856 * tApprox) - 3.289

        // 3. Sun's true longitude.
        var l = m + (1.916 * sin(m.deg())) + (0.020 * sin(2 * m.deg())) + 282.634
        l = l.normalize(360.0)

        // 4. Right ascension.
        var ra = atanDeg(0.91764 * tan(l.deg()))
        ra = ra.normalize(360.0)
        // Adjust RA into the same quadrant as L.
        val lQuadrant = (l / 90.0).toInt() * 90.0
        val raQuadrant = (ra / 90.0).toInt() * 90.0
        ra += (lQuadrant - raQuadrant)
        ra /= 15.0  // hours

        // 5. Sun's declination.
        val sinDec = 0.39782 * sin(l.deg())
        val cosDec = cos(asin(sinDec))

        // 6. Local hour angle.
        val cosH = (cos(ZENITH_DEG.deg()) - (sinDec * sin(lat.deg()))) /
                (cosDec * cos(lat.deg()))
        if (cosH > 1.0) return null   // sun never rises (polar night)
        if (cosH < -1.0) return null  // sun never sets (polar day)
        val h = if (sunrise) {
            (360.0 - acos(cosH).rad()) / 15.0
        } else {
            acos(cosH).rad() / 15.0
        }

        // 7. Local mean time.
        var t = h + ra - (0.06571 * tApprox) - 6.622

        // 8. Adjust to UTC then to local zone.
        var utHours = t - lngHour
        utHours = utHours.normalize(24.0)

        val secondsTotal = (utHours * 3600).toLong()
        val utHoursInt = (secondsTotal / 3600).toInt()
        val utMinutesInt = ((secondsTotal % 3600) / 60).toInt()

        // ZonedDateTime in UTC, then convert to local zone.
        val utc = date.atTime(utHoursInt.coerceIn(0, 23), utMinutesInt.coerceIn(0, 59))
            .atZone(ZoneId.of("UTC"))
        val local = utc.withZoneSameInstant(zone)
        return local.toLocalTime().withSecond(0).withNano(0)
    }

    private fun Double.deg(): Double = this * (PI / 180.0)
    private fun Double.rad(): Double = this * (180.0 / PI)
    private fun atanDeg(x: Double): Double = (kotlin.math.atan(x) * 180.0 / PI)
    private fun Double.normalize(modulus: Double): Double {
        var v = this
        while (v < 0) v += modulus
        while (v >= modulus) v -= modulus
        return v
    }
}
