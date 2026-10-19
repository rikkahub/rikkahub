package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderQuota
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.UsageWindow
import me.rerere.ai.provider.supportsQuota
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.DashboardSpeed02
import me.rerere.hugeicons.stroke.MoneyBag02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.utils.SimpleCache
import me.rerere.rikkahub.utils.toDp
import org.koin.compose.koinInject
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

private val cache = SimpleCache.builder<String, ProviderQuota>()
    .expireAfterWrite(2, TimeUnit.MINUTES)
    .build()

/**
 * 查询提供商的额度，结果缓存 2 分钟，失败不缓存。返回 null 表示还在查询。
 *
 * 调用前先用 [supportsQuota] 判断有没有额度可查。
 */
@Composable
fun rememberProviderQuota(providerSetting: ProviderSetting): Result<ProviderQuota>? {
    val providerManager = koinInject<ProviderManager>()
    // 查询配置或登录的账号变了要重新查
    val cacheKey = "${providerSetting.id}," + when (providerSetting) {
        is ProviderSetting.ApiKey -> providerSetting.quotaQuery.hashCode()
        is ProviderSetting.Account -> providerSetting.credential?.accountId
    }
    val quota by produceState(
        initialValue = cache.getIfPresent(cacheKey)?.let { Result.success(it) },
        cacheKey,
    ) {
        val cached = cache.getIfPresent(cacheKey)
        if (cached != null) {
            value = Result.success(cached)
            return@produceState
        }
        value = null
        value = runCatching {
            providerManager.getProviderByType(providerSetting).getQuota(providerSetting)
                ?: error("Quota is not available")
        }.onSuccess { cache.put(cacheKey, it) }
    }
    return quota
}

/** 一行显示额度，用在模型列表的提供商标题和设置项的副标题上 */
@Composable
fun ProviderQuotaText(
    providerSetting: ProviderSetting,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified
) {
    if (!providerSetting.supportsQuota) return

    val result = rememberProviderQuota(providerSetting)
    val quota = result?.getOrNull()
    val text = when {
        result == null -> "~"
        quota == null -> "Error: ${result.exceptionOrNull()?.message}"
        else -> when (quota) {
            is ProviderQuota.Balance -> quota.value
            is ProviderQuota.UsageLimits -> quota.windows.joinToString(" · ") { window ->
                "${window.shortLabel()} ${window.usedPercent.roundToInt()}%".trim()
            }
        }
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = if (quota is ProviderQuota.UsageLimits) HugeIcons.DashboardSpeed02 else HugeIcons.MoneyBag02,
            contentDescription = null,
            modifier = Modifier.size(style.fontSize.toDp()),
            tint = color.takeOrElse { LocalContentColor.current }
        )
        Text(
            text = text,
            style = style,
            maxLines = 1,
            color = color
        )
    }
}

private enum class WindowUnit(val minutes: Int, val suffix: String) {
    WEEK(7 * 24 * 60, "w"),
    DAY(24 * 60, "d"),
    HOUR(60, "h"),
    MINUTE(1, "m"),
}

// 按能整除的最大单位拆分窗口长度：300 -> 5 小时，10080 -> 1 周
private fun Int.toWindowSpan(): Pair<Int, WindowUnit> {
    val unit = WindowUnit.entries.first { this % it.minutes == 0 }
    return this / unit.minutes to unit
}

// 300 -> 5h，10080 -> 1w，未知时为空
internal fun UsageWindow.shortLabel(): String {
    val (count, unit) = windowMinutes?.toWindowSpan() ?: return ""
    return "$count${unit.suffix}"
}

// 5 小时 -> 5-hour limit，一周 -> Weekly limit
@Composable
internal fun UsageWindow.longLabel(): String {
    val (count, unit) = windowMinutes?.toWindowSpan()
        ?: return stringResource(R.string.setting_provider_page_usage_limit)
    return when (unit) {
        WindowUnit.WEEK -> if (count == 1) {
            stringResource(R.string.setting_provider_page_usage_limit_weekly)
        } else {
            stringResource(R.string.setting_provider_page_usage_limit_weeks, count)
        }

        WindowUnit.DAY -> if (count == 1) {
            stringResource(R.string.setting_provider_page_usage_limit_daily)
        } else {
            stringResource(R.string.setting_provider_page_usage_limit_days, count)
        }

        WindowUnit.HOUR -> stringResource(R.string.setting_provider_page_usage_limit_hours, count)
        WindowUnit.MINUTE -> stringResource(R.string.setting_provider_page_usage_limit_minutes, count)
    }
}
