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

import contrast.Contrast
import dislike.DislikeAnalyzer
import dynamiccolor.DynamicScheme.Platform
import dynamiccolor.ToneDeltaPair.DeltaConstraint
import dynamiccolor.ToneDeltaPair.TonePolarity
import hct.Hct
import palettes.TonalPalette
import temperature.TemperatureCache
import utils.MathUtils
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** [ColorSpec] implementation for the 2021 spec. */
open class ColorSpec2021 : ColorSpec {
  // ////////////////////////////////////////////////////////////////
  // Main Palettes //
  // ////////////////////////////////////////////////////////////////
  override val primaryPaletteKeyColor: DynamicColor
    get() =
      DynamicColor(
        name = "primary_palette_key_color",
        palette = { scheme -> scheme.primaryPalette },
        tone = { scheme -> scheme.primaryPalette.keyColor.tone },
      )

  override val secondaryPaletteKeyColor: DynamicColor
    get() =
      DynamicColor(
        name = "secondary_palette_key_color",
        palette = { scheme -> scheme.secondaryPalette },
        tone = { scheme -> scheme.secondaryPalette.keyColor.tone },
      )

  override val tertiaryPaletteKeyColor: DynamicColor
    get() =
      DynamicColor(
        name = "tertiary_palette_key_color",
        palette = { scheme -> scheme.tertiaryPalette },
        tone = { scheme -> scheme.tertiaryPalette.keyColor.tone },
      )

  override val neutralPaletteKeyColor: DynamicColor
    get() =
      DynamicColor(
        name = "neutral_palette_key_color",
        palette = { scheme -> scheme.neutralPalette },
        tone = { scheme -> scheme.neutralPalette.keyColor.tone },
      )

  override val neutralVariantPaletteKeyColor: DynamicColor
    get() =
      DynamicColor(
        name = "neutral_variant_palette_key_color",
        palette = { scheme -> scheme.neutralVariantPalette },
        tone = { scheme -> scheme.neutralVariantPalette.keyColor.tone },
      )

  override val errorPaletteKeyColor: DynamicColor
    get() =
      DynamicColor(
        name = "error_palette_key_color",
        palette = { scheme -> scheme.errorPalette },
        tone = { scheme -> scheme.errorPalette.keyColor.tone },
      )

  // ////////////////////////////////////////////////////////////////
  // Surfaces [S] //
  // ////////////////////////////////////////////////////////////////
  override val background: DynamicColor
    get() =
      DynamicColor(
        name = "background",
        palette = { scheme -> scheme.neutralPalette },
        tone = { scheme -> if (scheme.isDark) 6.0 else 98.0 },
        isBackground = true,
      )

  override val onBackground: DynamicColor
    get() =
      DynamicColor(
        name = "on_background",
        palette = { scheme -> scheme.neutralPalette },
        tone = { scheme -> if (scheme.isDark) 90.0 else 10.0 },
        background = { background },
        contrastCurve = { ContrastCurve(3.0, 3.0, 4.5, 7.0) },
      )

  override val surface: DynamicColor
    get() =
      DynamicColor(
        name = "surface",
        palette = { scheme -> scheme.neutralPalette },
        tone = { scheme -> if (scheme.isDark) 6.0 else 98.0 },
        isBackground = true,
      )

  override val surfaceDim: DynamicColor
    get() =
      DynamicColor(
        name = "surface_dim",
        palette = { scheme -> scheme.neutralPalette },
        tone = { scheme ->
          if (scheme.isDark) {
            6.0
          } else {
            ContrastCurve(87.0, 87.0, 80.0, 75.0).get(scheme.contrastLevel)
          }
        },
        isBackground = true,
      )

  override val surfaceBright: DynamicColor
    get() =
      DynamicColor(
        name = "surface_bright",
        palette = { scheme -> scheme.neutralPalette },
        tone = { scheme ->
          if (scheme.isDark) {
            ContrastCurve(24.0, 24.0, 29.0, 34.0).get(scheme.contrastLevel)
          } else {
            98.0
          }
        },
        isBackground = true,
      )

  override val surfaceContainerLowest: DynamicColor
    get() =
      DynamicColor(
        name = "surface_container_lowest",
        palette = { scheme -> scheme.neutralPalette },
        tone = { scheme ->
          if (scheme.isDark) ContrastCurve(4.0, 4.0, 2.0, 0.0).get(scheme.contrastLevel) else 100.0
        },
        isBackground = true,
      )

  override val surfaceContainerLow: DynamicColor
    get() =
      DynamicColor(
        name = "surface_container_low",
        palette = { scheme -> scheme.neutralPalette },
        tone = { scheme ->
          if (scheme.isDark) {
            ContrastCurve(10.0, 10.0, 11.0, 12.0).get(scheme.contrastLevel)
          } else {
            ContrastCurve(96.0, 96.0, 96.0, 95.0).get(scheme.contrastLevel)
          }
        },
        isBackground = true,
      )

  override val surfaceContainer: DynamicColor
    get() =
      DynamicColor(
        name = "surface_container",
        palette = { scheme -> scheme.neutralPalette },
        tone = { scheme ->
          if (scheme.isDark) {
            ContrastCurve(12.0, 12.0, 16.0, 20.0).get(scheme.contrastLevel)
          } else {
            ContrastCurve(94.0, 94.0, 92.0, 90.0).get(scheme.contrastLevel)
          }
        },
        isBackground = true,
      )

