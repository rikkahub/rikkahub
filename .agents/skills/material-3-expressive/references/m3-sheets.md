# Sheets (M3 Expressive)

Source URLs:

- https://m3.material.io/components/bottom-sheets/overview
- https://m3.material.io/components/side-sheets/overview
  Generated: 2026-02-10

Extracted guidance

- Use bottom sheets in compact and medium window sizes
- Two variants: standard and modal
- Content should be additional or secondary (not the app's main content)
- Bottom sheets can be dismissed in order to interact with the main content
- Differences from M2
- Color: New color mappings and compatibility with dynamic color
- Shape: Bottom sheets have a 28dp top corner radius
- Layout: New max-width of 640dp and an optional drag handle with an accessible 48dp hit target
- Modal bottom sheets are above a scrim while standard bottom sheets don't have a scrim. Besides this, both variants of
  bottom sheets have the same specs.
- Tokens and specs Browse the component elements, attributes, tokens, and their values. Learn more about design tokens
- Color Color values are implemented through design tokens. For design, this means working with color values that
  correspond with tokens. For implementation, a color value will be a token that references a value. Learn more about
  design tokens
- Bottom sheets span the full window width up to 640dp. When the window width exceeds 640dp, bottom sheets adjust to
  have a top margin of 56dp and side margins of 56dp. Attribute Value Drag handle alignment (horizontal) Center Drag
  handle padding top/bottom 22dp Top margin 72dp Top margin (window width > 640dp) 56dp Start/end margin (window width >
  640dp) 56dp Width Full width, up to max-width 640dp Height Variable
- Bottom sheets display supplementary content and actions on a mobile screen.
- Bottom sheets are a versatile component that can contain a wide variety of information and layouts, including menu
  items (in list or grid layouts), actions, and supplemental content.
- A container is the only required element of a bottom sheet. Bottom sheet layouts can vary widely to support the kinds
  of content they contain.
- Bottom sheet containers hold all bottom sheet elements. Their size is determined by the space those elements occupy.
  The container is the only required element of a bottom sheet. All other elements are optional.
- List items (optional)
- Lists are a continuous group of text or images. List items can include label text, icons, and text buttons, among
  other elements.
- Dividers can be used to separate related content in bottom sheets.
- Thumbnail Bottom sheets can include thumbnails for an avatar or logo. Image Bottom sheets can include photos,
  illustrations, and other graphics, such as weather icons. Video Bottom sheets can include video.
- Standard bottom sheets
- Standard bottom sheets co-exist with the screen's main UI region and allow for simultaneously viewing and interacting
  with both regions, especially when the main UI region is frequently scrolled or panned. Use a standard bottom sheet to
  display content that complements the screen's primary content, such as an audio player in a music app.
- At full-screen height, standard bottom sheets contain a collapse icon in an app bar to return to their initial
  position. Standard bottom sheets can contain supplementary content that continues below the screen, such as location
  information over a map.
- Modal bottom sheets
- Like dialogs, modal bottom sheets appear in front of app content, disabling all other app functionality when they
  appear, and remaining on screen until confirmed, dismissed, or a required action has been taken.
- Use a modal bottom sheet as an alternative to inline menus or simple dialogs on mobile, especially when offering a
  long list of action items, or when items require longer descriptions and icons. Modal bottom sheets are used in mobile
  apps only.
- To provide access to its top actions, the initial vertical position of modal bottom sheets is capped at 50% of the
  screen height. Modal bottom sheets whose contents exceed 50% of the screen height can then be pulled across the full
  screen and scrolled internally to access their remaining items.
- Tapping a menu item or action within the bottom sheet
- Tapping the scrim
- Swiping the sheet down
- Using a close affordance within the bottom sheet's app bar, if available
- Compact window size In compact window sizes, like mobile devices, bottom sheets extend across the width of a screen
  and are elevated above the primary content.
- Medium and expanded window sizes For larger screens with medium and expanded window sizes, bottom sheets have a
  default max-width to prevent undesired layouts and awkward spacing. However, this can be overridden if needed. For
  more complex tasks and flows, consider using a non-transient surface such as a floating sheet.
- On larger expanded window sizes, like desktop, a bottom sheet can be swapped for a side sheet that shows similar
  content.
- Bottom sheets can offer an expansion option where the sheet is fully raised and toggled between a collapsed and
  expanded state. This provides a more predictable footprint of the sheet, and can be set by the system or toggled by
  the user.
- The drag handle can be dragged or selected to change the bottom sheet height. Sheets should be able to cycle through
  preset heights and close completely without dragging. Selecting the drag handle should toggle through preset heights
  or close the sheet, while selecting the scrim should always close the bottom sheet. If the bottom sheet has multiple
  preset heights but can't use a drag handle, Material requires the inclusion of a single-pointer alternative to change
  height.
- Bottom sheets can be horizontally scrolled, independent of the rest of the screen's content.
- Bottom sheet detaches from the left and right edges of the screen to signal it will close
- Previous screen is revealed in a preview
- Users should be able to: Resize bottom sheets without having to rely on touch gestures
- Interaction & style
- Touch target area The top 48dp portion of the bottom sheet is interactive when user-initiated resizing is available
  and the drag handle is present.
- Initial focus The optional drag handle can be focused in the tab order and interacted with using non-touch inputs,
  such as keyboard or switch controls.
- Dragging Include a single-pointer alternative for any action that can be completed by dragging. Drag handles should
  cycle the bottom sheet through available heights when selected. If a drag handle can't be used, add a button to do
  this action.
- Keys Actions Tab Focus lands on drag handle Space / Enter Toggles between available heights
- Label only the drag handle. The accessibility role for the drag handle is "button."
- Use side sheets to provide optional content and actions without interrupting the main content
- People can navigate to another region within the sheet
- Side sheets can contain a back icon for navigation
- Right-to-left (RTL) language support with left side sheet
- Shape: Modal side sheets have a 16dp corner radius
- Standard side sheet
- Standard side sheet color Color values are implemented through design tokens. For design, this means working with
  color values that correspond with tokens. For implementation, a color value will be a token that references a value.
  Learn more about design tokens
- Standard side sheet measurements
- Attribute Value Start/end padding 24dp Padding between top elements 12dp Bottom actions height 72dp Bottom actions top
  padding 16dp Bottom actions bottom padding 24dp Bottom actions alignment (horizontal) Left Max-width 400dp Margins (
  when detached) 16dp
- Modal side sheet
- Modal side sheet color Color values are implemented through design tokens. For design, this means working with color
  values that correspond with tokens. For implementation, a color value will be a token that references a value. Learn
  more about design tokens .
- Modal side sheet measurements
- Attribute Value Start/end padding 24dp Start padding with icon 16dp Padding between top elements 12dp Bottom actions
  height 72dp Bottom actions top padding 16dp Bottom actions bottom padding 24dp Bottom actions alignment (horizontal)
  Left Max-width 400dp Margins (when detached) 16dp
- Displaying a list of actions that affect the screen's primary content, such as filters
- Displaying supplemental content and features
- Modal side sheets are preferred in compact window sizes, like mobile, due to limited screen size. They can display the
  same kinds of content as standard side sheets, but must be dismissed in order to interact with the underlying content.
- Side sheets have a fixed width and typically span the height of the screen. Their dimensions depend on how the app's
  layout is subdivided into UI regions.
- Container Side sheet containers hold all side sheet elements. Their size is determined by the space those elements
  occupy. The container is the only required element of a side sheet.
- Back icon button (optional) Icon buttons can provide ways to exit a side sheet or move to a different experience.
  Because the primary content behind or beside a side sheet is always visible, it's important to provide affordances for
  leaving a side sheet and returning to the primary content.
- Close icon button (optional) A close affordance provides a consistent method for dismissing a side sheet. A close icon
  button is highly recommended, increases accessibility, and makes focused side sheets easier to close.
- Action buttons (optional) Buttons represent actions available from a side sheet. Examples: Save , Edit , Download Use
  elevation, fill, and tone to call attention to specific actions.
- Action buttons from content
- User-generated content from system-generated content
- Content (optional) Side sheets can display a wide variety of content and layouts, ranging from a list of actions to
  supplemental content in a tabular layout.
- Side sheets have a default width, but can be resized depending on the needs of the layout. When a standard side sheet
  opens, the body area shrinks to accommodate the sheet's width while maintaining a margin on the body's trailing edge.
- RTL language support In right-to-left (RTL) languages, side sheets should appear on the left edge of the window with
  all elements reversed.
- Side sheets can vertically scroll independent of the rest of the UI. This allows their scroll position and content to
  persist while the page is scrolled, and vice versa. Side sheets cannot scroll horizontally.
- The side sheet detaches from the top and bottom edges of the screen to signal it will close
- The previous screen is revealed in a preview
- The side sheet and its content always scales in the direction of the gesture
- People should be able to dismiss the side sheet using assistive technology.
- Material requires that a close affordance, such as a close icon button, is always present within a side sheet.
- Actions within a side sheet can be focused by tab order using a keyboard or switch control.
- Keys Actions Tab Focus lands on (non-disabled) icon button Space or Enter Activates the (non-disabled) icon button
- The accessibility role for a side sheet is Dialog .
