package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/**
 * Single-line label for selectors that split a fixed width among N options
 * (SegmentedButton rows, chips). The default Material label has no overflow policy,
 * so a label wider than its allotted segment wraps — and wraps character-by-character
 * (vertical text) when the segment is tiny. This shrinks the text to fit one line
 * instead, down to a legibility floor, so segments keep an even height regardless of
 * label length or locale.
 */
@Composable
fun SegmentedButtonLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        maxLines = 1,
        textAlign = TextAlign.Center,
        autoSize = TextAutoSize.StepBased(
            minFontSize = 9.sp,
            maxFontSize = 14.sp,
            stepSize = 0.5.sp,
        ),
        modifier = modifier,
    )
}
