package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.takeOrElse
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import org.koin.compose.koinInject
import java.io.File
import java.security.MessageDigest

private val DIAGRAM_PATTERNS = listOf(
    Regex("""\\begin\{tikz\w*\}"""),   // 前缀通配：tikzpicture/tikzcd/tikztiming...
    Regex("""\\begin\{axis\}"""),          // pgfplots
    Regex("""\\xymatrix\{"""),
    Regex("""\\begin\{CD\}"""),
)

private fun isDiagramLatex(latex: String): Boolean =
    DIAGRAM_PATTERNS.any { it.containsMatchIn(latex) }

/** 去掉 Markdown 块公式的 $$ / \[...\] 外层包裹（对齐 LatexText.processLatex 行为） */
private fun stripDollarWrappers(latex: String): String {
    val t = latex.trim()
    return when {
        t.startsWith("$$") && t.endsWith("$$") && t.length > 4 -> t.substring(2, t.length - 2).trim()
        t.startsWith("\\[") && t.endsWith("\\]") && t.length > 4 -> t.substring(2, t.length - 2).trim()
        else -> t
    }
}

private fun latexKey(latex: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(latex.toByteArray())
        .joinToString("") { "%02x".format(it) }

private sealed class DiagramRenderState {
    data object Idle : DiagramRenderState()
    data object Rendering : DiagramRenderState()
    data class Ready(val svg: String) : DiagramRenderState()
    data class Failed(val reason: String) : DiagramRenderState()
}

private val renderCache = LatexRenderCache()

@Composable
fun MathInline(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified
) {
    LatexText(
        latex = latex,
        color = LocalContentColor.current,
        fontSize = fontSize.takeOrElse { LocalTextStyle.current.fontSize },
        modifier = modifier,
    )
}

@Composable
fun MathBlock(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified
) {
    val settings = LocalSettings.current.displaySetting
    val context = LocalContext.current
    val repo: WorkspaceRepository = koinInject()
    // 跟随应用内"通用设置-颜色模式"（LocalDarkMode 由 RouteActivity 按 colorMode 计算），
    // 与 HighlightCodeBlock/Mermaid 等组件一致；不能用 isSystemInDarkTheme()（只反映系统主题，
    // 应用内设浅色+系统深色时会错误地按深色渲染出白图）
    val isDark = LocalDarkMode.current

    val enableRendering = settings.enableDiagramRendering
    val isDiagram = remember(latex) { isDiagramLatex(latex) }
    // 颜色在 LaTeX 源头注入（\color{white}/\color{black}），亮/暗模式各存一份缓存
    val key = remember(latex, isDark) { "${if (isDark) "d" else "l"}_${latexKey(latex)}" }

    var renderState by remember { mutableStateOf<DiagramRenderState>(DiagramRenderState.Idle) }
    val latestLatex by rememberUpdatedState(latex)

    LaunchedEffect(enableRendering, isDiagram, latex) {
        if (!enableRendering || !isDiagram) {
            renderState = DiagramRenderState.Idle
            return@LaunchedEffect
        }

        // 1. 内存缓存
        renderCache.get(key)?.let {
            renderState = DiagramRenderState.Ready(it)
            return@LaunchedEffect
        }

        // 2. 磁盘缓存（IO 线程，避免主线程磁盘读）
        val diskFile = File(context.filesDir, "latex_renders/$key.svg")
        val cached = withContext(Dispatchers.IO) {
            if (diskFile.exists()) diskFile.readText() else null
        }
        if (cached != null) {
            renderCache.put(key, cached)
            renderState = DiagramRenderState.Ready(cached)
            return@LaunchedEffect
        }

        renderState = DiagramRenderState.Rendering

        try {
            kotlinx.coroutines.delay(100)
            val current = latestLatex

            val svg = withContext(Dispatchers.IO) {
                if (!LatexCapability.isAvailable(context)) {
                    throw IllegalStateException("LaTeX 未安装")
                }
                DiagramRenderer.render(repo, stripDollarWrappers(current), isDark)
            }

            // 存磁盘（原始 SVG，黑色）
            withContext(Dispatchers.IO) {
                diskFile.parentFile?.mkdirs()
                diskFile.writeText(svg)
            }

            renderCache.put(key, svg)
            renderState = DiagramRenderState.Ready(svg)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            renderState = DiagramRenderState.Failed(e.message ?: "未知错误")
        }
    }

    val state = renderState
    when (state) {
        DiagramRenderState.Idle -> {
            Box(modifier = modifier.padding(8.dp)) {
                LatexText(
                    latex = latex,
                    color = LocalContentColor.current,
                    fontSize = fontSize.takeOrElse { LocalTextStyle.current.fontSize },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .horizontalScroll(rememberScrollState()),
                )
            }
        }

        is DiagramRenderState.Failed -> {
            Column(modifier = modifier.padding(8.dp)) {
                LatexText(
                    latex = latex,
                    color = LocalContentColor.current.copy(alpha = 0.5f),
                    fontSize = fontSize.takeOrElse { LocalTextStyle.current.fontSize },
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
                Text(
                    text = state.reason,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        DiagramRenderState.Rendering -> {
            Box(modifier = modifier.padding(8.dp)) {
                LatexText(
                    latex = latex,
                    color = LocalContentColor.current.copy(alpha = 0.4f),
                    fontSize = fontSize.takeOrElse { LocalTextStyle.current.fontSize },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .horizontalScroll(rememberScrollState()),
                )
            }
        }

        is DiagramRenderState.Ready -> {
            // SVG 颜色已在 LaTeX 源头按 isDark 注入（\color{white}/\color{black}），无需字符串替换
            DiagramImage(
                svg = state.svg,
                key = key,
                modifier = modifier,
                kFull = settings.diagramFontSize / 10f,
                kMin = settings.diagramMinFontSize / 10f,
            )
        }
    }
}

@Composable
private fun DiagramImage(svg: String, key: String, modifier: Modifier, kFull: Float, kMin: Float) {
    val context = LocalContext.current
    var svgFile by remember { mutableStateOf<File?>(null) }

    // IO 线程写文件，组合期不做磁盘 IO
    LaunchedEffect(svg) {
        svgFile = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "latex_svg")
            dir.mkdirs()
            File(dir, "$key.svg").apply { writeText(svg) }
        }
    }

    // SVG 逻辑尺寸（1px→1dp），与 Bitmap 渲染密度解耦，避免高 dpi 下图过大
    val size = remember(svg) { parseSvgSize(svg) }

    val file = svgFile
    if (file != null && size != null) {
        val (w, h) = size
        // LaTeX 式智能缩放：优先保字号（恒定 18dp，K=1.8），图框随内容自然调整。
        // pdftocairo 直出 1pt≈1px，TeX 10pt 字 em≈10px → K = 字号/10。
        // 宽度策略（分级）：
        //   1. w×1.8 ≤ 可用宽 → 字号 18dp 完整显示，不滚动
        //   2. 超宽 → 降级到最小字号 14dp（K=1.4）再试
        //   3. 仍超宽 → 横向滚动查看（字号保持 14dp 不再压缩）
        val maxW = (LocalConfiguration.current.screenWidthDp - 48).coerceAtLeast(240)
        // 字号由设置控制：默认 kFull（18dp → K=1.8）、最小 kMin（14dp → K=1.4）
        val scale = if (w * kFull <= maxW) kFull else kMin
        val scrollable = w * scale > maxW

        val wdp = (w * scale).dp
        val hdp = (h * scale).dp
        val finalModifier = if (scrollable) {
            modifier.horizontalScroll(rememberScrollState()).size(wdp, hdp)
        } else {
            modifier.size(wdp, hdp)
        }
        AsyncImage(
            model = file,
            contentDescription = "交换图",
            modifier = finalModifier.padding(vertical = 4.dp),
            contentScale = ContentScale.Fit,
        )
    }
}

/**
 * 从 pdftocairo 生成的 SVG 提取逻辑尺寸。
 *
 * pdftocairo 输出的 width/height 无单位（CSS 默认 px），TeX 默认 10pt 字 ≈ 13.3px。
 * 显示时按 1px→1dp 映射，与 Bitmap（scaleToDensity=true 按设备 density 渲染）解耦——
 * 修复 Coil SvgDecoder 的 Bitmap px 被 Compose 直接当 dp 布局导致的高 dpi 下
 * "小图显示 200dp+、字号 36dp+" 的图过大问题。修复后字号统一 ≈13dp，
 * 图大小与内容量成正比（小图 75dp、大图 157dp）。
 */
private fun parseSvgSize(svg: String): Pair<Float, Float>? {
    val m = Regex("""<svg[^>]*\bwidth="([\d.]+)"[^>]*\bheight="([\d.]+)"""").find(svg)
        ?: Regex("""<svg[^>]*\bheight="([\d.]+)"[^>]*\bwidth="([\d.]+)"""").find(svg)
    return m?.let {
        val w = it.groupValues[1].toFloatOrNull()
        val h = it.groupValues[2].toFloatOrNull()
        if (w != null && h != null && w > 0f && h > 0f) w to h else null
    }
}
