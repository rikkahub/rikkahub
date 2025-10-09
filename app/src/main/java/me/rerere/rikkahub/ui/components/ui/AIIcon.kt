package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.svg.css
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.hooks.rememberAvatarShape
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.utils.toCssHex

@Composable
private fun AIIcon(
    path: String,
    name: String,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    color: Color = MaterialTheme.colorScheme.secondaryContainer,
) {
    val contentColor = LocalContentColor.current
    val context = LocalContext.current
    val model = remember(path, contentColor, context) {
        ImageRequest.Builder(context)
            .data("file:///android_asset/icons/$path")
            .css(
                """
                svg {
                  fill: ${contentColor.toCssHex()};
                }
            """.trimIndent()
            )
            .build()
    }
    Surface(
        modifier = modifier.size(24.dp),
        shape = rememberAvatarShape(loading),
        color = color,
    ) {
        AsyncImage(
            model = model,
            contentDescription = name,
            modifier = Modifier.padding(4.dp)
        )
    }
}

@Composable
fun AutoAIIcon(
    name: String,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    color: Color = MaterialTheme.colorScheme.secondaryContainer,
) {
    val path = remember(name) { computeAIIconByName(name) } ?: run {
        TextAvatar(text = name, modifier = modifier, loading = loading, color = color)
        return
    }
    AIIcon(
        path = path,
        name = name,
        modifier = modifier,
        loading = loading,
        color = color,
    )
}

@Preview
@Composable
private fun PreviewAutoAIIcon() {
    Column {
        AutoAIIcon("测试")
    }
}

// https://lobehub.com/zh/icons
private fun computeAIIconByName(name: String): String? {
    // 检查缓存
    ICON_CACHE[name]?.let { return it }

    val lowerName = name.lowercase()
    val result = when {
        PATTERN_OPENAI.containsMatchIn(lowerName) -> "openai.svg"
        PATTERN_GEMINI.containsMatchIn(lowerName) -> "gemini-color.svg"
        PATTERN_GOOGLE.containsMatchIn(lowerName) -> "google-color.svg"
        PATTERN_CLAUDE.containsMatchIn(lowerName) -> "claude-color.svg"
        PATTERN_ANTHROPIC.containsMatchIn(lowerName) -> "anthropic.svg"
        PATTERN_DEEPSEEK.containsMatchIn(lowerName) -> "deepseek-color.svg"
        PATTERN_GROK.containsMatchIn(lowerName) -> "grok.svg"
        PATTERN_QWEN.containsMatchIn(lowerName) -> "qwen-color.svg"
        PATTERN_DOUBAO.containsMatchIn(lowerName) -> "doubao-color.svg"
        PATTERN_OPENROUTER.containsMatchIn(lowerName) -> "openrouter.svg"
        PATTERN_ZHIPU.containsMatchIn(lowerName) -> "zhipu-color.svg"
        PATTERN_MISTRAL.containsMatchIn(lowerName) -> "mistral-color.svg"
        PATTERN_META.containsMatchIn(lowerName) -> "meta-color.svg"
        PATTERN_HUNYUAN.containsMatchIn(lowerName) -> "hunyuan-color.svg"
        PATTERN_GEMMA.containsMatchIn(lowerName) -> "gemma-color.svg"
        PATTERN_PERPLEXITY.containsMatchIn(lowerName) -> "perplexity-color.svg"
        PATTERN_ALIYUN.containsMatchIn(lowerName) -> "alibabacloud-color.svg"
        PATTERN_BYTEDANCE.containsMatchIn(lowerName) -> "bytedance-color.svg"
        PATTERN_SILLICON_CLOUD.containsMatchIn(lowerName) -> "siliconflow.svg"
        PATTERN_AIHUBMIX.containsMatchIn(lowerName) -> "aihubmix-color.svg"
        PATTERN_OLLAMA.containsMatchIn(lowerName) -> "ollama.svg"
        PATTERN_GITHUB.containsMatchIn(lowerName) -> "github.svg"
        PATTERN_CLOUDFLARE.containsMatchIn(lowerName) -> "cloudflare-color.svg"
        PATTERN_MINIMAX.containsMatchIn(lowerName) -> "minimax-color.svg"
        PATTERN_XAI.containsMatchIn(lowerName) -> "xai.svg"
        PATTERN_JUHENEXT.containsMatchIn(lowerName) -> "juhenext.png"
        PATTERN_KIMI.containsMatchIn(lowerName) -> "kimi-color.svg"
        PATTERN_MOONSHOT.containsMatchIn(lowerName) -> "moonshot.svg"
        PATTERN_302.containsMatchIn(lowerName) -> "302ai.svg"
        PATTERN_STEP.containsMatchIn(lowerName) -> "stepfun-color.svg"
        PATTERN_INTERN.containsMatchIn(lowerName) -> "internlm-color.svg"
        PATTERN_COHERE.containsMatchIn(lowerName) -> "cohere-color.svg"
        PATTERN_TAVERN.containsMatchIn(lowerName) -> "tavern.png"
        PATTERN_CEREBRAS.containsMatchIn(lowerName) -> "cerebras-color.svg"
        PATTERN_NVIDIA.containsMatchIn(lowerName) -> "nvidia-color.svg"
        PATTERN_PPIO.containsMatchIn(lowerName) -> "ppio-color.svg"
        PATTERN_VERCEL.containsMatchIn(lowerName) -> "vercel.svg"
        PATTERN_GROQ.containsMatchIn(lowerName) -> "groq.svg"
        PATTERN_TOKENPONY.containsMatchIn(lowerName) -> "tokenpony.svg"
        PATTERN_LING.containsMatchIn(lowerName) -> "ling.png"

        PATTERN_SEARCH_LINKUP.containsMatchIn(lowerName) -> "linkup.png"
        PATTERN_SEARCH_BING.containsMatchIn(lowerName) -> "bing.png"
        PATTERN_SEARCH_TAVILY.containsMatchIn(lowerName) -> "tavily.png"
        PATTERN_SEARCH_EXA.containsMatchIn(lowerName) -> "exa.png"
        PATTERN_SEARCH_BRAVE.containsMatchIn(lowerName) -> "brave.svg"
        PATTERN_SEARCH_METASO.containsMatchIn(lowerName) -> "metaso.svg"
        PATTERN_SEARCH_FIRECRAWL.containsMatchIn(lowerName) -> "firecrawl.svg"
        PATTERN_SEARCH_JINA.containsMatchIn(lowerName) -> "jina.svg"

        else -> null
    }

    // 保存到缓存
    result?.let { ICON_CACHE[name] = it }

    return result
}

