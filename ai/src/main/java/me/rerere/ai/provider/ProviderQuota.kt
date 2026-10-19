package me.rerere.ai.provider

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * 提供商的额度状态，由 [Provider.getQuota] 返回。
 *
 * 按语义而不是按提供商分类型，界面按类型分别展示；新的提供商优先复用已有类型。
 */
sealed interface ProviderQuota {
    /** 余额。用户自定义查询（[QuotaQuery]）的结果都归到这里 */
    data class Balance(val value: String) : ProviderQuota

    /** 订阅制的用量限制，按时间窗口统计已用比例 */
    data class UsageLimits(
        val windows: List<UsageWindow>,
        // 已经触发限额，暂时不能继续使用
        val limitReached: Boolean,
    ) : ProviderQuota
}

data class UsageWindow(
    // 0~100
    val usedPercent: Double,
    // 窗口长度，未知时为 null
    val windowMinutes: Int?,
    val resetsAt: Instant?,
)

/**
 * 用户自定义的额度查询，只有 API Key 类提供商有。账号类的查询写死在各自的 Provider 里，
 * 不让用户配置，账号凭据就不会被发往自定义地址。
 *
 * [url] 和 [headers] 的值可以引用 [VARIABLES] 里的变量，写作 `{{apiKey}}`。
 * 查询结果按 [resultPath] 从响应 JSON 里取值，表达式语法见 `evaluateJsonExpr`。
 */
@Serializable
data class QuotaQuery(
    val enabled: Boolean = false,
    val url: String = "{{baseUrl}}/credits",
    // 鉴权头也在这里配置，默认值按提供商类型给
    val headers: List<CustomHeader> = listOf(CustomHeader("Authorization", "Bearer {{apiKey}}")),
    val resultPath: String = "data.total_usage",
) {
    companion object {
        const val VARIABLE_BASE_URL = "baseUrl"

        // 填了多个 key 时取其中一个，和对话请求一样轮换
        const val VARIABLE_API_KEY = "apiKey"

        val VARIABLES = listOf(VARIABLE_BASE_URL, VARIABLE_API_KEY)
    }
}

/** 是否有额度可查，界面据此决定要不要显示 */
val ProviderSetting.supportsQuota: Boolean
    get() = when (this) {
        is ProviderSetting.ApiKey -> quotaQuery.enabled
        is ProviderSetting.Account -> credential != null
    }
