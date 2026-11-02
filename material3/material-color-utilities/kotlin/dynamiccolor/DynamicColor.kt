/*
 * Copyright 2022 Google LLC
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
import dynamiccolor.ColorSpec.SpecVersion
import hct.Hct
import palettes.TonalPalette
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A color that adjusts itself based on UI state, represented by DynamicScheme.
 *
 * This color automatically adjusts to accommodate a desired contrast level, or other adjustments
 * such as differing in light mode versus dark mode, or what the theme is, or what the color that
 * produced the theme is, etc.
 *
 * Colors without backgrounds do not change tone when contrast changes. Colors with backgrounds
 * become closer to their background as contrast lowers, and further when contrast increases.
 *
 * For example, the default behavior of adjust tone at max contrast to be at a 7.0 ratio with its
 * background is principled and matches accessibility guidance. That does not mean it's the desired
 * approach for _every_ design system, and every color pairing, always, in every case.
 *
 * Ultimately, each component necessary for calculating a color, adjusting it for a desired contrast
 * level, and ensuring it has a certain lightness/tone difference from another color, is provided by
 * a function that takes a DynamicScheme and returns a value. This ensures ultimate flexibility, any
 * desired behavior of a color for any design system, but is usually unnecessary.
 *
 * @param name The name of the dynamic color.
 * @param palette Function that provides a TonalPalette given DynamicScheme. A TonalPalette is
 *   defined by a hue and chroma, so this replaces the need to specify hue/chroma. By providing a
 *   tonal palette, when contrast adjustments are made, intended chroma can be preserved.
 * @param isBackground Whether this dynamic color is a background, with some other color as the
 *   foreground.
 * @param chromaMultiplier Function that provides a chroma multiplier, given a DynamicScheme.
 * @param background Function that provides a background color, given a DynamicScheme.
 * @param tone Function that provides a tone, given a DynamicScheme.
 * @param secondBackground Function that provides a second background color, given a DynamicScheme.
 * @param contrastCurve Function that provides a contrast curve, given a DynamicScheme.
 * @param toneDeltaPair Function that provides a tone delta pair, given a DynamicScheme.
 * @param opacity Function that provides an opacity percentage, given a DynamicScheme.
 */
data class DynamicColor(
  val name: String,
  val palette: (DynamicScheme) -> TonalPalette,
  val isBackground: Boolean = false,
  val chromaMultiplier: ((DynamicScheme) -> Double)? = null,
  val background: ((DynamicScheme) -> DynamicColor?)? = null,
  val tone: (DynamicScheme) -> Double = getInitialToneFromBackground(background),
  val secondBackground: ((DynamicScheme) -> DynamicColor?)? = null,
  val contrastCurve: ((DynamicScheme) -> ContrastCurve?)? = null,
  val toneDeltaPair: ((DynamicScheme) -> ToneDeltaPair?)? = null,
  val opacity: ((DynamicScheme) -> Double?)? = null,
) {
  init {
    if (background == null && secondBackground != null) {
      throw IllegalArgumentException(
        "Color $name has secondBackground defined, but background is not defined."
      )
    }
    if (background == null && contrastCurve != null) {
      throw IllegalArgumentException(
        "Color $name has contrastCurve defined, but background is not defined."
      )
    }
    if (background != null && contrastCurve == null) {
      throw IllegalArgumentException(
        "Color $name has background defined, but contrastCurve is not defined."
      )
    }
  }

  private val hctCache = mutableMapOf<DynamicScheme, Hct>()

  /**
   * Returns an ARGB integer (i.e. a hex code).
   *
   * @param scheme Defines the conditions of the user interface, for example, whether or not it is
   *   dark mode or light mode, and what the desired contrast level is.
   */
  fun getArgb(scheme: DynamicScheme): Int {
    val argb = getHct(scheme).toInt()
    val opacityPercentage = opacity?.invoke(scheme)
    return if (opacityPercentage == null) {
      argb
    } else {
      val alpha = (opacityPercentage * 255).roundToInt().coerceIn(0, 255)
      (argb and 0x00ffffff) or (alpha shl 24)
    }
  }

  /**
   * Returns an HCT object.
   *
   * @param scheme Defines the conditions of the user interface, for example, whether or not it is
   *   dark mode or light mode, and what the desired contrast level is.
   */
  fun getHct(scheme: DynamicScheme): Hct {
    val cachedAnswer = hctCache[scheme]
    if (cachedAnswer != null) {
      return cachedAnswer
    }
    val answer = ColorSpecs.get(scheme.specVersion).getHct(scheme, this)
    // NOMUTANTS--trivial test with onerous dependency injection requirement.
    if (hctCache.size > 4) {
      hctCache.clear()
    }
    // NOMUTANTS--trivial test with onerous dependency injection requirement.
    hctCache[scheme] = answer
    return answer
  }

  /** Returns the tone in HCT, ranging from 0 to 100, of the resolved color given scheme. */
  fun getTone(scheme: DynamicScheme): Double {
    return ColorSpecs.get(scheme.specVersion).getTone(scheme, this)
  }

  companion object {
    /**
     * Create a DynamicColor from a hex code.
     *
     * Result has no background; thus no support for increasing/decreasing contrast for a11y.
     *
     * @param name The name of the dynamic color.
     * @param argb The source color from which to extract the hue and chroma.
     */
    @JvmStatic
    fun fromArgb(name: String, argb: Int): DynamicColor {
      val hct = Hct.fromInt(argb)
      val palette = TonalPalette.fromInt(argb)
      return DynamicColor(name = name, palette = { palette }, tone = { hct.tone })
    }

    /**
     * Given a background tone, find a foreground tone, while ensuring they reach a contrast ratio
     * that is as close to ratio as possible.
     */
    @JvmStatic
    fun foregroundTone(bgTone: Double, ratio: Double): Double {
      val lighterTone = Contrast.lighterUnsafe(bgTone, ratio)
      val darkerTone = Contrast.darkerUnsafe(bgTone, ratio)
      val lighterRatio = Contrast.ratioOfTones(lighterTone, bgTone)
      val darkerRatio = Contrast.ratioOfTones(darkerTone, bgTone)
      val preferLighter = tonePrefersLightForeground(bgTone)
      if (preferLighter) {
        // "Neglible difference" handles an edge case where the initial contrast ratio is high
        // (ex. 13.0), and the ratio passed to the function is that high ratio, and both the lighter
        // and darker ratio fails to pass that ratio.
        //
        // This was observed with Tonal Spot's On Primary Container turning black momentarily
        // between
        // high and max contrast in light mode. PC's standard tone was T90, OPC's was T10, it was
        // light mode, and the contrast level was 0.6568521221032331.
        val negligibleDifference =
          abs(lighterRatio - darkerRatio) < 0.1 && lighterRatio < ratio && darkerRatio < ratio
        return if (lighterRatio >= ratio || lighterRatio >= darkerRatio || negligibleDifference) {
          lighterTone
        } else {
          darkerTone
        }
      } else {
        return if (darkerRatio >= ratio || darkerRatio >= lighterRatio) darkerTone else lighterTone
      }
    }

    /**
     * Adjust a tone down such that white has 4.5 contrast, if the tone is reasonably close to
     * supporting it.
     */
    @JvmStatic
    fun enableLightForeground(tone: Double): Double {
      return if (tonePrefersLightForeground(tone) && !toneAllowsLightForeground(tone)) {
        49.0
      } else {
        tone
      }
    }

    /**
     * People prefer white foregrounds on ~T60-70. Observed over time, and also by Andrew Somers
     * during research for APCA.
     *
     * T60 used as to create the smallest discontinuity possible when skipping down to T49 in order
     * to ensure light foregrounds.
     *
     * Since `tertiaryContainer` in dark monochrome scheme requires a tone of 60, it should not be
     * adjusted. Therefore, 60 is excluded here.
     */
    @JvmStatic
    fun tonePrefersLightForeground(tone: Double): Boolean {
      return tone.roundToInt() < 60
    }

    /** Tones less than ~T50 always permit white at 4.5 contrast. */
    @JvmStatic
    fun toneAllowsLightForeground(tone: Double): Boolean {
      return tone.roundToInt() <= 49
    }

    @JvmStatic
    fun getInitialToneFromBackground(
      background: ((DynamicScheme) -> DynamicColor?)?
    ): (DynamicScheme) -> Double {
      if (background == null) {
        return { 50.0 }
      }
      return { scheme -> background(scheme)?.getTone(scheme) ?: 50.0 }
    }
  }
}

