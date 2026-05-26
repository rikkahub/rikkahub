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

// Set of Material typography styles to start with
val Typography = Typography(
    displayLargeEmphasized = base.displayLargeEmphasized.copy(fontFamily = GoogleSansFlex.Display.Emphasized.Large),
    displayMediumEmphasized = base.displayMediumEmphasized.copy(fontFamily = GoogleSansFlex.Display.Emphasized.Medium),
    displaySmallEmphasized = base.displaySmallEmphasized.copy(fontFamily = GoogleSansFlex.Display.Emphasized.Large),
    headlineLargeEmphasized = base.headlineLargeEmphasized.copy(fontFamily = GoogleSansFlex.Headline.Emphasized.Large),
    headlineMediumEmphasized = base.headlineMediumEmphasized.copy(fontFamily = GoogleSansFlex.Headline.Emphasized.Medium),
    headlineSmallEmphasized = base.headlineSmallEmphasized.copy(fontFamily = GoogleSansFlex.Headline.Emphasized.Large),
    titleLargeEmphasized = base.titleLargeEmphasized.copy(fontFamily = GoogleSansFlex.Title.Emphasized.Large),
    titleMediumEmphasized = base.titleMediumEmphasized.copy(fontFamily = GoogleSansFlex.Title.Emphasized.Medium),
    titleSmallEmphasized = base.titleSmallEmphasized.copy(fontFamily = GoogleSansFlex.Title.Emphasized.Small),
    bodyLargeEmphasized = base.bodyLargeEmphasized.copy(fontFamily = GoogleSansFlex.Body.Emphasized.Large),
    bodyMediumEmphasized = base.bodyMediumEmphasized.copy(fontFamily = GoogleSansFlex.Body.Emphasized.Medium),
    bodySmallEmphasized = base.bodySmallEmphasized.copy(fontFamily = GoogleSansFlex.Body.Emphasized.Small),
    labelLargeEmphasized = base.labelLargeEmphasized.copy(fontFamily = GoogleSansFlex.Label.Emphasized.Large),
    labelMediumEmphasized = base.labelMediumEmphasized.copy(fontFamily = GoogleSansFlex.Label.Emphasized.Medium),
    labelSmallEmphasized = base.labelSmallEmphasized.copy(fontFamily = GoogleSansFlex.Label.Emphasized.Small),
)

@OptIn(ExperimentalTextApi::class)
val JetbrainsMono = FontFamily(
    Font(
        resId = R.font.jetbrains_mono,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(FontWeight.Normal.weight),
        )
    )
)
