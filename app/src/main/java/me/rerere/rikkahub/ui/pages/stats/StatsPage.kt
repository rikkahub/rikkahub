package me.rerere.rikkahub.ui.pages.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.PercentCircle
import me.rerere.hugeicons.stroke.Rocket01
import me.rerere.hugeicons.stroke.Upload02
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.UsageSource
import me.rerere.rikkahub.data.db.dao.SourceUsage
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

// 排行里单独列出的条目数，其余并成「其他」
private const val RANKING_LIMIT = 8

@Composable
fun StatsPage(vm: StatsVM = koinViewModel()) {
    val stats by vm.stats.collectAsStateWithLifecycle()
    val usage by vm.usage.collectAsStateWithLifecycle()
    val range by vm.range.collectAsStateWithLifecycle()

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.stats_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        if (stats.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = padding + PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    HeatmapCard(
                        requestsPerDay = stats.requestsPerDay,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                item {
                    StatsGrid(
                        stats = stats,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                item {
                    UsageHeader(
                        range = range,
                        onRangeChange = vm::setRange,
                        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 16.dp),
                    )
                }
                usage?.let { usage ->
                    if (usage.totals.promptTokens + usage.totals.completionTokens == 0L) {
                        item {
                            EmptyUsageCard(modifier = Modifier.padding(horizontal = 8.dp))
                        }
                    } else {
                        item {
                            UsageTotalsGrid(usage = usage, modifier = Modifier.padding(horizontal = 8.dp))
                        }
                        item {
                            UsageTrendCard(usage = usage, modifier = Modifier.padding(horizontal = 8.dp))
                        }
                        item {
                            RankingCard(
                                title = stringResource(R.string.stats_page_by_model),
                                items = usage.models.map { model ->
                                    (model.name ?: stringResource(R.string.stats_page_unknown_model)) to model.totalTokens
                                },
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        }
                        item {
                            RankingCard(
                                title = stringResource(R.string.stats_page_by_source),
                                items = usage.sources.map { source -> sourceLabel(source) to source.totalTokens },
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeatmapCard(requestsPerDay: Map<LocalDate, Int>, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.stats_page_heatmap_title), style = MaterialTheme.typography.titleMedium)

            ChatHeatmap(requestsPerDay = requestsPerDay)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.stats_page_heatmap_less),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(2.dp))
                listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { alpha ->
                    HeatmapCell(alpha = alpha, sizeDp = 10)
                }
                Spacer(Modifier.width(2.dp))
                Text(
                    text = stringResource(R.string.stats_page_heatmap_more),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChatHeatmap(requestsPerDay: Map<LocalDate, Int>) {
    val today = LocalDate.now()
    val startSunday = today
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        .minusWeeks(52)

    val numWeeks = 53
    val activeCounts = requestsPerDay.values.filter { it > 0 }.sorted()
    val q1 = activeCounts.getOrElse((activeCounts.size * 0.25).toInt()) { 1 }
    val q2 = activeCounts.getOrElse((activeCounts.size * 0.50).toInt()) { 2 }
    val q3 = activeCounts.getOrElse((activeCounts.size * 0.75).toInt()) { 3 }
    val cellSize = 11.dp
    val cellSpacing = 2.dp
    // Month label row height
    val monthLabelHeight = 14.dp

    // Day-of-week labels (only Mon/Wed/Fri to save space, Sun=0)
    val dowLabels = listOf(
        "",
        stringResource(R.string.stats_page_dow_mon),
        "",
        stringResource(R.string.stats_page_dow_wed),
        "",
        stringResource(R.string.stats_page_dow_fri),
        ""
    )

    // Shared scroll state so month labels + grid scroll together
    val scrollState = rememberScrollState(initial = Int.MAX_VALUE)

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        // Fixed left column: spacer for month label row + DOW labels
        Column(
            modifier = Modifier.width(12.dp),
            verticalArrangement = Arrangement.spacedBy(cellSpacing),
        ) {
            Spacer(Modifier.height(monthLabelHeight + 2.dp))
            dowLabels.forEach { label ->
                Box(
                    modifier = Modifier.size(cellSize),
                    contentAlignment = Alignment.Center,
                ) {
                    if (label.isNotEmpty()) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.7,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Scrollable area: month labels + heatmap grid share one scroll state
        Column(
            modifier = Modifier.horizontalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Month labels row
            Row(horizontalArrangement = Arrangement.spacedBy(cellSpacing)) {
                for (weekIdx in 0 until numWeeks) {
                    val weekStart = startSunday.plusDays((weekIdx * 7).toLong())
                    val labelDate = (0..6)
                        .map { weekStart.plusDays(it.toLong()) }
                        .firstOrNull { it.dayOfMonth == 1 }
                    Box(
                        modifier = Modifier
                            .width(cellSize)
                            .height(monthLabelHeight),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        if (labelDate != null) {
                            Text(
                                text = if (labelDate.monthValue == 1) {
                                    labelDate.year.toString()
                                } else {
                                    labelDate.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                                },
                                modifier = Modifier.wrapContentWidth(unbounded = true),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.75,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                softWrap = false,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            // Heatmap grid
            Row(horizontalArrangement = Arrangement.spacedBy(cellSpacing)) {
                for (weekIdx in 0 until numWeeks) {
                    Column(verticalArrangement = Arrangement.spacedBy(cellSpacing)) {
                        for (dow in 0..6) {
                            val date = startSunday.plusDays((weekIdx * 7 + dow).toLong())
                            val isFuture = date.isAfter(today)
                            val count = if (isFuture) 0 else (requestsPerDay[date] ?: 0)
                            val alpha = when {
                                isFuture -> -1f
                                count == 0 -> 0f
                                count <= q1 -> 0.25f
                                count <= q2 -> 0.5f
                                count <= q3 -> 0.75f
                                else -> 1f
                            }
                            HeatmapCell(alpha = alpha, sizeDp = cellSize.value.toInt())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeatmapCell(alpha: Float, sizeDp: Int) {
    val color = when {
        alpha < 0f -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f) // future
        alpha == 0f -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.primary.copy(alpha = alpha)
    }
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(color)
    )
}

@Composable
private fun StatsGrid(stats: AppStats, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                icon = HugeIcons.ChartColumn,
                label = stringResource(R.string.stats_page_total_conversations),
                value = formatCount(stats.totalConversations.toLong()),
            )
            StatCard(
                modifier = Modifier.weight(1f),
                icon = HugeIcons.Message01,
                label = stringResource(R.string.stats_page_total_messages),
                value = formatCount(stats.totalMessages.toLong()),
            )
        }
        StatCard(
            modifier = Modifier.fillMaxWidth(),
            icon = HugeIcons.Rocket01,
            label = stringResource(R.string.stats_page_launch_count),
            value = formatCount(stats.launchCount.toLong()),
        )
    }
}

// 时间范围放在所有用量卡片上方，只有这一处筛选
@Composable
private fun UsageHeader(range: UsageRange, onRangeChange: (UsageRange) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.stats_page_usage_title),
            style = MaterialTheme.typography.titleMediumEmphasized,
        )
        ConnectedChoiceButtons(
            options = UsageRange.entries,
            selected = range,
            onSelect = onRangeChange,
        ) { option, selected ->
            ConnectedChoiceLabel(
                text = stringResource(
                    when (option) {
                        UsageRange.WEEK -> R.string.stats_page_range_7d
                        UsageRange.MONTH -> R.string.stats_page_range_30d
                        UsageRange.QUARTER -> R.string.stats_page_range_90d
                        UsageRange.ALL -> R.string.stats_page_range_all
                    }
                ),
                selected = selected,
            )
        }
    }
}

@Composable
private fun EmptyUsageCard(modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth(), colors = CustomColors.cardColorsOnSurfaceContainer) {
        Text(
            text = stringResource(R.string.stats_page_no_usage),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp),
        )
    }
}

@Composable
private fun UsageTotalsGrid(usage: UsageStats, modifier: Modifier = Modifier) {
    val totals = usage.totals
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                icon = HugeIcons.Upload02,
                label = stringResource(R.string.stats_page_input_tokens),
                value = formatTokens(totals.promptTokens),
            )
            StatCard(
                modifier = Modifier.weight(1f),
                icon = HugeIcons.Download04,
                label = stringResource(R.string.stats_page_output_tokens),
                value = formatTokens(totals.completionTokens),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                icon = HugeIcons.Zap,
                label = stringResource(R.string.stats_page_cached_tokens),
                value = formatTokens(totals.cachedTokens),
            )
            StatCard(
                modifier = Modifier.weight(1f),
                icon = HugeIcons.PercentCircle,
                label = stringResource(R.string.stats_page_cache_hit_rate),
                value = if (totals.promptTokens > 0) {
                    "%.1f%%".format(totals.cachedTokens * 100.0 / totals.promptTokens)
                } else {
                    "-"
                },
            )
        }
    }
}

/**
 * 每天（全部范围时每月）的总用量柱状图，点一根柱子在上方查看那天的输入、缓存和输出。
 *
 * 只用一种颜色：主题色跟随壁纸变化，没法保证两个角色色在所有主题下都能分清，明细交给读数行。
 */
@Composable
private fun UsageTrendCard(usage: UsageStats, modifier: Modifier = Modifier) {
    var selectedIndex by rememberSaveable(usage.range) { mutableStateOf<Int?>(null) }
    val selected = selectedIndex?.let { usage.points.getOrNull(it) }
    val dateFormatter = remember(usage.byMonth) {
        if (usage.byMonth) {
            DateTimeFormatter.ofPattern("yyyy-MM")
        } else {
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        }
    }

    Card(modifier = modifier.fillMaxWidth(), colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(
                    if (usage.byMonth) R.string.stats_page_usage_monthly else R.string.stats_page_usage_daily
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            UsageReadout(
                label = selected?.date?.format(dateFormatter)
                    ?: stringResource(R.string.stats_page_usage_period_total),
                promptTokens = selected?.promptTokens ?: usage.totals.promptTokens,
                cachedTokens = selected?.cachedTokens ?: usage.totals.cachedTokens,
                completionTokens = selected?.completionTokens ?: usage.totals.completionTokens,
            )
            UsageColumnChart(
                points = usage.points,
                byMonth = usage.byMonth,
                selectedIndex = selectedIndex,
                onSelect = { index -> selectedIndex = index.takeIf { it != selectedIndex } },
            )
        }
    }
}

@Composable
private fun UsageReadout(label: String, promptTokens: Long, cachedTokens: Long, completionTokens: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = formatTokens(promptTokens + completionTokens),
            style = MaterialTheme.typography.headlineSmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(
                R.string.chat_message_usage_input to promptTokens,
                R.string.chat_message_usage_cached to cachedTokens,
                R.string.chat_message_usage_output to completionTokens,
            ).forEach { (label, tokens) ->
                Text(
                    text = "${stringResource(label)} ${formatTokens(tokens)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun UsageColumnChart(
    points: List<UsagePoint>,
    byMonth: Boolean,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colorScheme.onSurfaceVariant)
    val textMeasurer = rememberTextMeasurer()
    val axisMax = remember(points) { niceCeil(points.maxOfOrNull { it.totalTokens } ?: 0L) }
    val yLabels = remember(axisMax, labelStyle) {
        listOf(axisMax, axisMax / 2, 0L).map { textMeasurer.measure(formatAxis(it), labelStyle) }
    }
    val xFormatter = remember(byMonth) { DateTimeFormatter.ofPattern(if (byMonth) "yyyy-MM" else "M/d") }
    val xLabels = remember(points, labelStyle) {
        val indexes = if (points.size >= 3) listOf(0, points.lastIndex / 2, points.lastIndex) else points.indices.toList()
        indexes.map { it to textMeasurer.measure(points[it].date.format(xFormatter), labelStyle) }
    }
    val axisWidth = (yLabels.maxOfOrNull { it.size.width } ?: 0).toFloat()

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .pointerInput(points.size, axisWidth) {
                detectTapGestures { offset ->
                    val plotLeft = axisWidth + 8.dp.toPx()
                    if (points.isEmpty() || offset.x < plotLeft) return@detectTapGestures
                    val slot = (size.width - plotLeft) / points.size
                    onSelect(((offset.x - plotLeft) / slot).toInt().coerceIn(points.indices))
                }
            },
    ) {
        val plotLeft = axisWidth + 8.dp.toPx()
        val xLabelHeight = (xLabels.maxOfOrNull { it.second.size.height } ?: 0) + 6.dp.toPx()
        val plotTop = yLabels.first().size.height / 2f
        val plotBottom = size.height - xLabelHeight
        val plotHeight = plotBottom - plotTop
        val plotWidth = size.width - plotLeft

        // 三条水平参考线：最大值、一半、0，细线、不抢数据的戏
        yLabels.forEachIndexed { index, label ->
            val y = plotTop + plotHeight * index / 2f
            drawLine(
                color = colorScheme.outlineVariant,
                start = Offset(plotLeft, y),
                end = Offset(size.width, y),
                strokeWidth = 1f,
            )
            drawText(label, topLeft = Offset(axisWidth - label.size.width, y - label.size.height / 2f))
        }

        if (points.isEmpty() || axisMax <= 0) return@Canvas
        val slot = plotWidth / points.size
        // 柱宽最多 24dp，相邻柱子之间留 2dp 空隙；90 天这种密集的范围空隙按比例缩小，柱子才不会细成线
        val gap = minOf(2.dp.toPx(), slot * 0.3f)
        val barWidth = minOf(24.dp.toPx(), slot - gap).coerceAtLeast(1f)
        val radius = minOf(4.dp.toPx(), barWidth / 2)
        points.forEachIndexed { index, point ->
            if (point.totalTokens <= 0) return@forEachIndexed
            val height = (point.totalTokens.toFloat() / axisMax * plotHeight).coerceAtLeast(1.dp.toPx())
            val left = plotLeft + slot * index + (slot - barWidth) / 2
            val color = if (selectedIndex == null || selectedIndex == index) {
                colorScheme.primary
            } else {
                colorScheme.primary.copy(alpha = 0.35f)
            }
            // 顶端圆角，贴着基线的一端保持方角
            val path = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = left,
                        top = plotBottom - height,
                        right = left + barWidth,
                        bottom = plotBottom,
                        topLeftCornerRadius = CornerRadius(radius),
                        topRightCornerRadius = CornerRadius(radius),
                        bottomLeftCornerRadius = CornerRadius.Zero,
                        bottomRightCornerRadius = CornerRadius.Zero,
                    )
                )
            }
            drawPath(path, color)
        }

        xLabels.forEach { (index, label) ->
            val center = plotLeft + slot * (index + 0.5f)
            val x = (center - label.size.width / 2f).coerceIn(plotLeft, size.width - label.size.width)
            drawText(label, topLeft = Offset(x, size.height - label.size.height))
        }
    }
}

/** 单色横向条形排行，条长按第一名缩放，右侧标出用量和占比 */
@Composable
private fun RankingCard(title: String, items: List<Pair<String, Long>>, modifier: Modifier = Modifier) {
    val otherLabel = stringResource(R.string.stats_page_other)
    val rows = remember(items, otherLabel) {
        val sorted = items.filter { it.second > 0 }.sortedByDescending { it.second }
        if (sorted.size <= RANKING_LIMIT) {
            sorted
        } else {
            sorted.take(RANKING_LIMIT - 1) + (otherLabel to sorted.drop(RANKING_LIMIT - 1).sumOf { it.second })
        }
    }
    if (rows.isEmpty()) return
    val total = rows.sumOf { it.second }
    val max = rows.maxOf { it.second }

    Card(modifier = modifier.fillMaxWidth(), colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            rows.forEach { (label, tokens) ->
                RankingRow(
                    label = label,
                    tokens = tokens,
                    fraction = tokens.toFloat() / max,
                    share = tokens.toFloat() / total,
                )
            }
        }
    }
}

@Composable
private fun RankingRow(label: String, tokens: Long, fraction: Float, share: Float) {
    val barColor = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${formatTokens(tokens)} · ${"%.0f".format(share * 100)}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
        ) {
            val width = (size.width * fraction).coerceAtLeast(size.height)
            val radius = CornerRadius(minOf(4.dp.toPx(), size.height / 2))
            // 起点贴着左侧基线保持方角，末端圆角
            val path = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = 0f,
                        top = 0f,
                        right = width,
                        bottom = size.height,
                        topLeftCornerRadius = CornerRadius.Zero,
                        bottomLeftCornerRadius = CornerRadius.Zero,
                        topRightCornerRadius = radius,
                        bottomRightCornerRadius = radius,
                    )
                )
            }
            drawPath(path, barColor)
        }
    }
}

