# Toolbars (M3 Expressive)

Source URL:
https://m3.material.io/components/toolbars/overview
Generated: 2026-02-10

Extracted guidance

- Two expressive types: docked toolbar and floating toolbar
- Use the vibrant color style for greater emphasis
- Can display a wide variety of control types, like buttons, icon buttons, and text fields
- Can be paired with FABs to emphasize certain actions
- Don't show at the same time as a navigation bar
- Added docked toolbar to replace bottom app bar Size: Shorter height
- Flexibility: More layout and element options
- Added floating toolbar with the following configurations: Layout: Horizontal or vertical
- Color: Standard or vibrant
- Flexibility: Can hold many elements and components. Can be paired with FAB.
- Differences from M2
- Elevation: No shadow
- Layout: Container height is taller and the FAB is now contained within the app bar container
- Not recommended types
- The original bottom app bar is no longer recommended. It should be replaced with the docked toolbar, which is very
  similar and more flexible.
- Type Original M3 M3 Expressive Docked toolbar -- Available Floating toolbar -- Available Bottom app bar Available Not
  recommended Use docked toolbar instead
- star Note: Implementation differs per platform. On Jetpack Compose, the floating toolbar is a separate component from
  the docked toolbar and bottom app bar.
- Category Configuration Original M3 M3 Expressive Color Standard (default) Available as bottom app bar Available
  Vibrant -- Available Floating toolbar layout Horizontal (default) -- Available Vertical -- Available Other elements
  With FAB Available as bottom app bar Available*
- star Note: *Implementation differs per platform. On Jetpack Compose, floating toolbar with FAB is fully supported . On
  other platforms, each component needs to be added separately.
- Flexibility & slots
- When configuring a toolbar, think of it as a container with several slots. Each slot can be a different element. The
  most common elements are icon buttons, buttons, and text fields.
- Color values are implemented through design tokens. For design, this means working with color values that correspond
  with tokens. For implementation, a color value will be a token that references a value. Learn more about design tokens
- By default all toolbars are 64dp high, center-aligned, have equal padding between items, and have a minimum outside
  padding of 16dp.
- Bottom app bar (not recommended)
- Bottom app bar tokens are in one token set.
- Color values are implemented through design tokens. For designers, this means working with color values that
  correspond with tokens. In implementation, a color value will be a token that references a value. Learn more about
  design tokens
- Use a toolbar to provide actions related to the current page. Toolbars can contain many actions and can scale to show
  more actions in larger windows.
- There are two types of toolbars: Docked toolbar Spans the full width of the window. It's best used for global actions
  that remain the same across multiple pages. Floating toolbar Floats above the body content. It's best used for
  contextual actions relevant to the body content or the specific page. The original bottom app bar is no longer
  recommended, but is still supported.
- When actions don't fit in a toolbar, add a menu.
- Standard A low-emphasis color scheme best used for focusing attention on the body content.
- Vibrant A high-emphasis color scheme that draws attention to the controls. It can also indicate a temporary change in
  the page behavior, such as entering edit mode.
- Toolbars & navigation bars
- The toolbar and navigation bar are both placed at the bottom of the window, so should not be shown at the same time.
  Show the navigation bar on primary pages, and toolbars on subsequent pages with actions.
- Floating toolbars can be used as tabs between related subsequent pages in the product hierarchy. This helps group
  similar pages together, and shows that the selection affects the body content underneath.
- Consider the existing app hierarchy when using a toolbar for local navigation. Avoid redundant or confusing navigation
  combinations in the same view.
- Container The docked toolbar's container spans the full width of the window. Avoid applying rounded corners to the
  container. This can imply the container expands or changes upon interaction.
- As long as there's a minimum of 16dp padding on the leading and trailing edge, arrange controls inside however you see
  fit. The 32dp padding between items is just the default. All elements need a minimum 48x48dp target area to be
  accessible. Be cautious of including too many controls as it can be overwhelming.
- The floating toolbar's container should be fully visible on screen. If more actions are needed, use an overflow menu.
- Elevation Floating toolbars have elevation by default. If the content beneath the toolbar is visually distinct,
  elevation can be removed.
- Flexibility & slots When configuring a toolbar, think of it as a container with several slots. These slots can be
  populated by buttons, icon buttons, images, text fields, or any kind of custom component. Icon buttons provide an even
  hierarchy of controls. Mixing in a filled icon button can help add emphasis to a single action.
- Use different icon button color styles, such as filled, tonal, and standard
- Customize the color roles of a single action, such as a primary or secondary palette
- Use wide and narrow icon buttons
- Pair the toolbar with a FAB
- Avoid using square icon buttons in floating toolbars. Their square shape conflicts with the fully-rounded shape of the
  floating toolbar container. Square buttons can be used in the docked toolbar.
- Floating toolbar with FAB A FAB can be placed next to a floating toolbar to present one high-priority action alongside
  a unified set of toolbar actions. Use a FAB for the highest-priority action in the view, or to complement the
  controls.
- Position & orientation
- Only place docked toolbars at the bottom of the window. If using other bottom-aligned elements, such as a navigation
  bar, don't use a docked toolbar.
- Floating toolbars can be horizontal or vertical. Horizontal toolbars should have a minimum 16dp margin from the edge
  of the window.
