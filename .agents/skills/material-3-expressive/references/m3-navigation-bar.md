# Navigation Bar (M3 Expressive)

Source URL:
https://m3.material.io/components/navigation-bar/overview
Generated: 2026-02-10

Extracted guidance

- Use navigation bars in compact or medium window sizes Can contain 3-5 destinations of equal importance Destinations
  don't change. They should be consistent across app screens.
- Baseline navigation bar is no longer recommended
- Added flexible navigation bar Shorter height
- Can be used in medium window sizes with horizontal navigation items
- Active label changed from on-surface-variant to secondary
- Differences from M2
- Color: New color mappings and compatibility with dynamic color
- Elevation: No shadow
- Layout: Container height is taller
- States: The active destination can be indicated with a pill shape in a contrasting color
- Name: Bottom navigation has been renamed navigation bar
- The baseline nav bar is no longer recommended, and should be replaced by the flexible nav bar, which is shorter and
  supports horizontal navigation items in medium windows. View baseline nav bar specs
- Variant M3 M3 Expressive Flexible navigation bar -- Available Navigation bar Available Not recommended. Use flexible
  navigation bar .
- In compact windows, navigation bars use vertical items. In medium windows, navigation bars should use horizontal
  items.
- Category Configuration M3 M3 Expressive Navigation item layout Vertical (default) Available Available Horizontal --
  Available
- Use the table's menu to switch between token sets for the navigation bar and the nav items. Learn about design tokens
- Color Color values are implemented through design tokens. For designers, this means working with color values that
  correspond with tokens; in implementation, a color value will be a token that references a value. Learn more about
  design tokens
- For badge color roles, go to badge specs .
- States States are visual representations used to communicate the status of a component or an interactive element.
- The navigation bar stretches the full window width.
- Vertical navigation items dynamically change width to equally fit the container. Horizontal navigation items have a
  fixed width, so extra space is added to the ends of the navigation bar instead.
- Baseline navigation bar
- These tokens are for the baseline navigation bar.
- Navigation bars provide access to three to five destinations. The nav bar is positioned at the bottom of windows for
  convenient access. Each destination is represented by an icon and label text. One navigation destination is always
  active. When a navigation bar icon is tapped or focused, people are taken to the navigation destination associated
  with that icon.
- Three to five main pages in the product
- Mobile or tablet only
- Use vertical items in compact windows, like mobile
- Use horizontal items in medium windows, like tablets
- For products with more than five navigation items, don't use a navigation bar; the elements may collide and there
  likely won't be enough space for translated text. Instead, consider using tabs to organize similar content within a
  page, or hide the navigation behind a menu icon using a modal expanded navigation rail.
- Container The container should always be placed at the bottom of the product and span the full length of the window.
  Navigation items are centered within the container. The container has a color fill to provide separation from other
  content.
- Navigation items Navigation items hold all elements for each destination: the icon, label text, and active indicator.
  They can be vertical , with the text below the icon and indicator, or horizontal , with the icon and text beside each
  other inside the indicator. Vertical items are best in compact windows, and horizontal items are best in medium
  windows. Horizontal items are centered in the nav bar with outer margins.
- Icons Navigation rail items must use icons that symbolize the content of their page. Browse popular icon . Use a
  filled icon for the active destination and outlined icons for inactive destinations. If an icon doesn't have a filled
  version, apply semibold weight to the icon instead.
- Active and inactive icons must have a minimum 3:1 contrast ratio with the container.
- Active indicator The active indicator shows which page from the nav bar is currently being displayed.
- Label text The label text should be a short, meaningful description of each navigation destination and another way for
  people to understand an icon's meaning. All navigation items require a label text. It should be 1-2 words.
- Badges (optional) Navigation bars can display badges in the upper right corners of the destination icon. Badges can
  contain dynamic information, such as the number of new messages.
- The floating action button (FAB) is placed above the navigation bar. Nav bars are always placed at the bottom of the
  window.
- Navigation bars can be temporarily covered by dialogs, bottom sheets, navigation drawers, the on-screen keyboard, or
  other elements needed to complete a flow. They should not be permanently obstructed on any screen.
- Resizing Only use navigation bars for compact and medium window size classes. Compact : For narrow windows, use a
  navigation bar or modal navigation rail. Medium : Use a navigation bar or navigation rail. Decide based on whether
  horizontal or vertical space is more important. Expanded and extra-large : Use a navigation rail instead. Decide based
  on available window space and the number of navigation destinations.