@Composable
private fun sourceLabel(source: SourceUsage): String {
    val usageSource = UsageSource.entries.find { it.name == source.source } ?: return source.source
    return stringResource(
        when (usageSource) {
            UsageSource.CHAT -> R.string.stats_page_source_chat
            UsageSource.COMPRESSION -> R.string.stats_page_source_compression
            UsageSource.TITLE -> R.string.stats_page_source_title
            UsageSource.SUGGESTION -> R.string.stats_page_source_suggestion
            UsageSource.TRANSLATION -> R.string.stats_page_source_translation
            UsageSource.MEMORY -> R.string.stats_page_source_memory
            UsageSource.OCR -> R.string.stats_page_source_ocr
        }
    )
}

private val SourceUsage.totalTokens get() = promptTokens + completionTokens

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String,
) {
    Card(modifier = modifier, colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// 坐标轴上限取 (1, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10) × 10^n 里不小于最大值的那个，刻度整齐又不会空出太多
private fun niceCeil(value: Long): Long {
    if (value <= 0) return 0
    var magnitude = 1L
    while (magnitude * 10 <= value) magnitude *= 10
    return listOf(10L, 15L, 20L, 25L, 30L, 40L, 50L, 60L, 80L, 100L)
        .map { it * magnitude / 10 }
        .first { it >= value }
}

// 刻度用的紧凑数字，去掉末尾多余的 0（500K、2.5M 而不是 500.0K、2.50M）
private fun formatAxis(count: Long): String {
    val (value, unit) = when {
        count >= 1_000_000_000 -> count / 1_000_000_000.0 to "B"
        count >= 1_000_000 -> count / 1_000_000.0 to "M"
        count >= 1_000 -> count / 1_000.0 to "K"
        else -> count.toDouble() to ""
    }
    return BigDecimal(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + unit
}

private fun formatCount(count: Long): String = when {
    count >= 1_000_000 -> "%.1fM".format(count / 1_000_000.0)
    count >= 1_000 -> "%.1fK".format(count / 1_000.0)
    else -> count.toString()
}

private fun formatTokens(count: Long): String = when {
    count >= 1_000_000_000 -> "%.2fB".format(count / 1_000_000_000.0)
    count >= 1_000_000 -> "%.2fM".format(count / 1_000_000.0)
    count >= 1_000 -> "%.1fK".format(count / 1_000.0)
    else -> count.toString()
}
