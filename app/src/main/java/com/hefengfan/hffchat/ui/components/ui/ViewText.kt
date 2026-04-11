package com.hefengfan.hffchat.ui.components.ui

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.os.Build
import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.text.style.AbsoluteSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

@Composable
fun ViewText(
    text: CharSequence,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
) {
    val density = LocalDensity.current
    val mergedStyle = style.merge(LocalContentColor.current)
    AndroidView(
        factory = { context ->
            TextView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                movementMethod = LinkMovementMethod.getInstance()
                setText(text)
                setComposeTextStyle(density, mergedStyle)
            }
        },
        modifier = modifier,
        update = { view ->
            view.setComposeTextStyle(density, mergedStyle)
            view.text = text
        }
    )
}

@Preview(showBackground = true)
@Composable
private fun TextViewPreview() {
    MaterialTheme {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val style = MaterialTheme.typography.bodyMedium
            Text(
                text = buildAnnotatedString {
                    append("How many roads must a man walk down How many roads must a man walk downHow many roads must a man walk down")
                    withStyle(SpanStyle(fontSize = 39.sp)) {
                        append("BIG TEXT")
                    }
                    append("ahah")
                },
                style = style,
            )

            HorizontalDivider()

            // AndroidView TextView 复刻版本
            // 创建 SpannableString 来复刻 AnnotatedString 的效果
            val fullText =
                "How many roads must a man walk down How many roads must a man walk downHow many roads must a man walk downBIG TEXTahah"
            val spannableString = SpannableString(fullText)

            // 找到 "BIG TEXT" 的位置并应用大字体样式
            val bigTextStart = fullText.indexOf("BIG TEXT")
            val bigTextEnd = bigTextStart + "BIG TEXT".length

            // 将 39.sp 转换为像素
            val density = LocalDensity.current
            val bigTextSizePx = with(density) { 39.sp.toPx().toInt() }
            spannableString.setSpan(
                AbsoluteSizeSpan(bigTextSizePx),
                bigTextStart,
                bigTextEnd,
                SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            ViewText(
                text = spannableString,
                modifier = Modifier.fillMaxWidth(),
                style = style
            )
        }
    }
}

private fun TextView.setComposeTextStyle(
    density: Density,
    textStyle: TextStyle
) {
    with(density) {
        // text color
        setTextColor(textStyle.color.toArgb())

        // text size
        setTextSize(TypedValue.COMPLEX_UNIT_PX, textStyle.fontSize.toPx())

        // letter spacing
        if (textStyle.letterSpacing.isSpecified) {
            letterSpacing = when (textStyle.letterSpacing.type) {
                TextUnitType.Em -> textStyle.letterSpacing.value
                TextUnitType.Sp -> textStyle.letterSpacing.toPx() / textStyle.fontSize.toPx()
                else -> 1f
            }
        }

        // decoration
        textStyle.textDecoration?.let {
            var flags = paintFlags
            if (it.contains(TextDecoration.Underline)) {
                flags = flags or Paint.UNDERLINE_TEXT_FLAG
            }
            if (it.contains(TextDecoration.LineThrough)) {
                flags = flags or Paint.STRIKE_THRU_TEXT_FLAG
            }
            paintFlags = flags
        }

        // align
        textStyle.textAlign.let {
            gravity = when (it) {
                TextAlign.Left -> Gravity.LEFT
                TextAlign.Right -> Gravity.RIGHT
                TextAlign.Center -> Gravity.CENTER_HORIZONTAL
                TextAlign.Start -> Gravity.START
                TextAlign.End -> Gravity.END
                TextAlign.Justify -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                        justificationMode = LineBreaker.JUSTIFICATION_MODE_INTER_CHARACTER
                    }
                    // 两端对齐也需要一个基础的 gravity，通常是 START
                    Gravity.START
                }

                else -> gravity // 保持当前 gravity
            }
        }

        // line height
        if (textStyle.lineHeight.isSpecified) {
            val lineHeightPx = when (textStyle.lineHeight.type) {
                TextUnitType.Em -> textStyle.lineHeight.value * textStyle.fontSize.toPx()
                TextUnitType.Sp -> textStyle.lineHeight.toPx()
                else -> textStyle.lineHeight.value // 默认使用 px
            }
            // Android P (API 28) 及以上版本可以直接设置行高
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lineHeight = lineHeightPx.roundToInt()
            } else {
                // 对于旧版本，通过 setLineSpacing 实现
                // 第一个参数是额外间距，第二个是行高倍数
                // extra = desired_line_height - font_metrics_height
                val fontMetrics = paint.fontMetricsInt
                val extraSpacing = lineHeightPx - (fontMetrics.descent - fontMetrics.ascent)
                setLineSpacing(extraSpacing.toFloat(), 1.0f)
            }
        }

        // shadow
        textStyle.shadow?.let { shadow ->
            setShadowLayer(
                shadow.blurRadius,
                shadow.offset.x,
                shadow.offset.y,
                shadow.color.toArgb()
            )
        }

        // 这是最复杂的部分，因为它需要将 Compose 的字体概念映射到 Android 的 Typeface
        val typefaceStyle = getAndroidTypefaceStyle(
            fontWeight = textStyle.fontWeight,
            fontStyle = textStyle.fontStyle
        )
        val finalTypeface = when (textStyle.fontFamily) {
            FontFamily.SansSerif, null -> Typeface.create(Typeface.SANS_SERIF, typefaceStyle)
            FontFamily.Serif -> Typeface.create(Typeface.SERIF, typefaceStyle)
            FontFamily.Monospace -> Typeface.create(Typeface.MONOSPACE, typefaceStyle)
            FontFamily.Cursive -> Typeface.create(
                Typeface.SANS_SERIF,
                typefaceStyle
            ) // Cursive 没有直接映射，回退到 SansSerif
            // 注意：这里没有处理自定义字体 (FontFamily(Font(...)))
            // 要处理自定义字体，需要更复杂的逻辑来加载字体资源
            else -> Typeface.create(typeface, typefaceStyle)
        }
        setTypeface(finalTypeface)
    }
}

private fun getAndroidTypefaceStyle(
    fontWeight: FontWeight?,
    fontStyle: FontStyle?
): Int {
    val isBold = fontWeight != null && fontWeight >= FontWeight.W600
    val isItalic = fontStyle == FontStyle.Italic
    return when {
        isBold && isItalic -> Typeface.BOLD_ITALIC
        isBold -> Typeface.BOLD
        isItalic -> Typeface.ITALIC
        else -> Typeface.NORMAL
    }
}