- In larger window sizes, floating toolbars can be vertical and placed on either side of the screen. Vertical toolbars
  should have a minimum 24dp margin.
- To keep vertical toolbars compact, don't use wide icon buttons. Use narrow or default icon buttons instead.
- Vertical toolbars should be positioned opposite the navigation rail to balance out the screen and keep actions easy to
  access. When showing a navigation rail and vertical floating toolbar at once, use the centered configuration of the
  navigation rail.
- Centering all elements
- Customizing to center a key action, and aligning other elements to the edges
- On Material (web), the docked toolbar is rounded by default, and can be placed in different parts of the page.
  Dividers can be used to organize large amounts of items. Only shrink the height and use extra small buttons if
  vertical space is limited.
- Floating The container should only be as big as needed to hold the items inside before reaching the 16dp margin. If
  there's not enough space for all items, put them in an overflow menu in the trailing slot. As the window size expands,
  more actions can be revealed. The floating toolbar width can also be capped to keep it smaller and hide more elements.
- Vertical toolbars aren't recommended for compact windows. They take up a significant area of the screen and may feel
  visually overwhelming, especially on screens with complex layouts. Only use them when the screen is simple or when the
  toolbar has a few controls.
- In larger window sizes, floating toolbars can be aligned to opposite edges of the screen so they're easy to reach and
  group similar actions. For example, consider placing the undo and redo actions in one toolbar, and editing controls
  like highlight, erase, and select in another. Stylistic differences can help emphasize each toolbar's purpose and
  clarify hierarchy.
- Don't use multiple toolbars in compact windows. There typically isn't enough room on screen. Instead, use one toolbar
  for all actions.
- Actions at the trailing edge of the toolbar can collapse into an overflow menu at smaller window sizes, and become
  visible again at larger sizes.
- Right-to-left languages In right-to-left (RTL) languages, mirror individual items that need it, like icons and text
  direction. If the order of actions is important, flip the order of the actions as well.
- Scrolling Docked toolbars can either remain on the screen during scroll, or animate offscreen.
- Floating toolbars can remain on the screen, animate offscreen, or collapse into a single, high-emphasis action on
  scroll.
- Consider other scrolling behavior, like collapsing to a FAB or key action on scroll. Note: This is not yet available
  in code.
- Don't collapse actions and scroll at the same time.
- star Note: This is a rapidly changing space. Guidelines are primarily intended for designers at this time. Find what's
  implemented in code in the design kit .
- Extended reality (XR) interfaces have special design requirements, like showing apps in 3D space. Material has an
  XR-specific toolbar with custom specs and guidance. Read XR developer documentation for more details.
- Types There is one toolbar orbiter. It closely aligns with the floating toolbar. It can be configured to be horizontal
  or vertical.
- Color & elevation XR uses color to communicate the elevation of UI elements and orbiters. With spatial elevation , the
  toolbar displays above the spatial panel on the Z-axis. Elevated toolbars can use any of these color options:
- Usage A toolbar can appear in an orbiter for a more immersive experience. Currently, this spatial capability is only
  available in full space. In home space, use a regular toolbar on the same plane as the body content to mimic a 2D
  experience.
- Local context (recommended) When placed in local context, the toolbar orbiter is centered at the bottom of the spatial
  panel it controls. It repositions in response to layout or content changes.
- Global context When placed in global context, the toolbar orbiter is centered at the bottom of the app. It stays
  anchored to the app during layout or content changes.
- Expand & collapse Toolbar orbiters with more than five items can expand and collapse to reveal or hide additional
  content. When a toolbar orbiter expands, it stays within the bounds of the adjacent spatial panel. Alternatively, more
  complex toolbars can be split into multiple toolbars.
- Additional toolbars In some cases, full space apps can have more than one toolbar orbiter, placed in either global or
  local context.
- Offset & inset positioning In full space, a toolbar orbiter can be positioned adjacent to or overlap a spatial panel.
- Spatial panel alignment By default, toolbar orbiters are center-aligned to the spatial panel. Their placement can be
  adjusted to accommodate specific user needs, such as improved ergonomics or right-to-left (RTL) languages .
- Avoid placing a vertical toolbar orbiter between spatial panels. This negatively affects the interface structure and
  can make it difficult to find.
- Accessibility considerations XR accessibility guidelines are still evolving. XR toolbars should follow applicable
  Material toolbar accessibility standards .
- Navigate and activate any actions in the toolbar
- Select a destination from a menu
- Activate a back button
- Maintain access to toolbar controls when the content is scrolled or collapsed
- Interaction & style The toolbar has no interactions by default. All interactions are with the elements placed inside.
- When tapping on an icon button in the toolbar, a touch ripple appears, indicating interaction feedback.
- When hovered, the hover state provides a visual cue to the user that the element is interactive.
- When clicked (in both active and inactive states), a ripple appears, showing the user feedback.
- Initial focus Focus lands on the first interactive element. Use Tab to navigate through all other actions.
- Keyboard navigation Keys Actions Tab or Arrows Navigate between interactive elements Space or Enter Activate the
  focused element
- Labeling elements On web, the toolbar container should have the toolbar role. On mobile, it can be a generic
  container. All actions inside the toolbar should follow their respective accessibility guidelines.
