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
package scheme

import dynamiccolor.ColorSpec.SpecVersion
import dynamiccolor.ColorSpecs
import dynamiccolor.DynamicScheme
import dynamiccolor.Variant
import hct.Hct

/** A playful theme - the source color's hue does not appear in the theme. */
class SchemeFruitSalad(
  sourceColorHctList: List<Hct>,
  isDark: Boolean,
  contrastLevel: Double,
  specVersion: SpecVersion = DEFAULT_SPEC_VERSION,
  platform: Platform = DEFAULT_PLATFORM,
) :
  DynamicScheme(
    sourceColorHctList,
    Variant.FRUIT_SALAD,
    isDark,
    contrastLevel,
    platform,
    specVersion,
    ColorSpecs.get(specVersion)
      .getPrimaryPalette(
        Variant.FRUIT_SALAD,
        sourceColorHctList.first(),
        isDark,
        platform,
        contrastLevel,
      ),
    ColorSpecs.get(specVersion)
      .getSecondaryPalette(
        Variant.FRUIT_SALAD,
        sourceColorHctList.first(),
        isDark,
        platform,
        contrastLevel,
      ),
    ColorSpecs.get(specVersion)
      .getTertiaryPalette(
        Variant.FRUIT_SALAD,
        sourceColorHctList.first(),
        isDark,
        platform,
        contrastLevel,
      ),
    ColorSpecs.get(specVersion)
      .getNeutralPalette(
        Variant.FRUIT_SALAD,
        sourceColorHctList.first(),
        isDark,
        platform,
        contrastLevel,
      ),
    ColorSpecs.get(specVersion)
      .getNeutralVariantPalette(
        Variant.FRUIT_SALAD,
        sourceColorHctList.first(),
        isDark,
        platform,
        contrastLevel,
      ),
    ColorSpecs.get(specVersion)
      .getErrorPalette(
        Variant.FRUIT_SALAD,
        sourceColorHctList.first(),
        isDark,
        platform,
        contrastLevel,
      ),
  ) {
  constructor(
    sourceColorHct: Hct,
    isDark: Boolean,
    contrastLevel: Double,
    specVersion: SpecVersion = DEFAULT_SPEC_VERSION,
    platform: Platform = DEFAULT_PLATFORM,
  ) : this(listOf(sourceColorHct), isDark, contrastLevel, specVersion, platform)
}