  override val surfaceContainerHigh: DynamicColor
    get() =
      DynamicColor(
        name = "surface_container_high",
        palette = { scheme -> scheme.neutralPalette },
        tone = { scheme ->
          if (scheme.isDark) {
            ContrastCurve(17.0, 17.0, 21.0, 25.0).get(scheme.contrastLevel)
          } else {
            ContrastCurve(92.0, 92.0, 88.0, 85.0).get(scheme.contrastLevel)
          }
        },
        isBackground = true,
      )

  override val surfaceContainerHighest: DynamicColor
    get() =
      DynamicColor(
        name = "surface_container_highest",
        palette = { scheme -> scheme.neutralPalette },
        tone = { scheme ->
          if (scheme.isDark) {
            ContrastCurve(22.0, 22.0, 26.0, 30.0).get(scheme.contrastLevel)
          } else {
            ContrastCurve(90.0, 90.0, 84.0, 80.0).get(scheme.contrastLevel)
          }
        },
        isBackground = true,
      )

  override val onSurface: DynamicColor
    get() =
      DynamicColor(
        name = "on_surface",
        palette = { scheme -> scheme.neutralPalette },
        tone = { scheme -> if (scheme.isDark) 90.0 else 10.0 },
        background = this::highestSurface,
        contrastCurve = { ContrastCurve(4.5, 7.0, 11.0, 21.0) },
      )

  override val surfaceVariant: DynamicColor
    get() =
      DynamicColor(
        name = "surface_variant",
        palette = { scheme -> scheme.neutralVariantPalette },
        tone = { scheme -> if (scheme.isDark) 30.0 else 90.0 },
        isBackground = true,
      )

  override val onSurfaceVariant: DynamicColor
    get() =
      DynamicColor(
        name = "on_surface_variant",
        palette = { scheme -> scheme.neutralVariantPalette },
        tone = { scheme -> if (scheme.isDark) 80.0 else 30.0 },
        background = this::highestSurface,
        contrastCurve = { ContrastCurve(3.0, 4.5, 7.0, 11.0) },
      )

  override val inverseSurface: DynamicColor
    get() =
      DynamicColor(
        name = "inverse_surface",
        palette = { scheme -> scheme.neutralPalette },
        tone = { scheme -> if (scheme.isDark) 90.0 else 20.0 },
        isBackground = true,
      )

  override val inverseOnSurface: DynamicColor
    get() =
      DynamicColor(
        name = "inverse_on_surface",
        palette = { scheme -> scheme.neutralPalette },
        tone = { scheme -> if (scheme.isDark) 20.0 else 95.0 },
        background = { inverseSurface },
        contrastCurve = { ContrastCurve(4.5, 7.0, 11.0, 21.0) },
      )

  override val outline: DynamicColor
    get() =
      DynamicColor(
        name = "outline",
        palette = { scheme -> scheme.neutralVariantPalette },
        tone = { scheme -> if (scheme.isDark) 60.0 else 50.0 },
        background = this::highestSurface,
        contrastCurve = { ContrastCurve(1.5, 3.0, 4.5, 7.0) },
      )

  override val outlineVariant: DynamicColor
    get() =
      DynamicColor(
        name = "outline_variant",
        palette = { scheme -> scheme.neutralVariantPalette },
        tone = { scheme -> if (scheme.isDark) 30.0 else 80.0 },
        background = this::highestSurface,
        contrastCurve = { ContrastCurve(1.0, 1.0, 3.0, 4.5) },
      )

  override val shadow: DynamicColor
    get() =
      DynamicColor(name = "shadow", palette = { scheme -> scheme.neutralPalette }, tone = { 0.0 })

  override val scrim: DynamicColor
    get() =
      DynamicColor(name = "scrim", palette = { scheme -> scheme.neutralPalette }, tone = { 0.0 })

  override val surfaceTint: DynamicColor
    get() =
      DynamicColor(
        name = "surface_tint",
        palette = { scheme -> scheme.primaryPalette },
        tone = { scheme -> if (scheme.isDark) 80.0 else 40.0 },
        isBackground = true,
      )

  // ////////////////////////////////////////////////////////////////
  // Primaries [P] //
  // ////////////////////////////////////////////////////////////////
  override val primary: DynamicColor
    get() =
      DynamicColor(
        name = "primary",
        palette = { scheme -> scheme.primaryPalette },
        tone = { scheme ->
          if (isMonochrome(scheme)) {
            if (scheme.isDark) 100.0 else 0.0
          } else {
            if (scheme.isDark) 80.0 else 40.0
          }
        },
        isBackground = true,
        background = this::highestSurface,
        contrastCurve = { ContrastCurve(3.0, 4.5, 7.0, 7.0) },
        toneDeltaPair = {
          ToneDeltaPair(
            roleA = primaryContainer,
            roleB = primary,
            delta = 10.0,
            polarity = TonePolarity.RELATIVE_LIGHTER,
            stayTogether = false,
            constraint = DeltaConstraint.NEARER,
          )
        },
      )

  override val primaryDim: DynamicColor?
    get() = null

  override val onPrimary: DynamicColor
    get() =
      DynamicColor(
        name = "on_primary",
        palette = { scheme -> scheme.primaryPalette },
        tone = { scheme ->
          if (isMonochrome(scheme)) {
            if (scheme.isDark) 10.0 else 90.0
          } else {
            if (scheme.isDark) 20.0 else 100.0
          }
        },
        background = { primary },
        contrastCurve = { ContrastCurve(4.5, 7.0, 11.0, 21.0) },
      )

