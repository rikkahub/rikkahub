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

/** Named colors, otherwise known as tokens, or roles, in the Material Design system. */
class MaterialDynamicColors {
  private val colorSpec: ColorSpec = ColorSpec2026()

  fun highestSurface(scheme: DynamicScheme): DynamicColor = colorSpec.highestSurface(scheme)

  // ////////////////////////////////////////////////////////////////
  // Main Palettes //
  // ////////////////////////////////////////////////////////////////
  val primaryPaletteKeyColor: DynamicColor
    get() = colorSpec.primaryPaletteKeyColor

  val secondaryPaletteKeyColor: DynamicColor
    get() = colorSpec.secondaryPaletteKeyColor

  val tertiaryPaletteKeyColor: DynamicColor
    get() = colorSpec.tertiaryPaletteKeyColor

  val neutralPaletteKeyColor: DynamicColor
    get() = colorSpec.neutralPaletteKeyColor

  val neutralVariantPaletteKeyColor: DynamicColor
    get() = colorSpec.neutralVariantPaletteKeyColor

  val errorPaletteKeyColor: DynamicColor
    get() = colorSpec.errorPaletteKeyColor

  // ////////////////////////////////////////////////////////////////
  // Surfaces [S] //
  // ////////////////////////////////////////////////////////////////
  val background: DynamicColor
    get() = colorSpec.background

  val onBackground: DynamicColor
    get() = colorSpec.onBackground

  val surface: DynamicColor
    get() = colorSpec.surface

  val surfaceDim: DynamicColor
    get() = colorSpec.surfaceDim

  val surfaceBright: DynamicColor
    get() = colorSpec.surfaceBright

  val surfaceContainerLowest: DynamicColor
    get() = colorSpec.surfaceContainerLowest

  val surfaceContainerLow: DynamicColor
    get() = colorSpec.surfaceContainerLow

  val surfaceContainer: DynamicColor
    get() = colorSpec.surfaceContainer

  val surfaceContainerHigh: DynamicColor
    get() = colorSpec.surfaceContainerHigh

  val surfaceContainerHighest: DynamicColor
    get() = colorSpec.surfaceContainerHighest

  val onSurface: DynamicColor
    get() = colorSpec.onSurface

  val surfaceVariant: DynamicColor
    get() = colorSpec.surfaceVariant

  val onSurfaceVariant: DynamicColor
    get() = colorSpec.onSurfaceVariant

  val inverseSurface: DynamicColor
    get() = colorSpec.inverseSurface

  val inverseOnSurface: DynamicColor
    get() = colorSpec.inverseOnSurface

  val outline: DynamicColor
    get() = colorSpec.outline

  val outlineVariant: DynamicColor
    get() = colorSpec.outlineVariant

  val shadow: DynamicColor
    get() = colorSpec.shadow

  val scrim: DynamicColor
    get() = colorSpec.scrim

  val surfaceTint: DynamicColor
    get() = colorSpec.surfaceTint

  // ////////////////////////////////////////////////////////////////
  // Primaries [P] //
  // ////////////////////////////////////////////////////////////////
  val primary: DynamicColor
    get() = colorSpec.primary

  val primaryDim: DynamicColor?
    get() = colorSpec.primaryDim

  val onPrimary: DynamicColor
    get() = colorSpec.onPrimary

  val primaryContainer: DynamicColor
    get() = colorSpec.primaryContainer

  val onPrimaryContainer: DynamicColor
    get() = colorSpec.onPrimaryContainer

  val inversePrimary: DynamicColor
    get() = colorSpec.inversePrimary

  // ///////////////////////////////////////////////////////////////
  // Primary Fixed Colors [PF] //
  // ///////////////////////////////////////////////////////////////
  val primaryFixed: DynamicColor
    get() = colorSpec.primaryFixed

  val primaryFixedDim: DynamicColor
    get() = colorSpec.primaryFixedDim

  val onPrimaryFixed: DynamicColor
    get() = colorSpec.onPrimaryFixed

  val onPrimaryFixedVariant: DynamicColor
    get() = colorSpec.onPrimaryFixedVariant

  // ////////////////////////////////////////////////////////////////
  // Secondaries [Q] //
  // ////////////////////////////////////////////////////////////////
  val secondary: DynamicColor
    get() = colorSpec.secondary

  val secondaryDim: DynamicColor?
    get() = colorSpec.secondaryDim

  val onSecondary: DynamicColor
    get() = colorSpec.onSecondary

