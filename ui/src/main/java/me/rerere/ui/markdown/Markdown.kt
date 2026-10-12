package me.rerere.ui.markdown

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull

/**
 * 渲染 Markdown 文本，支持 GFM（表格、任务列表、删除线）、LaTeX 公式和内嵌 HTML。
 *
 * 组件不依赖任何业务逻辑：代码高亮、图片加载、公式排版等通过 [components] 接入，视觉参数通过 [theme] 调整。
 * [content] 可以高频变化（流式输出），解析在后台线程进行，内容没变的块不会重组。
 *
 * @param softBreakAsLineBreak 段落内的单个换行是否渲染为换行
 * @param onLinkClick 链接点击回调，为 null 时交给系统的 UriHandler 打开
 */
@Composable
fun Markdown(
    content: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    theme: MarkdownTheme = MarkdownDefaults.theme(style),
    components: MarkdownComponents = MarkdownDefaults.components,
    softBreakAsLineBreak: Boolean = true,
    onLinkClick: ((url: String) -> Unit)? = null,
) {
    Markdown(
        document = rememberMarkdownDocument(content, softBreakAsLineBreak),
        modifier = modifier,
        style = style,
        theme = theme,
        components = components,
        onLinkClick = onLinkClick,
    )
}

/** 渲染已经解析好的 [document]，适合自己控制解析时机的场景（例如提前在后台调用 [parseMarkdown]） */
@Composable
fun Markdown(
    document: MarkdownDocument,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    theme: MarkdownTheme = MarkdownDefaults.theme(style),
    components: MarkdownComponents = MarkdownDefaults.components,
    onLinkClick: ((url: String) -> Unit)? = null,
) {
    val currentOnLinkClick by rememberUpdatedState(onLinkClick)
    val handlesLinks = onLinkClick != null
    val linkListener = remember(handlesLinks) {
        if (handlesLinks) {
            LinkInteractionListener { link ->
                if (link is LinkAnnotation.Url) currentOnLinkClick?.invoke(link.url)
            }
        } else {
            null
        }
    }
    val context = remember(theme, components, linkListener) {
        MarkdownContext(theme, components, linkListener)
    }
    CompositionLocalProvider(LocalMarkdownContext provides context) {
        ProvideTextStyle(style) {
            MarkdownBlocks(blocks = document.blocks, modifier = modifier)
        }
    }
}

/**
 * 解析 [content] 并在它变化时更新。
 *
 * 首次解析是同步的，保证第一帧就有内容；之后的更新在后台线程进行。
 * 更新比解析快时只解析最新的内容，中间的版本直接跳过。
 */
@Composable
fun rememberMarkdownDocument(content: String, softBreakAsLineBreak: Boolean = true): MarkdownDocument {
    val state = remember(softBreakAsLineBreak) {
        mutableStateOf(parseMarkdown(content, softBreakAsLineBreak))
    }
    val currentContent by rememberUpdatedState(content)
    LaunchedEffect(state) {
        snapshotFlow { currentContent }
            .conflate()
            .filter { it != state.value.source }
            .mapNotNull { runCatching { parseMarkdown(it, softBreakAsLineBreak) }.getOrNull() }
            .flowOn(Dispatchers.Default)
            .collect { state.value = it }
    }
    return state.value
}

@Preview(showBackground = true)
@Composable
private fun MarkdownPreview() {
    MaterialTheme {
        Surface {
            Markdown(
                content = """
                    # Markdown
                    A paragraph with **bold**, *italic*, ~~deleted~~, `code`, a [link](https://example.com)
                    and <span style="color: #e53935">colored <u>HTML</u></span>.

                    1. Ordered item
                       - Nested bullet
                       - [x] Finished task
                       - [ ] Pending task
                    2. Another item

                    > A quote with ${'$'}E = mc^2$ inside.

                    ```kotlin
                    fun main() = println("Hello")
                    ```

                    | Name | Score |
                    |:-----|------:|
                    | Rikka | 100 |

                    <details open>
                    <summary>Details</summary>

                    Hidden *markdown* content.

                    </details>
                """.trimIndent(),
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
