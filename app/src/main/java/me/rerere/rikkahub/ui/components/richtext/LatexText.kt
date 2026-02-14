package me.rerere.rikkahub.ui.components.richtext

import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import ru.noties.jlatexmath.JLatexMathDrawable

private const val MIN_LATEX_BOUNDS_PX = 1

fun assumeLatexSize(latex: String, fontSize: Float): Rect {
    return runCatching {
        JLatexMathDrawable.builder(processLatex(latex))
            .textSize(fontSize)
            .padding(0)
            .build()
            .bounds
            .sanitizeBounds()
    }.getOrElse { Rect(0, 0, MIN_LATEX_BOUNDS_PX, MIN_LATEX_BOUNDS_PX) }
}

@Composable
fun LatexText(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current
) {
    val style = style.merge(
        fontSize = fontSize,
        color = color
    )
    val density = LocalDensity.current

    val processedLatex = remember(latex) { processLatex(latex) }
    val drawable = remember(processedLatex, fontSize, style) {
        runCatching {
            with(density) {
                getLatexDrawable(
                    latex = processedLatex,
                    fontSize = fontSize.toPx(),
                    color = style.color.toArgb(),
                    background = style.background.toArgb()
                )
            }
        }.onFailure {
            it.printStackTrace()
        }.getOrNull()
    }

    if (drawable != null) {
        val safeBounds = drawable.bounds.sanitizeBounds()
        with(density) {
            Canvas(
                modifier = modifier
                    .size(
                        width = safeBounds.width().toDp(),
                        height = safeBounds.height().toDp()
                    )
            ) {
                drawable.draw(drawContext.canvas.nativeCanvas)
            }
        }
    } else {
        Text(
            text = latex,
            style = style,
            modifier = modifier
        )
    }
}

fun getLatexDrawable(
    latex: String,
    fontSize: Float,
    color: Int,
    background: Int
): JLatexMathDrawable? {
    return runCatching {
        JLatexMathDrawable.builder(latex)
            .textSize(fontSize)
            .color(color)
            .background(background)
            .padding(0)
            .align(JLatexMathDrawable.ALIGN_LEFT)
            .build()
    }.onFailure {
        it.printStackTrace()
    }.getOrNull()
}

private fun Rect.sanitizeBounds(): Rect {
    val safeWidth = width().coerceAtLeast(MIN_LATEX_BOUNDS_PX)
    val safeHeight = height().coerceAtLeast(MIN_LATEX_BOUNDS_PX)
    return Rect(0, 0, safeWidth, safeHeight)
}

private val inlineDollarRegex = Regex("""^\$(.*?)\$""", RegexOption.DOT_MATCHES_ALL)
private val displayDollarRegex = Regex("""^\$\$(.*?)\$\$""", RegexOption.DOT_MATCHES_ALL)
private val inlineParenRegex = Regex("""^\\\((.*?)\\\)""", RegexOption.DOT_MATCHES_ALL)
private val displayBracketRegex = Regex("""^\\\[(.*?)\\\]""", RegexOption.DOT_MATCHES_ALL)

private fun processLatex(latex: String): String {
    val trimmed = latex.trim()
    return when {
        displayDollarRegex.matches(trimmed) ->
            displayDollarRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed

        inlineDollarRegex.matches(trimmed) ->
            inlineDollarRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed

        displayBracketRegex.matches(trimmed) ->
            displayBracketRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed

        inlineParenRegex.matches(trimmed) ->
            inlineParenRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed

        else -> trimmed
    }
}
