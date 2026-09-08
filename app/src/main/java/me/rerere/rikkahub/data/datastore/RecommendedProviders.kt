package me.rerere.rikkahub.data.datastore

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import kotlin.uuid.Uuid

/**
 * 推荐的提供商列表，在提供商设置页右上角的推荐 Sheet 中展示。
 */
val RECOMMENDED_PROVIDERS: List<ProviderSetting> = listOf(
    ProviderSetting.OpenAI(
        id = Uuid.parse("1b1395ed-b702-4aeb-8bc1-b681c4456953"),
        name = "AiHubMix",
        baseUrl = "https://aihubmix.com/v1",
        apiKey = "",
        enabled = true,
        description = {
            Text(
                text = buildAnnotatedString {
                    append("提供 OpenAI、Claude、Google Gemini 等主流模型的高并发和稳定服务")
                    appendLine()
                    append("官网：")
                    withLink(LinkAnnotation.Url("https://aihubmix.com?aff=pG7r")) {
                        withStyle(SpanStyle(MaterialTheme.colorScheme.primary)) {
                            append("https://aihubmix.com")
                        }
                    }
                    appendLine()
                    append("充值: ")
                    withLink(LinkAnnotation.Url("https://console.aihubmix.com/topup")) {
                        withStyle(SpanStyle(MaterialTheme.colorScheme.primary)) {
                            append("https://console.aihubmix.com/topup")
                        }
                    }
                }
            )
        },
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("aecf04fd-cb5c-4582-aed2-e8bf393923fd"),
        name = "随想AI网关",
        baseUrl = "https://sui-xiang.com/v1",
        apiKey = "",
        enabled = true,
        description = {
            Text(
                text = buildAnnotatedString {
                    append("可靠高效的 API 中继服务，提供 Claude、Codex、Gemini 等中继服务。注重隐私·无数据倒卖·无模型掺水，充值额度 1:1，按量付费。多线路冗余、跨区域容灾、自动故障切换，长链路 SSE 不中断。")
                    appendLine()
                    append("官网：")
                    withLink(LinkAnnotation.Url("https://sui-xiang.com")) {
                        withStyle(SpanStyle(MaterialTheme.colorScheme.primary)) {
                            append("https://sui-xiang.com")
                        }
                    }
                }
            )
        },
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("afbc54ad-807e-4455-9594-7d7a546356ad"),
        name = "MaruCode",
        baseUrl = "https://api.muteki.site/v1",
        apiKey = "",
        enabled = true,
        description = {
            Text(
                text = buildAnnotatedString {
                    append("MaruCode 是一家偶尔做做慈善的小破站 API，自营号池，主要提供 Codex、Claude Code、GPT Image 等主流模型，支持 Websocket 协议，明码标价(Codex 0.25x, CC 1.5x)，透明汇率(1:1)。")
                    appendLine()
                    withLink(LinkAnnotation.Url("https://api.muteki.site/register?aff=Rikkahub&promo=Rikkahub")) {
                        withStyle(SpanStyle(MaterialTheme.colorScheme.primary)) {
                            append("新用户注册送 2 刀")
                        }
                    }
                    appendLine()
                    withLink(LinkAnnotation.Url("https://images-2.muteki.site")) {
                        withStyle(SpanStyle(MaterialTheme.colorScheme.primary)) {
                            append("生图工作台🖼️")
                        }
                    }
                }
            )
        },
        useResponseApi = true,
    ),
)
