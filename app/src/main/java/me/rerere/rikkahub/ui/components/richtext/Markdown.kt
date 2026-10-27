package me.rerere.rikkahub.ui.components.richtext

import android.content.ClipData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Download04
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalWorkspaceId
import me.rerere.rikkahub.ui.modifier.onClick
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.bettertext.latex.LatexInline
import me.rerere.bettertext.markdown.MarkdownBlock
import me.rerere.bettertext.markdown.MarkdownComponents
import me.rerere.bettertext.markdown.MarkdownInline
import me.rerere.bettertext.markdown.plainText
import me.rerere.bettertext.text.BetterInline
import me.rerere.bettertext.text.ComposableInline
import me.rerere.bettertext.text.InlineAlign
import org.koin.compose.koinInject
import kotlin.time.Clock

@Preview(showBackground = true)
@Composable
private fun MarkdownPreview() {
    MaterialTheme {
        CompositionLocalProvider(LocalSettings provides Settings()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                MarkdownBlock(
                    content = "Hi there!", modifier = Modifier.background(Color.Red)
                )
                MarkdownBlock(
                    content = """
                    ### 🌍 This is Markdown Test This Markdown Test
                    1. How many roads must a man walk down
                        * the slings and arrows of outrageous fortune, Or to take arms against a sea of troubles,
                        * by opposing end them.
                            * How many times must a man look up, Before he can see the sky?
                            * How many times $ f(x) = \sum_{n=0}^{\infty} \frac{f^{(n)}(a)}{n!}(x-a)^n$
                    2. How many times must a man look up, Before he can see the sky?

                    * [ ] Before they're allowed to be free? Yes, 'n' how many times can a man turn his head
                    * [x] Before they're allowed to be free? Yes, 'n' how many times can a man turn his head

                    4. For in that sleep of death what dreams may come [citation](1)

                    This is Markdown Test, This <br/> is Markdown Test.
                    ha<br/>ha

                    ***
                    This is Markdown Test, This is Markdown Test.

                    | Name | Age | Address | Email | Job | Homepage |
                    | ---- | --- | ------- | ----- | --- | -------- |
                    | John | 25  | New York | john@example.com | Software Engineer | john.com |
                    | Jane | 26  | London   | jane@example.com | Data Scientist | jane.com |

                    ## HTML Escaping
                    This is a &gt;  test
                """.trimIndent()
                )
            }
        }
    }
}

/**
 * 应用内使用的 Markdown：在通用的 [Markdown] 组件上接入代码高亮、图片预览、LaTeX 渲染、
 * 表格工具栏和搜索引用角标这些和应用相关的能力。
 *
 * @param streaming 内容还在生成时为 true，末尾没写完的语法（如 `**加粗`、`$\frac{`）会被补全或隐藏
 */
@Composable
fun MarkdownBlock(
    content: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    streaming: Boolean = false,
    onClickCitation: (String) -> Unit = {}
) {
    val enableLatexRendering = LocalSettings.current.displaySetting.enableLatexRendering
    val enableQuoteHighlight = LocalSettings.current.displaySetting.enableQuoteHighlight
    val fontSize = LocalTextStyle.current.merge(style).fontSize
    val density = LocalDensity.current
    val quoteColor = MaterialTheme.colorScheme.tertiary
    val defaultTheme = MaterialMarkdownDefaults.theme(style)
    val theme = remember(defaultTheme, fontSize, density, enableQuoteHighlight, quoteColor) {
        defaultTheme.copy(
            inlineCode = defaultTheme.inlineCode.copy(
                fontFamily = JetbrainsMono,
                fontFeatureSettings = "'calt' 0, 'liga' 0, 'clig' 0",
            ),
            // 段落间距跟随字号，调整字体大小时保持比例
            blockSpacing = if (fontSize.isSp) with(density) { fontSize.toDp() } else defaultTheme.blockSpacing,
            quotedText = SpanStyle(color = quoteColor).takeIf { enableQuoteHighlight },
        )
    }

    val currentOnClickCitation by rememberUpdatedState(onClickCitation)
    val components = remember(enableLatexRendering) {
        MarkdownComponents(
            codeBlock = { block ->
                HighlightCodeBlock(
                    code = block.code,
                    language = block.language ?: "plaintext",
                    modifier = Modifier.fillMaxWidth(),
                    completeCodeBlock = block.closed,
                )
            },
            image = { image, _ ->
                ZoomableAsyncImage(
                    model = rememberWorkspaceImageUrl(image.url),
                    contentDescription = image.alt,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .widthIn(min = 120.dp)
                        .heightIn(min = 120.dp),
                )
            },
            // 禁用 LaTeX 渲染时不注册公式组件，公式以等宽字体显示源码
            mathBlock = if (enableLatexRendering) {
                { formula -> MathBlock(latex = formula, modifier = Modifier.fillMaxWidth()) }
            } else {
                null
            },
            table = { table -> TableCard(table) },
            details = MaterialMarkdownDefaults.components.details,
            inlineMath = if (enableLatexRendering) {
                { formula -> LatexInline.cached(formula, context) }
            } else {
                null
            },
            inlineLink = { link -> citationWidget(link) { currentOnClickCitation(it) } },
        )
    }

    MaterialMarkdown(
        content = content,
        modifier = modifier.padding(horizontal = 4.dp),
        style = style,
        theme = theme,
        components = components,
        streaming = streaming,
    )
}

