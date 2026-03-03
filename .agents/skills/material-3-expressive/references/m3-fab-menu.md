# FAB Menu (M3 Expressive)

Source URL:
https://m3.material.io/components/fab-menu/overview
Generated: 2026-02-10

Extracted guidance

- One FAB menu size for all sizes of FABs
- Not used with extended FABs
- Available in primary, secondary, and tertiary color sets
- One menu size that pairs with any FAB
- Replaces any usage of stacked small FABs
- Contrasting close button and item colors
- Supports dynamic color
- Compatible with any FAB color style
- Differences from M2
- Type Original M3 M3 Expressive FAB menu -- Available
- Category Configuration Original M3 M3 Expressive Color Primary set, secondary set, tertiary set -- Available
- Use the table's menu to switch token sets. The FAB menu has a common token set and six color sets, three for each
  element (close button and menu item). Learn about design tokens
- Color Color values are implemented through design tokens. For designers, this means working with color values that
  correspond with tokens. In implementation, a color value will be a token that references a value. Learn more about
  design tokens
- States States are visual representations used to communicate the status of a component or interactive element. Learn
  more about interaction states
- FAB menu items share the same measurements as the medium button specs. The close button should always be 56dp.
- The FAB menu animates from the top trailing edge of the FAB to ensure a smooth animation.
- Larger FABs will place the FAB menu slightly higher, with larger margins underneath.
- A FAB menu opens from a FAB to show multiple related actions. It should always appear in the same place as the FAB
  that opened it. This makes actions immediately accessible, and keeps the UI clean by concealing actions when they're
  not needed. Don't open a FAB menu from an extended FAB or any other component.
- The FAB menu should be aligned to the trailing edge of the window. In right-to-left (RTL) languages, this means the
  FAB and FAB menu should be aligned to the left edge, and the layout of elements should be mirrored.
- FAB menus can contain 2-6 items. These should be closely related under a single action, like Share . Avoid grouping
  unrelated actions in the same FAB menu.
- When a FAB is paired with other components, like the floating toolbar or navigation rail, don't use the FAB menu.
- Color sets FAB menus have three color sets: primary, secondary, and tertiary. Use the color set that best matches the
  FAB color style. Use the primary FAB menu color set with the primary or primary container FAB color styles.
- Use the secondary FAB menu color set with the secondary or secondary container FAB color styles.
- Use the tertiary FAB menu color set with the tertiary or tertiary container FAB color styles.
- FAB menu items should always have label text. The icons shouldn't be removed since they make each item easy to
  identify.
- The list item should always hug its contents and look consistent. Avoid truncating text or setting fixed widths. All
  FAB menu elements should be rounded.
- The FAB menu can open from any sized FAB. Use with a FAB size suitable for the window size class. For example, larger
  FABs are recommended for larger windows.
- The FAB menu should remain anchored to the same corner or edge regardless of window size. In large and extra large
  windows, the FAB and FAB menu margins should increase from 16dp to 24dp.
- Appearing The FAB should transform into the close button of the FAB menu. The menu items should appear using the enter
  and exit transition. Originate the transition from one of the FAB's trailing corners, preferably the top-aligned
  corner.
- To ensure accessibility for keyboard users on the web, avoid positioning the FAB menu to completely obscure the focus
  indicator of an actionable element. Partially covering the desired element is fine, as long as the focus indicator is
  visible.
- Scrolling When window height is limited, like when viewing phones in horizontal orientation, FAB menu items can
  scroll. The items should scroll behind the close button.
- Expanding Any FAB menu item can expand and adapt to any shape using a container transform transition pattern. This
  includes a surface that is part of the app structure, or a surface that spans the entire screen.
- Navigate and interact with the FAB menu
- Ensure focus is correct when navigating through the menu
- Interaction & style
- FAB menu elements meet the minimum target size of 48dp.
- When the FAB menu can scroll, make sure the items scroll behind the close button. The close button should always be
  easy to access and unobstructed.
- When the FAB is selected, the FAB menu opens, and initial focus remains on the close button, which takes the place of
  the original FAB. Then the focus moves from the top menu item to the bottom.
- Keys Actions Tab Navigate to the next interactive element Space or Enter Activate the focused button or item
- The close button of the FAB menu should have the button role and label close .
- On mobile web, the items should have the menu item roles. The menu items should have labels matching the UI text.
