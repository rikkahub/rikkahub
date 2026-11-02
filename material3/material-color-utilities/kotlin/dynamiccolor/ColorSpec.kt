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

import dynamiccolor.DynamicScheme.Platform
import hct.Hct
import palettes.TonalPalette

/** An interface defining all the necessary methods that could be different between specs. */
interface ColorSpec {
  /** All available spec versions. */
  enum class SpecVersion {
    SPEC_2021,
    SPEC_2025,
    SPEC_2026,
  }

  // ////////////////////////////////////////////////////////////////
  // Main Palettes //
  // ////////////////////////////////////////////////////////////////
  val primaryPaletteKeyColor: DynamicColor
  val secondaryPaletteKeyColor: DynamicColor
  val tertiaryPaletteKeyColor: DynamicColor
  val neutralPaletteKeyColor: DynamicColor
  val neutralVariantPaletteKeyColor: DynamicColor
  val errorPaletteKeyColor: DynamicColor

  // ////////////////////////////////////////////////////////////////
  // Surfaces [S] //
  // ////////////////////////////////////////////////////////////////
  val background: DynamicColor
  val onBackground: DynamicColor
  val surface: DynamicColor
  val surfaceDim: DynamicColor
  val surfaceBright: DynamicColor
  val surfaceContainerLowest: DynamicColor
  val surfaceContainerLow: DynamicColor
  val surfaceContainer: DynamicColor
  val surfaceContainerHigh: DynamicColor
  val surfaceContainerHighest: DynamicColor
  val onSurface: DynamicColor
  val surfaceVariant: DynamicColor
  val onSurfaceVariant: DynamicColor
  val inverseSurface: DynamicColor
  val inverseOnSurface: DynamicColor
  val outline: DynamicColor
  val outlineVariant: DynamicColor
  val shadow: DynamicColor
  val scrim: DynamicColor
  val surfaceTint: DynamicColor

  // ////////////////////////////////////////////////////////////////
  // Primaries [P] //
  // ////////////////////////////////////////////////////////////////
  val primary: DynamicColor
  val primaryDim: DynamicColor?
  val onPrimary: DynamicColor
  val primaryContainer: DynamicColor
  val onPrimaryContainer: DynamicColor
  val inversePrimary: DynamicColor

  // ////////////////////////////////////////////////////////////////
  // Secondaries [Q] //
  // ////////////////////////////////////////////////////////////////
  val secondary: DynamicColor
  val secondaryDim: DynamicColor?
  val onSecondary: DynamicColor
  val secondaryContainer: DynamicColor
  val onSecondaryContainer: DynamicColor

  // ////////////////////////////////////////////////////////////////
  // Tertiaries [T] //
  // ////////////////////////////////////////////////////////////////
  val tertiary: DynamicColor
  val tertiaryDim: DynamicColor?
  val onTertiary: DynamicColor
  val tertiaryContainer: DynamicColor
  val onTertiaryContainer: DynamicColor

  // ////////////////////////////////////////////////////////////////
  // Errors [E] //
  // ////////////////////////////////////////////////////////////////
  val error: DynamicColor
  val errorDim: DynamicColor?
  val onError: DynamicColor
  val errorContainer: DynamicColor
  val onErrorContainer: DynamicColor

  // ////////////////////////////////////////////////////////////////
  // Primary Fixed Colors [PF] //
  // ////////////////////////////////////////////////////////////////
  val primaryFixed: DynamicColor
  val primaryFixedDim: DynamicColor
  val onPrimaryFixed: DynamicColor
  val onPrimaryFixedVariant: DynamicColor

  // ////////////////////////////////////////////////////////////////
  // Secondary Fixed Colors [QF] //
  // ////////////////////////////////////////////////////////////////
  val secondaryFixed: DynamicColor
  val secondaryFixedDim: DynamicColor
  val onSecondaryFixed: DynamicColor
  val onSecondaryFixedVariant: DynamicColor

  // ////////////////////////////////////////////////////////////////
  // Tertiary Fixed Colors [TF] //
  // ////////////////////////////////////////////////////////////////
  val tertiaryFixed: DynamicColor
  val tertiaryFixedDim: DynamicColor
  val onTertiaryFixed: DynamicColor
  val onTertiaryFixedVariant: DynamicColor

  // ////////////////////////////////////////////////////////////////
  // Other //
  // ////////////////////////////////////////////////////////////////
  fun highestSurface(scheme: DynamicScheme): DynamicColor

  // ////////////////////////////////////////////////////////////////
  // Color value calculations //
  // ////////////////////////////////////////////////////////////////
  fun getHct(scheme: DynamicScheme, color: DynamicColor): Hct

  fun getTone(scheme: DynamicScheme, color: DynamicColor): Double

  // ////////////////////////////////////////////////////////////////
  // Scheme Palettes //
  // ////////////////////////////////////////////////////////////////
  fun getPrimaryPalette(
    variant: Variant,
    sourceColorHct: Hct,
    isDark: Boolean,
    platform: Platform,
    contrastLevel: Double,
  ): TonalPalette

  fun getSecondaryPalette(
    variant: Variant,
    sourceColorHct: Hct,
    isDark: Boolean,
    platform: Platform,
    contrastLevel: Double,
  ): TonalPalette

  fun getTertiaryPalette(
    variant: Variant,
    sourceColorHct: Hct,
    isDark: Boolean,
    platform: Platform,
    contrastLevel: Double,
  ): TonalPalette

  fun getNeutralPalette(
    variant: Variant,
    sourceColorHct: Hct,
    isDark: Boolean,
    platform: Platform,
    contrastLevel: Double,
  ): TonalPalette

  fun getNeutralVariantPalette(
    variant: Variant,
    sourceColorHct: Hct,
    isDark: Boolean,
    platform: Platform,
    contrastLevel: Double,
  ): TonalPalette

  fun getErrorPalette(
    variant: Variant,
    sourceColorHct: Hct,
    isDark: Boolean,
    platform: Platform,
    contrastLevel: Double,
  ): TonalPalette
}
