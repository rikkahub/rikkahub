package me.rerere.rikkahub.ui.pages.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.UsageSource
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.SourceUsage
import me.rerere.rikkahub.data.db.dao.UsageBucket
import me.rerere.rikkahub.data.db.dao.UsageRecordDAO
import me.rerere.rikkahub.data.db.dao.UsageTotals
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.uuid.Uuid

data class AppStats(
    val isLoading: Boolean = true,
    val totalConversations: Int = 0,
    val totalMessages: Int = 0,
    // 热力图：每天的对话请求次数
    val requestsPerDay: Map<LocalDate, Int> = emptyMap(),
    val launchCount: Int = 0,
)

/** 用量统计的时间范围；[days] 为空表示全部，按月分桶 */
enum class UsageRange(val days: Int?) {
    WEEK(7),
    MONTH(30),
    QUARTER(90),
    ALL(null),
}

data class UsagePoint(
    // 按天时是当天，按月时是当月 1 号
    val date: LocalDate,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
) {
    val totalTokens get() = promptTokens + completionTokens
}

data class ModelUsageItem(
    // 模型已被删除且没有名称快照时为空
    val name: String?,
    val totalTokens: Long,
)

data class UsageStats(
    val range: UsageRange,
    val totals: UsageTotals,
    val points: List<UsagePoint>,
    val byMonth: Boolean,
    val models: List<ModelUsageItem>,
    val sources: List<SourceUsage>,
)

class StatsVM(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val usageRecordDAO: UsageRecordDAO,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _stats = MutableStateFlow(AppStats())
    val stats = _stats.asStateFlow()

    // 切换范围时先保留旧数据，查完再替换，避免页面闪成加载状态
    private val _usage = MutableStateFlow<UsageStats?>(null)
    val usage = _usage.asStateFlow()

    private val _range = MutableStateFlow(UsageRange.MONTH)
    val range = _range.asStateFlow()

    private var usageJob: Job? = null

    init {
        viewModelScope.launch { loadStats() }
        loadUsage(_range.value)
    }

    fun setRange(range: UsageRange) {
        if (range == _range.value) return
        _range.value = range
        loadUsage(range)
    }

    private suspend fun loadStats() {
        delay(50)

        val today = LocalDate.now()

        // 热力图起始日期（52 周前的周日）
        val startDate = today
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
            .minusWeeks(52)
        val since = startDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        // 按对话请求数统计活跃度，走 usage_record 的 created_at 索引，不用解析消息 JSON；
        // 多步工具调用和重试会多算几次，热力图按四分位分档，只看相对活跃程度
        val requestsPerDay = usageRecordDAO.getRequestCountPerDay(UsageSource.CHAT.name, since)
            .mapNotNull { entry ->
                runCatching { LocalDate.parse(entry.day) to entry.count }.getOrNull()
            }
            .toMap()

        val totalConversations = conversationDAO.countAll()
        val totalMessages = messageNodeDAO.countAll()
        val launchCount = settingsStore.launchCountFlow.first()

        _stats.value = AppStats(
            isLoading = false,
            totalConversations = totalConversations,
            totalMessages = totalMessages,
            requestsPerDay = requestsPerDay,
            launchCount = launchCount,
        )
    }

    private fun loadUsage(range: UsageRange) {
        usageJob?.cancel()
        usageJob = viewModelScope.launch {
            val today = LocalDate.now()
            val zone = ZoneId.systemDefault()
            val startDate = range.days?.let { today.minusDays(it - 1L) }
            val since = startDate?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: 0L
            // 用量流水包含标题、翻译等不落到消息里的请求，删掉的会话也还算在内
            val totals = usageRecordDAO.getTotals(since)
            val byMonth = range.days == null
            val buckets = usageRecordDAO.getTotalsByBucket(if (byMonth) "%Y-%m" else "%Y-%m-%d", since)
            val points = if (byMonth) monthlyPoints(buckets, today) else dailyPoints(buckets, startDate!!, today)
            val settings = settingsStore.awaitLoaded()
            val models = usageRecordDAO.getTotalsByModel(since).map { usage ->
                val model = usage.modelId
                    ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                    ?.let { settings.findModelById(it) }
                ModelUsageItem(
                    name = model?.displayName?.ifBlank { null } ?: model?.modelId ?: usage.modelName,
                    totalTokens = usage.promptTokens + usage.completionTokens,
                )
            }
            _usage.value = UsageStats(
                range = range,
                totals = totals,
                points = points,
                byMonth = byMonth,
                models = models,
                sources = usageRecordDAO.getTotalsBySource(since),
            )
        }
    }

    // 没有用量的日子补 0，柱子才能按日期均匀排开
    private fun dailyPoints(buckets: List<UsageBucket>, start: LocalDate, today: LocalDate): List<UsagePoint> {
        val byDate = buckets.associateBy { it.bucket }
        return generateSequence(start) { it.plusDays(1) }
            .takeWhile { !it.isAfter(today) }
            .map { date -> byDate[date.toString()].toPoint(date) }
            .toList()
    }

    // 全部范围按月，从最早有用量的月份排到本月
    private fun monthlyPoints(buckets: List<UsageBucket>, today: LocalDate): List<UsagePoint> {
        val byMonth = buckets.mapNotNull { bucket ->
            runCatching { YearMonth.parse(bucket.bucket) to bucket }.getOrNull()
        }.toMap()
        val first = byMonth.keys.minOrNull() ?: return emptyList()
        val current = YearMonth.from(today)
        return generateSequence(first) { it.plusMonths(1) }
            .takeWhile { !it.isAfter(current) }
            .map { month -> byMonth[month].toPoint(month.atDay(1)) }
            .toList()
    }

    private fun UsageBucket?.toPoint(date: LocalDate) = UsagePoint(
        date = date,
        promptTokens = this?.promptTokens ?: 0,
        completionTokens = this?.completionTokens ?: 0,
        cachedTokens = this?.cachedTokens ?: 0,
    )
}
