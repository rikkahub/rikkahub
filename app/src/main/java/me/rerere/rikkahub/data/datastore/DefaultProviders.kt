package me.rerere.rikkahub.data.datastore

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import me.rerere.ai.provider.BalanceOption
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import kotlin.uuid.Uuid

val SILICONFLOW_QWEN3_8B_ID = Uuid.parse("dd82297e-4237-4d3c-85b3-58d5c7084fc2")

val DEFAULT_PROVIDERS = listOf(
    ProviderSetting.OpenAI(
        id = Uuid.parse("1eeea727-9ee5-4cae-93e6-6fb01a4d051e"),
        name = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        apiKey = "",
        builtIn = true
    ),
    ProviderSetting.Google(
        id = Uuid.parse("6ab18148-c138-4394-a46f-1cd8c8ceaa6d"),
        name = "Gemini",
        apiKey = "",
        enabled = true,
        builtIn = true
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("1b1395ed-b702-4aeb-8bc1-b681c4456953"),
        name = "AiHubMix",
        baseUrl = "https://aihubmix.com/v1",
        apiKey = "",
        enabled = true,
        builtIn = true,
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
        shortDescription = {
            Text(
                text = "支持gpt, claude, gemini等200+模型"
            )
        },
        models = listOf(
            Model(
                id = Uuid.parse("ea7b9574-e590-42ac-a9ac-01e3aa213f4f"),
                modelId = "gpt-5",
                displayName = "GPT 5",
                inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
                outputModalities = listOf(Modality.TEXT),
                abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            ),
            Model(
                id = Uuid.parse("5c33502d-2307-40bd-83fc-133f504bb0c9"),
                modelId = "claude-sonnet-4-5-20250929",
                displayName = "Claude Sonnet 4.5",
                inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
                outputModalities = listOf(Modality.TEXT),
                abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            ),
            Model(
                id = Uuid.parse("64081a31-4331-4ead-91bc-96e05497431a"),
                modelId = "DeepSeek-V3.2-Exp",
                displayName = "DeepSeek V3.2 Exp",
                inputModalities = listOf(Modality.TEXT),
                outputModalities = listOf(Modality.TEXT),
                abilities = listOf(ModelAbility.TOOL),
            ),
            Model(
                id = Uuid.parse("71d7f143-4ce1-49b5-b70d-7a6620a4e716"),
                modelId = "qwen3-max",
                displayName = "Qwen3 Max",
                inputModalities = listOf(Modality.TEXT),
                outputModalities = listOf(Modality.TEXT),
                abilities = listOf(ModelAbility.TOOL),
            ),
        )
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("56a94d29-c88b-41c5-8e09-38a7612d6cf8"),
        name = "硅基流动",
        baseUrl = "https://api.siliconflow.cn/v1",
        apiKey = "",
        builtIn = true,
        description = {
            MarkdownBlock(
                content = """
                    ${stringResource(R.string.silicon_flow_description)}
                    ${stringResource(R.string.silicon_flow_website)}

                    ${stringResource(R.string.silicon_flow_built_in_models)}
                """.trimIndent()
            )
        },
        models = listOf(
            Model(
                id = SILICONFLOW_QWEN3_8B_ID,
                modelId = "Qwen/Qwen3-8B",
                displayName = "Qwen3-8B",
                inputModalities = listOf(Modality.TEXT),
                outputModalities = listOf(Modality.TEXT),
                abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            ),
            Model(
                id = Uuid.parse("e4b836cd-6cbe-4350-b9e5-8c3b2d448b00"),
                modelId = "THUDM/GLM-4.1V-9B-Thinking",
                displayName = "GLM-4.1V-9B",
                inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
                outputModalities = listOf(Modality.TEXT),
                abilities = listOf(),
            ),
        ),
        balanceOption = BalanceOption(
            enabled = true,
            apiPath = "/user/info",
            resultPath = "data.totalBalance",
        ),
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("f099ad5b-ef03-446d-8e78-7e36787f780b"),
        name = "DeepSeek",
        baseUrl = "https://api.deepseek.com/v1",
        apiKey = "",
        builtIn = true,
        balanceOption = BalanceOption(
            enabled = true,
            apiPath = "/user/balance",
            resultPath = "balance_infos[0].total_balance"
        )
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("d5734028-d39b-4d41-9841-fd648d65440e"),
        name = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        apiKey = "",
        builtIn = true,
        balanceOption = BalanceOption(
            enabled = true,
            apiPath = "/credits",
            resultPath = "data.total_credits - data.total_usage",
        )
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("da020a90-f7b3-4c29-b90e-c511a0630630"),
        name = "小马算力",
        baseUrl = "https://api.tokenpony.cn/v1",
        apiKey = "",
        enabled = false,
        builtIn = true,
        description = {
            MarkdownBlock(
                content = """
                    小马算力是一家提供国产模型的API网关服务，使用统一接口接入多种模型
                    官网: [tokenpony.cn](https://www.tokenpony.cn/79clb)
                """.trimIndent()
            )
        }
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("f76cae46-069a-4334-ab8e-224e4979e58c"),
        name = "阿里云百炼",
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        apiKey = "",
        enabled = false,
        builtIn = true
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("3dfd6f9b-f9d9-417f-80c1-ff8d77184191"),
        name = "火山引擎",
        baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
        apiKey = "",
        enabled = false,
        builtIn = true
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("d6c4d8c6-3f62-4ca9-a6f3-7ade6b15ecc3"),
        name = "月之暗面",
        baseUrl = "https://api.moonshot.cn/v1",
        apiKey = "",
        enabled = false,
        builtIn = true,
        balanceOption = BalanceOption(
            enabled = true,
            apiPath = "/users/me/balance",
            resultPath = "data.available_balance"
        )
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("3bc40dc1-b11a-46fa-863b-6306971223be"),
        name = "智谱AI开放平台",
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        apiKey = "",
        enabled = false,
        builtIn = true
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("f4f8870e-82d3-495b-9b64-d58e508b3b2c"),
        name = "阶跃星辰",
        baseUrl = "https://api.stepfun.com/v1",
        apiKey = "",
        enabled = false,
        builtIn = true
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("89e67540-32fe-4c62-9970-2e9aed9bd59d"),
        name = "JuheNext",
        baseUrl = "https://api.juheai.top/v1",
        apiKey = "",
        enabled = false,
        builtIn = true,
        description = {
            Text(
                text = buildAnnotatedString {
                    append("一站式API中转平台, 官网：")
                    withLink(LinkAnnotation.Url("https://api.juheai.top/register?aff=qG7E")) {
                        withStyle(SpanStyle(MaterialTheme.colorScheme.primary)) {
                            append("https://api.juheai.top")
                        }
                    }
                }
            )
        }
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("da93779f-3956-48cc-82ef-67bb482eaaf7"),
        name = "302.AI",
        baseUrl = "https://api.302.ai/v1",
        apiKey = "",
        enabled = false,
        builtIn = true,
        description = {
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
        }
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("ef5d149b-8e34-404b-818c-6ec242e5c3c5"),
        name = "腾讯Hunyuan",
        baseUrl = "https://api.hunyuan.cloud.tencent.com/v1",
        apiKey = "",
        enabled = false,
        builtIn = true
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("ff3cde7e-0f65-43d7-8fb2-6475c99f5990"),
        name = "xAI",
        baseUrl = "https://api.x.ai/v1",
        apiKey = "",
        enabled = false,
        builtIn = true
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("53027b08-1b58-43d5-90ed-29173203e3d8"),
        name = "AckAI",
        baseUrl = "https://ackai.fun/v1",
        apiKey = "",
        enabled = false,
        builtIn = true,
        description = {
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
        }
    ),
)
