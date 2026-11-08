package me.rerere.rikkahub.data.datastore

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import me.rerere.ai.provider.ProviderSetting
import kotlin.uuid.Uuid

/**
 * 推荐的提供商列表，在提供商设置页右上角的推荐 Sheet 中展示。
 *
 * 推荐 Sheet 和提供商列表展示 [ProviderSetting.shortDescription]，
 * 添加后的提供商详情页展示带链接的完整 [ProviderSetting.description]。
 */
val RECOMMENDED_PROVIDERS: List<ProviderSetting> = listOf(
    ProviderSetting.OpenAI(
        id = Uuid.parse("3de58b04-7ac6-4f25-8880-2e2638aeb1d6"),
        name = "STA1N API🍪",
        baseUrl = "https://sta1n.ai/v1",
        apiKey = "",
        enabled = true,
        description = {
            FullDescription(
                text = "主要为 RP 玩家服务的站点",
                DescriptionLink(prefix = "官网：", text = "https://sta1n.ai", url = "https://sta1n.ai"),
            )
        },
        shortDescription = {
            ShortDescription("主要为 RP 玩家服务的站点")
        },
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("228e2443-f3bb-4222-970c-2c800aff514d"),
        name = "钠API",
        baseUrl = "https://naapi.cc/v1",
        apiKey = "",
        enabled = true,
        description = {
            FullDescription(
                text = "钠API（naapi.cc）是 OpenAI 兼容的 LLM 网关，以优惠价格提供 OpenAI、Anthropic、Google 等 300+ 模型访问",
                DescriptionLink(
                    prefix = "官网：",
                    text = "https://naapi.cc",
                    url = "https://naapi.cc/register?aff=rikkahub"
                ),
                DescriptionLink(
                    prefix = "充值：",
                    text = "https://naapi.cc/console/topup",
                    url = "https://naapi.cc/console/topup"
                ),
            )
        },
        shortDescription = {
            ShortDescription("OpenAI 兼容网关，优惠价提供 300+ 模型")
        },
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("2a05506f-3a59-450a-a493-33a82bc85a81"),
        name = "APIMart",
        baseUrl = "https://api.apimart.ai/v1",
        apiKey = "",
        enabled = true,
        description = {
            FullDescription(
                text = "APIMart 是专注 AI 图片/视频生成的低价 API 平台，GPT-Image-2 低至 $0.006/张，1 美元可出图 160+ 张。图片、视频一套异步 API 通吃，提交任务拿 ID、回调取结果，跑批万张不超时、换模型不改代码。按量付费、无月费。",
                DescriptionLink(text = "通过此注册链接注册即可开用", url = "https://go.apimart.ai/gh-rikkahub"),
            )
        },
        shortDescription = {
            ShortDescription("AI 图片/视频生成，GPT-Image-2 低至 $0.006/张")
        },
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("aecf04fd-cb5c-4582-aed2-e8bf393923fd"),
        name = "随想AI网关",
        baseUrl = "https://sui-xiang.com/v1",
        apiKey = "",
        enabled = true,
        description = {
            FullDescription(
                text = "可靠高效的 API 中继服务，提供 Claude、Codex、Gemini 等中继服务。注重隐私·无数据倒卖·无模型掺水，充值额度 1:1，按量付费。多线路冗余、跨区域容灾、自动故障切换，长链路 SSE 不中断。",
                DescriptionLink(prefix = "官网：", text = "https://sui-xiang.com", url = "https://sui-xiang.com"),
            )
        },
        shortDescription = {
            ShortDescription("Claude、Codex、Gemini 中继，充值 1:1，按量付费")
        },
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("afbc54ad-807e-4455-9594-7d7a546356ad"),
        name = "MaruCode",
        baseUrl = "https://api.muteki.site/v1",
        apiKey = "",
        enabled = true,
        description = {
            FullDescription(
                text = "MaruCode 是一家偶尔做做慈善的小破站 API，自营号池，主要提供 Codex、Claude Code、GPT Image 等主流模型，支持 Websocket 协议，明码标价(Codex 0.25x, CC 1.5x)，透明汇率(1:1)。",
                DescriptionLink(
                    text = "新用户注册送 2 刀",
                    url = "https://api.muteki.site/register?aff=Rikkahub&promo=Rikkahub"
                ),
                DescriptionLink(text = "生图工作台🖼️", url = "https://images-2.muteki.site"),
            )
        },
        shortDescription = {
            ShortDescription("自营号池，Codex 0.25x、CC 1.5x，汇率 1:1")
        },
        useResponseApi = true,
    ),
)

private data class DescriptionLink(
    val prefix: String = "",
    val text: String,
    val url: String,
)

@Composable
private fun FullDescription(text: String, vararg links: DescriptionLink) {
    val linkColor = MaterialTheme.colorScheme.primary
    Text(
        text = buildAnnotatedString {
            append(text)
            links.forEach { link ->
                appendLine()
                append(link.prefix)
                withLink(LinkAnnotation.Url(link.url)) {
                    withStyle(SpanStyle(linkColor)) {
                        append(link.text)
                    }
                }
            }
        }
    )
}

@Composable
private fun ShortDescription(text: String) {
    Text(
        text = text,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}
