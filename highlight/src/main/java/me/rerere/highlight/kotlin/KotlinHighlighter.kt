package me.rerere.highlight.kotlin

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
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

@Preview
@Composable
private fun KotlinHighlightTextPreview(
    @PreviewParameter(HighlightPreviewProvider::class)
    sample: HighlightPreviewSample,
) {
    Surface(
        color = Color(0xFF282C34),
        contentColor = Color(0xFFABB2BF),
    ) {
        KotlinHighlightText(
            code = sample.code,
            language = sample.language,
            modifier = Modifier.padding(16.dp),
        )
    }
}

private data class HighlightPreviewSample(
    val language: String,
    val code: String,
) {
    override fun toString(): String = language
}

private class HighlightPreviewProvider : PreviewParameterProvider<HighlightPreviewSample> {
    override val values = sequenceOf(
        HighlightPreviewSample(
            language = "kotlin",
            code = """
                data class User(val name: String, val age: Int)

                fun main() {
                    val user = User(name = "Rikka", age = 18)
                    println("${'$'}{user.name}: ${'$'}{user.age}")
                }
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "java",
            code = """
                record User(String name, int age) {}

                class Main {
                    public static void main(String[] args) {
                        var user = new User("Rikka", 18);
                        System.out.println(user.name());
                    }
                }
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "javascript",
            code = """
                const greet = (name) => `Hello, ${'$'}{name}!`;
                console.log(greet("Rikka"));
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "typescript",
            code = """
                interface User {
                    name: string;
                    age: number;
                }

                const greet = (user: User): string => `Hello, ${'$'}{user.name}!`;
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "json",
            code = """
                {
                  "name": "Rikka",
                  "age": 18,
                  "active": true
                }
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "bash",
            code = """
                #!/usr/bin/env bash
                name="Rikka"
                echo "Hello, ${'$'}name!"
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "toml",
            code = """
                [user]
                name = "Rikka"
                age = 18
                active = true
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "sql",
            code = """
                SELECT name, age
                FROM users
                WHERE active = TRUE
                ORDER BY age DESC;
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "css",
            code = """
                .profile-card {
                  display: grid;
                  color: #6a8759;
                  border-radius: 12px;
                }
            """.trimIndent(),
        ),
    )
}
