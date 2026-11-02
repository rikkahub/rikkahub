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
package quantize

import utils.ColorUtils

/**
 * Provides conversions needed for K-Means quantization. Converting input to points, and converting
 * the final state of the K-Means algorithm to colors.
 */
class PointProviderLab : PointProvider {
  /**
   * Convert a color represented in ARGB to a 3-element array of L*a*b* coordinates of the color.
   */
  override fun fromInt(argb: Int): DoubleArray {
    val lab = ColorUtils.labFromArgb(argb)
    return doubleArrayOf(lab[0], lab[1], lab[2])
  }

  /** Convert a 3-element array to a color represented in ARGB. */
  override fun toInt(point: DoubleArray): Int {
    return ColorUtils.argbFromLab(point[0], point[1], point[2])
  }

  /**
   * Standard CIE 1976 delta E formula also takes the square root, unneeded here. This method is
   * used by quantization algorithms to compare distance, and the relative ordering is the same,
   * with or without a square root.
   *
   * This relatively minor optimization is helpful because this method is called at least once for
   * each pixel in an image.
   */
  override fun distance(a: DoubleArray, b: DoubleArray): Double {
    val dL = a[0] - b[0]
    val dA = a[1] - b[1]
    val dB = a[2] - b[2]
    return dL * dL + dA * dA + dB * dB
  }
}
