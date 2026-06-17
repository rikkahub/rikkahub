package me.rerere.rikkahub.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import me.rerere.rikkahub.R

/**
 * Inter — neutral grotesque used for UI/body text. Bundled as static weights so
 * no variable-axis support is required at runtime. Latin/Cyrillic only; Compose
 * falls back to the platform font for CJK glyphs.
 */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/**
 * Noto Serif — transitional serif used for display headings. Shipped as a single
 * variable font; weights are selected through the (wght) axis so one file covers
 * the whole display range.
 */
@OptIn(ExperimentalTextApi::class)
val NotoSerif = FontFamily(
    Font(
        R.font.noto_serif,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(FontWeight.Normal.weight)),
    ),
    Font(
        R.font.noto_serif,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(FontWeight.Medium.weight)),
    ),
    Font(
        R.font.noto_serif,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(FontWeight.SemiBold.weight)),
    ),
    Font(
        R.font.noto_serif,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(FontWeight.Bold.weight)),
    ),
)