- The navigation bar container spans 100% of the window width.
- The navigation bar is used on smaller devices. It's not intended for desktop.
- Presentation In medium window sizes, use horizontal nav items to better use available space. Horizontal nav items
  should remain centered with the same padding at each window size.
- Preserve state : If someone has interacted with this destination, it returns to their scroll position, current tab,
  and in-line search status.
- Reset state : Any prior user interactions are reset, including scroll position, tab selection, and in-line search.
- Re-selecting the currently active destination should reset the scroll position to the top of the page. Don't swipe
  between destinations Swiping across the screen does not navigate between destinations, and is not supported by the
  navigation bar. Swipe behavior should be reserved for related items, such as cards in a carousel, or actions such as
  archiving a list item.
- Scrolling Upon scroll, the navigation bar can appear or disappear. Don't hide the navigation bar on scroll when a
  screen reader is active.
- Selection The icon becomes filled and the active indicator expands from the center of the icon when switching between
  destinations. The active indicator animation should only apply on one axis to better represent a flat, shared plane.
- When a destination is tapped, the destination screens use a top level transition pattern. In addition, the icon
  becomes filled and the active indicator expands from the center of the icon.
- star Note: This is a rapidly changing space. Guidelines are primarily intended for designers at this time. Find what's
  implemented in code in the design kit .
- Extended reality (XR) interfaces have special design requirements, like showing apps in 3D space. Material has an
  XR-specific navigation bar with custom specs and guidance. See XR developer documentation for more details.
- Color & elevation
- On XR, color is used to highlight elevated UI elements and orbiters. With spatial elevation , the navigation bar
  displays above the spatial panel, on the Z-axis. Color communicates elevation on UI elements and orbiters. Elevated
  nav bars can use any of these color options:
- In full space, a navigation bar can appear in an orbiter for a more immersive experience. Currently, spatial
  capabilities, such as orbiters, are only available in full space. In home space, use a regular navigation bar on the
  same plane as the body content to mimic a 2D experience.
- Global context When placed in global context, the navigation bar orbiter is centered at the bottom of the app it
  controls. It stays anchored to the app during layout or content changes. This ensures navigation elements are easy to
  find and use.
- Local context When placed in local context, the navigation bar orbiter is centered at the bottom of the spatial panel
  it controls. It repositions in response to layout or content changes.
- Use offset positioning for global actions that affect the overall app experience
- Use inset positioning for local actions that are specific to a spatial panel
- Inset positioning Don't obstruct content. To ensure a balanced and uncluttered layout, a navigation bar orbiter should
  overlap spatial panels by 12dp and no more than half their height.
- Horizontal alignment The navigation bar orbiter placement shouldn't exceed the width of adjacent spatial panels.
- Spatial panel alignment A navigation bar orbiter should always be placed at the bottom of a spatial panel and within
  the immediate field of view (FOV). Follow common usability practices to make the experience easy to use and consistent
  across platforms. Avoid placing the navigation bar orbiter at the top of a spatial panel, as this area is typically
  reserved for app bar orbiters or other critical UI elements.
- XR accessibility guidelines are still evolving. XR navigation bars should follow applicable Material nav bar
  accessibility standards .
- Move between navigation destinations
- Select a particular navigation destination from a set
- Get appropriate feedback based on input type
- Interaction & style
- When a navigation item is tapped, the active indicator appears in place, providing feedback that it's selected
- A touch ripple passes through the indicator
- The icon switches from outlined to filled
- The icon changes color
- When hovered, the active indicator appears in a reduced state providing a visual cue that the destination is
  interactive
- When clicked (in both active and inactive states), a ripple passes through the indicator
- The icon changes color, becoming darker
- Text scaling and truncation When someone sets their device to show a larger text size, the navigation bar should grow
  vertically to accommodate larger labels while retaining the default padding. It's okay for scaled text to wrap in
  navigation items. To remain accessible, ensure the full label is always visible on-screen at up to 2x text sizing.
  Beyond this size, text can truncate.
- Initial focus lands directly on the first navigation item, since that is the first interactive element of the
  component.
- Use a filled icon with a bold label for selected destinations. For unselected destinations use an outlined icon with a
  medium label. If an icon doesn't have a filled style, use a thicker or heavier version of the icon instead.
- Keyboard navigation Keys Actions Tab Move between navigation items Space / Enter Selects the focused navigation item
- The accessibility label for a navigation item is typically the same as the destination name.
- When the visible UI text is ambiguous, accessibility labels need to be more descriptive. For example, a navigation
  destination visibly labeled Library would benefit from additional information in its accessibility label to clarify
  the destination's intent. Note: On MDC-Android, a more descriptive accessibility label is not available and the role
  is not announced.
