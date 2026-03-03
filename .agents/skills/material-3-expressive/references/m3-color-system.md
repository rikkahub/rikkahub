# Color System (M3 Expressive)

Source URL:
https://m3.material.io/styles/color/system/overview
Generated: 2026-02-10

Extracted guidance

- Built-in set of accessible color relationships
- 26+ color roles mapped to Material Components
- Built-in dark theme colors
- Static baseline color scheme with default colors assigned to each color role
- Dynamic color features including user-generated and content-based color
- For products migrating from M2 to M3, start by mapping the baseline color scheme to your existing product. It can
  easily switch to dynamic color when ready.
- Automatic dark theme
- Resources Type Link Status Design Design Kit (Figma) Available Implementation MDC-Android Available Jetpack Compose
  Available Flutter Available Tools Material Theme Builder Available
- May 2025 Three levels of contrast Color roles support three levels of contrast so people can select the one that best
  suits their vision needs. Contrasts also are tokenized.
- On primary container
- On secondary container
- On tertiary container
- On error container
- Bottom app bar
- How the system works
- Feb 2023 Tone-based surface colors Tone-based surface color roles have replaced the previous approach of surfaces at
  +1 to +5 elevation. The new color roles are not tied to elevation and offer more flexibility and support for color
  features, such as user-controlled contrast.
- Updated the default light theme surface from tone 99 to tone 98
- Updated the chroma for the neutral palette, increasing it from 4 to 6
- Slightly darkened surface roles in dark theme
- Feb 2023 Additional accent colors Additional accent colors in the scheme provide more flexibility and choice for color
  application. In particular, a new set of fixed colors for the primary , secondary , and tertiary accent groups provide
  colors which stay the same across light and dark themes.
- It's like paint-by-number
- Each element on the screen has a number
- Each number is assigned a color
- You can hand-pick a color for every "number" to create a static color scheme.
- But now, you can also use Material's dynamic color system to automatically generate an entire palette of accessible
  colors for each "number" from a single source. This source can be a user's wallpaper, or in-app content like imagery.
  If the source changes, the product colors update to match.
- Setting the color source
- Adding static or harmonized colors
- Changing which "numbers" are assigned to which elements
- Color role Like the "numbers" on a paint-by-number canvas, color roles are assigned to specific UI elements. They have
  semantic names like primary , on primary , and primary container, and matching color tokens. See all color roles
  Dynamic color Dynamic color takes a single color from a user's wallpaper or in-app content and creates an accessible
  color scheme assigned to elements in the UI. If the user's wallpaper or the in-app content changes, the colors in the
  UI will change to match. Static color UI colors that don't change based on the user's wallpaper or in-app content.
  Static colors can be hand-picked or generated in design tools like the Material Theme Builder. Once assigned to their
  respective color roles and UX elements, the colors remain constant. Baseline static color The default static color
  scheme for Material products. See the baseline color scheme
- How dynamic color generates color schemes
-
  1. It starts with a source color There are three ways to get a source color.
- A. Generate it from a wallpaper User-generated color is sourced from a user's personal wallpaper. The wallpaper is
  digitally analyzed through a process called quantization, and a single color is selected as the source color.
- B. Generate it from in-app content Content-based color is sources from in-app content, like an album thumbnail image,
  logo, or video preview. Like user-generated color, the image is digitally analyzed through quantization, and a single
  color selected as the source color.
- C. Pick it by hand A hand-picked source color is deliberately selected by a designer. Did you know? The baseline
  static color scheme uses a hand-picked source color.
- User-generated color algorithm Uses personal wallpaper to identify source color. Maps colors of specific tones (
  lighter or darker) into the scheme according to a combination of system design choices and user preferences.
- Content-based color algorithm Uses image for source color. Tones are adjusted to match the appearance of the source
  image, while maintaining accessible contrast.
- Custom colors Colors closely match the chosen input colors, such as those representing brand or semantic meaning.
-
  3. The algorithm generates key colors
-
  4. The algorithm creates tonal palettes The system then manipulates tone and chroma values to create a tonal palette
     for each key color. Colors in these palettes are given a number from 0 to 100 in increments of 10, as well as 95,
     98, and 99. Some palettes include more values.
-
  5. The algorithm assigns tones to color roles The algorithm uses accessible color relationships to assign specific
     tones to the 26 color roles in both light and dark theme.
- For example, the algorithm assigns the color tone primary40 to the p rimary role and the tone primary100 to the o n
  primary role. See all color roles
- Dark theme colors are also automatically assigned so that apps receive both light and dark themes through a single set
  of color roles.
-
  6. The new colors are applied to the UI The 26 standard color roles are already assigned to elements of the UI. When a
     new source color is picked, the UI dynamically changes color.
- Color roles support three levels of contrast
- The contrast settings are automatically applied to both light and dark themes.
- Custom components can support contrast levels by using Material's appropriate color roles. For example, use primary
  container and on primary container . Use design tokens to apply color roles to custom components.
- Pairing accessible tones
- The system manipulates hue, chroma, and tone (HCT) values to create a tonal palette for each color with tones ranging
  from 0 to 100. Color has physical limitations-whether it's actual physics, our own biological visual limitations, or
  the limitations of on-screen color rendering. For example, some hues cannot exist with certain chroma or tones. Color
  limitations are the reason colors such as bright light blue or bright light red are not quite possible. This is why
  the chroma value may increase or decrease for some tones in a tonal palette.
- Material's color algorithms use these palettes to find and pair contrasting tones, creating accessible color
  combinations.
- Because tone can describe the lightness or darkness of a color, it's used to define accessible color relationships.
  Those relationships are built into Material's color algorithms. For example, the algorithms assign a dark tone to a
  button's container color and a light tone to its label color, ensuring that the colors have a 3:1 contrast.
- For even more contrast, the algorithms assign tones even farther apart, achieving a 7:1 contrast. This is the concept
  powering user-controlled contrast features.
- Defining colors with hue, chroma, and tone (HCT)
- The system uses a color space called HCT , which defines all colors using three dimensions: hue, chroma, and tone.
  Changing HCT values lets you manipulate colors in flexible but predictable ways. Unlike other color spaces (like HSL
  or RGB), HCT allows the manipulation of a color's hue and chroma without affecting its tone. Watch to learn more:
- Hue Hue is the perception of a color as red, orange, yellow, green, blue, violet, and so on. Hue is quantified by a
  number ranging from 0-360 and is a circular spectrum (values 0 and 360 are the same hue).
- Chroma Chroma is how colorful or neutral (grey, black or white) a color appears. Chroma is quantified by a number
  ranging from 0 (completely grey, black or white) to infinity (most vibrant), though Chroma values in HCT top out at
  roughly 120. Because of biological and screen rendering limitations, different hues and different tones will have
  different maximal chroma values.
- Tone Tone is how light or dark a color appears. Tone is sometimes also referred to as luminance. Tone is quantified by
  a number ranging from 0 (pure black, no luminance) to 100 (pure white, complete luminance). Tone is crucial for visual
  accessibility because it determines contrast. Colors with a greater difference in tone create higher contrast, while
  those with a smaller difference create lower contrast.
