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
package palettes

import hct.Hct
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A convenience class for retrieving colors that are constant in hue and chroma, but vary in tone.
 *
 * TonalPalette is intended for use in a single thread due to its stateful caching.
 */
class TonalPalette
private constructor(
  /** The hue of the Tonal Palette, in HCT. Ranges from 0 to 360. */
  val hue: Double,
  /** The chroma of the Tonal Palette, in HCT. Ranges from 0 to ~130 (for sRGB gamut). */
  val chroma: Double,
  /** The key color is the first tone, starting from T50, that matches the palette's chroma. */
  val keyColor: Hct,
) {
  var cache: MutableMap<Int, Int> = mutableMapOf()

  /**
   * Create an ARGB color with HCT hue and chroma of this Tones instance, and the provided HCT tone.
   *
   * @param tone HCT tone, measured from 0 to 100.
   * @return ARGB representation of a color with that tone.
   */
  fun tone(tone: Int): Int {
    var color = cache[tone]
    if (color == null) {
      color =
        if (tone == 99 && Hct.isYellow(hue)) {
          averageArgb(tone(98), tone(100))
        } else {
          Hct.from(hue, chroma, tone.toDouble()).toInt()
        }
      cache[tone] = color
    }
    return color
  }

  /** Given a tone, use hue and chroma of palette to create a color, and return it as HCT. */
  fun getHct(tone: Double): Hct {
    if (tone == 99.0 && Hct.isYellow(hue)) {
      return Hct.fromInt(tone(99))
    }
    return Hct.from(hue, chroma, tone)
  }

  private fun averageArgb(argb1: Int, argb2: Int): Int {
    val red1 = (argb1 ushr 16) and 0xff
    val green1 = (argb1 ushr 8) and 0xff
    val blue1 = argb1 and 0xff
    val red2 = (argb2 ushr 16) and 0xff
    val green2 = (argb2 ushr 8) and 0xff
    val blue2 = argb2 and 0xff
    val red = ((red1 + red2) / 2f).roundToInt()
    val green = ((green1 + green2) / 2f).roundToInt()
    val blue = ((blue1 + blue2) / 2f).roundToInt()
    return (255 shl 24) or ((red and 255) shl 16) or ((green and 255) shl 8) or (blue and 255)
  }

  /** Key color is a color that represents the hue and chroma of a tonal palette. */
  private class KeyColor(private val hue: Double, private val requestedChroma: Double) {
    // Cache that maps tone to max chroma to avoid duplicated HCT calculation.
    private val chromaCache: MutableMap<Int, Double> = mutableMapOf()

    /**
     * Creates a key color from a [hue] and a [chroma]. The key color is the first tone, starting
     * from T50, matching the given hue and chroma.
     *
     * @return Key color [Hct]
     */
    fun create(): Hct {
      // Pivot around T50 because T50 has the most chroma available, on
      // average. Thus it is most likely to have a direct answer.
      val pivotTone = 50
      val toneStepSize = 1
      // Epsilon to accept values slightly higher than the requested chroma.
      val epsilon = 0.01

      // Binary search to find the tone that can provide a chroma that is closest
      // to the requested chroma.
      var lowerTone = 0
      var upperTone = 100
      while (lowerTone < upperTone) {
        val midTone = (lowerTone + upperTone) / 2
        val isAscending = maxChroma(midTone) < maxChroma(midTone + toneStepSize)
        val sufficientChroma = maxChroma(midTone) >= requestedChroma - epsilon
        if (sufficientChroma) {
          // Either range [lowerTone, midTone] or [midTone, upperTone] has
          // the answer, so search in the range that is closer the pivot tone.
          if (abs((lowerTone - pivotTone).toDouble()) < abs((upperTone - pivotTone).toDouble())) {
            upperTone = midTone
          } else {
            if (lowerTone == midTone) {
              return Hct.from(hue, requestedChroma, lowerTone.toDouble())
            }
            lowerTone = midTone
          }
        } else {
          // As there is no sufficient chroma in the midTone, follow the direction to the chroma
          // peak.
          if (isAscending) {
            lowerTone = midTone + toneStepSize
          } else {
            // Keep midTone for potential chroma peak.
            upperTone = midTone
          }
        }
      }
      return Hct.from(hue, requestedChroma, lowerTone.toDouble())
    }

    // Find the maximum chroma for a given tone
    private fun maxChroma(tone: Int): Double {
      return chromaCache.getOrPut(tone) { Hct.from(hue, MAX_CHROMA_VALUE, tone.toDouble()).chroma }
    }

    companion object {
      private const val MAX_CHROMA_VALUE = 200.0
    }
  }

  companion object {
    /**
     * Create tones using the HCT hue and chroma from a color.
     *
     * @param argb ARGB representation of a color
     * @return Tones matching that color's hue and chroma.
     */
    @JvmStatic
    fun fromInt(argb: Int): TonalPalette {
      return fromHct(Hct.fromInt(argb))
    }

    /**
     * Create tones using a HCT color.
     *
     * @param hct HCT representation of a color.
     * @return Tones matching that color's hue and chroma.
     */
    @JvmStatic
    fun fromHct(hct: Hct): TonalPalette {
      return TonalPalette(hct.hue, hct.chroma, hct)
    }

    /**
     * Create tones from a defined HCT hue and chroma.
     *
     * @param hue HCT hue
     * @param chroma HCT chroma
     * @return Tones matching hue and chroma.
     */
    @JvmStatic
    fun fromHueAndChroma(hue: Double, chroma: Double): TonalPalette {
      val keyColor = KeyColor(hue, chroma).create()
      return TonalPalette(hue, chroma, keyColor)
    }
  }
}