  override val primaryContainer: DynamicColor
    get() =
      DynamicColor(
        name = "primary_container",
        palette = { scheme -> scheme.primaryPalette },
        tone = { scheme ->
          if (isFidelity(scheme)) {
            scheme.sourceColorHct.tone
          } else if (isMonochrome(scheme)) {
            if (scheme.isDark) 85.0 else 25.0
          } else {
            if (scheme.isDark) 30.0 else 90.0
          }
        },
        isBackground = true,
        background = this::highestSurface,
        contrastCurve = { ContrastCurve(1.0, 1.0, 3.0, 4.5) },
        toneDeltaPair = {
          ToneDeltaPair(
            roleA = primaryContainer,
            roleB = primary,
            delta = 10.0,
            polarity = TonePolarity.RELATIVE_LIGHTER,
            stayTogether = false,
            constraint = DeltaConstraint.NEARER,
          )
        },
      )

  override val onPrimaryContainer: DynamicColor
    get() =
      DynamicColor(
        name = "on_primary_container",
        palette = { scheme -> scheme.primaryPalette },
        tone = { scheme ->
          if (isFidelity(scheme)) {
            DynamicColor.foregroundTone(primaryContainer.tone.invoke(scheme), 4.5)
          } else if (isMonochrome(scheme)) {
            if (scheme.isDark) 0.0 else 100.0
          } else {
            if (scheme.isDark) 90.0 else 30.0
          }
        },
        background = { primaryContainer },
        contrastCurve = { ContrastCurve(3.0, 4.5, 7.0, 11.0) },
      )

  override val inversePrimary: DynamicColor
    get() =
      DynamicColor(
        name = "inverse_primary",
        palette = { scheme -> scheme.primaryPalette },
        tone = { scheme -> if (scheme.isDark) 40.0 else 80.0 },
        background = { inverseSurface },
        contrastCurve = { ContrastCurve(3.0, 4.5, 7.0, 7.0) },
      )

  // ////////////////////////////////////////////////////////////////
  // Secondaries [Q] //
  // ////////////////////////////////////////////////////////////////
  override val secondary: DynamicColor
    get() =
      DynamicColor(
        name = "secondary",
        palette = { scheme -> scheme.secondaryPalette },
        tone = { scheme -> if (scheme.isDark) 80.0 else 40.0 },
        isBackground = true,
        background = this::highestSurface,
        contrastCurve = { ContrastCurve(3.0, 4.5, 7.0, 7.0) },
        toneDeltaPair = {
          ToneDeltaPair(
            roleA = secondaryContainer,
            roleB = secondary,
            delta = 10.0,
            polarity = TonePolarity.RELATIVE_LIGHTER,
            stayTogether = false,
            constraint = DeltaConstraint.NEARER,
          )
        },
      )

  override val secondaryDim: DynamicColor?
    get() = null

  override val onSecondary: DynamicColor
    get() =
      DynamicColor(
        name = "on_secondary",
        palette = { scheme -> scheme.secondaryPalette },
        tone = { scheme ->
          if (isMonochrome(scheme)) {
            if (scheme.isDark) 10.0 else 100.0
          } else {
            if (scheme.isDark) 20.0 else 100.0
          }
        },
        background = { secondary },
        contrastCurve = { ContrastCurve(4.5, 7.0, 11.0, 21.0) },
      )

  override val secondaryContainer: DynamicColor
    get() =
      DynamicColor(
        name = "secondary_container",
        palette = { scheme -> scheme.secondaryPalette },
        tone = { scheme ->
          val initialTone = if (scheme.isDark) 30.0 else 90.0
          if (isMonochrome(scheme)) {
            if (scheme.isDark) 30.0 else 85.0
          } else if (!isFidelity(scheme)) {
            initialTone
          } else {
            findDesiredChromaByTone(
              scheme.secondaryPalette.hue,
              scheme.secondaryPalette.chroma,
              initialTone,
              !scheme.isDark,
            )
          }
        },
        isBackground = true,
        background = this::highestSurface,
        contrastCurve = { ContrastCurve(1.0, 1.0, 3.0, 4.5) },
        toneDeltaPair = {
          ToneDeltaPair(
            roleA = secondaryContainer,
            roleB = secondary,
            delta = 10.0,
            polarity = TonePolarity.RELATIVE_LIGHTER,
            stayTogether = false,
            constraint = DeltaConstraint.NEARER,
          )
        },
      )

  override val onSecondaryContainer: DynamicColor
    get() =
      DynamicColor(
        name = "on_secondary_container",
        palette = { scheme -> scheme.secondaryPalette },
        tone = { scheme ->
          if (isMonochrome(scheme)) {
            if (scheme.isDark) 90.0 else 10.0
          } else if (!isFidelity(scheme)) {
            if (scheme.isDark) 90.0 else 30.0
          } else {
            DynamicColor.foregroundTone(secondaryContainer.tone.invoke(scheme), 4.5)
          }
        },
        background = { secondaryContainer },
        contrastCurve = { ContrastCurve(3.0, 4.5, 7.0, 11.0) },
      )

  // ////////////////////////////////////////////////////////////////
  // Tertiaries [T] //
  // ////////////////////////////////////////////////////////////////
  override val tertiary: DynamicColor
    get() =
      DynamicColor(
        name = "tertiary",
        palette = { scheme -> scheme.tertiaryPalette },
        tone = { scheme ->
          if (isMonochrome(scheme)) {
            if (scheme.isDark) 90.0 else 25.0
          } else {
            if (scheme.isDark) 80.0 else 40.0
          }
        },
        isBackground = true,
        background = this::highestSurface,
        contrastCurve = { ContrastCurve(3.0, 4.5, 7.0, 7.0) },
        toneDeltaPair = {
          ToneDeltaPair(
            roleA = tertiaryContainer,
            roleB = tertiary,
            delta = 10.0,
            polarity = TonePolarity.RELATIVE_LIGHTER,
            stayTogether = false,
            constraint = DeltaConstraint.NEARER,
          )
        },
      )