/**
 * 绑定了工作区时, 把 Rootfs 绝对路径 (如 /workspace/out.png) 解析成宿主机上的文件路径;
 * 解析期间返回 null 显示占位图, 找不到文件时原样返回交给 Coil
 *
 * Markdown 解析器会对 src 做百分号编码 (空格、中文等), 需取解码后的 path 并丢掉 query/fragment
 */
@Composable
private fun rememberWorkspaceImageUrl(url: String): String? {
    val workspaceId = LocalWorkspaceId.current
    if (workspaceId == null || !url.startsWith("/") || url.startsWith("//")) return url
    val path = remember(url) { url.toUri().path } ?: return url
    val workspaceRepository: WorkspaceRepository = koinInject()
    val resolved by produceState<String?>(initialValue = null, workspaceId, path) {
        value = workspaceRepository.resolveAbsoluteFile(workspaceId, path)?.absolutePath ?: url
    }
    return resolved
}

// 搜索引用在正文里写作 [citation,域名](引用id)，渲染成可点击的角标
private fun citationWidget(link: MarkdownInline.Link, onClick: (String) -> Unit): BetterInline? {
    val text = link.children.plainText()
    if (!text.startsWith("citation,")) return null
    val domain = text.substringAfter("citation,")
    val id = link.destination
    if (id.length != 6) {
        // id 不合法的引用没法跳转，不显示
        return ComposableInline {}
    }
    return ComposableInline(align = InlineAlign.Center) {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .clickable { onClick(id.trim()) }
                .background(MaterialTheme.colorScheme.tertiaryContainer)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = domain,
                style = TextStyle(
                    fontSize = 10.sp,
                    lineHeight = 10.sp,
                    fontFamily = JetbrainsMono,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
    }
}

// 表格卡片（工具栏 + 表格）
@Composable
private fun TableCard(table: MarkdownBlock.Table) {
    val clipboardManager = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val tableCsv = remember(table) { buildTableCsv(table) }
    // HTML 写出的表格没有 Markdown 源码，复制时退回到 CSV
    val tableText = table.source ?: tableCsv

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri?.let {
            scope.launch {
                try {
                    context.contentResolver.openOutputStream(it)?.use { outputStream ->
                        outputStream.write(tableCsv.toByteArray())
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "表格",
                fontSize = 12.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val iconSize = 16.dp
                val iconTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)

                Icon(
                    imageVector = HugeIcons.Copy01,
                    contentDescription = "Copy",
                    tint = iconTint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .onClick {
                            scope.launch {
                                clipboardManager.setClipEntry(ClipEntry(ClipData.newPlainText("table", tableText)))
                            }
                        }
                        .padding(4.dp)
                        .size(iconSize)
                )

                Icon(
                    imageVector = HugeIcons.Download04,
                    contentDescription = "Download",
                    tint = iconTint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .onClick {
                            createDocumentLauncher.launch(
                                "table_${
                                    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                                }.csv"
                            )
                        }
                        .padding(4.dp)
                        .size(iconSize)
                )
            }
        }
        MaterialMarkdownDefaults.Table(
            table = table,
            outerBorder = null,
            shape = RectangleShape,
        )
    }
}

// 构建CSV内容，对包含逗号/引号/换行的字段进行转义
private fun buildTableCsv(table: MarkdownBlock.Table): String {
    fun escape(field: String): String {
        return if (field.any { it == ',' || it == '"' || it == '\n' }) {
            "\"${field.replace("\"", "\"\"")}\""
        } else {
            field
        }
    }
    return buildString {
        appendLine(table.header.joinToString(",") { escape(it.content.plainText()) })
        table.rows.forEach { row ->
            appendLine(row.joinToString(",") { escape(it.content.plainText()) })
        }
    }
}
