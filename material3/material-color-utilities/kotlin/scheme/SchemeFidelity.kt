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

/**
 * A scheme that places the source color in Scheme.primaryContainer.
 *
 * Primary Container is the source color, adjusted for color relativity. It maintains constant
 * appearance in light mode and dark mode. This adds ~5 tone in light mode, and subtracts ~5 tone in
 * dark mode.
 *
 * Tertiary Container is the complement to the source color, using TemperatureCache. It also
 * maintains constant appearance.
 */
class SchemeFidelity(
  sourceColorHctList: List<Hct>,
  isDark: Boolean,
  contrastLevel: Double,
  specVersion: SpecVersion = DEFAULT_SPEC_VERSION,
  platform: Platform = DEFAULT_PLATFORM,
) :
  DynamicScheme(
    sourceColorHctList,
    Variant.FIDELITY,
    isDark,
    contrastLevel,
    platform,
    specVersion,
    ColorSpecs.get(specVersion)
      .getPrimaryPalette(
        Variant.FIDELITY,
        sourceColorHctList.first(),
        isDark,
        platform,
        contrastLevel,
      ),
    ColorSpecs.get(specVersion)
      .getSecondaryPalette(
        Variant.FIDELITY,
        sourceColorHctList.first(),
        isDark,
        platform,
        contrastLevel,
      ),
    ColorSpecs.get(specVersion)
      .getTertiaryPalette(
        Variant.FIDELITY,
        sourceColorHctList.first(),
        isDark,
        platform,
        contrastLevel,
      ),
    ColorSpecs.get(specVersion)
      .getNeutralPalette(
        Variant.FIDELITY,
        sourceColorHctList.first(),
        isDark,
        platform,
        contrastLevel,
      ),
    ColorSpecs.get(specVersion)
      .getNeutralVariantPalette(
        Variant.FIDELITY,
        sourceColorHctList.first(),
        isDark,
        platform,
        contrastLevel,
      ),
    ColorSpecs.get(specVersion)
      .getErrorPalette(
        Variant.FIDELITY,
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