  override val tertiaryDim: DynamicColor?
    get() = null

  override val onTertiary: DynamicColor
    get() =
      DynamicColor(
        name = "on_tertiary",
        palette = { scheme -> scheme.tertiaryPalette },
        tone = { scheme ->
          if (isMonochrome(scheme)) {
            if (scheme.isDark) 10.0 else 90.0
          } else {
            if (scheme.isDark) 20.0 else 100.0
          }
        },
        background = { tertiary },
        contrastCurve = { ContrastCurve(4.5, 7.0, 11.0, 21.0) },
      )

  override val tertiaryContainer: DynamicColor
    get() =
      DynamicColor(
        name = "tertiary_container",
        palette = { scheme -> scheme.tertiaryPalette },
        tone = { scheme ->
          if (isMonochrome(scheme)) {
            if (scheme.isDark) 60.0 else 49.0
          } else if (!isFidelity(scheme)) {
            if (scheme.isDark) 30.0 else 90.0
          } else {
            val proposedHct = scheme.tertiaryPalette.getHct(scheme.sourceColorHct.tone)
            DislikeAnalyzer.fixIfDisliked(proposedHct).tone
          }
        },
        isBackground = true,
        background = this::highestSurface,
        contrastCurve = { ContrastCurve(1.0, 1.0, 3.0, 4.5) },
        toneDeltaPair = {
          ToneDeltaPair(
            roleA = tertiaryContainer,
            roleB = tertiary,
            delta = 10.0,
            polarity = TonePolarity.RELATIVE_LIGHTER,
            stayTogether = false,
            constraint = DeltaConstraint.NEARER,
          )
        },
      )

  override val onTertiaryContainer: DynamicColor
    get() =
      DynamicColor(
        name = "on_tertiary_container",
        palette = { scheme -> scheme.tertiaryPalette },
        tone = { scheme ->
          if (isMonochrome(scheme)) {
            if (scheme.isDark) 0.0 else 100.0
          } else if (!isFidelity(scheme)) {
            if (scheme.isDark) 90.0 else 30.0
          } else {
            DynamicColor.foregroundTone(tertiaryContainer.tone.invoke(scheme), 4.5)
          }
        },
        background = { tertiaryContainer },
        contrastCurve = { ContrastCurve(3.0, 4.5, 7.0, 11.0) },
      )

  // ////////////////////////////////////////////////////////////////
  // Errors [E] //
  // ////////////////////////////////////////////////////////////////
  override val error: DynamicColor
    get() =
      DynamicColor(
        name = "error",
        palette = { scheme -> scheme.errorPalette },
        tone = { scheme -> if (scheme.isDark) 80.0 else 40.0 },
        isBackground = true,
        background = this::highestSurface,
        contrastCurve = { ContrastCurve(3.0, 4.5, 7.0, 7.0) },
        toneDeltaPair = {
          ToneDeltaPair(
            roleA = errorContainer,
            roleB = error,
            delta = 10.0,
            polarity = TonePolarity.RELATIVE_LIGHTER,
            stayTogether = false,
            constraint = DeltaConstraint.NEARER,
          )
        },
      )

  override val errorDim: DynamicColor?
    get() = null

  override val onError: DynamicColor
    get() =
      DynamicColor(
        name = "on_error",
        palette = { scheme -> scheme.errorPalette },
        tone = { scheme -> if (scheme.isDark) 20.0 else 100.0 },
        background = { error },
        contrastCurve = { ContrastCurve(4.5, 7.0, 11.0, 21.0) },
      )

  override val errorContainer: DynamicColor
    get() =
      DynamicColor(
        name = "error_container",
        palette = { scheme -> scheme.errorPalette },
        tone = { scheme -> if (scheme.isDark) 30.0 else 90.0 },
        isBackground = true,
        background = this::highestSurface,
        contrastCurve = { ContrastCurve(1.0, 1.0, 3.0, 4.5) },
        toneDeltaPair = {
          ToneDeltaPair(
            roleA = errorContainer,
            roleB = error,
            delta = 10.0,
            polarity = TonePolarity.RELATIVE_LIGHTER,
            stayTogether = false,
            constraint = DeltaConstraint.NEARER,
          )
        },
      )

  override val onErrorContainer: DynamicColor
    get() =
      DynamicColor(
        name = "on_error_container",
        palette = { scheme -> scheme.errorPalette },
        tone = { scheme ->
          if (isMonochrome(scheme)) {
            if (scheme.isDark) 90.0 else 10.0
          } else {
            if (scheme.isDark) 90.0 else 30.0
          }
        },
        background = { errorContainer },
        contrastCurve = { ContrastCurve(3.0, 4.5, 7.0, 11.0) },
      )

  // ////////////////////////////////////////////////////////////////
  // Primary Fixed Colors [PF] //
  // ////////////////////////////////////////////////////////////////
  override val primaryFixed: DynamicColor
    get() =
      DynamicColor(
        name = "primary_fixed",
        palette = { scheme -> scheme.primaryPalette },
        tone = { scheme -> if (isMonochrome(scheme)) 40.0 else 90.0 },
        isBackground = true,
        background = this::highestSurface,
        contrastCurve = { ContrastCurve(1.0, 1.0, 3.0, 4.5) },
        toneDeltaPair = {
          ToneDeltaPair(
            roleA = primaryFixed,
            roleB = primaryFixedDim,
            delta = 10.0,
            polarity = TonePolarity.LIGHTER,
            stayTogether = true,
          )
        },
      )

