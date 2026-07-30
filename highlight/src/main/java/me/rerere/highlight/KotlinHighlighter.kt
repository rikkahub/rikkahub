package me.rerere.highlight

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
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
import me.rerere.highlight.core.HighlightEngine
import me.rerere.highlight.languages.builtinLanguages

private const val MAX_CODE_LENGTH = 4096

val LocalKotlinHighlighter = staticCompositionLocalOf { KotlinHighlighter() }

/**
 * A pure Kotlin syntax highlighter.
 *
 * Grammars are ported from highlight.js 11.11.1 and run on [HighlightEngine], a port of its mode
 * stack parser. An unsupported language is returned unhighlighted.
 */
class KotlinHighlighter {
    private val engine = HighlightEngine(builtinLanguages())

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
    val highlighter = LocalKotlinHighlighter.current
    val annotatedString = remember(code, language, colors, highlighter) {
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
)

private class HighlightPreviewProvider : PreviewParameterProvider<HighlightPreviewSample> {
    /** Titles every preview after the language it renders instead of after its position. */
    override fun getDisplayName(index: Int): String = values.elementAt(index).language

    override val values = sequenceOf(
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
            language = "go",
            code = """
                package main

                import "fmt"

                func main() {
                    user := "Rikka"
                    fmt.Println("Hello,", user)
                }
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
            language = "yaml",
            code = """
                user:
                  name: Rikka
                  age: 18
                  tags:
                    - chat
                    - llm
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "dockerfile",
            code = """
                FROM eclipse-temurin:21-jre
                WORKDIR /app
                COPY app.jar .
                CMD ["java", "-jar", "app.jar"]
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "javascript",
            code = """
                const user = "Rikka"

                function greet(name) {
                  console.log(`Hello, ${'$'}{name}!`)
                }

                greet(user)
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "typescript",
            code = """
                interface User {
                  name: string
                  age?: number
                }

                const greet = (user: User): void => {
                  console.log(`Hello, ${'$'}{user.name}!`)
                }
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "html",
            code = """
                <div class="chat">
                  <p>Hello, Rikka!</p>
                  <img src="logo.svg" alt="logo" />
                </div>
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "css",
            code = """
                .chat {
                  display: flex;
                  color: #61afef;
                  margin: 0 auto;
                }
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "java",
            code = """
                public final class Greeter {
                    private final String name;

                    public String greet() {
                        return "Hello, " + name + "!";
                    }
                }
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "kotlin",
            code = """
                data class User(val name: String, val age: Int = 18)

                fun greet(user: User) {
                    println("Hello, ${'$'}{user.name}!")
                }
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "python",
            code = """
                from dataclasses import dataclass


                @dataclass
                class User:
                    name: str
                    age: int = 18

                    def greet(self) -> str:
                        return f"Hello, {self.name}!"
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "c",
            code = """
                #include <stdio.h>

                int main(void)
                {
                    const char *name = "Rikka";
                    printf("Hello, %s!\n", name);
                    return 0;
                }
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "cpp",
            code = """
                #include <iostream>
                #include <string>

                int main() {
                    const std::string name = "Rikka";
                    std::cout << "Hello, " << name << std::endl;
                    return 0;
                }
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "sql",
            code = """
                SELECT name, age
                  FROM users
                 WHERE active = true
                 ORDER BY age DESC
                 LIMIT 10;
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "diff",
            code = """
                --- a/greeter.kt
                +++ b/greeter.kt
                @@ -1,3 +1,3 @@
                 fun greet(name: String) {
                -    println("Hi, " + name)
                +    println("Hello, " + name)
                 }
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "markdown",
            code = """
                # RikkaHub

                A native **Android** LLM chat client.

                - [Docs](https://example.com)
                - Inline `code`
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "cmake",
            code = """
                cmake_minimum_required(VERSION 3.22)
                project(rikka LANGUAGES CXX)
                add_executable(rikka main.cpp)
            """.trimIndent(),
        ),
    )
}
