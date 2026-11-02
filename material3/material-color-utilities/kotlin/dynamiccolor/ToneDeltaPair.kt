/*
 * Copyright 2023 Google LLC
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

/**
 * Documents a constraint between two DynamicColors, in which their tones must have a certain
 * distance from each other.
 *
 * The polarity is an adjective that describes "A", compared to "B". For instance, ToneDeltaPair(A,
 * B, 15, 'darker') states that A's tone should be at least 15 darker than B's.
 *
 * Prefer a DynamicColor with a background, this is for special cases when designers want tonal
 * distance, literally contrast, between two colors that don't have a background / foreground
 * relationship or a contrast guarantee.
 *
 * @param roleA The first role in a pair.
 * @param roleB The second role in a pair.
 * @param delta Required difference between tones. Absolute value, negative values have undefined
 *   behavior.
 * @param polarity The relative relation between tones of roleA and roleB, as described above.
 * @param stayTogether Whether these two roles should stay on the same side of the "awkward zone"
 *   (T50-59). This is necessary for certain cases where one role has two backgrounds.
 * @param constraint How to fulfill the tone delta pair constraint.
 */
data class ToneDeltaPair(
  val roleA: DynamicColor,
  val roleB: DynamicColor,
  val delta: Double,
  val polarity: TonePolarity,
  val stayTogether: Boolean = true,
  val constraint: DeltaConstraint = DeltaConstraint.EXACT,
) {
  /**
   * Describes how to fulfill a tone delta pair constraint.
   *
   * Determines if the delta is a minimum, maximum, or exact tonal distance that must be maintained.
   */
  enum class DeltaConstraint {
    // The tone of roleA must be an exact delta away from the tone of roleB.
    EXACT,
    // The tonal distance of roleA and roleB must be at most delta.
    NEARER,
    // The tonal distance of roleA and roleB must be at least delta.
    FARTHER,
  }

  /**
   * Describes the relationship in lightness between two colors.
   *
   * 'relative_darker' and 'relative_lighter' describes the tone adjustment relative to the surface
   * color trend (white in light mode; black in dark mode). For instance, ToneDeltaPair(A, B, 10,
   * 'relative_lighter', 'farther') states that A should be at least 10 lighter than B in light
   * mode, and at least 10 darker than B in dark mode.
   */
  enum class TonePolarity {
    // The tone of roleA is always darker than the tone of roleB.
    DARKER,
    // The tone of roleA is always lighter than the tone of roleB.
    LIGHTER,
    // The tone of roleA is darker than the tone of roleB in light mode, and lighter than the tone
    // ofroleB in dark mode.
    RELATIVE_DARKER,
    // The tone of roleA is lighter than the tone of roleB in light mode, and darker than the tone
    // ofroleB in dark mode.
    RELATIVE_LIGHTER,
  }
}