// 静态缓存和正则模式
private val ICON_CACHE = mutableMapOf<String, String>()
private val PATTERN_OPENAI = Regex("(gpt|openai|o\\d)")
private val PATTERN_GEMINI = Regex("(gemini)")
private val PATTERN_GOOGLE = Regex("google")
private val PATTERN_ANTHROPIC = Regex("anthropic")
private val PATTERN_CLAUDE = Regex("claude")
private val PATTERN_DEEPSEEK = Regex("deepseek")
private val PATTERN_GROK = Regex("grok")
private val PATTERN_QWEN = Regex("qwen|qwq|qvq")
private val PATTERN_DOUBAO = Regex("doubao")
private val PATTERN_OPENROUTER = Regex("openrouter")
private val PATTERN_ZHIPU = Regex("zhipu|智谱|glm")
private val PATTERN_MISTRAL = Regex("mistral")
private val PATTERN_META = Regex("meta\\b|(?<!o)llama")
private val PATTERN_HUNYUAN = Regex("hunyuan|tencent")
private val PATTERN_GEMMA = Regex("gemma")
private val PATTERN_PERPLEXITY = Regex("perplexity")
private val PATTERN_BYTEDANCE = Regex("bytedance|火山")
private val PATTERN_ALIYUN = Regex("aliyun|阿里云|百炼")
private val PATTERN_SILLICON_CLOUD = Regex("silicon|硅基")
private val PATTERN_AIHUBMIX = Regex("aihubmix")
private val PATTERN_OLLAMA = Regex("ollama")
private val PATTERN_GITHUB = Regex("github")
private val PATTERN_CLOUDFLARE = Regex("cloudflare")
private val PATTERN_MINIMAX = Regex("minimax")
private val PATTERN_XAI = Regex("xai")
private val PATTERN_JUHENEXT = Regex("juhenext")
private val PATTERN_KIMI = Regex("kimi")
private val PATTERN_MOONSHOT = Regex("moonshot|月之暗面")
private val PATTERN_302 = Regex("302")
private val PATTERN_STEP = Regex("step|阶跃")
private val PATTERN_INTERN = Regex("intern|书生")
private val PATTERN_COHERE = Regex("cohere|command-.+")
private val PATTERN_TAVERN = Regex("tavern")
private val PATTERN_CEREBRAS = Regex("cerebras")
private val PATTERN_NVIDIA = Regex("nvidia")
private val PATTERN_PPIO = Regex("ppio|派欧")
private val PATTERN_VERCEL = Regex("vercel")
private val PATTERN_GROQ = Regex("groq")
private val PATTERN_TOKENPONY = Regex("tokenpony|小马算力")
private val PATTERN_LING = Regex("ling|ring|百灵")

private val PATTERN_SEARCH_LINKUP = Regex("linkup")
private val PATTERN_SEARCH_BING = Regex("bing")
private val PATTERN_SEARCH_TAVILY = Regex("tavily")
private val PATTERN_SEARCH_EXA = Regex("exa")
private val PATTERN_SEARCH_BRAVE = Regex("brave")
private val PATTERN_SEARCH_METASO = Regex("metaso|秘塔")
private val PATTERN_SEARCH_FIRECRAWL = Regex("firecrawl")
private val PATTERN_SEARCH_JINA = Regex("jina")

@Composable
fun SiliconFlowPowerByIcon(modifier: Modifier = Modifier) {
    val darkMode = LocalDarkMode.current
    if (!darkMode) {
        AsyncImage(model = R.drawable.siliconflow_light, contentDescription = null, modifier = modifier)
    } else {
        AsyncImage(model = R.drawable.siliconflow_dark, contentDescription = null, modifier = modifier)
    }
}
