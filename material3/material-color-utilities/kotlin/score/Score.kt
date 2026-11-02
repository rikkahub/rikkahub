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
package score

import hct.Hct
import utils.MathUtils
import java.util.ArrayList
import java.util.Collections
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Given a large set of colors, remove colors that are unsuitable for a UI theme, and rank the rest
 * based on suitability.
 *
 * Enables use of a high cluster count for image quantization, thus ensuring colors aren't muddied,
 * while curating the high cluster count to a much smaller number of appropriate choices.
 */
object Score {
  private const val TARGET_CHROMA = 48.0 // A1 Chroma
  private const val WEIGHT_PROPORTION = 0.7
  private const val WEIGHT_CHROMA_ABOVE = 0.3
  private const val WEIGHT_CHROMA_BELOW = 0.1
  private const val CUTOFF_CHROMA = 5.0
  private const val CUTOFF_EXCITED_PROPORTION = 0.01

  @JvmStatic
  fun score(colorsToPopulation: Map<Int, Int>): List<Int> {
    // Fallback color is Google Blue.
    return score(colorsToPopulation, 4, 0xff4285f4.toInt(), true)
  }

  @JvmStatic
  fun score(colorsToPopulation: Map<Int, Int>, desired: Int): List<Int> {
    return score(colorsToPopulation, desired, 0xff4285f4.toInt(), true)
  }

  @JvmStatic
  fun score(colorsToPopulation: Map<Int, Int>, desired: Int, fallbackColorArgb: Int): List<Int> {
    return score(colorsToPopulation, desired, fallbackColorArgb, true)
  }

  /**
   * Given a map with keys of colors and values of how often the color appears, rank the colors
   * based on suitability for being used for a UI theme.
   *
   * @param colorsToPopulation map with keys of colors and values of how often the color appears,
   *   usually from a source image.
   * @param desired max count of colors to be returned in the list.
   * @param fallbackColorArgb color to be returned if no other options available.
   * @param filter whether to filter out undesireable combinations.
   * @return Colors sorted by suitability for a UI theme. The most suitable color is the first item,
   *   the least suitable is the last. There will always be at least one color returned. If all the
   *   input colors were not suitable for a theme, a default fallback color will be provided, Google
   *   Blue.
   */
  @JvmStatic
  fun score(
    colorsToPopulation: Map<Int, Int>,
    desired: Int,
    fallbackColorArgb: Int,
    filter: Boolean,
  ): List<Int> {

    // Get the HCT color for each Argb value, while finding the per hue count and
    // total count.
    val colorsHct: MutableList<Hct> = ArrayList()
    val huePopulation = IntArray(360)
    var populationSum = 0.0
    for ((key, value) in colorsToPopulation) {
      val hct = Hct.fromInt(key)
      colorsHct.add(hct)
      val hue = floor(hct.hue).toInt()
      huePopulation[hue] += value
      populationSum += value.toDouble()
    }

    // Hues with more usage in neighboring 30 degree slice get a larger number.
    val hueExcitedProportions = DoubleArray(360)
    for (hue in 0..359) {
      val proportion = huePopulation[hue] / populationSum
      for (i in hue - 14 until hue + 16) {
        val neighborHue = MathUtils.sanitizeDegreesInt(i)
        hueExcitedProportions[neighborHue] += proportion
      }
    }

    // Scores each HCT color based on usage and chroma, while optionally
    // filtering out values that do not have enough chroma or usage.
    val scoredHcts: MutableList<ScoredHCT> = ArrayList()
    for (hct in colorsHct) {
      val hue = MathUtils.sanitizeDegreesInt(hct.hue.roundToInt())
      val proportion = hueExcitedProportions[hue]
      if (filter && (hct.chroma < CUTOFF_CHROMA || proportion <= CUTOFF_EXCITED_PROPORTION)) {
        continue
      }
      val proportionScore = proportion * 100.0 * WEIGHT_PROPORTION
      val chromaWeight =
        if (hct.chroma < TARGET_CHROMA) WEIGHT_CHROMA_BELOW else WEIGHT_CHROMA_ABOVE
      val chromaScore = (hct.chroma - TARGET_CHROMA) * chromaWeight
      val score = proportionScore + chromaScore
      scoredHcts.add(ScoredHCT(hct, score))
    }
    // Sorted so that colors with higher scores come first.
    Collections.sort(scoredHcts, ScoredComparator())

    // Iterates through potential hue differences in degrees in order to select
    // the colors with the largest distribution of hues possible. Starting at
    // 90 degrees(maximum difference for 4 colors) then decreasing down to a
    // 15 degree minimum.
    val chosenColors: MutableList<Hct> = ArrayList()
    for (differenceDegrees in 90 downTo 15) {
      chosenColors.clear()
      for (entry in scoredHcts) {
        val hct = entry.hct
        var hasDuplicateHue = false
        for (chosenHct in chosenColors) {
          if (MathUtils.differenceDegrees(hct.hue, chosenHct.hue) < differenceDegrees) {
            hasDuplicateHue = true
            break
          }
        }
        if (!hasDuplicateHue) {
          chosenColors.add(hct)
        }
        if (chosenColors.size >= desired) {
          break
        }
      }
      if (chosenColors.size >= desired) {
        break
      }
    }
    val colors: MutableList<Int> = ArrayList()
    if (chosenColors.isEmpty()) {
      colors.add(fallbackColorArgb)
    }
    for (chosenHct in chosenColors) {
      colors.add(chosenHct.toInt())
    }
    return colors
  }

  private class ScoredHCT(val hct: Hct, val score: Double)

  private class ScoredComparator() : Comparator<ScoredHCT> {
    override fun compare(entry1: ScoredHCT, entry2: ScoredHCT): Int {
      return entry2.score.compareTo(entry1.score)
    }
  }
}
