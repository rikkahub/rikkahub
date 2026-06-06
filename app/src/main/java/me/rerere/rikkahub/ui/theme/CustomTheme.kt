package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.ColorScheme
import dynamiccolor.ColorSpecs
import dynamiccolor.DynamicScheme
import dynamiccolor.Variant
import hct.Hct
import kotlinx.serialization.Serializable
import me.rerere.material3.toColorScheme
import palettes.TonalPalette
import kotlin.uuid.Uuid

@Serializable
data class CustomTheme(
    val id: String = Uuid.random().toString(),
    val name: String = "",
    val primaryColorArgb: Long = 0xFF6750A4,
    val secondaryColorArgb: Long? = null,
    val tertiaryColorArgb: Long? = null,
) {
    fun generateColorScheme(dark: Boolean): ColorScheme {
        val sourceHct = Hct.fromInt(primaryColorArgb.toInt())
        val specVersion = DynamicScheme.DEFAULT_SPEC_VERSION
        val platform = DynamicScheme.DEFAULT_PLATFORM
        val contrastLevel = 0.0
        val colorSpec = ColorSpecs.get(specVersion)

        val primaryPalette = colorSpec.getPrimaryPalette(
            Variant.TONAL_SPOT, sourceHct, dark, platform, contrastLevel,
        )
        val secondaryPalette = if (secondaryColorArgb != null) {
            TonalPalette.fromInt(secondaryColorArgb.toInt())
        } else {
            colorSpec.getSecondaryPalette(
                Variant.TONAL_SPOT, sourceHct, dark, platform, contrastLevel,
            )
        }
        val tertiaryPalette = if (tertiaryColorArgb != null) {
            TonalPalette.fromInt(tertiaryColorArgb.toInt())
        } else {
            colorSpec.getTertiaryPalette(
                Variant.TONAL_SPOT, sourceHct, dark, platform, contrastLevel,
            )
        }

        val scheme = DynamicScheme(
            sourceHct,
            Variant.TONAL_SPOT,
            dark,
            contrastLevel,
            platform,
            specVersion,
            primaryPalette,
            secondaryPalette,
            tertiaryPalette,
            colorSpec.getNeutralPalette(
                Variant.TONAL_SPOT, sourceHct, dark, platform, contrastLevel,
            ),
            colorSpec.getNeutralVariantPalette(
                Variant.TONAL_SPOT, sourceHct, dark, platform, contrastLevel,
            ),
            colorSpec.getErrorPalette(
                Variant.TONAL_SPOT, sourceHct, dark, platform, contrastLevel,
            ),
        )
        return scheme.toColorScheme()
    }
}
