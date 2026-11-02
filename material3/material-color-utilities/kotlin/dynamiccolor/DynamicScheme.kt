/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dynamiccolor

import dynamiccolor.ColorSpec.SpecVersion
import hct.Hct
import palettes.TonalPalette
import utils.MathUtils
import java.text.DecimalFormat
import java.util.Locale
import kotlin.math.min

/**
 * Provides important settings for creating colors dynamically, and 6 color palettes. Requires: 1. A
 * color. (source color) 2. A theme. (Variant) 3. Whether or not its dark mode. 4. Contrast level.
 * (-1 to 1, currently contrast ratio 3.0 and 7.0)
 */
open class DynamicScheme(
  /** The source color of the scheme in HCT format. */
  val sourceColorHctList: List<Hct>,
  /** The variant of the scheme. */
  val variant: Variant,
  /** Whether or not the scheme is dark mode. */
  val isDark: Boolean,
  /**
   * Value from -1 to 1. -1 represents minimum contrast. 0 represents standard (i.e. the design as
   * spec'd), and 1 represents maximum contrast.
   */
  val contrastLevel: Double,
  /** The platform on which this scheme is intended to be used. */
  val platform: Platform = DEFAULT_PLATFORM,
  /** The spec version of the scheme. */
  specVersion: SpecVersion = DEFAULT_SPEC_VERSION,
  val primaryPalette: TonalPalette,
  val secondaryPalette: TonalPalette,
  val tertiaryPalette: TonalPalette,
  val neutralPalette: TonalPalette,
  val neutralVariantPalette: TonalPalette,
  val errorPalette: TonalPalette,
) {

  /** The spec version of the scheme. */
  constructor(
    sourceColorHct: Hct,
    variant: Variant,
    isDark: Boolean,
    contrastLevel: Double,
    platform: Platform = DEFAULT_PLATFORM,
    specVersion: SpecVersion = DEFAULT_SPEC_VERSION,
    primaryPalette: TonalPalette,
    secondaryPalette: TonalPalette,
    tertiaryPalette: TonalPalette,
    neutralPalette: TonalPalette,
    neutralVariantPalette: TonalPalette,
    errorPalette: TonalPalette,
  ) : this(
    listOf(sourceColorHct),
    variant,
    isDark,
    contrastLevel,
    platform,
    specVersion,
    primaryPalette,
    secondaryPalette,
    tertiaryPalette,
    neutralPalette,
    neutralVariantPalette,
    errorPalette,
  )

  val specVersion: SpecVersion

  init {
    require(sourceColorHctList.isNotEmpty()) { "sourceColorHctList cannot be empty" }
    this.specVersion = maybeFallbackSpecVersion(specVersion, variant)
  }

  /** The source color of the scheme in HCT format. */
  val sourceColorHct: Hct = sourceColorHctList.first()

  /** The source color of the scheme in ARGB format. */
  val sourceColorArgb: Int = sourceColorHct.toInt()

  /** The platform on which this scheme is intended to be used. */
  enum class Platform {
    PHONE,
    WATCH,
  }

  fun getHct(dynamicColor: DynamicColor): Hct {
    return dynamicColor.getHct(this)
  }

  fun getArgb(dynamicColor: DynamicColor): Int {
    return dynamicColor.getArgb(this)
  }

  override fun toString(): String {
    val mode = if (isDark) "dark" else "light"
    val platformName = platform.name.lowercase(Locale.ENGLISH)
    val contrast = DecimalFormat("0.0").format(contrastLevel)
    val extraColors =
      if (sourceColorHctList.size <= 1) {
        ""
      } else {
        "sourceColorHctList=[${sourceColorHctList.joinToString(", ") { it.toString() }}], "
      }
    return "Scheme: variant=${variant.name}, mode=$mode, platform=$platformName, " +
      "contrastLevel=$contrast, seed=$sourceColorHct, ${extraColors}specVersion=$specVersion"
  }

  private val dynamicColors = MaterialDynamicColors()

  val primaryPaletteKeyColor: Int
    get() = getArgb(dynamicColors.primaryPaletteKeyColor)

  val secondaryPaletteKeyColor: Int
    get() = getArgb(dynamicColors.secondaryPaletteKeyColor)

  val tertiaryPaletteKeyColor: Int
    get() = getArgb(dynamicColors.tertiaryPaletteKeyColor)

  val neutralPaletteKeyColor: Int
    get() = getArgb(dynamicColors.neutralPaletteKeyColor)

  val neutralVariantPaletteKeyColor: Int
    get() = getArgb(dynamicColors.neutralVariantPaletteKeyColor)

  val background: Int
    get() = getArgb(dynamicColors.background)

  val onBackground: Int
    get() = getArgb(dynamicColors.onBackground)

  val surface: Int
    get() = getArgb(dynamicColors.surface)

  val surfaceDim: Int
    get() = getArgb(dynamicColors.surfaceDim)

  val surfaceBright: Int
    get() = getArgb(dynamicColors.surfaceBright)

  val surfaceContainerLowest: Int
    get() = getArgb(dynamicColors.surfaceContainerLowest)

  val surfaceContainerLow: Int
    get() = getArgb(dynamicColors.surfaceContainerLow)

  val surfaceContainer: Int
    get() = getArgb(dynamicColors.surfaceContainer)

  val surfaceContainerHigh: Int
    get() = getArgb(dynamicColors.surfaceContainerHigh)

  val surfaceContainerHighest: Int
    get() = getArgb(dynamicColors.surfaceContainerHighest)

  val onSurface: Int
    get() = getArgb(dynamicColors.onSurface)

  val surfaceVariant: Int
    get() = getArgb(dynamicColors.surfaceVariant)

  val onSurfaceVariant: Int
    get() = getArgb(dynamicColors.onSurfaceVariant)

  val inverseSurface: Int
    get() = getArgb(dynamicColors.inverseSurface)

  val inverseOnSurface: Int
    get() = getArgb(dynamicColors.inverseOnSurface)

  val outline: Int
    get() = getArgb(dynamicColors.outline)

  val outlineVariant: Int
    get() = getArgb(dynamicColors.outlineVariant)

  val shadow: Int
    get() = getArgb(dynamicColors.shadow)

  val scrim: Int
    get() = getArgb(dynamicColors.scrim)

  val surfaceTint: Int
    get() = getArgb(dynamicColors.surfaceTint)

  val primary: Int
    get() = getArgb(dynamicColors.primary)

  val onPrimary: Int
    get() = getArgb(dynamicColors.onPrimary)

  val primaryContainer: Int
    get() = getArgb(dynamicColors.primaryContainer)

  val onPrimaryContainer: Int
    get() = getArgb(dynamicColors.onPrimaryContainer)

  val inversePrimary: Int
    get() = getArgb(dynamicColors.inversePrimary)

  val secondary: Int
    get() = getArgb(dynamicColors.secondary)

  val onSecondary: Int
    get() = getArgb(dynamicColors.onSecondary)

  val secondaryContainer: Int
    get() = getArgb(dynamicColors.secondaryContainer)

  val onSecondaryContainer: Int
    get() = getArgb(dynamicColors.onSecondaryContainer)

  val tertiary: Int
    get() = getArgb(dynamicColors.tertiary)

  val onTertiary: Int
    get() = getArgb(dynamicColors.onTertiary)

  val tertiaryContainer: Int
    get() = getArgb(dynamicColors.tertiaryContainer)

  val onTertiaryContainer: Int
    get() = getArgb(dynamicColors.onTertiaryContainer)

  val error: Int
    get() = getArgb(dynamicColors.error)

  val onError: Int
    get() = getArgb(dynamicColors.onError)

  val errorContainer: Int
    get() = getArgb(dynamicColors.errorContainer)

  val onErrorContainer: Int
    get() = getArgb(dynamicColors.onErrorContainer)

  val primaryFixed: Int
    get() = getArgb(dynamicColors.primaryFixed)

  val primaryFixedDim: Int
    get() = getArgb(dynamicColors.primaryFixedDim)

  val onPrimaryFixed: Int
    get() = getArgb(dynamicColors.onPrimaryFixed)

  val onPrimaryFixedVariant: Int
    get() = getArgb(dynamicColors.onPrimaryFixedVariant)

  val secondaryFixed: Int
    get() = getArgb(dynamicColors.secondaryFixed)

  val secondaryFixedDim: Int
    get() = getArgb(dynamicColors.secondaryFixedDim)

  val onSecondaryFixed: Int
    get() = getArgb(dynamicColors.onSecondaryFixed)

  val onSecondaryFixedVariant: Int
    get() = getArgb(dynamicColors.onSecondaryFixedVariant)

  val tertiaryFixed: Int
    get() = getArgb(dynamicColors.tertiaryFixed)

  val tertiaryFixedDim: Int
    get() = getArgb(dynamicColors.tertiaryFixedDim)

  val onTertiaryFixed: Int
    get() = getArgb(dynamicColors.onTertiaryFixed)

  val onTertiaryFixedVariant: Int
    get() = getArgb(dynamicColors.onTertiaryFixedVariant)

  companion object {
    val DEFAULT_SPEC_VERSION = SpecVersion.SPEC_2021
    val DEFAULT_PLATFORM = Platform.PHONE

    @JvmStatic
    fun from(other: DynamicScheme, isDark: Boolean): DynamicScheme {
      return from(other, isDark, other.contrastLevel)
    }

    @JvmStatic
    fun from(other: DynamicScheme, isDark: Boolean, contrastLevel: Double): DynamicScheme {
      return DynamicScheme(
        other.sourceColorHctList,
        other.variant,
        isDark,
        contrastLevel,
        other.platform,
        other.specVersion,
        other.primaryPalette,
        other.secondaryPalette,
        other.tertiaryPalette,
        other.neutralPalette,
        other.neutralVariantPalette,
        other.errorPalette,
      )
    }

    /**
     * Returns a new hue based on a piecewise function and input color hue.
     *
     * For example, for the following function:
     * ```
     * result = 26, if 0 <= hue < 101;
     * result = 39, if 101 <= hue < 210;
     * result = 28, if 210 <= hue < 360.
     * ```
     *
     * call the function as:
     * ```
     * double[] hueBreakpoints = {0, 101, 210, 360};
     * double[] hues = {26, 39, 28};
     * double result = scheme.piecewise(sourceColor, hueBreakpoints, hues);
     * ```
     *
     * @param sourceColorHct The input value.
     * @param hueBreakpoints The breakpoints, in sorted order. No default lower or upper bounds are
     *   assumed.
     * @param hues The hues that should be applied when source color's hue is >= the same index in
     *   hueBreakpoints array, and < the hue at the next index in hueBreakpoints array. Otherwise,
     *   the source color's hue is returned.
     */
    @JvmStatic
    fun getPiecewiseValue(
      sourceColorHct: Hct,
      hueBreakpoints: DoubleArray,
      hues: DoubleArray,
    ): Double {
      val size = min(hueBreakpoints.size - 1, hues.size)
      val sourceHue = sourceColorHct.hue
      for (i in 0 until size) {
        if (sourceHue >= hueBreakpoints[i] && sourceHue < hueBreakpoints[i + 1]) {
          return MathUtils.sanitizeDegreesDouble(hues[i])
        }
      }
      // No condition matched, return the source value.
      return sourceHue
    }

    /**
     * Returns a shifted hue based on a piecewise function and input color hue.
     *
     * For example, for the following function:
     * ```
     * result = hue + 26, if 0 <= hue < 101;
     * result = hue - 39, if 101 <= hue < 210;
     * result = hue + 28, if 210 <= hue < 360.
     * ```
     *
     * call the function as:
     * ```
     * double[] hueBreakpoints = {0, 101, 210, 360};
     * double[] rotations = {26, -39, 28};
     * double result = scheme.getRotatedHue(sourceColor, hueBreakpoints, rotations);
     * ```
     *
     * @param sourceColorHct the source color of the theme, in HCT.
     * @param hueBreakpoints The "breakpoints", i.e. the hues at which a rotation should be apply.
     *   No default lower or upper bounds are assumed.
     * @param rotations The rotation that should be applied when source color's hue is >= the same
     *   index in hues array, and < the hue at the next index in hues array. Otherwise, the source
     *   color's hue is returned.
     */
    @JvmStatic
    fun getRotatedHue(
      sourceColorHct: Hct,
      hueBreakpoints: DoubleArray,
      rotations: DoubleArray,
    ): Double {
      var rotation = getPiecewiseValue(sourceColorHct, hueBreakpoints, rotations)
      if (min(hueBreakpoints.size - 1, rotations.size) <= 0) {
        // No condition matched, return the source hue.
        rotation = 0.0
      }
      return MathUtils.sanitizeDegreesDouble(sourceColorHct.hue + rotation)
    }

    /**
     * Returns the spec version to use for the given variant. If the variant is not supported by the
     * given spec version, the fallback spec version is returned.
     */
    private fun maybeFallbackSpecVersion(specVersion: SpecVersion, variant: Variant): SpecVersion {
      if (variant == Variant.CMF) {
        return specVersion
      }
      if (
        variant == Variant.EXPRESSIVE ||
          variant == Variant.VIBRANT ||
          variant == Variant.TONAL_SPOT ||
          variant == Variant.NEUTRAL
      ) {
        return if (specVersion == SpecVersion.SPEC_2026) SpecVersion.SPEC_2025 else specVersion
      }
      return SpecVersion.SPEC_2021
    }
  }
}
