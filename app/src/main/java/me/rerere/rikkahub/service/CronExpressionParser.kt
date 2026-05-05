package me.rerere.rikkahub.service

import com.cronutils.model.Cron
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import java.time.ZonedDateTime
import java.util.LinkedHashMap

/**
 * Thin wrapper around cron-utils. Validates 5-field UNIX cron expressions plus:
 *   - Standard nicknames: @hourly, @daily, @weekly, @monthly, @yearly
 *   - Duration aliases:  @every Nm | @every Ns | @every Nh
 *
 * @every is NOT a cron-utils feature; it is expanded to a 5-field equivalent:
 *   @every Xm  →  *\/X * * * *        (every X minutes, 1..59)
 *   @every Xs  bucketed by magnitude:
 *               X < 60   → rejected   (sub-minute scheduling not supported)
 *               X < 3600 → *\/(X/60) * * * *   (every N minutes)
 *               X < 86400 → 0 *\/(X/3600) * * *  (every N hours, on the hour)
 *               X <= 86400*31 → 0 0 *\/(X/86400) * *  (every N days at midnight)
 *               X > 86400*31 → rejected (>31 days not expressible in 5-field cron)
 *   @every Xh  →  0 *\/X * * *        (every X hours, 1..23)
 *
 * The cron definition used is a UNIX-flavored definition with all standard
 * nicknames explicitly enabled (the built-in CronType.UNIX omits them).
 *
 * Thread-safe: parse() performs the entire cache lookup and write under a single
 * synchronized(cache) block, preserving === identity on cache hits.
 *
 * cron-utils handles DST natively when given a ZonedDateTime basis,
 * which is the entire reason we don't hand-roll this.
 */
object CronExpressionParser {

    private val parser: CronParser = run {
        val def = CronDefinitionBuilder.defineCron()
            .withMinutes().withValidRange(0, 59).and()
            .withHours().withValidRange(0, 23).and()
            .withDayOfMonth().withValidRange(1, 31)
                .supportsL().supportsW().supportsLW().supportsQuestionMark().and()
            .withMonth().withValidRange(1, 12).and()
            .withDayOfWeek().withValidRange(0, 7).withMondayDoWValue(1)
                .supportsHash().supportsL().supportsQuestionMark().and()
            .withSupportedNicknameYearly()
            .withSupportedNicknameAnnually()
            .withSupportedNicknameMonthly()
            .withSupportedNicknameWeekly()
            .withSupportedNicknameDaily()
            .withSupportedNicknameMidnight()
            .withSupportedNicknameHourly()
            .instance()
        CronParser(def)
    }

    private const val CACHE_CAP = 32

    /** LRU cache keyed by the ORIGINAL (pre-expansion) expression string. */
    private val cache = object : LinkedHashMap<String, Cron>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Cron>?) = size > CACHE_CAP
    }

    /**
     * Expand @every duration aliases into standard 5-field cron expressions.
     * Returns null if [expr] is not an @every alias OR if the value is out of range
     * (caller should treat null as a parse failure for @every forms).
     *
     * Bucketing for 's' suffix:
     *   value < 1        → null  (reject zero / negative)
     *   value < 60       → null  (sub-minute scheduling not supported)
     *   value < 3600     → every N minutes: *\/(value/60) * * * *
     *   value < 86400    → every N hours on the hour: 0 *\/(value/3600) * * *
     *   value <= 86400*31 → every N days at midnight: 0 0 *\/(value/86400) * *
     *   value > 86400*31 → null  (>31 days not expressible in 5-field cron)
     */
    private fun expandEvery(expr: String): String? {
        val trimmed = expr.trim()
        if (!trimmed.startsWith("@every ")) return null
        val duration = trimmed.removePrefix("@every ").trim()
        val value = duration.dropLast(1).toLongOrNull() ?: return null
        if (value <= 0) return null   // reject @every 0m / 0s / 0h as invalid
        return when (duration.last()) {
            's' -> when {
                value < 60 -> null  // sub-minute not supported
                value < 3600 -> {
                    val minutes = (value / 60).coerceIn(1, 59)
                    "*/$minutes * * * *"
                }
                value < 86400 -> {
                    val hours = (value / 3600).coerceIn(1, 23)
                    "0 */$hours * * *"
                }
                value <= 86400L * 31 -> {
                    val days = (value / 86400).coerceIn(1, 31)
                    "0 0 */$days * *"
                }
                else -> null  // >31 days not expressible in 5-field cron
            }
            'm' -> {
                val minutes = value.coerceAtMost(59)
                "*/$minutes * * * *"
            }
            'h' -> {
                val hours = value.coerceAtMost(23)
                "0 */$hours * * *"
            }
            else -> null
        }
    }

    /**
     * Parse [expr]. Returns Result.success on a parseable expression. Does NOT validate
     * that the cron will ever fire — use [nextExecution] for that. Returns Result.failure
     * with the cron-utils exception on parse error.
     *
     * The cache is keyed by the original expression string, so [parse]("@every 30m")
     * and [parse]("@every 30m") return the same [Cron] instance (=== identity).
     *
     * Thread-safe: the entire cache lookup, expansion, and write are performed under a
     * single synchronized(cache) block, guaranteeing === identity on cache hits with
     * no TOCTOU race between two threads parsing the same expression simultaneously.
     */
    fun parse(expr: String): Result<Cron> {
        synchronized(cache) {
            cache[expr]?.let { return Result.success(it) }
            return runCatching {
                val effective = expandEvery(expr)
                    ?: if (expr.trim().startsWith("@every ")) error("Unsupported @every value: $expr")
                    else expr
                val cron = parser.parse(effective).validate()
                cache[expr] = cron
                cron
            }
        }
    }

    /**
     * Compute the next fire time after [basis]. Returns null if the expression has no
     * future fire from this basis (e.g. impossible date like Feb 30).
     */
    fun nextExecution(cron: Cron, basis: ZonedDateTime): ZonedDateTime? {
        val et = ExecutionTime.forCron(cron)
        return et.nextExecution(basis).orElse(null)
    }
}
