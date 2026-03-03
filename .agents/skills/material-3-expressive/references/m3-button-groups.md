# Button Groups (M3 Expressive)

Source URL:
https://m3.material.io/components/button-groups/overview
Generated: 2026-02-10

Extracted guidance

- Two types: standard and connected
- Applies shape morph when pressed and selected
- Connected button groups replace the segmented button
- Works with all button sizes: XS, S, M, L, and XL
- Support for single-select, multi-select, and selection-required
- Added standard button group
- Added connected button group Use instead of segmented button, which is deprecated
- Applies default shape to all buttons: round or square
- Type Original M3 M3 Expressive Standard button group -- Available Connected button group Available as segmented button
  Available
- Standard and connected button group tokens are organized by size. Select the type and size from the token set menu. Go
  to the button and icon button pages to view their tokens. Learn about design tokens
- Button groups are invisible containers that add padding between buttons and modify button shape. They don't contain
  any buttons by default.
- Common layouts Mix and match buttons and icon buttons for different scenarios.
- Selection & activation
- Standard button groups add interaction between adjacent buttons when a button is selected or activated. This
  interaction changes the width, shape, and padding of the selected or activated button, which adjusts the width of
  buttons directly next to it.
- Connected button groups don't add any interaction between buttons when selected or activated. They only affect the
  shape of the button being selected or activated.
- Standard button group
- When a button is pressed, standard button groups modify the width and shape of that button and adjacent buttons.
- When a toggle button is selected in a standard button group , its shape should change between square and round. The
  color should change according to the button specs .
- Connected button group
- Connected button groups have different shape changes than standard button groups. Selecting a button does not affect
  adjacent buttons.
- Standard groups apply padding between all buttons. The amount of padding changes based on button size to ensure a
  minimum accessible target size of 48dp. More details on padding: Button specs , icon button specs
- For all connected button groups, use 2dp padding. This provides visual consistency at scale.
- Extra small and small connected button groups have 48dp target areas and a minimum width of 48dp.
- High contrast mode
- High contrast mode is an accessibility feature that aims to maximize legibility by using a limited color palette.
  Follow the contrast mode set by the buttons. For selection, use white.
- The selected button changes shape and width
- A selected toggle button also changes color
- Adjacent buttons move and temporarily change width
- Mix and match the different types of buttons, widths, and colors to emphasize what's important, and to visually group
  related buttons. By default, all buttons in a standard group should be the same size (XS to XL) and shape (round or
  square). Only use multiple sizes in a group for hero moments Avoid mixing sizes frequently Only use a different shape
  in a group when a button is selected, or to add meaning or contrast
- Connected button groups help people select options, switch views, or sort elements in a page. They behave similarly to
  standard groups, except they don't affect adjacent buttons. Connected groups should replace the segmented button,
  which is deprecated.
- Use connected button groups when the button content is related, and buttons can be selected.
- Connected button groups should be used for single or multi-select patterns that use toggle buttons. Avoid using a
  connected group when none of the buttons can be toggled.
- Container The standard button group container has padding between buttons so they can animate width and shape without
  disrupting the product layout. The standard button group hugs the width of the buttons inside.
- The connected button group should span the width of the page or surface it's placed on, increasing the button widths
  inside. In larger windows, consider adding a maximum width to the connected group to avoid it growing too wide.
- Fixed : Manually define the button width (narrow to wide), size (XS to XL), or padding at each window size.
- Flexible : Automatically increase or decrease the width of buttons and the button group. Button groups grow until all
  flexible buttons are at their largest width.
- In compact windows, consider using smaller, narrower buttons so all buttons in the button group can fit. In large and
  extra large windows, consider using larger, wider buttons to better fill in the available space. Flexible buttons or
  button groups will automatically adjust width.
- When scaling to larger window sizes, make sure that the visual hierarchy of each button is preserved using qualities
  like color and size. For example, the primary action should remain the largest, widest, or most visually prominent
  button at all window sizes.
- Presentation Buttons at the trailing edge of the button group can be customized to collapse into an overflow menu at
  smaller window sizes, and become visible again at larger sizes. Place the overflow menu at the trailing end of the
  group. Buttons outside the group aren't affected by button group behavior.
- Pressed When a button is pressed, it changes width and shape. In a standard button group, pressing a button also
  affects the width of adjacent buttons. In a connected button group, only the shape of the pressed button changes.
- Selected A selected button should change shape from round to square, or square to round.
- Navigate to and interact with each button in the group
- Identify when buttons are selected
- Interaction & style Each button in a group should have a minimum 48x48dp target. Extra small and small button groups
  have larger inner padding to ensure accessible targets. Avoid reducing the padding in these sizes.
- Initial focus The button group container is not a focusable element. Initial focus should land on the first button in
  the group and then move to each button.
- Use Tab to navigate through each item in the group, and Space or Enter to select buttons.
- Keyboard navigation Keys Actions Tab Navigates to the next button Space or Enter Activates the focused button
- Labeling elements The button group container does not need to be labeled. Label each button according to the button
  and icon button accessibility guidance.
