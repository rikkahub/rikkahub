package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jsoup.nodes.Element

@Composable
internal fun HtmlCodeBlock(element: Element) {
    val codeElement = element.selectFirst("code")
    val language = codeElement?.classNames()
        ?.find { it.startsWith("language-") }
        ?.removePrefix("language-")
        ?: "plaintext"
    val code = codeElement?.wholeText()?.trimEnd('\n') ?: element.wholeText().trimEnd('\n')

    HighlightCodeBlock(
        code = code,
        language = language,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        completeCodeBlock = true,
    )
}
