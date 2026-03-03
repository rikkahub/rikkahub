# Extended FAB (M3 Expressive)

Source URL:
https://m3.material.io/components/extended-fab/overview
Generated: 2026-02-10

Extracted guidance

- Three sizes: small, medium, and large
- Use instead of FAB when label text is needed to understand action
- Added new sizes Small: 56dp
- Deprecated Original extended FAB (56dp)
- Surface extended FAB
- Adjusted typography to be larger
- Differences from M2
- Layout: Extended FAB is the same height as the FAB
- Shape: Boxier style with smaller corner radius
- The original extended FAB is deprecated in the expressive update. Use a small extended FAB; the type style was updated
  from label large to title medium , and the inner padding was reduced. View deprecated extended FAB specs
- Type Original M3 M3 Expressive Small extended FAB -- Available Medium extended FAB -- Available Large extended FAB --
  Available Extended FAB Available Deprecated Use small extended FAB
- Use the table's menu to select a token set. Extended FAB tokens are organized by size and color.
- Color values are implemented through design tokens. For design, this means working with color values that correspond
  with tokens. For implementation, a color value will be a token that references a value. Learn more about design tokens
- Color styles Extended FABs can use several combinations of color and on color styles, such as primary and on primary .
  The following color mappings provide the same level of contrast and functionality, so choose a color mapping based on
  visual preference.
- Deprecated color styles
- Extended FABs should no longer use surface color styles. They're still available, but not recommended.
- States are visual representations used to communicate the status of a component or interactive element. Learn more
  about interaction states
- When using a non-default color mapping for extended FABs, make sure the state layer color is the same as the icon
  color. For example, the state layer color for primary mapping should be md.sys.color.primary.
- Deprecated extended FAB
- Use the table's menu to select a token set. The deprecated extended FAB token sets are common tokens and surface color
  styles. Other color styles like primary, secondary, and tertiary are not deprecated as they're still used by the
  latest extended FABs.
- Deprecated colors Color values are implemented through design tokens. For design, this means working with color values
  that correspond with tokens. For implementation, a color value will be a token that references a value. Learn more
  about design tokens
- Additional color mappings Extended FABs can use other combinations of container and icon colors. The color mappings
  below provide the same legibility and functionality as the default, so the color mapping you use depends on style
  alone.
- Deprecated states States are visual representations used to communicate the status of a component or interactive
  element. Learn more about interaction states .
- Attribute Value Container height 56dp Container width Dynamic, 80dp min Container shape 16dp corner radius Icon size
  24dp Padding 16dp
- Use an extended FAB on screens with long, scrolling views that require persistent access to an action, such as a
  checkout screen. Use it when label text helps understand the main action, or to add further emphasis to the button.
- Additional emphasis The extended FAB can provide more emphasis and clarity to a product's primary action. Since it has
  room for both a text label and icon, the extended FAB can be effective where an icon alone is ambiguous. However, the
  relationship between an extended FAB's icon and label should be clear.
- Like the regular FAB, only one extended FAB should be used per screen. Multiple FABs compete for attention. If
  additional high-level actions are required, consider adding more buttons elsewhere on the page.
- The extended FAB shouldn't be used as an option in a set of actions. Instead, use filled buttons for a similar level
  of emphasis.
- Choosing a size There are three types of extended FABs: small, medium, and large. Choose an appropriately-sized
  extended FAB to add the right amount of emphasis for an action. In compact windows with one prominent action, the
  large extended FAB can be appropriate. In larger window sizes, use a medium or large extended FAB.
- Container The extended FAB container is a rounded rectangle that hugs its contents. The extended FAB grows and shrinks
  with text length.
- Icon (optional) An extended FAB's icon should intuitively represent its action.
- Label text The extended FAB's label should clearly describe its action. Use 1-2 words at most. Keep in mind that
  localization may increase the amount of characters and width of the extended FAB.
- Avoid putting other floating components, like the floating toolbar, on screen with the extended FAB.
- The FAB and extended FAB can transform into each other depending on available space and layout. In a collapsed
  navigation rail, a FAB would be used. When the rail is expanded, the FAB can transform into an extended FAB.
- Right-to-left languages Extended FABs should mirror their elements in right-to-left (RTL) languages.
- Window sizes In compact and medium window sizes, the extended FAB should be placed at the bottom of the screen, either
  center-aligned or aligned to the trailing edge of the window.
- At the bottom right edge of the window, in both LTR and RTL languages
- Within the navigation rail
- Appearing The extended FAB surface expands when appearing on screen using an enter and exit transition pattern.
- Expanding The extended FAB can expand and adapt to any shape using a container transform transition pattern. This
  includes a surface that is part of the app structure, or a surface that spans the entire screen.
- Transforming The extended FAB can transform into a FAB on scroll to temporarily take up less space on screen.
- Scrolling The extended FAB can transform into a FAB when scrolling down, and back to an extended FAB when scrolling
  up.
- The FAB shape changes
- FAB icon moves to the left
- FAB text label fades in
- Navigate to and activate the extended FAB
- Interaction & style
- To make it easier for users of screen readers to reach a primary action such as an extended FAB, consider placing the
  action in the upper left region of large web screens, like in an expanded navigation rail. In smaller windows, the
  best place for the extended FAB is the lower right corner of a screen.
- Initial focus Ensure the extended FAB is prioritized in the overall focus order to create an efficient experience for
  people who navigate UIs with assistive tech. On mobile, the focus order may start with the app bar, move to the
  navigation bar, and then skip past any other content on the page to land on the extended FAB. When using an extended
  FAB, both the visible label and icon should be treated as one focusable element. The extended FAB doesn't need a
  tooltip because it already has a visible label.
- Keys Actions Tab Moves focus to the extended FAB Space or Enter Activates the extended FAB
- To ensure the action is clear, use consistent icons and text labels, such as a Compos e icon with a Compose text
  label. The icon and text label combination should have one distinct purpose. The accessibility label must include the
  same first word as the visible label. For example, if the visible button is Create , then the accessibility label
  might say Create a new invite .