  val secondaryContainer: DynamicColor
    get() = colorSpec.secondaryContainer

  val onSecondaryContainer: DynamicColor
    get() = colorSpec.onSecondaryContainer

  // ///////////////////////////////////////////////////////////////
  // Secondary Fixed Colors [QF] //
  // ///////////////////////////////////////////////////////////////
  val secondaryFixed: DynamicColor
    get() = colorSpec.secondaryFixed

  val secondaryFixedDim: DynamicColor
    get() = colorSpec.secondaryFixedDim

  val onSecondaryFixed: DynamicColor
    get() = colorSpec.onSecondaryFixed

  val onSecondaryFixedVariant: DynamicColor
    get() = colorSpec.onSecondaryFixedVariant

  // ////////////////////////////////////////////////////////////////
  // Tertiaries [T] //
  // ////////////////////////////////////////////////////////////////
  val tertiary: DynamicColor
    get() = colorSpec.tertiary

  val tertiaryDim: DynamicColor?
    get() = colorSpec.tertiaryDim

  val onTertiary: DynamicColor
    get() = colorSpec.onTertiary

  val tertiaryContainer: DynamicColor
    get() = colorSpec.tertiaryContainer

  val onTertiaryContainer: DynamicColor
    get() = colorSpec.onTertiaryContainer

  // ///////////////////////////////////////////////////////////////
  // Tertiary Fixed Colors [TF] //
  // ///////////////////////////////////////////////////////////////
  val tertiaryFixed: DynamicColor
    get() = colorSpec.tertiaryFixed

  val tertiaryFixedDim: DynamicColor
    get() = colorSpec.tertiaryFixedDim

  val onTertiaryFixed: DynamicColor
    get() = colorSpec.onTertiaryFixed

  val onTertiaryFixedVariant: DynamicColor
    get() = colorSpec.onTertiaryFixedVariant

  // ////////////////////////////////////////////////////////////////
  // Errors [E] //
  // ////////////////////////////////////////////////////////////////
  val error: DynamicColor
    get() = colorSpec.error

  val errorDim: DynamicColor?
    get() = colorSpec.errorDim

  val onError: DynamicColor
    get() = colorSpec.onError

  val errorContainer: DynamicColor
    get() = colorSpec.errorContainer

  val onErrorContainer: DynamicColor
    get() = colorSpec.onErrorContainer

  // ////////////////////////////////////////////////////////////////
  // All Colors //
  // ////////////////////////////////////////////////////////////////
  /** All dynamic colors in Material Design system. */
  val allDynamicColors: List<() -> DynamicColor?> by lazy {
    listOf(
      this::primaryPaletteKeyColor,
      this::secondaryPaletteKeyColor,
      this::tertiaryPaletteKeyColor,
      this::neutralPaletteKeyColor,
      this::neutralVariantPaletteKeyColor,
      this::errorPaletteKeyColor,
      this::background,
      this::onBackground,
      this::surface,
      this::surfaceDim,
      this::surfaceBright,
      this::surfaceContainerLowest,
      this::surfaceContainerLow,
      this::surfaceContainer,
      this::surfaceContainerHigh,
      this::surfaceContainerHighest,
      this::onSurface,
      this::surfaceVariant,
      this::onSurfaceVariant,
      this::outline,
      this::outlineVariant,
      this::inverseSurface,
      this::inverseOnSurface,
      this::shadow,
      this::scrim,
      this::surfaceTint,
      this::primary,
      this::primaryDim,
      this::onPrimary,
      this::primaryContainer,
      this::onPrimaryContainer,
      this::primaryFixed,
      this::primaryFixedDim,
      this::onPrimaryFixed,
      this::onPrimaryFixedVariant,
      this::inversePrimary,
      this::secondary,
      this::secondaryDim,
      this::onSecondary,
      this::secondaryContainer,
      this::onSecondaryContainer,
      this::secondaryFixed,
      this::secondaryFixedDim,
      this::onSecondaryFixed,
      this::onSecondaryFixedVariant,
      this::tertiary,
      this::tertiaryDim,
      this::onTertiary,
      this::tertiaryContainer,
      this::onTertiaryContainer,
      this::tertiaryFixed,
      this::tertiaryFixedDim,
      this::onTertiaryFixed,
      this::onTertiaryFixedVariant,
      this::error,
      this::errorDim,
      this::onError,
      this::errorContainer,
      this::onErrorContainer,
    )
  }
}