  override val primaryFixedDim: DynamicColor
    get() =
      DynamicColor(
        name = "primary_fixed_dim",
        palette = { scheme -> scheme.primaryPalette },
        tone = { scheme -> if (isMonochrome(scheme)) 30.0 else 80.0 },
        isBackground = true,
        background = this::highestSurface,
        contrastCurve = { ContrastCurve(1.0, 1.0, 3.0, 4.5) },
        toneDeltaPair = {
          ToneDeltaPair(
            roleA = primaryFixed,
            roleB = primaryFixedDim,
            delta = 10.0,
            polarity = TonePolarity.LIGHTER,
            stayTogether = true,
          )
        },
      )

  override val onPrimaryFixed: DynamicColor
    get() =
      DynamicColor(
        name = "on_primary_fixed",
        palette = { scheme -> scheme.primaryPalette },
        tone = { scheme -> if (isMonochrome(scheme)) 100.0 else 10.0 },
        background = { primaryFixedDim },
        secondBackground = { primaryFixed },
        contrastCurve = { ContrastCurve(4.5, 7.0, 11.0, 21.0) },
      )

  override val onPrimaryFixedVariant: DynamicColor
    get() =
      DynamicColor(
        name = "on_primary_fixed_variant",
        palette = { scheme -> scheme.primaryPalette },
        tone = { scheme -> if (isMonochrome(scheme)) 90.0 else 30.0 },
        background = { primaryFixedDim },
        secondBackground = { primaryFixed },
        contrastCurve = { ContrastCurve(3.0, 4.5, 7.0, 11.0) },
      )

  // ////////////////////////////////////////////////////////////////
  // Secondary Fixed Colors [QF] //
  // ////////////////////////////////////////////////////////////////
  override val secondaryFixed: DynamicColor
    get() =
      DynamicColor(
        name = "secondary_fixed",
        palette = { scheme -> scheme.secondaryPalette },
        tone = { scheme -> if (isMonochrome(scheme)) 80.0 else 90.0 },
        isBackground = true,
        background = this::highestSurface,
        contrastCurve = { ContrastCurve(1.0, 1.0, 3.0, 4.5) },
        toneDeltaPair = {
          ToneDeltaPair(
            roleA = secondaryFixed,
            roleB = secondaryFixedDim,
            delta = 10.0,
            polarity = TonePolarity.LIGHTER,
            stayTogether = true,
          )
        },
      )

  override val secondaryFixedDim: DynamicColor
    get() =
      DynamicColor(
        name = "secondary_fixed_dim",
        palette = { scheme -> scheme.secondaryPalette },
        tone = { scheme -> if (isMonochrome(scheme)) 70.0 else 80.0 },
        isBackground = true,
        background = this::highestSurface,
        contrastCurve = { ContrastCurve(1.0, 1.0, 3.0, 4.5) },
        toneDeltaPair = {
          ToneDeltaPair(
            roleA = secondaryFixed,
            roleB = secondaryFixedDim,
            delta = 10.0,
            polarity = TonePolarity.LIGHTER,
            stayTogether = true,
          )
        },
      )

  override val onSecondaryFixed: DynamicColor
    get() =
      DynamicColor(
        name = "on_secondary_fixed",
        palette = { scheme -> scheme.secondaryPalette },
        tone = { 10.0 },
        background = { secondaryFixedDim },
        secondBackground = { secondaryFixed },
        contrastCurve = { ContrastCurve(4.5, 7.0, 11.0, 21.0) },
      )

  override val onSecondaryFixedVariant: DynamicColor
    get() =
      DynamicColor(
        name = "on_secondary_fixed_variant",
        palette = { scheme -> scheme.secondaryPalette },
        tone = { scheme -> if (isMonochrome(scheme)) 25.0 else 30.0 },
        background = { secondaryFixedDim },
        secondBackground = { secondaryFixed },
        contrastCurve = { ContrastCurve(3.0, 4.5, 7.0, 11.0) },
      )

  // ////////////////////////////////////////////////////////////////
  // Tertiary Fixed Colors [TF] //
  // ////////////////////////////////////////////////////////////////
  override val tertiaryFixed: DynamicColor
    get() =
      DynamicColor(
        name = "tertiary_fixed",
        palette = { scheme -> scheme.tertiaryPalette },
        tone = { scheme -> if (isMonochrome(scheme)) 40.0 else 90.0 },
        isBackground = true,
        background = this::highestSurface,
        contrastCurve = { ContrastCurve(1.0, 1.0, 3.0, 4.5) },
        toneDeltaPair = {
          ToneDeltaPair(
            roleA = tertiaryFixed,
            roleB = tertiaryFixedDim,
            delta = 10.0,
            polarity = TonePolarity.LIGHTER,
            stayTogether = true,
          )
        },
      )

  override val tertiaryFixedDim: DynamicColor
    get() =
      DynamicColor(
        name = "tertiary_fixed_dim",
        palette = { scheme -> scheme.tertiaryPalette },
        tone = { scheme -> if (isMonochrome(scheme)) 30.0 else 80.0 },
        isBackground = true,
        background = this::highestSurface,
        contrastCurve = { ContrastCurve(1.0, 1.0, 3.0, 4.5) },
        toneDeltaPair = {
          ToneDeltaPair(
            roleA = tertiaryFixed,
            roleB = tertiaryFixedDim,
            delta = 10.0,
            polarity = TonePolarity.LIGHTER,
            stayTogether = true,
          )
        },
      )

  override val onTertiaryFixed: DynamicColor
    get() =
      DynamicColor(
        name = "on_tertiary_fixed",
        palette = { scheme -> scheme.tertiaryPalette },
        tone = { scheme -> if (isMonochrome(scheme)) 100.0 else 10.0 },
        background = { tertiaryFixedDim },
        secondBackground = { tertiaryFixed },
        contrastCurve = { ContrastCurve(4.5, 7.0, 11.0, 21.0) },
      )

