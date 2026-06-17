package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.R

val base = Typography()

// Noto Serif for the large display headings only. Body/title/label/headline keep
// the platform default font: a global swap to Inter changes glyph advances and
// overflows width-constrained controls (segmented selectors, fixed label columns)
// that were laid out for the default metrics. Display text is short and large, so
// the serif there is safe. Inter stays available in AppFonts for opt-in per-call use.
val Typography = base.copy(
    displayLarge = base.displayLarge.copy(fontFamily = NotoSerif),
    displayMedium = base.displayMedium.copy(fontFamily = NotoSerif),
    displaySmall = base.displaySmall.copy(fontFamily = NotoSerif),
    displayLargeEmphasized = base.displayLargeEmphasized.copy(fontFamily = NotoSerif),
    displayMediumEmphasized = base.displayMediumEmphasized.copy(fontFamily = NotoSerif),
    displaySmallEmphasized = base.displaySmallEmphasized.copy(fontFamily = NotoSerif),
)

// Set of Material typography styles to start with
//val Typography = Typography(
//    displayLargeEmphasized = base.displayLargeEmphasized.copy(
//        fontFamily = GoogleSansFlex.Display.Emphasized.Large,
//        fontWeight = FontWeight.Bold
//    ),
//    displayMediumEmphasized = base.displayMediumEmphasized.copy(
//        fontFamily = GoogleSansFlex.Display.Emphasized.Medium,
//        fontWeight = FontWeight.Bold
//    ),
//    displaySmallEmphasized = base.displaySmallEmphasized.copy(
//        fontFamily = GoogleSansFlex.Display.Emphasized.Large,
//        fontWeight = FontWeight.Bold
//    ),
//    headlineLargeEmphasized = base.headlineLargeEmphasized.copy(
//        fontFamily = GoogleSansFlex.Headline.Emphasized.Large,
//        fontWeight = FontWeight.Bold
//    ),
//    headlineMediumEmphasized = base.headlineMediumEmphasized.copy(
//        fontFamily = GoogleSansFlex.Headline.Emphasized.Medium,
//        fontWeight = FontWeight.Bold
//    ),
//    headlineSmallEmphasized = base.headlineSmallEmphasized.copy(
//        fontFamily = GoogleSansFlex.Headline.Emphasized.Large,
//        fontWeight = FontWeight.Bold
//    ),
//    titleLargeEmphasized = base.titleLargeEmphasized.copy(
//        fontFamily = GoogleSansFlex.Title.Emphasized.Large,
//        fontWeight = FontWeight.Bold
//    ),
//    titleMediumEmphasized = base.titleMediumEmphasized.copy(
//        fontFamily = GoogleSansFlex.Title.Emphasized.Medium,
//        fontWeight = FontWeight.Bold
//    ),
//    titleSmallEmphasized = base.titleSmallEmphasized.copy(
//        fontFamily = GoogleSansFlex.Title.Emphasized.Small,
//        fontWeight = FontWeight.Bold
//    ),
//    bodyLargeEmphasized = base.bodyLargeEmphasized.copy(
//        fontFamily = GoogleSansFlex.Body.Emphasized.Large,
//        fontWeight = FontWeight.Bold
//    ),
//    bodyMediumEmphasized = base.bodyMediumEmphasized.copy(
//        fontFamily = GoogleSansFlex.Body.Emphasized.Medium,
//        fontWeight = FontWeight.Bold
//    ),
//    bodySmallEmphasized = base.bodySmallEmphasized.copy(
//        fontFamily = GoogleSansFlex.Body.Emphasized.Small,
//        fontWeight = FontWeight.Bold
//    ),
//    labelLargeEmphasized = base.labelLargeEmphasized.copy(
//        fontFamily = GoogleSansFlex.Label.Emphasized.Large,
//        fontWeight = FontWeight.Bold
//    ),
//    labelMediumEmphasized = base.labelMediumEmphasized.copy(
//        fontFamily = GoogleSansFlex.Label.Emphasized.Medium,
//        fontWeight = FontWeight.Bold
//    ),
//    labelSmallEmphasized = base.labelSmallEmphasized.copy(
//        fontFamily = GoogleSansFlex.Label.Emphasized.Small,
//        fontWeight = FontWeight.Bold
//    ),
//)

@OptIn(ExperimentalTextApi::class)
val JetbrainsMono = FontFamily(
    Font(
        resId = R.font.jetbrains_mono,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(FontWeight.Normal.weight),
        )
    )
)
