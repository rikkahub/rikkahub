# Elevation (M3 Expressive)

Source URL:
https://m3.material.io/styles/elevation/overview
Generated: 2026-02-10

Extracted guidance

- Elevation is applied to all surfaces and components
- Tokens codify the distance on the z-axis to ensure components appear consistently relative to each other
- Tokens have no shadows or color; each platform determines the specific shadows and values to use at each elevation
  level
- Elevation can be shown as tonal surface colors or shadows
- Avoid changing the default elevation of Material 3 components
- Stick to using a small amount of elevation levels
- Elevation is measured as the distance between components along the z-axis in density-independent pixels (dps).
- Resource Status Design Design Kit (Figma) Available Implementation Flutter Available Jetpack Compose Available MDC -
  Android Available MWC - Web Available
- Differences from M2
- Shadows: Instead of applying shadows by default to all levels, use shadows only when required to create additional
  protection against a background or to encourage interaction
- Color: New color mappings and compatibility with dynamic color
- Levels: Elevation is now described in terms of levels
- All surfaces and components have elevation values
- Allow surfaces to move in front of and behind other surfaces, such as content scrolling behind app bars
- Reflect spatial relationships, such as how a FAB's shadow indicates it is separate from a card collection
- Focus attention on the highest elevation, such as a dialog temporarily appearing in front of other surfaces
- Resting elevation (default)
- All components have a default resting elevation. Avoid changing the default elevation of Material components.
- Components should change elevation in response to system events or user interaction, like hovering. This elevation
  change should be consistent across all similar elements. For example, hovering a FAB temporarily increases the
  elevation by 1 level, from level 3 to level 4. All Material buttons increase elevation by 1 level when hovered.
- Material 3's elevation system is deliberately limited to just a handful of levels. This creative constraint means you
  need to make thoughtful decisions about your UI's elevation story.
- Elevation can be depicted using shadows or other visual cues, such as surface fills with a tone difference or scrims.
- Surface edges, contrasting the surface from its surroundings
- Overlap with other surfaces, either at rest or in motion
- Distance from other surfaces
- Giving surfaces a drop shadow
- Placing a scrim behind a surface
- For interactive components, edges must create sufficient contrast between surfaces (by meeting or exceeding accessible
  contrast ratios) for them to be seen as separate from one another.
- Surface color roles and elevation
- You can pick from a range of surface and surface container color roles. These roles are not tied to elevation, and
  provide flexibility for defining containment areas. Any overlapping containment areas or components should have
  different color roles in order to visually communicate separation. Guideline on surface color roles
- Deprecated - Surface tint overlays Before introducing the surface color roles in March 2023, surface elevation was
  communicated through tint overlays. Material Design no longer supports those overlays, and they should not be used in
  combination with the new surface color roles. For products that haven't yet migrated to the new surface color roles,
  here is reference information on the now-deprecated tint overlays: Surfaces at elevation levels +1 to +5 are tinted
  via color overlays based on the primary color, such as app bars or menus. The addition of a grade from +1 to +5
  introduces tonal variation to the surface baseline.
- Shadows can express the degree of elevation between surfaces in ways that other techniques cannot. Both a shadow's
  size and amount of softness or diffusion express the degree of distance between two surfaces. For example, a surface
  with a shadow that is small and sharp indicates a surface's close proximity to the surface behind it. Larger, softer
  shadows express more distance.
- When it comes to applying shadows, less is more. The fewer levels in your UI, the more power they have to direct
  attention and action.
- When to use visible shadows
- Protect elements When a background is patterned or visually busy, the hairline style might not provide sufficient
  protection. In these cases, use elevation to separate and emphasize elements such as cards, chips, or buttons, as in
  the image below.
- Encourage interaction Elements can temporarily lift on focus, selection, or another kind of interaction, like swipe. A
  raised element can also lower when a higher element appears
- A scrim can bring focus to specific elements by increasing the visual contrast of a large layered surface. Use the
  scrim beneath elements like modals and expanded navigation menus. Scrims use the scrim color role at an opacity of
  32%.
- Elevation levels can be implemented with tokens. Surface tint color is deprecated - use elevation level tokens (0-5)
  instead. Learn more about design tokens
- Most components have a default elevation. Component elevation is only used to determine where the component sits in
  relation to other components, including when hovered or focused (which usually raises elevation by one level).
  Elevation has no shadow or value of its own by default.
- Resting level Component DP Height 5 (not assigned as resting level) 12dp 4 (not assigned as resting level) 8dp 3 Date
  pickers Dialogs (modal) Extended FAB FAB FAB Menu (close button) Search Time pickers 6dp 2 App bar (scrolled) Menu
  Navigation bar Rich tooltip Toolbar 3dp 1 Banner Bottom sheet (modal) Button (elevated) Card (elevated) Chips (
  elevated) Navigation drawer (modal) Side sheet (modal) 1dp 0 App Bar (not scrolled) Buttons (filled, tonal, outlined)
  Button groups Cards (filled, outlined) Carousel Chips Dialog (full-screen) Extended FAB (in navigation rail) FAB (in
  navigation rail) FAB Menu (list items) Icon buttons List Navigation rail Segmented button Side sheet (docked) Slider
  Split button Tabs 0dp
