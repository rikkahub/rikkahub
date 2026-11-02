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
package scheme

import dynamiccolor.ColorSpec.SpecVersion
import dynamiccolor.ColorSpecs
import dynamiccolor.DynamicScheme
import dynamiccolor.Variant
import hct.Hct

/** A loud theme, colorfulness is maximum for Primary palette, increased for others. */
class SchemeVibrant(
  sourceColorHctList: List<Hct>,
  isDark: Boolean,
  contrastLevel: Double,
  specVersion: SpecVersion = DEFAULT_SPEC_VERSION,
  platform: Platform = DEFAULT_PLATFORM,
) :
  DynamicScheme(
    sourceColorHctList,
    Variant.VIBRANT,
    isDark,
    contrastLevel,
    platform,
    specVersion,
    ColorSpecs.get(specVersion)
      .getPrimaryPalette(
        Variant.VIBRANT,
        sourceColorHctList.first(),
        isDark,
        platform,
        contrastLevel,
      ),
    ColorSpecs.get(specVersion)
      .getSecondaryPalette(
        Variant.VIBRANT,
        sourceColorHctList.first(),
        isDark,
        platform,
        contrastLevel,
      ),
    ColorSpecs.get(specVersion)
      .getTertiaryPalette(
        Variant.VIBRANT,
        sourceColorHctList.first(),
        isDark,
        platform,
        contrastLevel,
      ),
    ColorSpecs.get(specVersion)
      .getNeutralPalette(
        Variant.VIBRANT,
        sourceColorHctList.first(),
        isDark,
        platform,
        contrastLevel,
      ),
    ColorSpecs.get(specVersion)
      .getNeutralVariantPalette(
        Variant.VIBRANT,
        sourceColorHctList.first(),
        isDark,
        platform,
        contrastLevel,
      ),
    ColorSpecs.get(specVersion)
      .getErrorPalette(Variant.VIBRANT, sourceColorHctList.first(), isDark, platform, contrastLevel),
  ) {
  constructor(
    sourceColorHct: Hct,
    isDark: Boolean,
    contrastLevel: Double,
    specVersion: SpecVersion = DEFAULT_SPEC_VERSION,
    platform: Platform = DEFAULT_PLATFORM,
  ) : this(listOf(sourceColorHct), isDark, contrastLevel, specVersion, platform)
}
