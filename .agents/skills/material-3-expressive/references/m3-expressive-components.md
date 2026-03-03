# M3 Expressive Components (New/Updated)

Source URLs:

- https://m3.material.io/components/button-groups/overview
- https://m3.material.io/components/fab-menu/overview
- https://m3.material.io/components/loading-indicator/overview
- https://m3.material.io/components/split-button/overview
- https://m3.material.io/components/toolbars/overview

Scope
Expressive component updates introduced with M3 Expressive, focusing on new components and major behavior changes.

Button groups (May 2025)

- Purpose: organize buttons and add interactions between them.
- Types: standard and connected.
- Connected button groups replace segmented buttons (segmented is deprecated).
- Works with all sizes: XS, S, M, L, XL.
- Supports single-select, multi-select, and selection-required.
- Applies shape morph on press and selection.
- Can contain buttons and icon buttons.
- Availability: Figma (Design Kit), Jetpack Compose Expressive, MDC-Android Expressive; Web Expressive unavailable.

FAB menu (May 2025)

- Purpose: opens from a FAB to show 2-6 related actions.
- One menu size works with any FAB size; not used with extended FABs.
- Colors: primary, secondary, tertiary; uses contrasting close button and item colors.
- Supports dynamic color and any FAB color style.
- Replaces M2 speed dial and stacked small FAB patterns.
- Availability: Figma (Design Kit), Jetpack Compose Expressive; MDC-Android Expressive and Web Expressive unavailable.
- Differences from M2: larger item size and dynamic color vs small round speed dial FABs.

Loading indicator (May 2025)

- Purpose: shows short wait time progress (under ~5 seconds).
- Replacement for indeterminate circular progress in most cases.
- Used for pull-to-refresh; not for flows that transition from indeterminate to determinate.
- Never decorative; uses shape and motion to capture attention.
- Variants: contained or uncontained; scalable in size.
- Availability: Figma (Design Kit), Jetpack Compose Expressive, MDC-Android Expressive; Web Expressive unavailable.

Split button (May 2025)

- Purpose: primary action plus menu of related actions.
- Structure: common button + menu icon button.
- Sizes: XS, S, M, L, XL.
- Color styles: elevated, filled, tonal, outlined.
- Menu button spins and changes shape when activated.
- Can be used alongside other buttons of the same size.
- Availability: Figma (Design Kit), Jetpack Compose Expressive, MDC-Android Expressive; Web Expressive unavailable.

Toolbars (May 2025)

- Purpose: show frequently used actions relevant to the current page.
- Types: docked toolbar and floating toolbar.
- Use vibrant color style for stronger emphasis.
- Can display mixed controls (buttons, icon buttons, text fields).
- Can be paired with FABs; do not show at the same time as a navigation bar.
- Update: bottom app bar is no longer recommended; replace with docked toolbar (shorter, more flexible).
- Floating toolbar adds versatility, supports more actions, and can be placed variably.
- Floating configurations: horizontal or vertical; standard or vibrant color.
- Differences from M2: new color mappings with dynamic color, no shadow, taller container; FAB contained within bar.
- Availability: Figma (Design Kit), Flutter, Jetpack Compose, Jetpack Compose Expressive, MDC-Android (including
  expressive); Web unavailable.
