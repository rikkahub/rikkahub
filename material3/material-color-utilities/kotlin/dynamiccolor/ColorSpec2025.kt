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
import dynamiccolor.DynamicScheme.Platform
import dynamiccolor.ToneDeltaPair.DeltaConstraint
import dynamiccolor.ToneDeltaPair.TonePolarity
import hct.Hct
import palettes.TonalPalette
import kotlin.math.max
import kotlin.math.min

/** [ColorSpec] implementation for the 2025 spec. */
open class ColorSpec2025 : ColorSpec2021() {
  // ////////////////////////////////////////////////////////////////
  // Surfaces [S] //
  // ////////////////////////////////////////////////////////////////
  override val background: DynamicColor
    get() {
      // Remapped to surface for 2025 spec.
      val color2025 = surface.copy(name = "background")
      return super.background.extendSpecVersion(ColorSpec.SpecVersion.SPEC_2025, color2025)
    }

  override val onBackground: DynamicColor
    get() {
      // Remapped to onSurface for 2025 spec.
      val color2025 =
        onSurface.copy(
          name = "on_background",
          tone = { scheme ->
            if (scheme.platform == Platform.WATCH) 100.0 else onSurface.getTone(scheme)
          },
        )
      return super.onBackground.extendSpecVersion(ColorSpec.SpecVersion.SPEC_2025, color2025)
    }

