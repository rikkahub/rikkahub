# App Bars (M3 Expressive)

Source URL:
https://m3.material.io/components/app-bars/overview
Generated: 2026-02-10

Extracted guidance

- Focus on describing the current page and provide 1-2 essential actions
- Displays labels and page navigation controls at the top of the page. (Use a toolbar to display page actions)
- Four types: Search app bar, small, medium flexible, large flexible
- On scroll, apply a fill color to separate from body content
- Can animate on and off screen with another bar of controls, like a row of chips
- Renamed component from top app bar to app bar
- Added search app bar
- Deprecating medium and large app bars
- Added medium flexible and large flexible app bars with: Reduced overall height
- Larger title text
- Left- and center-aligned text options
- More flexible elements for imagery and filled buttons
- Added features to small app bar: Subtitle
- Center-aligned text option
- Differences from M2
- Color: New color mappings and compatibility with dynamic color
- On scroll: No drop shadow, instead a color fill creates separation from content
- Typography: Larger default text
- Layout: Smaller default height
- Deprecated types The medium and large app bars are being deprecated in the expressive update, and should be replaced
  with the medium flexible and large flexible app bars. The flexible app bars have flexible heights, multi-line support,
  and can contain a wider variety of elements, like images. View deprecated app bar specs
- Type Original M3 M3 Expressive Search app bar -- Available Small Available Available Center-aligned Available Merged
  into small . Use centered-text configuration. Medium Available Deprecated Use medium flexible Medium flexible --
  Available Large Available Deprecated Use large flexible Large flexible -- Available
- Category Configuration Original M3 M3 Expressive Text alignment Leading edge (default) Available Available Centered --
  Available
- An image or logo
- A filled icon button
- The search app bar can include trailing actions inside and outside the search bar. When the search bar is selected, it
  should open the search view component.
- Image An image can be placed in the app bar. In small app bars, this can replace the label text.
- Filled trailing icon button The app bar's trailing icon buttons can be replaced with a single, primary, or tonal
  filled icon button in default or wide sizes.
- Color Color values are implemented through design tokens. For design, this means working with color values that
  correspond with tokens. For implementation, a color value will be a token that references a value. Learn more about
  design tokens All app bars share the same color roles. On scroll, the container changes color to surface container .
- Search app bar
- Small app bar
- Medium flexible app bar
- Large flexible app bar
- Deprecated app bars
- The medium and large app bars are deprecated in the expressive update. Use the medium flexible and large flexible app
  bars in their place.
- Select a token set to view in the table's menu. Deprecated app bar token sets are organized into a medium, large, and
  older deprecated token sets. Learn about design tokens
- Color values are implemented through design tokens. For designers, this means working with color values that
  correspond with tokens. In implementation, a color value will be a token that references a value. Learn more about
  design tokens
- Medium app bar
- Large app bar
- Use an app bar to provide content and actions related to the current page, such as page navigation actions, headlines,
  images, and 1-2 essential actions. The information and actions in the app bar should be contextual and specific to a
  page, but can also include global product controls, such as search or notifications.
- App bars should only have one action, two if necessary. The primary action should alter or exit the entire page, like
  Send , Save , or Edit . If the product has many actions, place those in a toolbar. Avoid placing an overflow menu in
  the app bar when possible.
- To boost visibility of a primary action, change the style of the icon button to filled or tonal, and consider using a
  wide icon button. Avoid using multiple filled or tonal buttons.
- Search app bar Use on home pages when search is key to the product.
- Small Use in dense layouts or when a page is scrolled.
- Medium flexible Use to display a larger headline. It can collapse into a small app bar on scroll.
- Large flexible Use to emphasize the headline of the page.
- Medium Replace with medium flexible.
- Large Replace with large flexible.
- Use a search app bar to provide an emphasized entry-point to open the search view.
- Searching a specific area Example: Search inbox
- Search [Product] Example: Search Photos
- Buttons in search app bar In addition to a trailing avatar, search app bars can have up to two trailing icons on
  mobile. Trailing icons can be placed inside or outside the search bar.
- The leading element of a search app bar can be used for a product's logo to brand the app's overall experience. This
  logo can be purely cosmetic, or can trigger an action like returning to the home screen or refreshing it. Avoid using
  a logo to open an expanded navigation rail.
- Don't use more than two trailing icon buttons with an avatar. If more actions are needed, place them in a toolbar
  instead.
- Alternate color options By default, search containers in app bars use the surface container color to distinguish it
  from the app background. If the background is darker, use a lighter container color on the search bar, like surface
  bright . When choosing alternate colors, make sure the search text and container have at least 3:1 contrast for
  readability.
- Container The app bar container holds all information and actions at the top of a screen, including navigation icons,
  headlines, and buttons. Avoid changing the position or shape of the container.
- Always use the default height of the app bar, and make it span the full width of the window.
- Adding logos Image logos can be used in app bars to bolster brand identity or visual appeal. The image should be high
  quality and pertinent, and shouldn't disrupt the app bar's functionality.
