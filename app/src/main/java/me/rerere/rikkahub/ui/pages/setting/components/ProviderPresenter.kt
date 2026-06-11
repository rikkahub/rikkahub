package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import kotlin.uuid.Uuid

private val RIKKAHUB = Uuid.parse("a8d2d463-e8c0-41f2-b89e-f5eb8e716cce")
private val AIHUBMIX = Uuid.parse("1b1395ed-b702-4aeb-8bc1-b681c4456953")
private val SILICON_FLOW = Uuid.parse("56a94d29-c88b-41c5-8e09-38a7612d6cf8")
private val TOKENPONY = Uuid.parse("da020a90-f7b3-4c29-b90e-c511a0630630")
private val AI302 = Uuid.parse("da93779f-3956-48cc-82ef-67bb482eaaf7")
private val ACKAI = Uuid.parse("53027b08-1b58-43d5-90ed-29173203e3d8")

private val PROVIDER_DESCRIPTIONS: Map<Uuid, @Composable () -> Unit> = mapOf(
    RIKKAHUB to {
        Text(stringResource(R.string.rikkahub_provider_description))
    },
    AIHUBMIX to {
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
    SILICON_FLOW to {
        MarkdownBlock(
            content = """
                ${stringResource(R.string.silicon_flow_description)}
                ${stringResource(R.string.silicon_flow_website)}
            """.trimIndent()
        )
    },
    TOKENPONY to {
        MarkdownBlock(
            content = """
                小马算力是一家提供国产模型的API网关服务，使用统一接口接入多种模型
                官网: [tokenpony.cn](https://www.tokenpony.cn/79clb)
            """.trimIndent()
        )
    },
    AI302 to {
        Text(
            text = buildAnnotatedString {
                append("企业级AI服务, 官网：")
                withLink(LinkAnnotation.Url("https://302.ai/")) {
                    withStyle(SpanStyle(MaterialTheme.colorScheme.primary)) {
                        append("https://302.ai/")
                    }
                }
            }
        )
    },
    ACKAI to {
        Text(
            text = buildAnnotatedString {
                append(
                    "所有AI大模型全都可以用！无需翻墙！价格是官方5折！\n" +
                        "官网："
                )
                withLink(LinkAnnotation.Url("https://ackai.fun/register?aff=jxpP")) {
                    withStyle(SpanStyle(MaterialTheme.colorScheme.primary)) {
                        append("https://ackai.fun")
                    }
                }
            }
        )
    },
)

private val PROVIDER_SHORT_DESCRIPTIONS: Map<Uuid, @Composable () -> Unit> = mapOf(
    AIHUBMIX to {
        Text(
            text = "支持gpt, claude, gemini等200+模型"
        )
    },
)

@Composable
fun ProviderDescription(provider: ProviderSetting) {
    PROVIDER_DESCRIPTIONS[provider.id]?.invoke()
}

@Composable
fun ProviderShortDescription(provider: ProviderSetting) {
    PROVIDER_SHORT_DESCRIPTIONS[provider.id]?.invoke()
}