  override val surface: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "surface",
          palette = { scheme -> scheme.neutralPalette },
          tone = { scheme ->
            if (scheme.platform == Platform.PHONE) {
              if (scheme.isDark) {
                4.0
              } else {
                if (Hct.isYellow(scheme.neutralPalette.hue)) {
                  99.0
                } else if (scheme.variant == Variant.VIBRANT) {
                  97.0
                } else {
                  98.0
                }
              }
            } else {
              0.0
            }
          },
          isBackground = true,
        )
      return super.surface.extendSpecVersion(ColorSpec.SpecVersion.SPEC_2025, color2025)
    }

  override val surfaceDim: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "surface_dim",
          palette = { scheme -> scheme.neutralPalette },
          tone = { scheme ->
            if (scheme.isDark) {
              4.0
            } else {
              if (Hct.isYellow(scheme.neutralPalette.hue)) {
                90.0
              } else if (scheme.variant == Variant.VIBRANT) {
                85.0
              } else {
                87.0
              }
            }
          },
          isBackground = true,
          chromaMultiplier = { scheme ->
            if (!scheme.isDark) {
              when (scheme.variant) {
                Variant.NEUTRAL -> 2.5
                Variant.TONAL_SPOT -> 1.7
                Variant.EXPRESSIVE -> if (Hct.isYellow(scheme.neutralPalette.hue)) 2.7 else 1.75
                Variant.VIBRANT -> 1.36
                else -> 1.0
              }
            } else {
              1.0
            }
          },
        )
      return super.surfaceDim.extendSpecVersion(ColorSpec.SpecVersion.SPEC_2025, color2025)
    }

  override val surfaceBright: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "surface_bright",
          palette = { scheme -> scheme.neutralPalette },
          tone = { scheme ->
            if (scheme.isDark) {
              18.0
            } else {
              if (Hct.isYellow(scheme.neutralPalette.hue)) {
                99.0
              } else if (scheme.variant == Variant.VIBRANT) {
                97.0
              } else {
                98.0
              }
            }
          },
          isBackground = true,
          chromaMultiplier = { scheme ->
            if (scheme.isDark) {
              when (scheme.variant) {
                Variant.NEUTRAL -> 2.5
                Variant.TONAL_SPOT -> 1.7
                Variant.EXPRESSIVE -> if (Hct.isYellow(scheme.neutralPalette.hue)) 2.7 else 1.75
                Variant.VIBRANT -> 1.36
                else -> 1.0
              }
            } else {
              1.0
            }
          },
        )
      return super.surfaceBright.extendSpecVersion(ColorSpec.SpecVersion.SPEC_2025, color2025)
    }

  override val surfaceContainerLowest: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "surface_container_lowest",
          palette = { scheme -> scheme.neutralPalette },
          tone = { scheme -> if (scheme.isDark) 0.0 else 100.0 },
          isBackground = true,
        )
      return super.surfaceContainerLowest.extendSpecVersion(
        ColorSpec.SpecVersion.SPEC_2025,
        color2025,
      )
    }

  override val surfaceContainerLow: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "surface_container_low",
          palette = { scheme -> scheme.neutralPalette },
          tone = { scheme ->
            if (scheme.platform == Platform.PHONE) {
              if (scheme.isDark) {
                6.0
              } else {
                if (Hct.isYellow(scheme.neutralPalette.hue)) {
                  98.0
                } else if (scheme.variant == Variant.VIBRANT) {
                  95.0
                } else {
                  96.0
                }
              }
            } else {
              15.0
            }
          },
          isBackground = true,
          chromaMultiplier = { scheme ->
            if (scheme.platform == Platform.PHONE) {
              when (scheme.variant) {
                Variant.NEUTRAL -> 1.3
                Variant.TONAL_SPOT -> 1.25
                Variant.EXPRESSIVE -> if (Hct.isYellow(scheme.neutralPalette.hue)) 1.3 else 1.15
                Variant.VIBRANT -> 1.08
                else -> 1.0
              }
            } else {
              1.0
            }
          },
        )
      return super.surfaceContainerLow.extendSpecVersion(ColorSpec.SpecVersion.SPEC_2025, color2025)
    }

  override val surfaceContainer: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "surface_container",
          palette = { scheme -> scheme.neutralPalette },
          tone = { scheme ->
            if (scheme.platform == Platform.PHONE) {
              if (scheme.isDark) {
                9.0
              } else {
                if (Hct.isYellow(scheme.neutralPalette.hue)) {
                  96.0
                } else if (scheme.variant == Variant.VIBRANT) {
                  92.0
                } else {
                  94.0
                }
              }
            } else {
              20.0
            }
          },
          isBackground = true,
          chromaMultiplier = { scheme ->
            if (scheme.platform == Platform.PHONE) {
              when (scheme.variant) {
                Variant.NEUTRAL -> 1.6
                Variant.TONAL_SPOT -> 1.4
                Variant.EXPRESSIVE -> if (Hct.isYellow(scheme.neutralPalette.hue)) 1.6 else 1.3
                Variant.VIBRANT -> 1.15
                else -> 1.0
              }
            } else {
              1.0
            }
          },
        )
      return super.surfaceContainer.extendSpecVersion(ColorSpec.SpecVersion.SPEC_2025, color2025)
    }

  override val surfaceContainerHigh: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "surface_container_high",
          palette = { scheme -> scheme.neutralPalette },
          tone = { scheme ->
            if (scheme.platform == Platform.PHONE) {
              if (scheme.isDark) {
                12.0
              } else {
                if (Hct.isYellow(scheme.neutralPalette.hue)) {
                  94.0
                } else if (scheme.variant == Variant.VIBRANT) {
                  90.0
                } else {
                  92.0
                }
              }
            } else {
              25.0
            }
          },
          isBackground = true,
          chromaMultiplier = { scheme ->
            if (scheme.platform == Platform.PHONE) {
              when (scheme.variant) {
                Variant.NEUTRAL -> 1.9
                Variant.TONAL_SPOT -> 1.5
                Variant.EXPRESSIVE -> if (Hct.isYellow(scheme.neutralPalette.hue)) 1.95 else 1.45
                Variant.VIBRANT -> 1.22
                else -> 1.0
              }
            } else {
              1.0
            }
          },
        )
      return super.surfaceContainerHigh.extendSpecVersion(
        ColorSpec.SpecVersion.SPEC_2025,
        color2025,
      )
    }

  override val surfaceContainerHighest: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "surface_container_highest",
          palette = { scheme -> scheme.neutralPalette },
          tone = { scheme ->
            if (scheme.isDark) {
              15.0
            } else {
              if (Hct.isYellow(scheme.neutralPalette.hue)) {
                92.0
              } else if (scheme.variant == Variant.VIBRANT) {
                88.0
              } else {
                90.0
              }
            }
          },
          isBackground = true,
          chromaMultiplier = { scheme ->
            when (scheme.variant) {
              Variant.NEUTRAL -> 2.2
              Variant.TONAL_SPOT -> 1.7
              Variant.EXPRESSIVE -> if (Hct.isYellow(scheme.neutralPalette.hue)) 2.3 else 1.6
              Variant.VIBRANT -> 1.29
              else -> 1.0
            }
          },
        )
      return super.surfaceContainerHighest.extendSpecVersion(
        ColorSpec.SpecVersion.SPEC_2025,
        color2025,
      )
    }

  override val onSurface: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "on_surface",
          palette = { scheme -> scheme.neutralPalette },
          tone = { scheme ->
            if (scheme.variant == Variant.VIBRANT) {
              tMaxC(scheme.neutralPalette, 0.0, 100.0, 1.1)
            } else {
              DynamicColor.getInitialToneFromBackground { scheme: DynamicScheme ->
                  if (scheme.platform == Platform.PHONE) {
                    if (scheme.isDark) surfaceBright else surfaceDim
                  } else {
                    surfaceContainerHigh
                  }
                }
                .invoke(scheme)
            }
          },
          chromaMultiplier = { scheme ->
            if (scheme.platform == Platform.PHONE) {
              when (scheme.variant) {
                Variant.NEUTRAL -> 2.2
                Variant.TONAL_SPOT -> 1.7
                Variant.EXPRESSIVE -> {
                  if (Hct.isYellow(scheme.neutralPalette.hue)) {
                    (if (scheme.isDark) 3.0 else 2.3)
                  } else {
                    1.6
                  }
                }
                else -> 1.0
              }
            } else {
              1.0
            }
          },
          background = { scheme ->
            if (scheme.platform == Platform.PHONE) {
              if (scheme.isDark) surfaceBright else surfaceDim
            } else {
              surfaceContainerHigh
            }
          },
          contrastCurve = { scheme ->
            if (scheme.isDark && scheme.platform == Platform.PHONE) {
              getContrastCurve(11.0)
            } else {
              getContrastCurve(9.0)
            }
          },
        )
      return super.onSurface.extendSpecVersion(ColorSpec.SpecVersion.SPEC_2025, color2025)
    }

  override val surfaceVariant: DynamicColor
    get() {
      // Remapped to surfaceContainerHighest for 2025 spec.
      val color2025 = surfaceContainerHighest.copy(name = "surface_variant")
      return super.surfaceVariant.extendSpecVersion(ColorSpec.SpecVersion.SPEC_2025, color2025)
    }

  override val onSurfaceVariant: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "on_surface_variant",
          palette = { scheme -> scheme.neutralPalette },
          chromaMultiplier = { scheme ->
            if (scheme.platform == Platform.PHONE) {
              when (scheme.variant) {
                Variant.NEUTRAL -> 2.2
                Variant.TONAL_SPOT -> 1.7
                Variant.EXPRESSIVE -> {
                  if (Hct.isYellow(scheme.neutralPalette.hue)) {
                    (if (scheme.isDark) 3.0 else 2.3)
                  } else {
                    1.6
                  }
                }
                else -> 1.0
              }
            } else {
              1.0
            }
          },
          background = { scheme ->
            if (scheme.platform == Platform.PHONE) {
              if (scheme.isDark) surfaceBright else surfaceDim
            } else {
              surfaceContainerHigh
            }
          },
          contrastCurve = { scheme ->
            if (scheme.platform == Platform.PHONE) {
              (if (scheme.isDark) getContrastCurve(6.0) else getContrastCurve(4.5))
            } else {
              getContrastCurve(7.0)
            }
          },
        )
      return super.onSurfaceVariant.extendSpecVersion(ColorSpec.SpecVersion.SPEC_2025, color2025)
    }

  override val inverseSurface: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "inverse_surface",
          palette = { scheme -> scheme.neutralPalette },
          tone = { scheme -> if (scheme.isDark) 98.0 else 4.0 },
          isBackground = true,
        )
      return super.inverseSurface.extendSpecVersion(ColorSpec.SpecVersion.SPEC_2025, color2025)
    }

  override val inverseOnSurface: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "inverse_on_surface",
          palette = { scheme -> scheme.neutralPalette },
          background = { inverseSurface },
          contrastCurve = { getContrastCurve(7.0) },
        )
      return super.inverseOnSurface.extendSpecVersion(ColorSpec.SpecVersion.SPEC_2025, color2025)
    }

  override val outline: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "outline",
          palette = { scheme -> scheme.neutralPalette },
          chromaMultiplier = { scheme ->
            if (scheme.platform == Platform.PHONE) {
              when (scheme.variant) {
                Variant.NEUTRAL -> 2.2
                Variant.TONAL_SPOT -> 1.7
                Variant.EXPRESSIVE -> {
                  if (Hct.isYellow(scheme.neutralPalette.hue)) {
                    (if (scheme.isDark) 3.0 else 2.3)
                  } else {
                    1.6
                  }
                }
                else -> 1.0
              }
            } else {
              1.0
            }
          },
          background = { scheme ->
            if (scheme.platform == Platform.PHONE) {
              if (scheme.isDark) surfaceBright else surfaceDim
            } else {
              surfaceContainerHigh
            }
          },
          contrastCurve = { scheme ->
            if (scheme.platform == Platform.PHONE) getContrastCurve(3.0) else getContrastCurve(4.5)
          },
        )
      return super.outline.extendSpecVersion(ColorSpec.SpecVersion.SPEC_2025, color2025)
    }

  override val outlineVariant: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "outline_variant",
          palette = { scheme -> scheme.neutralPalette },
          chromaMultiplier = { scheme ->
            if (scheme.platform == Platform.PHONE) {
              when (scheme.variant) {
                Variant.NEUTRAL -> 2.2
                Variant.TONAL_SPOT -> 1.7
                Variant.EXPRESSIVE -> {
                  if (Hct.isYellow(scheme.neutralPalette.hue)) {
                    (if (scheme.isDark) 3.0 else 2.3)
                  } else {
                    1.6
                  }
                }
                else -> 1.0
              }
            } else {
              1.0
            }
          },
          background = { scheme ->
            if (scheme.platform == Platform.PHONE) {
              if (scheme.isDark) surfaceBright else surfaceDim
            } else {
              surfaceContainerHigh
            }
          },
          contrastCurve = { scheme ->
            if (scheme.platform == Platform.PHONE) getContrastCurve(1.5) else getContrastCurve(3.0)
          },
        )
      return super.outlineVariant.extendSpecVersion(ColorSpec.SpecVersion.SPEC_2025, color2025)
    }

  override val surfaceTint: DynamicColor
    get() {
      // Remapped to primary for 2025 spec.
      val color2025 = primary.copy(name = "surface_tint")
      return super.surfaceTint.extendSpecVersion(ColorSpec.SpecVersion.SPEC_2025, color2025)
    }

  // ////////////////////////////////////////////////////////////////
  // Primaries [P] //
  // ////////////////////////////////////////////////////////////////
  override val primary: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "primary",
          palette = { scheme -> scheme.primaryPalette },
          tone = { scheme ->
            when {
              scheme.variant == Variant.NEUTRAL -> {
                if (scheme.platform == Platform.PHONE) {
                  if (scheme.isDark) 80.0 else 40.0
                } else {
                  90.0
                }
              }
              scheme.variant == Variant.TONAL_SPOT -> {
                if (scheme.platform == Platform.PHONE) {
                  if (scheme.isDark) {
                    80.0
                  } else {
                    tMaxC(scheme.primaryPalette)
                  }
                } else {
                  tMaxC(scheme.primaryPalette, 0.0, 90.0)
                }
              }
              scheme.variant == Variant.EXPRESSIVE -> {
                if (scheme.platform == Platform.PHONE) {
                  tMaxC(
                    scheme.primaryPalette,
                    0.0,
                    if (scheme.isDark) {
                      if (Hct.isCyan(scheme.primaryPalette.hue)) 88.0 else 98.0
                    } else {
                      if (Hct.isYellow(scheme.primaryPalette.hue)) 25.0 else 98.0
                    },
                  )
                } else { // WATCH
                  tMaxC(scheme.primaryPalette)
                }
              }
              else -> { // VIBRANT
                if (scheme.platform == Platform.PHONE) {
                  tMaxC(
                    scheme.primaryPalette,
                    0.0,
                    if (Hct.isCyan(scheme.primaryPalette.hue)) 88.0 else 98.0,
                  )
                } else { // WATCH
                  tMaxC(scheme.primaryPalette)
                }
              }
            }
          },
          isBackground = true,
          background = { scheme ->
            if (scheme.platform == Platform.PHONE) {
              if (scheme.isDark) surfaceBright else surfaceDim
            } else {
              surfaceContainerHigh
            }
          },
          contrastCurve = { scheme ->
            if (scheme.platform == Platform.PHONE) getContrastCurve(4.5) else getContrastCurve(7.0)
          },
          toneDeltaPair = { scheme ->
            if (scheme.platform == Platform.PHONE) {
              ToneDeltaPair(
                roleA = primaryContainer,
                roleB = primary,
                delta = 5.0,
                polarity = TonePolarity.RELATIVE_LIGHTER,
                constraint = DeltaConstraint.FARTHER,
              )
            } else {
              null
            }
          },
        )
      return super.primary.extendSpecVersion(ColorSpec.SpecVersion.SPEC_2025, color2025)
    }

  override val primaryDim: DynamicColor
    get() {
      return DynamicColor(
        name = "primary_dim",
        palette = { scheme -> scheme.primaryPalette },
        tone = { scheme ->
          when (scheme.variant) {
            Variant.NEUTRAL -> 85.0
            Variant.TONAL_SPOT -> tMaxC(scheme.primaryPalette, 0.0, 90.0)
            else -> tMaxC(scheme.primaryPalette)
          }
        },
        isBackground = true,
        background = { surfaceContainerHigh },
        contrastCurve = { getContrastCurve(4.5) },
        toneDeltaPair = {
          ToneDeltaPair(
            roleA = primaryDim,
            roleB = primary,
            delta = 5.0,
            polarity = TonePolarity.DARKER,
            constraint = DeltaConstraint.FARTHER,
          )
        },
      )
    }

  override val onPrimary: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "on_primary",
          palette = { scheme -> scheme.primaryPalette },
          background = { scheme -> if (scheme.platform == Platform.PHONE) primary else primaryDim },
          contrastCurve = { scheme ->
            if (scheme.platform == Platform.PHONE) getContrastCurve(6.0) else getContrastCurve(7.0)
          },
        )
      return super.onPrimary.extendSpecVersion(ColorSpec.SpecVersion.SPEC_2025, color2025)
    }

  override val primaryContainer: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "primary_container",
          palette = { scheme -> scheme.primaryPalette },
          tone = { scheme ->
            when {
              scheme.platform == Platform.WATCH -> 30.0
              scheme.variant == Variant.NEUTRAL -> if (scheme.isDark) 30.0 else 90.0
              scheme.variant == Variant.TONAL_SPOT ->
                if (scheme.isDark) {
                  tMinC(scheme.primaryPalette, 35.0, 93.0)
                } else {
                  tMaxC(scheme.primaryPalette, 0.0, 90.0)
                }
              scheme.variant == Variant.EXPRESSIVE ->
                if (scheme.isDark) {
                  tMinC(scheme.primaryPalette, 30.0, 93.0)
                } else {
                  tMaxC(
                    scheme.primaryPalette,
                    78.0,
                    if (Hct.isCyan(scheme.primaryPalette.hue)) 88.0 else 90.0,
                  )
                }
              else -> { // VIBRANT
                if (scheme.isDark) {
                  tMinC(scheme.primaryPalette, 66.0, 93.0)
                } else {
                  tMaxC(
                    scheme.primaryPalette,
                    66.0,
                    if (Hct.isCyan(scheme.primaryPalette.hue)) 88.0 else 93.0,
                  )
                }
              }
            }
          },
          isBackground = true,
          background = { scheme ->
            if (scheme.platform == Platform.PHONE) {
              if (scheme.isDark) surfaceBright else surfaceDim
            } else {
              null
            }
          },
          toneDeltaPair = { scheme ->
            if (scheme.platform == Platform.WATCH) {
              ToneDeltaPair(
                roleA = primaryContainer,
                roleB = primaryDim,
                delta = 10.0,
                polarity = TonePolarity.DARKER,
                constraint = DeltaConstraint.FARTHER,
              )
            } else {
              null
            }
          },
          contrastCurve = { scheme ->
            if (scheme.platform == Platform.PHONE && scheme.contrastLevel > 0) {
              getContrastCurve(1.5)
            } else {
              null
            }
          },
        )
      return super.primaryContainer.extendSpecVersion(ColorSpec.SpecVersion.SPEC_2025, color2025)
    }

  override val onPrimaryContainer: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "on_primary_container",
          palette = { scheme -> scheme.primaryPalette },
          background = { primaryContainer },
          contrastCurve = { scheme ->
            if (scheme.platform == Platform.PHONE) getContrastCurve(6.0) else getContrastCurve(7.0)
          },
        )
      return super.onPrimaryContainer.extendSpecVersion(ColorSpec.SpecVersion.SPEC_2025, color2025)
    }

  override val inversePrimary: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "inverse_primary",
          palette = { scheme -> scheme.primaryPalette },
          tone = { scheme -> tMaxC(scheme.primaryPalette) },
          background = { inverseSurface },
          contrastCurve = { scheme ->
            if (scheme.platform == Platform.PHONE) getContrastCurve(6.0) else getContrastCurve(7.0)
          },
        )
      return super.inversePrimary.extendSpecVersion(ColorSpec.SpecVersion.SPEC_2025, color2025)
    }

  // ////////////////////////////////////////////////////////////////
  // Secondaries [Q] //
  // ////////////////////////////////////////////////////////////////
  override val secondary: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "secondary",
          palette = { scheme -> scheme.secondaryPalette },
          tone = { scheme ->
            when {
              scheme.platform == Platform.WATCH ->
                if (scheme.variant == Variant.NEUTRAL) {
                  90.0
                } else {
                  tMaxC(scheme.secondaryPalette, 0.0, 90.0)
                }
              scheme.variant == Variant.NEUTRAL ->
                if (scheme.isDark) {
                  tMinC(scheme.secondaryPalette, 0.0, 98.0)
                } else {
                  tMaxC(scheme.secondaryPalette)
                }
              scheme.variant == Variant.VIBRANT ->
                tMaxC(scheme.secondaryPalette, 0.0, if (scheme.isDark) 90.0 else 98.0)
              else -> { // EXPRESSIVE and TONAL_SPOT
                if (scheme.isDark) 80.0 else tMaxC(scheme.secondaryPalette)
              }
            }
          },
          isBackground = true,
          background = { scheme ->
            if (scheme.platform == Platform.PHONE) {
              if (scheme.isDark) surfaceBright else surfaceDim
            } else {
              surfaceContainerHigh
            }
          },
          contrastCurve = { scheme ->
            if (scheme.platform == Platform.PHONE) getContrastCurve(4.5) else getContrastCurve(7.0)
          },
          toneDeltaPair = { scheme ->
            if (scheme.platform == Platform.PHONE) {
              ToneDeltaPair(
                roleA = secondaryContainer,
                roleB = secondary,
                delta = 5.0,
                polarity = TonePolarity.RELATIVE_LIGHTER,
                constraint = DeltaConstraint.FARTHER,
              )
            } else {
              null
            }
          },
        )
      return super.secondary.extendSpecVersion(ColorSpec.SpecVersion.SPEC_2025, color2025)
    }

  override val secondaryDim: DynamicColor
    get() {
      return DynamicColor(
        name = "secondary_dim",
        palette = { scheme -> scheme.secondaryPalette },
        tone = { scheme ->
          if (scheme.variant == Variant.NEUTRAL) {
            85.0
          } else {
            tMaxC(scheme.secondaryPalette, 0.0, 90.0)
          }
        },
        isBackground = true,
        background = { surfaceContainerHigh },
        contrastCurve = { getContrastCurve(4.5) },
        toneDeltaPair = {
          ToneDeltaPair(
            roleA = secondaryDim,
            roleB = secondary,
            delta = 5.0,
            polarity = TonePolarity.DARKER,
            constraint = DeltaConstraint.FARTHER,
          )
        },
      )
    }

  override val onSecondary: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "on_secondary",
          palette = { scheme -> scheme.secondaryPalette },
          background = { scheme ->
            if (scheme.platform == Platform.PHONE) secondary else secondaryDim
          },
          contrastCurve = { scheme ->
            if (scheme.platform == Platform.PHONE) getContrastCurve(6.0) else getContrastCurve(7.0)
          },
        )
      return super.onSecondary.extendSpecVersion(ColorSpec.SpecVersion.SPEC_2025, color2025)
    }

  override val secondaryContainer: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "secondary_container",
          palette = { scheme -> scheme.secondaryPalette },
          tone = { scheme ->
            when {
              scheme.platform == Platform.WATCH -> 30.0
              scheme.variant == Variant.VIBRANT ->
                if (scheme.isDark) {
                  tMinC(scheme.secondaryPalette, 30.0, 40.0)
                } else {
                  tMaxC(scheme.secondaryPalette, 84.0, 90.0)
                }
              scheme.variant == Variant.EXPRESSIVE ->
                if (scheme.isDark) 15.0 else tMaxC(scheme.secondaryPalette, 90.0, 95.0)
              else -> if (scheme.isDark) 25.0 else 90.0
            }
          },
          isBackground = true,
          background = { scheme ->
            if (scheme.platform == Platform.PHONE) {
              if (scheme.isDark) surfaceBright else surfaceDim
            } else {
              null
            }
          },
          toneDeltaPair = { scheme ->
            if (scheme.platform == Platform.WATCH) {
              ToneDeltaPair(
                roleA = secondaryContainer,
                roleB = secondaryDim!!,
                delta = 10.0,
                polarity = TonePolarity.DARKER,
                constraint = DeltaConstraint.FARTHER,
              )
            } else {
              null
            }
          },
          contrastCurve = { scheme ->
            if (scheme.platform == Platform.PHONE && scheme.contrastLevel > 0) {
              getContrastCurve(1.5)
            } else {
              null
            }
          },
        )
      return super.secondaryContainer.extendSpecVersion(ColorSpec.SpecVersion.SPEC_2025, color2025)
    }

  override val onSecondaryContainer: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "on_secondary_container",
          palette = { scheme -> scheme.secondaryPalette },
          background = { secondaryContainer },
          contrastCurve = { scheme ->
            if (scheme.platform == Platform.PHONE) getContrastCurve(6.0) else getContrastCurve(7.0)
          },
        )
      return super.onSecondaryContainer.extendSpecVersion(
        ColorSpec.SpecVersion.SPEC_2025,
        color2025,
      )
    }

  // ////////////////////////////////////////////////////////////////
  // Tertiaries [T] //
  // ////////////////////////////////////////////////////////////////
  override val tertiary: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "tertiary",
          palette = { scheme -> scheme.tertiaryPalette },
          tone = { scheme ->
            when {
              scheme.platform == Platform.WATCH ->
                if (scheme.variant == Variant.TONAL_SPOT) {
                  tMaxC(scheme.tertiaryPalette, 0.0, 90.0)
                } else {
                  tMaxC(scheme.tertiaryPalette)
                }
              scheme.variant == Variant.EXPRESSIVE || scheme.variant == Variant.VIBRANT ->
                tMaxC(
                  scheme.tertiaryPalette,
                  lowerBound = 0.0,
                  upperBound =
                    if (Hct.isCyan(scheme.tertiaryPalette.hue)) {
                      88.0
                    } else if (scheme.isDark) {
                      98.0
                    } else {
                      100.0
                    },
                )
              else -> { // NEUTRAL and TONAL_SPOT
                if (scheme.isDark) {
                  tMaxC(scheme.tertiaryPalette, 0.0, 98.0)
                } else {
                  tMaxC(scheme.tertiaryPalette)
                }
              }
            }
          },
          isBackground = true,
          background = { scheme ->
            if (scheme.platform == Platform.PHONE) {
              if (scheme.isDark) surfaceBright else surfaceDim
            } else {
              surfaceContainerHigh
            }
          },
          contrastCurve = { scheme ->
            if (scheme.platform == Platform.PHONE) getContrastCurve(4.5) else getContrastCurve(7.0)
          },
          toneDeltaPair = { scheme ->
            if (scheme.platform == Platform.PHONE) {
              ToneDeltaPair(
                roleA = tertiaryContainer,
                roleB = tertiary,
                delta = 5.0,
                polarity = TonePolarity.RELATIVE_LIGHTER,
                constraint = DeltaConstraint.FARTHER,
              )
            } else {
              null
            }
          },
        )
      return super.tertiary.extendSpecVersion(ColorSpec.SpecVersion.SPEC_2025, color2025)
    }

  override val tertiaryDim: DynamicColor
    get() {
      return DynamicColor(
        name = "tertiary_dim",
        palette = { scheme -> scheme.tertiaryPalette },
        tone = { scheme ->
          if (scheme.variant == Variant.TONAL_SPOT) {
            tMaxC(scheme.tertiaryPalette, 0.0, 90.0)
          } else {
            tMaxC(scheme.tertiaryPalette)
          }
        },
        isBackground = true,
        background = { surfaceContainerHigh },
        contrastCurve = { getContrastCurve(4.5) },
        toneDeltaPair = {
          ToneDeltaPair(
            roleA = tertiaryDim,
            roleB = tertiary,
            delta = 5.0,
            polarity = TonePolarity.DARKER,
            constraint = DeltaConstraint.FARTHER,
          )
        },
      )
    }

  override val onTertiary: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "on_tertiary",
          palette = { scheme -> scheme.tertiaryPalette },
          background = { scheme ->
            if (scheme.platform == Platform.PHONE) tertiary else tertiaryDim
          },
          contrastCurve = { scheme ->
            if (scheme.platform == Platform.PHONE) getContrastCurve(6.0) else getContrastCurve(7.0)
          },
        )
      return super.onTertiary.extendSpecVersion(ColorSpec.SpecVersion.SPEC_2025, color2025)
    }

  override val tertiaryContainer: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "tertiary_container",
          palette = { scheme -> scheme.tertiaryPalette },
          tone = { scheme ->
            when {
              scheme.platform == Platform.WATCH ->
                if (scheme.variant == Variant.TONAL_SPOT) {
                  tMaxC(scheme.tertiaryPalette, 0.0, 90.0)
                } else {
                  tMaxC(scheme.tertiaryPalette)
                }
              scheme.variant == Variant.NEUTRAL ->
                if (scheme.isDark) {
                  tMaxC(scheme.tertiaryPalette, 0.0, 93.0)
                } else {
                  tMaxC(scheme.tertiaryPalette, 0.0, 96.0)
                }
              scheme.variant == Variant.TONAL_SPOT ->
                tMaxC(scheme.tertiaryPalette, 0.0, if (scheme.isDark) 93.0 else 100.0)
              scheme.variant == Variant.EXPRESSIVE ->
                tMaxC(
                  scheme.tertiaryPalette,
                  lowerBound = 75.0,
                  upperBound =
                    if (Hct.isCyan(scheme.tertiaryPalette.hue)) {
                      88.0
                    } else if (scheme.isDark) {
                      93.0
                    } else {
                      100.0
                    },
                )
              else -> { // VIBRANT
                if (scheme.isDark) {
                  tMaxC(scheme.tertiaryPalette, 0.0, 93.0)
                } else {
                  tMaxC(scheme.tertiaryPalette, 72.0, 100.0)
                }
              }
            }
          },
          isBackground = true,
          background = { scheme ->
            if (scheme.platform == Platform.PHONE) {
              if (scheme.isDark) surfaceBright else surfaceDim
            } else {
              null
            }
          },
          toneDeltaPair = { scheme ->
            if (scheme.platform == Platform.WATCH) {
              ToneDeltaPair(
                roleA = tertiaryContainer,
                roleB = tertiaryDim!!,
                delta = 10.0,
                polarity = TonePolarity.DARKER,
                constraint = DeltaConstraint.FARTHER,
              )
            } else {
              null
            }
          },
          contrastCurve = { scheme ->
            if (scheme.platform == Platform.PHONE && scheme.contrastLevel > 0) {
              getContrastCurve(1.5)
            } else {
              null
            }
          },
        )
      return super.tertiaryContainer.extendSpecVersion(ColorSpec.SpecVersion.SPEC_2025, color2025)
    }

  override val onTertiaryContainer: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "on_tertiary_container",
          palette = { scheme -> scheme.tertiaryPalette },
          background = { tertiaryContainer },
          contrastCurve = { scheme ->
            if (scheme.platform == Platform.PHONE) getContrastCurve(6.0) else getContrastCurve(7.0)
          },
        )
      return super.onTertiaryContainer.extendSpecVersion(ColorSpec.SpecVersion.SPEC_2025, color2025)
    }

  // ////////////////////////////////////////////////////////////////
  // Errors [E] //
  // ////////////////////////////////////////////////////////////////
  override val error: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "error",
          palette = { scheme -> scheme.errorPalette },
          tone = { scheme ->
            if (scheme.platform == Platform.PHONE) {
              if (scheme.isDark) {
                tMinC(scheme.errorPalette, 0.0, 98.0)
              } else {
                tMaxC(scheme.errorPalette)
              }
            } else {
              tMinC(scheme.errorPalette)
            }
          },
          isBackground = true,
          background = { scheme ->
            if (scheme.platform == Platform.PHONE) {
              if (scheme.isDark) surfaceBright else surfaceDim
            } else {
              surfaceContainerHigh
            }
          },
          contrastCurve = { scheme ->
            if (scheme.platform == Platform.PHONE) getContrastCurve(4.5) else getContrastCurve(7.0)
          },
          toneDeltaPair = { scheme ->
            if (scheme.platform == Platform.PHONE) {
              ToneDeltaPair(
                roleA = errorContainer,
                roleB = error,
                delta = 5.0,
                polarity = TonePolarity.RELATIVE_LIGHTER,
                constraint = DeltaConstraint.FARTHER,
              )
            } else {
              null
            }
          },
        )
      return super.error.extendSpecVersion(ColorSpec.SpecVersion.SPEC_2025, color2025)
    }

  override val errorDim: DynamicColor
    get() {
      return DynamicColor(
        name = "error_dim",
        palette = { scheme -> scheme.errorPalette },
        tone = { scheme -> tMinC(scheme.errorPalette) },
        isBackground = true,
        background = { surfaceContainerHigh },
        contrastCurve = { getContrastCurve(4.5) },
        toneDeltaPair = {
          ToneDeltaPair(
            roleA = errorDim,
            roleB = error,
            delta = 5.0,
            polarity = TonePolarity.DARKER,
            constraint = DeltaConstraint.FARTHER,
          )
        },
      )
    }

  override val onError: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "on_error",
          palette = { scheme -> scheme.errorPalette },
          background = { scheme -> if (scheme.platform == Platform.PHONE) error else errorDim },
          contrastCurve = { scheme ->
            if (scheme.platform == Platform.PHONE) getContrastCurve(6.0) else getContrastCurve(7.0)
          },
        )
      return super.onError.extendSpecVersion(ColorSpec.SpecVersion.SPEC_2025, color2025)
    }

  override val errorContainer: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "error_container",
          palette = { scheme -> scheme.errorPalette },
          tone = { scheme ->
            if (scheme.platform == Platform.WATCH) {
              30.0
            } else {
              if (scheme.isDark) {
                tMinC(scheme.errorPalette, 30.0, 93.0)
              } else {
                tMaxC(scheme.errorPalette, 0.0, 90.0)
              }
            }
          },
          isBackground = true,
          background = { scheme ->
            if (scheme.platform == Platform.PHONE) {
              if (scheme.isDark) surfaceBright else surfaceDim
            } else {
              null
            }
          },
          toneDeltaPair = { scheme ->
            if (scheme.platform == Platform.WATCH) {
              ToneDeltaPair(
                roleA = errorContainer,
                roleB = errorDim!!,
                delta = 10.0,
                polarity = TonePolarity.DARKER,
                constraint = DeltaConstraint.FARTHER,
              )
            } else {
              null
            }
          },
          contrastCurve = { scheme ->
            if (scheme.platform == Platform.PHONE && scheme.contrastLevel > 0) {
              getContrastCurve(1.5)
            } else {
              null
            }
          },
        )
      return super.errorContainer.extendSpecVersion(ColorSpec.SpecVersion.SPEC_2025, color2025)
    }

  override val onErrorContainer: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "on_error_container",
          palette = { scheme -> scheme.errorPalette },
          background = { errorContainer },
          contrastCurve = { scheme ->
            if (scheme.platform == Platform.PHONE) getContrastCurve(4.5) else getContrastCurve(7.0)
          },
        )
      return super.onErrorContainer.extendSpecVersion(ColorSpec.SpecVersion.SPEC_2025, color2025)
    }

  // ////////////////////////////////////////////////////////////////
  // Primary Fixed Colors [PF] //
  // ////////////////////////////////////////////////////////////////
  override val primaryFixed: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "primary_fixed",
          palette = { scheme -> scheme.primaryPalette },
          tone = { scheme ->
            val tempS = DynamicScheme.from(scheme, isDark = false, contrastLevel = 0.0)
            primaryContainer.getTone(tempS)
          },
          isBackground = true,
          background = { scheme ->
            if (scheme.platform == Platform.PHONE) {
              if (scheme.isDark) surfaceBright else surfaceDim
            } else {
              null
            }
          },
          contrastCurve = { scheme ->
            if (scheme.platform == Platform.PHONE && scheme.contrastLevel > 0) {
              getContrastCurve(1.5)
            } else {
              null
            }
          },
        )
      return super.primaryFixed.extendSpecVersion(ColorSpec.SpecVersion.SPEC_2025, color2025)
    }

  override val primaryFixedDim: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "primary_fixed_dim",
          palette = { scheme -> scheme.primaryPalette },
          tone = { scheme -> primaryFixed.getTone(scheme) },
          isBackground = true,
          toneDeltaPair = {
            ToneDeltaPair(
              roleA = primaryFixedDim,
              roleB = primaryFixed,
              delta = 5.0,
              polarity = TonePolarity.DARKER,
              constraint = DeltaConstraint.EXACT,
            )
          },
        )
      return super.primaryFixedDim.extendSpecVersion(ColorSpec.SpecVersion.SPEC_2025, color2025)
    }

  override val onPrimaryFixed: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "on_primary_fixed",
          palette = { scheme -> scheme.primaryPalette },
          background = { primaryFixedDim },
          contrastCurve = { getContrastCurve(7.0) },
        )
      return super.onPrimaryFixed.extendSpecVersion(ColorSpec.SpecVersion.SPEC_2025, color2025)
    }

  override val onPrimaryFixedVariant: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "on_primary_fixed_variant",
          palette = { scheme -> scheme.primaryPalette },
          background = { primaryFixedDim },
          contrastCurve = { getContrastCurve(4.5) },
        )
      return super.onPrimaryFixedVariant.extendSpecVersion(
        ColorSpec.SpecVersion.SPEC_2025,
        color2025,
      )
    }

  // ////////////////////////////////////////////////////////////////
  // Secondary Fixed Colors [QF] //
  // ////////////////////////////////////////////////////////////////
  override val secondaryFixed: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "secondary_fixed",
          palette = { scheme -> scheme.secondaryPalette },
          tone = { scheme ->
            val tempS = DynamicScheme.from(scheme, isDark = false, contrastLevel = 0.0)
            secondaryContainer.getTone(tempS)
          },
          isBackground = true,
          background = { scheme ->
            if (scheme.platform == Platform.PHONE) {
              if (scheme.isDark) surfaceBright else surfaceDim
            } else {
              null
            }
          },
          contrastCurve = { scheme ->
            if (scheme.platform == Platform.PHONE && scheme.contrastLevel > 0) {
              getContrastCurve(1.5)
            } else {
              null
            }
          },
        )
      return super.secondaryFixed.extendSpecVersion(ColorSpec.SpecVersion.SPEC_2025, color2025)
    }

  override val secondaryFixedDim: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "secondary_fixed_dim",
          palette = { scheme -> scheme.secondaryPalette },
          tone = { scheme -> secondaryFixed.getTone(scheme) },
          isBackground = true,
          toneDeltaPair = {
            ToneDeltaPair(
              roleA = secondaryFixedDim,
              roleB = secondaryFixed,
              delta = 5.0,
              polarity = TonePolarity.DARKER,
              constraint = DeltaConstraint.EXACT,
            )
          },
        )
      return super.secondaryFixedDim.extendSpecVersion(ColorSpec.SpecVersion.SPEC_2025, color2025)
    }

  override val onSecondaryFixed: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "on_secondary_fixed",
          palette = { scheme -> scheme.secondaryPalette },
          background = { secondaryFixedDim },
          contrastCurve = { getContrastCurve(7.0) },
        )
      return super.onSecondaryFixed.extendSpecVersion(ColorSpec.SpecVersion.SPEC_2025, color2025)
    }

  override val onSecondaryFixedVariant: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "on_secondary_fixed_variant",
          palette = { scheme -> scheme.secondaryPalette },
          background = { secondaryFixedDim },
          contrastCurve = { getContrastCurve(4.5) },
        )
      return super.onSecondaryFixedVariant.extendSpecVersion(
        ColorSpec.SpecVersion.SPEC_2025,
        color2025,
      )
    }

  // ////////////////////////////////////////////////////////////////
  // Tertiary Fixed Colors [TF] //
  // ////////////////////////////////////////////////////////////////
  override val tertiaryFixed: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "tertiary_fixed",
          palette = { scheme -> scheme.tertiaryPalette },
          tone = { scheme ->
            val tempS = DynamicScheme.from(scheme, isDark = false, contrastLevel = 0.0)
            tertiaryContainer.getTone(tempS)
          },
          isBackground = true,
          background = { scheme ->
            if (scheme.platform == Platform.PHONE) {
              if (scheme.isDark) surfaceBright else surfaceDim
            } else {
              null
            }
          },
          contrastCurve = { scheme ->
            if (scheme.platform == Platform.PHONE && scheme.contrastLevel > 0) {
              getContrastCurve(1.5)
            } else {
              null
            }
          },
        )
      return super.tertiaryFixed.extendSpecVersion(ColorSpec.SpecVersion.SPEC_2025, color2025)
    }

  override val tertiaryFixedDim: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "tertiary_fixed_dim",
          palette = { scheme -> scheme.tertiaryPalette },
          tone = { scheme -> tertiaryFixed.getTone(scheme) },
          isBackground = true,
          toneDeltaPair = {
            ToneDeltaPair(
              roleA = tertiaryFixedDim,
              roleB = tertiaryFixed,
              delta = 5.0,
              polarity = TonePolarity.DARKER,
              constraint = DeltaConstraint.EXACT,
            )
          },
        )
      return super.tertiaryFixedDim.extendSpecVersion(ColorSpec.SpecVersion.SPEC_2025, color2025)
    }

  override val onTertiaryFixed: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "on_tertiary_fixed",
          palette = { scheme -> scheme.tertiaryPalette },
          background = { tertiaryFixedDim },
          contrastCurve = { getContrastCurve(7.0) },
        )
      return super.onTertiaryFixed.extendSpecVersion(ColorSpec.SpecVersion.SPEC_2025, color2025)
    }

  override val onTertiaryFixedVariant: DynamicColor
    get() {
      val color2025 =
        DynamicColor(
          name = "on_tertiary_fixed_variant",
          palette = { scheme -> scheme.tertiaryPalette },
          background = { tertiaryFixedDim },
          contrastCurve = { getContrastCurve(4.5) },
        )
      return super.onTertiaryFixedVariant.extendSpecVersion(
        ColorSpec.SpecVersion.SPEC_2025,
        color2025,
      )
    }

  // /////////////////////////////////////////////////////////////////
  // Color value calculations //
  // /////////////////////////////////////////////////////////////////
  override fun getHct(scheme: DynamicScheme, color: DynamicColor): Hct {
    // This is crucial for aesthetics: we aren't simply taking the standard color
    // and changing its tone for contrast. Rather, we find the tone for contrast, then
    // use the specified chroma from the palette to construct a new color.
    //
    // For example, this enables colors with standard tone of T90, which has limited chroma, to
    // "recover" intended chroma as contrast increases.
    val palette = color.palette.invoke(scheme)
    val tone = getTone(scheme, color)
    val chromaMultiplier = color.chromaMultiplier?.invoke(scheme) ?: 1.0
    if (chromaMultiplier == 1.0) {
      return palette.getHct(tone)
    }

    val chroma = palette.chroma * chromaMultiplier
    if (tone == 99.0 && Hct.isYellow(palette.hue)) {
      return TonalPalette.fromHueAndChroma(palette.hue, chroma).getHct(tone)
    }
    return Hct.from(palette.hue, chroma, tone)
  }

  override fun getTone(scheme: DynamicScheme, color: DynamicColor): Double {
    val toneDeltaPair = color.toneDeltaPair?.invoke(scheme)

    // Case 0: tone delta pair.
    if (toneDeltaPair != null) {
      val roleA = toneDeltaPair.roleA
      val roleB = toneDeltaPair.roleB
      val polarity = toneDeltaPair.polarity
      val constraint = toneDeltaPair.constraint
      val absoluteDelta =
        if (
          polarity == TonePolarity.DARKER ||
            (polarity == TonePolarity.RELATIVE_LIGHTER && scheme.isDark) ||
            (polarity == TonePolarity.RELATIVE_DARKER && !scheme.isDark)
        ) {
          -toneDeltaPair.delta
        } else {
          toneDeltaPair.delta
        }
      val amRoleA = color.name == roleA.name
      val selfRole = if (amRoleA) roleA else roleB
      val referenceRole = if (amRoleA) roleB else roleA
      var selfTone = selfRole.tone.invoke(scheme)
      val referenceTone = referenceRole.getTone(scheme)
      val relativeDelta = absoluteDelta * (if (amRoleA) 1 else -1)
      when (constraint) {
        DeltaConstraint.EXACT -> selfTone = (referenceTone + relativeDelta).coerceIn(0.0, 100.0)
        DeltaConstraint.NEARER ->
          if (relativeDelta > 0) {
            selfTone =
              selfTone.coerceIn(referenceTone, referenceTone + relativeDelta).coerceIn(0.0, 100.0)
          } else {
            selfTone =
              selfTone.coerceIn(referenceTone + relativeDelta, referenceTone).coerceIn(0.0, 100.0)
          }
        DeltaConstraint.FARTHER ->
          if (relativeDelta > 0) {
            selfTone = selfTone.coerceIn(referenceTone + relativeDelta, 100.0)
          } else {
            selfTone = selfTone.coerceIn(0.0, referenceTone + relativeDelta)
          }
      }
      val background = color.background?.invoke(scheme)
      val contrastCurve = color.contrastCurve?.invoke(scheme)
      if (background != null && contrastCurve != null) {
        val bgTone = background.getTone(scheme)
        val selfContrast = contrastCurve.get(scheme.contrastLevel)
        selfTone =
          if (
            Contrast.ratioOfTones(bgTone, selfTone) >= selfContrast && scheme.contrastLevel >= 0
          ) {
            selfTone
          } else {
            DynamicColor.foregroundTone(bgTone, selfContrast)
          }
      }

      // This can avoid the awkward tones for background colors including the access fixed colors.
      // Accent fixed dim colors should not be adjusted.
      if (color.isBackground && !color.name.endsWith("_fixed_dim")) {
        selfTone =
          if (selfTone >= 57) {
            selfTone.coerceIn(65.0, 100.0)
          } else {
            selfTone.coerceIn(0.0, 49.0)
          }
      }
      return selfTone
    } else {
      // Case 1: No tone delta pair; just solve for itself.
      var answer = color.tone.invoke(scheme)
      val background = color.background?.invoke(scheme)
      val contrastCurve = color.contrastCurve?.invoke(scheme)
      if (background == null || contrastCurve == null) {
        return answer // No adjustment for colors with no background.
      }
      val bgTone = background.getTone(scheme)
      val desiredRatio = contrastCurve.get(scheme.contrastLevel)

      // Recalculate the tone from desired contrast ratio if the current
      // contrast ratio is not enough or desired contrast level is decreasing
      // (<0).
      answer =
        if (Contrast.ratioOfTones(bgTone, answer) >= desiredRatio && scheme.contrastLevel >= 0) {
          answer
        } else {
          DynamicColor.foregroundTone(bgTone, desiredRatio)
        }

      // This can avoid the awkward tones for background colors including the access fixed colors.
      // Accent fixed dim colors should not be adjusted.
      if (color.isBackground && !color.name.endsWith("_fixed_dim")) {
        answer =
          if (answer >= 57) {
            answer.coerceIn(65.0, 100.0)
          } else {
            answer.coerceIn(0.0, 49.0)
          }
      }
      val secondBackground = color.secondBackground?.invoke(scheme)
      if (secondBackground == null) {
        return answer
      }

      // Case 2: Adjust for dual backgrounds.
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
      Variant.NEUTRAL ->
        TonalPalette.fromHueAndChroma(
          sourceColorHct.hue,
          if (platform == Platform.PHONE) {
            (if (Hct.isBlue(sourceColorHct.hue)) 12.0 else 8.0)
          } else if (Hct.isBlue(sourceColorHct.hue)) {
            16.0
          } else {
            12.0
          },
        )
      Variant.TONAL_SPOT ->
        TonalPalette.fromHueAndChroma(
          sourceColorHct.hue,
          if (platform == Platform.PHONE && isDark) 26.0 else 32.0,
        )
      Variant.EXPRESSIVE ->
        TonalPalette.fromHueAndChroma(
          sourceColorHct.hue,
          if (platform == Platform.PHONE) {
            if (isDark) 36.0 else 48.0
          } else {
            40.0
          },
        )
      Variant.VIBRANT ->
        TonalPalette.fromHueAndChroma(
          sourceColorHct.hue,
          if (platform == Platform.PHONE) 74.0 else 56.0,
        )
      else -> super.getPrimaryPalette(variant, sourceColorHct, isDark, platform, contrastLevel)
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
      Variant.NEUTRAL ->
        TonalPalette.fromHueAndChroma(
          sourceColorHct.hue,
          if (platform == Platform.PHONE) {
            (if (Hct.isBlue(sourceColorHct.hue)) 6.0 else 4.0)
          } else if (Hct.isBlue(sourceColorHct.hue)) {
            10.0
          } else {
            6.0
          },
        )
      Variant.TONAL_SPOT -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 16.0)
      Variant.EXPRESSIVE ->
        TonalPalette.fromHueAndChroma(
          DynamicScheme.getRotatedHue(
            sourceColorHct,
            doubleArrayOf(0.0, 105.0, 140.0, 204.0, 253.0, 278.0, 300.0, 333.0, 360.0),
            doubleArrayOf(-160.0, 155.0, -100.0, 96.0, -96.0, -156.0, -165.0, -160.0),
          ),
          if (platform == Platform.PHONE) {
            if (isDark) 16.0 else 24.0
          } else {
            24.0
          },
        )
      Variant.VIBRANT ->
        TonalPalette.fromHueAndChroma(
          DynamicScheme.getRotatedHue(
            sourceColorHct,
            doubleArrayOf(0.0, 38.0, 105.0, 140.0, 333.0, 360.0),
            doubleArrayOf(-14.0, 10.0, -14.0, 10.0, -14.0),
          ),
          if (platform == Platform.PHONE) 56.0 else 36.0,
        )
      else -> super.getSecondaryPalette(variant, sourceColorHct, isDark, platform, contrastLevel)
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
      Variant.NEUTRAL ->
        TonalPalette.fromHueAndChroma(
          DynamicScheme.getRotatedHue(
            sourceColorHct,
            doubleArrayOf(0.0, 38.0, 105.0, 161.0, 204.0, 278.0, 333.0, 360.0),
            doubleArrayOf(-32.0, 26.0, 10.0, -39.0, 24.0, -15.0, -32.0),
          ),
          if (platform == Platform.PHONE) 20.0 else 36.0,
        )
      Variant.TONAL_SPOT ->
        TonalPalette.fromHueAndChroma(
          DynamicScheme.getRotatedHue(
            sourceColorHct,
            doubleArrayOf(0.0, 20.0, 71.0, 161.0, 333.0, 360.0),
            doubleArrayOf(-40.0, 48.0, -32.0, 40.0, -32.0),
          ),
          if (platform == Platform.PHONE) 28.0 else 32.0,
        )
      Variant.EXPRESSIVE ->
        TonalPalette.fromHueAndChroma(
          DynamicScheme.getRotatedHue(
            sourceColorHct,
            doubleArrayOf(0.0, 105.0, 140.0, 204.0, 253.0, 278.0, 300.0, 333.0, 360.0),
            doubleArrayOf(-165.0, 160.0, -105.0, 101.0, -101.0, -160.0, -170.0, -165.0),
          ),
          48.0,
        )
      Variant.VIBRANT ->
        TonalPalette.fromHueAndChroma(
          DynamicScheme.getRotatedHue(
            sourceColorHct,
            doubleArrayOf(0.0, 38.0, 71.0, 105.0, 140.0, 161.0, 253.0, 333.0, 360.0),
            doubleArrayOf(-72.0, 35.0, 24.0, -24.0, 62.0, 50.0, 62.0, -72.0),
          ),
          56.0,
        )
      else -> super.getTertiaryPalette(variant, sourceColorHct, isDark, platform, contrastLevel)
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
      Variant.NEUTRAL ->
        TonalPalette.fromHueAndChroma(
          sourceColorHct.hue,
          if (platform == Platform.PHONE) 1.4 else 6.0,
        )
      Variant.TONAL_SPOT ->
        TonalPalette.fromHueAndChroma(
          sourceColorHct.hue,
          if (platform == Platform.PHONE) 5.0 else 10.0,
        )
      Variant.EXPRESSIVE ->
        TonalPalette.fromHueAndChroma(
          getExpressiveNeutralHue(sourceColorHct),
          getExpressiveNeutralChroma(sourceColorHct, isDark, platform),
        )
      Variant.VIBRANT ->
        TonalPalette.fromHueAndChroma(
          getVibrantNeutralHue(sourceColorHct),
          getVibrantNeutralChroma(sourceColorHct, platform),
        )
      else -> super.getNeutralPalette(variant, sourceColorHct, isDark, platform, contrastLevel)
    }
  }

  override fun getNeutralVariantPalette(
    variant: Variant,
    sourceColorHct: Hct,
    isDark: Boolean,
    platform: Platform,
    contrastLevel: Double,
  ): TonalPalette {
    when (variant) {
      Variant.NEUTRAL ->
        return TonalPalette.fromHueAndChroma(
          sourceColorHct.hue,
          (if (platform == Platform.PHONE) 1.4 else 6.0) * 2.2,
        )
      Variant.TONAL_SPOT ->
        return TonalPalette.fromHueAndChroma(
          sourceColorHct.hue,
          (if (platform == Platform.PHONE) 5.0 else 10.0) * 1.7,
        )
      Variant.EXPRESSIVE -> {
        val expressiveNeutralHue = getExpressiveNeutralHue(sourceColorHct)
        val expressiveNeutralChroma = getExpressiveNeutralChroma(sourceColorHct, isDark, platform)
        return TonalPalette.fromHueAndChroma(
          expressiveNeutralHue,
          expressiveNeutralChroma *
            if (expressiveNeutralHue >= 105 && expressiveNeutralHue < 125) 1.6 else 2.3,
        )
      }
      Variant.VIBRANT -> {
        val vibrantNeutralHue = getVibrantNeutralHue(sourceColorHct)
        val vibrantNeutralChroma = getVibrantNeutralChroma(sourceColorHct, platform)
        return TonalPalette.fromHueAndChroma(vibrantNeutralHue, vibrantNeutralChroma * 1.29)
      }
      else ->
        return super.getNeutralVariantPalette(
          variant,
          sourceColorHct,
          isDark,
          platform,
          contrastLevel,
        )
    }
  }

  override fun getErrorPalette(
    variant: Variant,
    sourceColorHct: Hct,
    isDark: Boolean,
    platform: Platform,
    contrastLevel: Double,
  ): TonalPalette {
    val errorHue =
      DynamicScheme.getPiecewiseValue(
        sourceColorHct,
        doubleArrayOf(0.0, 3.0, 13.0, 23.0, 33.0, 43.0, 153.0, 273.0, 360.0),
        doubleArrayOf(12.0, 22.0, 32.0, 12.0, 22.0, 32.0, 22.0, 12.0),
      )
    return when (variant) {
      Variant.NEUTRAL ->
        TonalPalette.fromHueAndChroma(errorHue, if (platform == Platform.PHONE) 50.0 else 40.0)
      Variant.TONAL_SPOT ->
        TonalPalette.fromHueAndChroma(errorHue, if (platform == Platform.PHONE) 60.0 else 48.0)
      Variant.EXPRESSIVE ->
        TonalPalette.fromHueAndChroma(errorHue, if (platform == Platform.PHONE) 64.0 else 48.0)
      Variant.VIBRANT ->
        TonalPalette.fromHueAndChroma(errorHue, if (platform == Platform.PHONE) 80.0 else 60.0)
      else -> super.getErrorPalette(variant, sourceColorHct, isDark, platform, contrastLevel)
    }
  }

  private fun getExpressiveNeutralHue(sourceColorHct: Hct): Double {
    return DynamicScheme.getRotatedHue(
      sourceColorHct,
      doubleArrayOf(0.0, 71.0, 124.0, 253.0, 278.0, 300.0, 360.0),
      doubleArrayOf(10.0, 0.0, 10.0, 0.0, 10.0, 0.0),
    )
  }

  private fun getExpressiveNeutralChroma(
    sourceColorHct: Hct,
    isDark: Boolean,
    platform: Platform,
  ): Double {
    val neutralHue = getExpressiveNeutralHue(sourceColorHct)
    return if (platform == Platform.PHONE) {
      if (isDark) {
        if (Hct.isYellow(neutralHue)) 6.0 else 14.0
      } else {
        18.0
      }
    } else {
      12.0
    }
  }

  private fun getVibrantNeutralHue(sourceColorHct: Hct): Double {
    return DynamicScheme.getRotatedHue(
      sourceColorHct,
      doubleArrayOf(0.0, 38.0, 105.0, 140.0, 333.0, 360.0),
      doubleArrayOf(-14.0, 10.0, -14.0, 10.0, -14.0),
    )
  }

  private fun getVibrantNeutralChroma(sourceColorHct: Hct, platform: Platform): Double {
    val neutralHue = getVibrantNeutralHue(sourceColorHct)
    return if (platform == Platform.PHONE) 28.0 else if (Hct.isBlue(neutralHue)) 28.0 else 20.0
  }

  private fun tMaxC(
    palette: TonalPalette,
    lowerBound: Double = 0.0,
    upperBound: Double = 100.0,
    chromaMultiplier: Double = 1.0,
  ): Double {
    val answer = findBestToneForChroma(palette.hue, palette.chroma * chromaMultiplier, 100.0, true)
    return answer.coerceIn(lowerBound, upperBound)
  }

  private fun tMinC(
    palette: TonalPalette,
    lowerBound: Double = 0.0,
    upperBound: Double = 100.0,
  ): Double {
    val answer = findBestToneForChroma(palette.hue, palette.chroma, 0.0, false)
    return answer.coerceIn(lowerBound, upperBound)
  }

  private fun findBestToneForChroma(
    hue: Double,
    chroma: Double,
    tone: Double,
    byDecreasingTone: Boolean,
  ): Double {
    var tone = tone
    var answer = tone
    var bestCandidate = Hct.from(hue, chroma, answer)
    while (bestCandidate.chroma < chroma) {
      if (tone < 0 || tone > 100) {
        break
      }
      tone += if (byDecreasingTone) -1.0 else 1.0
      val newCandidate = Hct.from(hue, chroma, tone)
      if (bestCandidate.chroma < newCandidate.chroma) {
        bestCandidate = newCandidate
        answer = tone
      }
    }
    return answer
  }

  private fun getContrastCurve(defaultContrast: Double): ContrastCurve {
    return when (defaultContrast) {
      1.5 -> ContrastCurve(1.5, 1.5, 3.0, 5.5)
      3.0 -> ContrastCurve(3.0, 3.0, 4.5, 7.0)
      4.5 -> ContrastCurve(4.5, 4.5, 7.0, 11.0)
      6.0 -> ContrastCurve(6.0, 6.0, 7.0, 11.0)
      7.0 -> ContrastCurve(7.0, 7.0, 11.0, 21.0)
      9.0 -> ContrastCurve(9.0, 9.0, 11.0, 21.0)
      11.0 -> ContrastCurve(11.0, 11.0, 21.0, 21.0)
      21.0 -> ContrastCurve(21.0, 21.0, 21.0, 21.0)
      else -> ContrastCurve(defaultContrast, defaultContrast, 7.0, 21.0)
    }
  }
}