- A menu icon, which opens a modal expanded navigation rail
- A back arrow, which returns to the previous screen
- The current page
- The current section
- Headlines can be aligned to the leading edge or centered. The headline's typography size and style change depending on
  the type of app bar.
- Subtitle Subtitles can add additional context to a page. These can be leading-aligned or center-aligned with the
  headline text.
- Trailing icon buttons Up to two icon buttons can be placed after the headline, aligned to the trailing edge of the app
  bar. Place most-used actions closest to the leading edge. Avoid using these buttons to open a menu with more actions.
  If more actions are needed, place them in a toolbar instead. If changing the icon button color style to filled or
  tonal, only use one icon button.
- Use filled icons when possible for the best visibility. Outlined icons can also be used, particularly for unselected
  toggle buttons.
- Resizing The width of the app bar container responds to the view or device width. It should always span 100% of the
  window width.
- Resizing may cause actions at the trailing edge of the app bar to collapse into an overflow menu at smaller window
  sizes. These actions become visible again at larger sizes.
- The search container of the search app bar should fill 100% of the space between leading and trailing app bar elements
  until it reaches 312dp. Then, it should only grow further to fill 50% of that space.
- Presentation The app bar automatically supports right-to-left (RTL) languages by aligning the layout of elements to
  the leading and trailing edges of the container. This means that in RTL languages, the layout of the app bar is
  mirrored.
- Scrolling App bars should initially be the same color as the background, then fill with a contrasting color on scroll
  to provide visual separation from the background. The app bar can remain on a page at all times, or can hide and
  reappear when scrolling.
- Selecting the search bar should open the search view component.
- When scrolled, medium flexible and large flexible app bars can transform into small app bars. They should remain small
  until the page is scrolled back to the top. Don't transform app bars into a search app bar .
- star Note: This is a rapidly changing space. Guidelines are primarily intended for designers at this time. Find what's
  implemented in code in the design kit .
- Extended reality (XR) interfaces have special design requirements, like showing apps in 3D space. Material has an
  XR-specific app bar with custom specs and guidance. See XR developer documentation for more details.
- Types & configurations
- There is one app bar orbiter. It closely aligns with the small app bar. It can be configured to be center-aligned or
  left-aligned.
- Color & elevation
- XR uses color to communicate the elevation of UI elements and orbiters. With spatial elevation , the app bar displays
  above the spatial panel on the Z-axis. Elevated app bars can use any of these color options:
- An app bar can appear in an orbiter for a more immersive experience. Currently, this spatial capability is only
  available in full space. In home space, use a regular app bar on the same plane as the body content to mimic a 2D
  experience.
- Global context When placed in global context, the orbiter is centered at the top of the app it controls. It stays
  anchored to the app during layout or content changes. This ensures navigation elements are always easy to find and
  use.
- Local context When placed in local context, the orbiter is centered at the top of the spatial panel it controls. It
  repositions in response to layout or content changes.
- Additional app bars In most cases, apps should only have one app bar orbiter, placed in global context.
- Offset and inset positioning
- Spatial panel alignment By default, app bar orbiters are center-aligned to the spatial panel. Their width and
  placement can be adjusted to accommodate specific user needs, such as improved ergonomics or right-to-left (RTL)
  languages.
- Width boundaries An app bar orbiter's width should adjust to stay in a person's field of view . This makes crucial
  navigation elements easy to find.
- It's not recommended to increase the width of an app bar orbiter beyond a person's natural field of view . This
  creates a visual imbalance and makes it difficult to find navigation elements.
- Adaptable width When placed in local context, an app bar orbiter can expand to the width of its adjacent spatial
  panel. Be sure the orbiter stays in a person's field of view, and test for usability.
- XR accessibility guidelines are still evolving. XR app bars should follow applicable Material app bar accessibility
  standards .
- Understand what page they're currently visiting
- Take actions or navigate to a new page destination
- Maintain access to app bar actions when the content is scrolled
- Interaction & style
- Touch When tapping on an icon button, a touch ripple appears, indicating interaction feedback.
- Cursor When hovered, the hover state provides a visual cue to the user that the element is interactive. When clicked (
  in both active and inactive states), a ripple appears to indicate feedback.
- Keyboard or switch When navigating to an interactive element, a focus indicator appears to show that action can be
  taken. When the element is selected, an action is then performed.
- Initial focus Focus should initially land on the leading button, since it's the first interactive element of the app
  bar.
- Search container: surface container
- Search label: on surface variant
- Keyboard navigation Keys Actions Tab Move focus to the next interactive element Space or Enter Activate the focused
  element
- The accessibility label for a title should be the same as the content within the title. If needed, add additional
  context to the accessibility label to ensure users understand what page they're on or what content is being shown.
  Screen readers will read the UI text followed by the component's role.
- Label icon buttons according to their accessibility guidelines .
