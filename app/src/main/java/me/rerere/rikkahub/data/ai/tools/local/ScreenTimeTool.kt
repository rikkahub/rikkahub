package me.rerere.rikkahub.data.ai.tools.local

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.utils.hasUsageStatsPermission
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

internal fun buildScreenTimeTool(context: Context, eventBus: AppEventBus): Tool = Tool(
    name = "get_screen_time",
    description = """
        Get the user's app screen usage (screen time) over a time range.
        Specify a custom interval with 'begin'/'end', or use the 'range' preset (today/week).
        Returns the total foreground time and a per-app breakdown sorted by usage time (descending).
        The device timezone is '${ZoneId.systemDefault()}' (UTC offset ${OffsetDateTime.now().offset});
        times without an explicit offset are interpreted in this timezone.
        Requires the 'Usage access' special permission; if it is not granted, the device's usage
        access settings page is opened automatically and an error is returned.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("begin", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Start time (inclusive). Accepts an ISO-8601 date 'yyyy-MM-dd', a local " +
                            "date-time 'yyyy-MM-ddTHH:mm:ss', an offset date-time, or epoch milliseconds. " +
                            "When provided, 'range' is ignored."
                    )
                })
                put("end", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "End time (exclusive), same formats as 'begin'. Defaults to now."
                    )
                })
                put("range", buildJsonObject {
                    put("type", "string")
                    put(
                        "enum",
                        buildJsonArray {
                            add("today")
                            add("week")
                        }
                    )
                    put(
                        "description",
                        "Convenience preset, used only when 'begin' is omitted: today or week. Default today."
                    )
                })
                put("top", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum number of top apps to return, sorted by usage time. Default 10.")
                })
            }
        )
    },
    execute = {
        if (!context.hasUsageStatsPermission()) {
            eventBus.emit(AppEvent.OpenUsageAccessSettings)
            val payload = buildJsonObject {
                put("error", "NO_PERMISSION")
                put(
                    "message",
                    "Usage access permission is not granted. The system settings page has been " +
                        "opened; please ask the user to enable 'Usage access' for this app and try again."
                )
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        val params = it.jsonObject
        val top = params["top"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 50) ?: 10

        val now = ZonedDateTime.now()
        val zone = now.zone
        val beginRaw = params["begin"]?.jsonPrimitive?.contentOrNull
        val endRaw = params["end"]?.jsonPrimitive?.contentOrNull
        val rangePreset = params["range"]?.jsonPrimitive?.contentOrNull ?: "today"

        val startTime: ZonedDateTime
        val endTime: ZonedDateTime
        try {
            endTime = endRaw?.let { raw -> parseUsageTime(raw, zone) } ?: now
            startTime = if (beginRaw != null) {
                parseUsageTime(beginRaw, zone)
            } else when (rangePreset) {
                "week" -> now.minusDays(7)
                else -> now.toLocalDate().atStartOfDay(zone)
            }
        } catch (e: Exception) {
            val payload = buildJsonObject {
                put("error", "INVALID_TIME")
                put("message", e.message ?: "Invalid time format for begin/end.")
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        if (!startTime.isBefore(endTime)) {
            val payload = buildJsonObject {
                put("error", "INVALID_RANGE")
                put("message", "begin must be earlier than end.")
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        val isCustom = beginRaw != null || endRaw != null
        val endMs = endTime.toInstant().toEpochMilli()
        val startMs = startTime.toInstant().toEpochMilli()

        val usageStatsManager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val pm = context.packageManager

        // 通过逐个前台/后台事件配对计算真实前台时长, 比 queryAndAggregateUsageStats
        // 更接近系统"屏幕使用时间", 避免统计桶溢出导致的范围偏差; 排除桌面 launcher.
        val launcherPackages = resolveLauncherPackages(pm)
        val foregroundMs = computeForegroundTime(usageStatsManager, startMs, endMs, launcherPackages)

        val sorted = foregroundMs.entries
            .filter { entry -> entry.value > 0 }
            .sortedByDescending { entry -> entry.value }

        val totalMs = sorted.sumOf { entry -> entry.value }
        val apps = sorted.take(top)

        val payload = buildJsonObject {
            put("range", if (isCustom) "custom" else rangePreset)
            put("start", startTime.withNano(0).toString())
            put("end", endTime.withNano(0).toString())
            put("total_ms", totalMs)
            put("total_minutes", totalMs / 60000)
            put("apps", buildJsonArray {
                apps.forEach { entry ->
                    add(buildJsonObject {
                        put("package", entry.key)
                        put("app_name", resolveAppName(pm, entry.key))
                        put("total_ms", entry.value)
                        put("total_minutes", entry.value / 60000)
                    })
                }
            })
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

// 计算屏幕时间时向前回看的窗口(12h), 用于还原区间开始时刻已在前台的 App;
// 取值需覆盖典型的一次连续使用时长, 过小会漏算开头, 过大只是多遍历些事件.
private const val LOOKBACK_MS = 12L * 60 * 60 * 1000

/**
 * 用"全局单一前台"模型计算 [startMs, endMs) 区间内每个 App 的前台时长(毫秒).
 *
 * 任意时刻只有一个 App 处于计时状态: 新 App 进入前台时先结算上一个前台 App, 息屏时停止计时.
 * 这样各 App 时段串行不重叠, 不会出现 per-app 配对那种因前台时段重叠相加而偏大的问题, 结果
 * 与系统"屏幕使用时间"口径基本一致. 边界处理:
 * - 为正确处理"区间开始前已进入前台、区间内继续使用"的 App, 查询起点向前回看 [LOOKBACK_MS],
 *   据此还原区间开始时刻正在前台的 App; 结算时把累加区间裁剪到 [startMs, endMs], startMs
 *   之前的部分自动被裁掉, 既补回开头那段使用又不会高估.
 * - 区间结束时仍在前台的 App, 以 endMs 截断.
 * - [excludedPackages] 中的包(如桌面 launcher)不计入结果, 其停留时间视为"无 App 前台".
 */
@Suppress(
    "DEPRECATION", // MOVE_TO_FOREGROUND/BACKGROUND 与 API29 的 ACTIVITY_RESUMED/PAUSED 值相同, 兼容 minSdk 26
    "NewApi" // SCREEN_NON_INTERACTIVE 是编译期常量, 低版本设备不会产生该事件, 引用安全
)
private fun computeForegroundTime(
    usageStatsManager: UsageStatsManager,
    startMs: Long,
    endMs: Long,
    excludedPackages: Set<String>,
): Map<String, Long> {
    val foregroundMs = HashMap<String, Long>()
    // 向前回看一段时间以捕获"区间开始前就进入前台"的事件; 累加时再裁剪回 [startMs, endMs]
    val events = usageStatsManager.queryEvents(startMs - LOOKBACK_MS, endMs)
    val event = UsageEvents.Event()

    // 当前正在计时的前台包及其起始时间; null 表示当前无 App 在前台(如停留桌面/息屏)
    var currentPkg: String? = null
    var currentStart = 0L

    // 结算当前前台段: 把 [currentStart, until) 与 [startMs, endMs] 的交集累加给 currentPkg
    fun settle(until: Long) {
        val pkg = currentPkg
        currentPkg = null
        if (pkg == null || pkg in excludedPackages) return // 排除的包不计入, 但仍清空计时状态
        val from = maxOf(currentStart, startMs) // 裁掉 startMs 之前的部分
        val duration = until - from
        if (duration > 0) {
            foregroundMs[pkg] = (foregroundMs[pkg] ?: 0L) + duration
        }
    }

    while (events.hasNextEvent()) {
        events.getNextEvent(event)
        when (event.eventType) {
            UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                if (event.packageName != currentPkg) {
                    settle(event.timeStamp)         // 先结算上一个前台 App
                    currentPkg = event.packageName  // 再开始为新 App 计时
                    currentStart = event.timeStamp
                }
            }

            UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                // 只结算当前正在计时的前台包; 其他包的 background 一律忽略(避免重叠/高估)
                if (event.packageName == currentPkg) {
                    settle(event.timeStamp)
                }
            }

            // 息屏: 停止计时, 系统在息屏期间同样不计入屏幕使用时间
            UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                settle(event.timeStamp)
            }
        }
    }
    // 区间结束时仍在前台的 App, 用 endMs 截断
    settle(endMs)
    return foregroundMs
}

/**
 * 解析设备上所有桌面(HOME)应用的包名, 用于在屏幕使用时间里排除 launcher.
 * 查询所有响应 HOME intent 的 Activity, 覆盖默认及其他已安装的桌面应用.
 */
private fun resolveLauncherPackages(pm: PackageManager): Set<String> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    return runCatching {
        pm.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }.getOrDefault(emptySet())
}

private fun resolveAppName(pm: PackageManager, packageName: String): String {
    return runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
}

/**
 * 解析 begin/end 时间参数, 依次尝试: epoch 毫秒 -> 带偏移日期时间 -> Instant ->
 * 本地日期时间 -> 本地日期(当天 0 点). 全部失败时抛出异常.
 */
private fun parseUsageTime(raw: String, zone: ZoneId): ZonedDateTime {
    val text = raw.trim()
    text.toLongOrNull()?.let { return Instant.ofEpochMilli(it).atZone(zone) }
    runCatching { return OffsetDateTime.parse(text).atZoneSameInstant(zone) }
    runCatching { return Instant.parse(text).atZone(zone) }
    runCatching { return LocalDateTime.parse(text).atZone(zone) }
    runCatching { return LocalDate.parse(text).atStartOfDay(zone) }
    error("Invalid time format: '$raw'. Use ISO-8601 date/date-time or epoch milliseconds.")
}