  override val onTertiaryFixedVariant: DynamicColor
    get() =
      DynamicColor(
        name = "on_tertiary_fixed_variant",
        palette = { scheme -> scheme.tertiaryPalette },
        tone = { scheme -> if (isMonochrome(scheme)) 90.0 else 30.0 },
        background = { tertiaryFixedDim },
        secondBackground = { tertiaryFixed },
        contrastCurve = { ContrastCurve(3.0, 4.5, 7.0, 11.0) },
      )

  // ////////////////////////////////////////////////////////////////
  // Other //
  // ////////////////////////////////////////////////////////////////
  override fun highestSurface(scheme: DynamicScheme): DynamicColor {
    return if (scheme.isDark) surfaceBright else surfaceDim
  }

  // ///////////////////////////////////////////////////////////////
  // Color value calculations //
  // ///////////////////////////////////////////////////////////////
  override fun getHct(scheme: DynamicScheme, color: DynamicColor): Hct {
    // This is crucial for aesthetics: we aren't simply taking the standard color
    // and changing its tone for contrast. Rather, we find the tone for contrast, then
    // use the specified chroma from the palette to construct a new color.
    //
    // For example, this enables colors with standard tone of T90, which has limited chroma, to
    // "recover" intended chroma as contrast increases.
    val tone = getTone(scheme, color)
    return color.palette.invoke(scheme).getHct(tone)
  }

  override fun getTone(scheme: DynamicScheme, color: DynamicColor): Double {
    val decreasingContrast = scheme.contrastLevel < 0
    val toneDeltaPair = color.toneDeltaPair?.invoke(scheme)

    // Case 1: dual foreground, pair of colors with delta constraint.
    if (toneDeltaPair != null) {
      val roleA = toneDeltaPair.roleA
      val roleB = toneDeltaPair.roleB
      val delta = toneDeltaPair.delta
      val polarity = toneDeltaPair.polarity
      val stayTogether = toneDeltaPair.stayTogether
      val aIsNearer =
        (toneDeltaPair.constraint == DeltaConstraint.NEARER ||
          (polarity == TonePolarity.LIGHTER && !scheme.isDark) ||
          (polarity == TonePolarity.DARKER && !scheme.isDark))
      val nearer = if (aIsNearer) roleA else roleB
      val farther = if (aIsNearer) roleB else roleA
      val amNearer = color.name == nearer.name
      val expansionDir = if (scheme.isDark) 1 else -1
      var nTone = nearer.tone.invoke(scheme)
      var fTone = farther.tone.invoke(scheme)

      // 1st round: solve to min, each
      val background = color.background
      val nContrastCurve = nearer.contrastCurve?.invoke(scheme)
      val fContrastCurve = farther.contrastCurve?.invoke(scheme)
      if (
        background != null &&
          nearer.contrastCurve != null &&
          farther.contrastCurve != null &&
          nContrastCurve != null &&
          fContrastCurve != null
      ) {
        val bg = background.invoke(scheme)
        if (bg != null) {
          val nContrast = nContrastCurve.get(scheme.contrastLevel)
          val fContrast = fContrastCurve.get(scheme.contrastLevel)
          val bgTone = bg.getTone(scheme)

          // If a color is good enough, it is not adjusted.
          // Initial and adjusted tones for `nearer`
          if (Contrast.ratioOfTones(bgTone, nTone) < nContrast) {
            nTone = DynamicColor.foregroundTone(bgTone, nContrast)
          }
          // Initial and adjusted tones for `farther`
          if (Contrast.ratioOfTones(bgTone, fTone) < fContrast) {
            fTone = DynamicColor.foregroundTone(bgTone, fContrast)
          }
          if (decreasingContrast) {
            // If decreasing contrast, adjust color to the "bare minimum"
            // that satisfies contrast.
            nTone = DynamicColor.foregroundTone(bgTone, nContrast)
            fTone = DynamicColor.foregroundTone(bgTone, fContrast)
          }
        }
      }

      // If constraint is not satisfied, try another round.
      if ((fTone - nTone) * expansionDir < delta) {
        // 2nd round: expand farther to match delta.
        fTone = (nTone + delta * expansionDir).coerceIn(0.0, 100.0)
        // If constraint is not satisfied, try another round.
        if ((fTone - nTone) * expansionDir < delta) {
          // 3rd round: contract nearer to match delta.
          nTone = (fTone - delta * expansionDir).coerceIn(0.0, 100.0)
        }
      }

      // Avoids the 50-59 awkward zone.
      if (50 <= nTone && nTone < 60) {
        // If `nearer` is in the awkward zone, move it away, together with
        // `farther`.
        if (expansionDir > 0) {
          nTone = 60.0
          fTone = max(fTone, nTone + delta * expansionDir)
        } else {
          nTone = 49.0
          fTone = min(fTone, nTone + delta * expansionDir)
        }
      } else if (50 <= fTone && fTone < 60) {
        if (stayTogether) {
          // Fixes both, to avoid two colors on opposite sides of the "awkward
          // zone".
          if (expansionDir > 0) {
            nTone = 60.0
            fTone = max(fTone, nTone + delta * expansionDir)
          } else {
            nTone = 49.0
            fTone = min(fTone, nTone + delta * expansionDir)
          }
        } else {
          // Not required to stay together; fixes just one.
          if (expansionDir > 0) {
            fTone = 60.0
          } else {
            fTone = 49.0
          }
        }
      }

      // Returns `nTone` if this color is `nearer`, otherwise `fTone`.
      return if (amNearer) nTone else fTone
    } else {
      // Case 2: No contrast pair; just solve for itself.
      var answer = color.tone.invoke(scheme)
      val background = color.background?.invoke(scheme)
      val contrastCurve = color.contrastCurve?.invoke(scheme)
      if (background == null || contrastCurve == null) {
        return answer // No adjustment for colors with no background.
      }
      val bgTone = background.getTone(scheme)
      val desiredRatio = contrastCurve.get(scheme.contrastLevel)
      if (Contrast.ratioOfTones(bgTone, answer) >= desiredRatio) {
        // Don't "improve" what's good enough.
      } else {
        // Rough improvement.
        answer = DynamicColor.foregroundTone(bgTone, desiredRatio)
      }
      if (decreasingContrast) {
        answer = DynamicColor.foregroundTone(bgTone, desiredRatio)
      }
      if (color.isBackground && 50 <= answer && answer < 60) {
        // Must adjust
        answer =
          if (Contrast.ratioOfTones(49.0, bgTone) >= desiredRatio) {
            49.0
          } else {
            60.0
          }
      }
      val secondBackground = color.secondBackground?.invoke(scheme)
      if (secondBackground == null) {
        return answer
      }

      // Case 3: Adjust for dual backgrounds.
      val bgTone1 = background.getTone(scheme)
      val bgTone2 = secondBackground.getTone(scheme)
      val upper = max(bgTone1, bgTone2)
      val lower = min(bgTone1, bgTone2)
      if (
        Contrast.ratioOfTones(upper, answer) >= desiredRatio &&
          Contrast.ratioOfTones(lower, answer) >= desiredRatio
      ) {
        return answer
      }

      // The darkest light tone that satisfies the desired ratio,
      // or -1 if such ratio cannot be reached.
      val lightOption = Contrast.lighter(upper, desiredRatio)

      // The lightest dark tone that satisfies the desired ratio,
      // or -1 if such ratio cannot be reached.
      val darkOption = Contrast.darker(lower, desiredRatio)

      // Tones suitable for the foreground.
      val availables = mutableListOf<Double>()
      if (lightOption != null) {
        availables.add(lightOption)
      }
      if (darkOption != null) {
        availables.add(darkOption)
      }
      val prefersLight =
        DynamicColor.tonePrefersLightForeground(bgTone1) ||
          DynamicColor.tonePrefersLightForeground(bgTone2)
      if (prefersLight) {
        return lightOption ?: 100.0
      }
      return if (availables.size == 1) {
        availables[0]
      } else if (darkOption == null) {
        0.0
      } else {
        darkOption
      }
    }
  }