fun DynamicColor.extendSpecVersion(
  specVersion: SpecVersion,
  extendedColor: DynamicColor,
): DynamicColor {
  validateExtendedColor(specVersion, extendedColor)
  return copy(
    palette = { scheme ->
      (if (scheme.specVersion >= specVersion) extendedColor.palette else this.palette).invoke(
        scheme
      )
    },
    tone = { scheme ->
      (if (scheme.specVersion >= specVersion) extendedColor.tone else this.tone).invoke(scheme)
    },
    chromaMultiplier = { scheme ->
      (if (scheme.specVersion >= specVersion) {
          extendedColor.chromaMultiplier
        } else {
          this.chromaMultiplier
        })
        ?.invoke(scheme) ?: 1.0
    },
    background = { scheme ->
      (if (scheme.specVersion >= specVersion) extendedColor.background else this.background)
        ?.invoke(scheme)
    },
    secondBackground = { scheme ->
      (if (scheme.specVersion >= specVersion) {
          extendedColor.secondBackground
        } else {
          this.secondBackground
        })
        ?.invoke(scheme)
    },
    contrastCurve = { scheme ->
      (if (scheme.specVersion >= specVersion) extendedColor.contrastCurve else this.contrastCurve)
        ?.invoke(scheme)
    },
    toneDeltaPair = { scheme ->
      (if (scheme.specVersion >= specVersion) extendedColor.toneDeltaPair else this.toneDeltaPair)
        ?.invoke(scheme)
    },
    opacity = { scheme ->
      (if (scheme.specVersion >= specVersion) extendedColor.opacity else this.opacity)?.invoke(
        scheme
      )
    },
  )
}

private fun DynamicColor.validateExtendedColor(
  specVersion: SpecVersion,
  extendedColor: DynamicColor,
) {
  require(this.name == extendedColor.name) {
    "Attempting to extend color $name with color ${extendedColor.name} of different name for spec version $specVersion."
  }
  require(this.isBackground == extendedColor.isBackground) {
    "Attempting to extend color $name as a ${if (isBackground) "background" else "foreground"} with color ${extendedColor.name} as a ${if (extendedColor.isBackground) "background" else "foreground"} for spec version $specVersion."
  }
}
