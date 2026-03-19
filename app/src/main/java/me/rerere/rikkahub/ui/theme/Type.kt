package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.R

private val BaseTypography = Typography()

// Set of Material typography styles to start with
val Typography = BaseTypography.copy(
    bodyLarge = BaseTypography.bodyLarge.copy(lineHeight = 26.sp),
    bodyMedium = BaseTypography.bodyMedium.copy(lineHeight = 24.sp),
    bodySmall = BaseTypography.bodySmall.copy(lineHeight = 20.sp),
    titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = BaseTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = BaseTypography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    headlineMedium = BaseTypography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineLarge = BaseTypography.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
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
