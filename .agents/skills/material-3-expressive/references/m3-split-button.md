# Split Button (M3 Expressive)

Source URL:
https://m3.material.io/components/split-button/overview
Generated: 2026-02-10

Extracted guidance

- Use to show an action with a menu of related actions
- Same size range as buttons and icon buttons: XS, S, M, L, XL
- Type Original M3 M3 Expressive Split button -- Available
- Category Configuration Original M3 M3 Expressive Size XS, S, M, L, XL -- Available Color Elevated, filled, tonal,
  outlined -- Available
- Use the table's menu to select a token set. Split button token sets are organized by size. Learn about design tokens
- The leading button in split buttons can have an icon, label text, or both. The trailing button should always have a
  menu icon.
- Color values are implemented through design tokens. For designers, this means working with color values that
  correspond with tokens; in implementation, a color value will be a token that references a value.
- Split buttons use the same color schemes as standard buttons. However, unlike toggle buttons, the split button color
  doesn't change when selected-only a state layer is applied. Split buttons use the same colors and state layers as
  buttons, shown in the following token module. Go to buttons for more details.
- States States are visual representations used to communicate the status of a component or an interactive element.
  Split button states use the same colors and state layers as buttons and icon buttons. Go to those specs for details.
- Leading button shape The inner corners change shape for hovered, focused, and pressed states.
- Trailing button shape The inner corners change shape for hovered, focused, and pressed states, and the icon becomes
  centered when selected.
- Text and icons are optically centered when the buttons are asymmetrical. They're centered normally when symmetrical.
- The inner corner radius changes depending on button sizing. The space should always be 2dp.
- Maximize legibility by using a limited color palette, following the same guidance for buttons.
- Split buttons are used to add a menu of actions alongside a main action. This reduces visual complexity by hiding
  extra options. Split buttons work well alone or alongside common buttons and icon buttons.
- Split buttons can be used alongside other buttons and button groups.
- Split buttons can be of different sizes from other buttons on the page, especially since they take up more space.
- The split button typically opens a menu, but can be customized to open other components like cards.
- The leading button should be brief, just one or two words, with an icon that best matches the action. The trailing
  button should always have the expand and collapse icon since it rotates when selected. Avoid modifying the icon.
- In right-to-left languages, the component layout is mirrored.
- The split button uses the standard motion scheme (not the expressive motion scheme) when rotating the menu button. The
  menu button rotates inwards 180 when opened and closed.
- Menu placement When using the split button with a menu, align the menu with the trailing button when possible.
- If there's not enough room, align the menu to one of the sides of the button.
- Depending on window size, scroll position, and other factors, the menu may need to appear elsewhere around the button.
  Always try to align it with one of the edges of the button. The menu should be 4dp from the split button.
- Navigate to each button and interact with them
- Navigate to any element opened by the trailing button
- Understand the current selection state of the button
- Interaction & style
- Each button in the split button needs a minimum target area of 48x48dp. Extra small and small split buttons are
  shorter than 48dp, so the target areas around them need to be at least 48dp tall.
- Focus should land on the leading button then move to the trailing button. This can depend on the operating system's
  settings.
- Keys Actions Tab Navigate between buttons Space or enter Activate focused button
- The accessibility label for the leading button is the same as buttons.
- The trailing icon button should have an extra state or similar label indicating that the menu is expanded or
  collapsed. Label the button to clearly indicate that there are more options. The label of the secondary button should
  indicate that it provides additional choices related to the action of the main button. For instance, if the main
  button says "Watch later," the secondary button should be something like "More watch options." Label the opened menu
  according to the menu accessibility guidance .
