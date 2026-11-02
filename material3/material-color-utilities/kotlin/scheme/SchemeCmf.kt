/*
 * Copyright 2026 Google LLC
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

import dynamiccolor.ColorSpec
import dynamiccolor.DynamicScheme
import dynamiccolor.Variant
import hct.Hct
import palettes.TonalPalette

/** A Dynamic Color theme with 2 source colors. */
class SchemeCmf(
  sourceColorHctList: List<Hct>,
  isDark: Boolean,
  contrastLevel: Double,
  specVersion: ColorSpec.SpecVersion = ColorSpec.SpecVersion.SPEC_2026,
  platform: Platform = DEFAULT_PLATFORM,
) :
  DynamicScheme(
    sourceColorHctList = sourceColorHctList,
    variant = Variant.CMF,
    isDark = isDark,
    contrastLevel = contrastLevel,
    platform = platform,
    specVersion = specVersion,
    primaryPalette =
      TonalPalette.fromHueAndChroma(
        sourceColorHctList.first().hue,
        sourceColorHctList.first().chroma,
      ),
    secondaryPalette =
      TonalPalette.fromHueAndChroma(
        sourceColorHctList.first().hue,
        sourceColorHctList.first().chroma * 0.5,
      ),
    tertiaryPalette = tertiaryPalette(sourceColorHctList),
    neutralPalette =
      TonalPalette.fromHueAndChroma(
        sourceColorHctList.first().hue,
        sourceColorHctList.first().chroma * 0.2,
      ),
    neutralVariantPalette =
      TonalPalette.fromHueAndChroma(
        sourceColorHctList.first().hue,
        sourceColorHctList.first().chroma * 0.2,
      ),
    errorPalette =
      TonalPalette.fromHueAndChroma(
        getErrorHue(sourceColorHctList.first().hue, tertiaryPalette(sourceColorHctList).hue),
        sourceColorHctList.first().chroma.coerceAtLeast(50.0),
      ),
  ) {
  init {
    require(specVersion == ColorSpec.SpecVersion.SPEC_2026) {
      "SchemeCmf can only be used with spec version 2026."
    }
  }

  constructor(
    sourceColorHct: Hct,
    isDark: Boolean,
    contrastLevel: Double,
    specVersion: ColorSpec.SpecVersion = ColorSpec.SpecVersion.SPEC_2026,
    platform: Platform = DEFAULT_PLATFORM,
  ) : this(listOf(sourceColorHct), isDark, contrastLevel, specVersion, platform)
}

private fun getErrorHue(primaryHue: Double, tertiaryHue: Double): Double {
  if (primaryHue <= 8) {
    return if (tertiaryHue <= 24) 28.0 else if (tertiaryHue <= 32) 16.0 else 20.0
  } else if (primaryHue <= 16) {
    return if (tertiaryHue <= 24) 32.0 else if (tertiaryHue <= 32) 20.0 else 24.0
  } else if (primaryHue <= 20) {
    return if (tertiaryHue <= 28) 32.0 else if (tertiaryHue <= 32) 24.0 else 28.0
  } else if (primaryHue <= 28) {
    return if (tertiaryHue <= 24) 32.0 else 16.0
  } else if (primaryHue <= 32) {
    return if (tertiaryHue <= 20) 24.0 else if (tertiaryHue <= 28) 16.0 else 20.0
  } else if (primaryHue <= 40) {
    return if (tertiaryHue > 20 && tertiaryHue <= 28) 16.0 else 24.0
  } else if (primaryHue <= 152) {
    return if (tertiaryHue > 24 && tertiaryHue <= 36) 20.0 else 32.0
  } else if (primaryHue <= 272) {
    return if (tertiaryHue > 20 && tertiaryHue <= 28) 16.0 else 24.0
  } else {
    return if (tertiaryHue > 12 && tertiaryHue <= 28) 32.0 else 16.0
  }
}

private fun tertiaryPalette(sourceColorHctList: List<Hct>): TonalPalette {
  val sourceColorHct = sourceColorHctList.first()
  val secondarySourceColorHct = sourceColorHctList.getOrNull(1) ?: sourceColorHct
  return if (sourceColorHct.toInt() == secondarySourceColorHct.toInt()) {
    TonalPalette.fromHueAndChroma(sourceColorHct.hue, sourceColorHct.chroma * 0.75)
  } else {
    TonalPalette.fromHueAndChroma(secondarySourceColorHct.hue, secondarySourceColorHct.chroma)
  }
}
