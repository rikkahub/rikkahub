package me.rerere.highlight.kotlin

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import me.rerere.highlight.HighlightToken
import me.rerere.highlight.HighlightTextColorPalette
import me.rerere.highlight.buildHighlightText
import me.rerere.highlight.kotlin.engine.GrammarEngine
import me.rerere.highlight.kotlin.languages.bash.BashLanguage
import me.rerere.highlight.kotlin.languages.css.CssLanguage
import me.rerere.highlight.kotlin.languages.java.JavaLanguage
import me.rerere.highlight.kotlin.languages.javascript.JavaScriptLanguage
import me.rerere.highlight.kotlin.languages.javascript.TypeScriptLanguage
import me.rerere.highlight.kotlin.languages.json.JsonLanguage
import me.rerere.highlight.kotlin.languages.kotlin.KotlinLanguage
import me.rerere.highlight.kotlin.languages.sql.SqlLanguage
import me.rerere.highlight.kotlin.languages.toml.TomlLanguage

private const val MAX_CODE_LENGTH = 4096

/**
 * A pure Kotlin syntax highlighter
 *
 * The grammar and matching order are based on highlight.js 11.11.2
 */
class KotlinHighlighter {
    private val engine = GrammarEngine(
        languages = listOf(
            JavaScriptLanguage.definition,
            TypeScriptLanguage.definition,
            JsonLanguage.definition,
            BashLanguage.definition,
            TomlLanguage.definition,
            SqlLanguage.definition,
            CssLanguage.definition,
            JavaLanguage.definition,
            KotlinLanguage.definition,
        ),
    )

    fun highlight(code: String, language: String): List<HighlightToken> {
        if (code.isEmpty()) return emptyList()

        return engine.highlight(code, language)
            ?: listOf(HighlightToken.Plain(code))
    }

    fun supports(language: String): Boolean = engine.supports(language)
}

@Composable
fun KotlinHighlightText(
    code: String,
    language: String,
    modifier: Modifier = Modifier,
    colors: HighlightTextColorPalette = HighlightTextColorPalette.Default,
    fontSize: TextUnit = 12.sp,
    fontFamily: FontFamily = FontFamily.Monospace,
    fontStyle: FontStyle = FontStyle.Normal,
    fontWeight: FontWeight = FontWeight.Normal,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
) {
    val highlighter = remember { KotlinHighlighter() }
    val annotatedString = remember(code, language, colors) {
        if (code.length > MAX_CODE_LENGTH) {
            AnnotatedString(code)
        } else {
            buildAnnotatedString {
                highlighter.highlight(code, language).forEach { token ->
                    buildHighlightText(token, colors)
                }
            }
        }
    }

    Text(
        modifier = modifier,
        text = annotatedString,
        fontSize = fontSize,
        fontFamily = fontFamily,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
    )
}

@Preview(showBackground = true)
@Composable
private fun KotlinHighlightTextPreview() {
    KotlinHighlightText(
        code = """
            data class User(
                val name: String,
                val age: Int,
            )

            fun main() {
                val user = User(name = "Rikka", age = 18)
                // Kotlin syntax highlighting preview
                println("${'$'}{user.name}: ${'$'}{user.age}")
            }
        """.trimIndent(),
        language = "kotlin",
        modifier = Modifier,
    )
}