  // ////////////////////////////////////////////////////////////////
  // Scheme Palettes //
  // ////////////////////////////////////////////////////////////////
  override fun getPrimaryPalette(
    variant: Variant,
    sourceColorHct: Hct,
    isDark: Boolean,
    platform: Platform,
    contrastLevel: Double,
  ): TonalPalette {
    return when (variant) {
      Variant.CONTENT,
      Variant.FIDELITY -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, sourceColorHct.chroma)
      Variant.FRUIT_SALAD ->
        TonalPalette.fromHueAndChroma(
          MathUtils.sanitizeDegreesDouble(sourceColorHct.hue - 50.0),
          48.0,
        )
      Variant.MONOCHROME -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 0.0)
      Variant.NEUTRAL -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 12.0)
      Variant.RAINBOW -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 48.0)
      Variant.TONAL_SPOT -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 36.0)
      Variant.EXPRESSIVE ->
        TonalPalette.fromHueAndChroma(
          MathUtils.sanitizeDegreesDouble(sourceColorHct.hue + 240),
          40.0,
        )
      Variant.VIBRANT -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 200.0)
      else -> throw IllegalArgumentException("$variant variant is not supported in current spec.")
    }
  }

  override fun getSecondaryPalette(
    variant: Variant,
    sourceColorHct: Hct,
    isDark: Boolean,
    platform: Platform,
    contrastLevel: Double,
  ): TonalPalette {
    return when (variant) {
      Variant.CONTENT,
      Variant.FIDELITY ->
        TonalPalette.fromHueAndChroma(
          sourceColorHct.hue,
          max(sourceColorHct.chroma - 32.0, sourceColorHct.chroma * 0.5),
        )
      Variant.FRUIT_SALAD ->
        TonalPalette.fromHueAndChroma(
          MathUtils.sanitizeDegreesDouble(sourceColorHct.hue - 50.0),
          36.0,
        )
      Variant.MONOCHROME -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 0.0)
      Variant.NEUTRAL -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 8.0)
      Variant.RAINBOW -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 16.0)
      Variant.TONAL_SPOT -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 16.0)
      Variant.EXPRESSIVE ->
        TonalPalette.fromHueAndChroma(
          DynamicScheme.getRotatedHue(
            sourceColorHct,
            doubleArrayOf(0.0, 21.0, 51.0, 121.0, 151.0, 191.0, 271.0, 321.0, 360.0),
            doubleArrayOf(45.0, 95.0, 45.0, 20.0, 45.0, 90.0, 45.0, 45.0, 45.0),
          ),
          24.0,
        )
      Variant.VIBRANT ->
        TonalPalette.fromHueAndChroma(
          DynamicScheme.getRotatedHue(
            sourceColorHct,
            doubleArrayOf(0.0, 41.0, 61.0, 101.0, 131.0, 181.0, 251.0, 301.0, 360.0),
            doubleArrayOf(18.0, 15.0, 10.0, 12.0, 15.0, 18.0, 15.0, 12.0, 12.0),
          ),
          24.0,
        )
      else -> throw IllegalArgumentException("$variant variant is not supported in current spec.")
    }
  }

  override fun getTertiaryPalette(
    variant: Variant,
    sourceColorHct: Hct,
    isDark: Boolean,
    platform: Platform,
    contrastLevel: Double,
  ): TonalPalette {
    return when (variant) {
      Variant.CONTENT ->
        TonalPalette.fromHct(
          DislikeAnalyzer.fixIfDisliked(
            TemperatureCache(sourceColorHct).getAnalogousColors(count = 3, divisions = 6)[2]
          )
        )
      Variant.FIDELITY ->
        TonalPalette.fromHct(
          DislikeAnalyzer.fixIfDisliked(TemperatureCache(sourceColorHct).complement)
        )
      Variant.FRUIT_SALAD -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 36.0)
      Variant.MONOCHROME -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 0.0)
      Variant.NEUTRAL -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 16.0)
      Variant.RAINBOW,
      Variant.TONAL_SPOT ->
        TonalPalette.fromHueAndChroma(
          MathUtils.sanitizeDegreesDouble(sourceColorHct.hue + 60.0),
          24.0,
        )
      Variant.EXPRESSIVE ->
        TonalPalette.fromHueAndChroma(
          DynamicScheme.getRotatedHue(
            sourceColorHct,
            doubleArrayOf(0.0, 21.0, 51.0, 121.0, 151.0, 191.0, 271.0, 321.0, 360.0),
            doubleArrayOf(120.0, 120.0, 20.0, 45.0, 20.0, 15.0, 20.0, 120.0, 120.0),
          ),
          32.0,
        )
      Variant.VIBRANT ->
        TonalPalette.fromHueAndChroma(
          DynamicScheme.getRotatedHue(
            sourceColorHct,
            doubleArrayOf(0.0, 41.0, 61.0, 101.0, 131.0, 181.0, 251.0, 301.0, 360.0),
            doubleArrayOf(35.0, 30.0, 20.0, 25.0, 30.0, 35.0, 30.0, 25.0, 25.0),
          ),
          32.0,
        )
      else -> throw IllegalArgumentException("$variant variant is not supported in current spec.")
    }
  }

  override fun getNeutralPalette(
    variant: Variant,
    sourceColorHct: Hct,
    isDark: Boolean,
    platform: Platform,
    contrastLevel: Double,
  ): TonalPalette {
    return when (variant) {
      Variant.CONTENT,
      Variant.FIDELITY ->
        TonalPalette.fromHueAndChroma(sourceColorHct.hue, sourceColorHct.chroma / 8.0)
      Variant.FRUIT_SALAD -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 10.0)
      Variant.MONOCHROME -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 0.0)
      Variant.NEUTRAL -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 2.0)
      Variant.RAINBOW -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 0.0)
      Variant.TONAL_SPOT -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 6.0)
      Variant.EXPRESSIVE ->
        TonalPalette.fromHueAndChroma(MathUtils.sanitizeDegreesDouble(sourceColorHct.hue + 15), 8.0)
      Variant.VIBRANT -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 10.0)
      else -> throw IllegalArgumentException("$variant variant is not supported in current spec.")
    }
  }

  override fun getNeutralVariantPalette(
    variant: Variant,
    sourceColorHct: Hct,
    isDark: Boolean,
    platform: Platform,
    contrastLevel: Double,
  ): TonalPalette {
    return when (variant) {
      Variant.CONTENT,
      Variant.FIDELITY ->
        TonalPalette.fromHueAndChroma(sourceColorHct.hue, (sourceColorHct.chroma / 8.0) + 4.0)
      Variant.FRUIT_SALAD -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 16.0)
      Variant.MONOCHROME -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 0.0)
      Variant.NEUTRAL -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 2.0)
      Variant.RAINBOW -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 0.0)
      Variant.TONAL_SPOT -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 8.0)
      Variant.EXPRESSIVE ->
        TonalPalette.fromHueAndChroma(
          MathUtils.sanitizeDegreesDouble(sourceColorHct.hue + 15),
          12.0,
        )
      Variant.VIBRANT -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 12.0)
      else -> throw IllegalArgumentException("$variant variant is not supported in current spec.")
    }
  }

  override fun getErrorPalette(
    variant: Variant,
    sourceColorHct: Hct,
    isDark: Boolean,
    platform: Platform,
    contrastLevel: Double,
  ): TonalPalette {
    return TonalPalette.fromHueAndChroma(25.0, 84.0)
  }

  private fun isFidelity(scheme: DynamicScheme): Boolean {
    return scheme.variant == Variant.FIDELITY || scheme.variant == Variant.CONTENT
  }

  private fun isMonochrome(scheme: DynamicScheme): Boolean {
    return scheme.variant == Variant.MONOCHROME
  }

  private fun findDesiredChromaByTone(
    hue: Double,
    chroma: Double,
    tone: Double,
    byDecreasingTone: Boolean,
  ): Double {
    var answer = tone
    var closestToChroma = Hct.from(hue, chroma, tone)
    if (closestToChroma.chroma < chroma) {
      var chromaPeak = closestToChroma.chroma
      while (closestToChroma.chroma < chroma) {
        answer += if (byDecreasingTone) -1.0 else 1.0
        val potentialSolution = Hct.from(hue, chroma, answer)
        if (chromaPeak > potentialSolution.chroma) {
          break
        }
        if (abs(potentialSolution.chroma - chroma) < 0.4) {
          break
        }
        val potentialDelta = abs(potentialSolution.chroma - chroma)
        val currentDelta = abs(closestToChroma.chroma - chroma)
        if (potentialDelta < currentDelta) {
          closestToChroma = potentialSolution
        }
        chromaPeak = max(chromaPeak, potentialSolution.chroma)
      }
    }
    return answer
  }
}
