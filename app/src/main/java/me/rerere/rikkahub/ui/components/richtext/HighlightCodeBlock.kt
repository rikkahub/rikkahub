package me.rerere.rikkahub.ui.components.richtext

import android.content.ClipData
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.composables.icons.lucide.ChevronsDown
import com.composables.icons.lucide.ChevronsUp
import com.composables.icons.lucide.Lucide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.highlight.HighlightText
import me.rerere.highlight.Highlighter
import me.rerere.highlight.LocalHighlighter
import me.rerere.highlight.buildHighlightText
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.hooks.heroAnimation
import me.rerere.rikkahub.ui.modifier.onClick
import me.rerere.rikkahub.ui.theme.AtomOneDarkPalette
import me.rerere.rikkahub.ui.theme.AtomOneLightPalette
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.utils.base64Encode
import me.rerere.rikkahub.utils.toDp
import kotlin.time.Clock

private const val COLLAPSE_LINES = 10

@Composable
fun HighlightCodeBlock(
    code: String,
    language: String,
    modifier: Modifier = Modifier,
    completeCodeBlock: Boolean = true,
    style: TextStyle? = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
) {
    val darkMode = LocalDarkMode.current
    val colorPalette = if (darkMode) AtomOneDarkPalette else AtomOneLightPalette
    val scrollState = rememberScrollState()
    val clipboardManager = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current
    val context = LocalContext.current
    val settings = LocalSettings.current

    var isExpanded by remember(settings.displaySetting.codeBlockAutoCollapse) {
        mutableStateOf(!settings.displaySetting.codeBlockAutoCollapse)
    }
    val autoWrap = settings.displaySetting.codeBlockAutoWrap

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                try {
                    context.contentResolver.openOutputStream(it)?.use { outputStream ->
                        outputStream.write(code.toByteArray())
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
    ) {
        HighlightCodeActions(
            language = language,
            scope = scope,
            clipboardManager = clipboardManager,
            code = code,
            createDocumentLauncher = createDocumentLauncher,
            navController = navController,
        )
        if (completeCodeBlock && language == "mermaid") {
            Mermaid(
                code = code,
                modifier = Modifier.fillMaxWidth(),
            )
            return
        }
        Spacer(Modifier.height(8.dp))

        val textStyle = LocalTextStyle.current.merge(style)
        val codeLines = remember(code) { code.lines() }
        val collapsedCode = remember(codeLines) { codeLines.take(COLLAPSE_LINES).joinToString("\n") }
        SelectionContainer {
            HighlightText(
                code = if (isExpanded) code else collapsedCode,
                language = language,
                modifier = Modifier
                    .animateContentSize()
                    .then(
                        if (autoWrap) {
                            // 换行模式：不需要横向滚动
                            Modifier
                        } else {
                            // 不换行模式：启用横向滚动
                            Modifier.horizontalScroll(scrollState)
                        }
                    ),
                fontSize = textStyle.fontSize,
                lineHeight = textStyle.lineHeight,
                colors = colorPalette,
                overflow = TextOverflow.Visible,
                softWrap = autoWrap,
                fontFamily = JetbrainsMono
            )
        }

        Spacer(Modifier.height(4.dp))
        // 代码折叠按钮
        if (settings.displaySetting.codeBlockAutoCollapse && codeLines.size > COLLAPSE_LINES) {
            Box(
                modifier = Modifier
                    .onClick {
                        isExpanded = !isExpanded
                    }
                    .fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isExpanded) Lucide.ChevronsUp else Lucide.ChevronsDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(textStyle.fontSize.toDp())
                    )
                    Text(
                        text = if (isExpanded) {
                            stringResource(id = R.string.code_block_collapse)
                        } else {
                            stringResource(id = R.string.code_block_expand)
                        },
                        fontSize = textStyle.fontSize,
                        lineHeight = textStyle.lineHeight,
                    )
                }
            }
        }
    }
}

@Composable
private fun HighlightCodeActions(
    language: String,
    scope: CoroutineScope,
    clipboardManager: Clipboard,
    code: String,
    createDocumentLauncher: ManagedActivityResultLauncher<String, Uri?>,
    navController: NavHostController,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = language,
            fontSize = 12.sp,
            lineHeight = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
                .copy(alpha = 0.5f),
        )
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable {
                    scope.launch {
                        clipboardManager.setClipEntry(
                            ClipEntry(
                                ClipData.newPlainText("code", code),
                            )
                        )
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(id = R.string.chat_page_save),
                fontSize = 12.sp,
                lineHeight = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.clickable {
                    val extension = when (language.lowercase()) {
                        "kotlin" -> "kt"
                        "java" -> "java"
                        "python" -> "py"
                        "javascript" -> "js"
                        "typescript" -> "ts"
                        "cpp", "c++" -> "cpp"
                        "c" -> "c"
                        "html" -> "html"
                        "css" -> "css"
                        "xml" -> "xml"
                        "json" -> "json"
                        "yaml", "yml" -> "yml"
                        "markdown", "md" -> "md"
                        "sql" -> "sql"
                        "sh", "bash" -> "sh"
                        else -> "txt"
                    }
                    createDocumentLauncher.launch(
                        "code_${
                            Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                        }.$extension"
                    )
                }
            )

            Text(
                text = stringResource(id = R.string.code_block_copy),
                fontSize = 12.sp,
                lineHeight = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.clickable {
                    scope.launch {
                        clipboardManager.setClipEntry(ClipEntry(ClipData.newPlainText("code", code)))
                    }
                }
            )

            if (language == "html") {
                Text(
                    text = stringResource(id = R.string.code_block_preview),
                    fontSize = 12.sp,
                    lineHeight = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .clickable {
                            navController.navigate(Screen.WebView(content = code.base64Encode()))
                        }
                )
            }
        }
    }
}

class HighlightCodeVisualTransformation(
    val language: String,
    val highlighter: Highlighter,
    val darkMode: Boolean
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val annotatedString = try {
            val colorPalette = if (darkMode) AtomOneDarkPalette else AtomOneLightPalette
            if (text.text.isEmpty()) {
                AnnotatedString("")
            } else {
                runBlocking {
                    val tokens = highlighter.highlight(text.text, language)
                    buildAnnotatedString {
                        tokens.forEach { token ->
                            buildHighlightText(token, colorPalette)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AnnotatedString(text.text)
        }

        return TransformedText(
            text = annotatedString,
            offsetMapping = OffsetMapping.Identity
        )
    }

    companion object {
        @Composable
        fun regex() = HighlightCodeVisualTransformation(
            language = "regex",
            highlighter = LocalHighlighter.current,
            darkMode = LocalDarkMode.current,
        )
    }
}
