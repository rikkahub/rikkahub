# Buttons (M3 Expressive)

Source URL:
https://m3.material.io/components/buttons/overview
Generated: 2026-02-10

Extracted guidance

- Two variants: default and toggle
- Can contain an optional leading icon
- Five color options: elevated, filled, tonal, outlined, and text
- Five size recommendations: extra small, small, medium, large, and extra large
- Two shape options: round and square
- Keep labels concise and use sentence case
- Default and toggle (selection)
- Color styles are now configurations. (elevated, filled, tonal, outlined, text)
- Round and square
- Shape morphs when pressed
- Shape morphs when selected
- Small (existing, default)
- 16dp (recommended to match padding of new sizes)
- 24dp (not recommended)
- Differences from M2
- Color: New color mappings and compatibility with dynamic color. Icons and labels now share the same color. Neutral
  text button is no longer recommended.
- Icons: Standard size for leading and trailing icons is now 20dp
- Shape: Fully-rounded corner radius and additional height options
- Variants M3 M3 Expressive Default Available Available Toggle (selection) -- Available
- Category Configuration M3 M3 Expressive Size Small (default) Available Available XS, M, L, XL -- Available Shape
  Round (default) Available Available Square -- Available Color Elevated, filled (default), tonal, outlined, standard
  Available Available Small button padding 24dp Available Not recommended. Use 16dp 16dp -- Available
- There are five built-in button color styles: elevated, filled, tonal, outlined, and text
- The default and toggle buttons use different colors
- Toggle buttons don't use the text style
- star Note: These color roles were chosen to create design coherence and familiarity. Other color roles can be used as
  long as the container and text have a 3:1 contrast ratio. For example, tertiary and on tertiary.
- Default Toggle unselected Toggle selected Elevated container Elevated icon & label Surface container low Primary
  Surface container low Primary Primary On primary Filled container Filled icon & label Primary On primary Surface
  container On surface variant Primary On primary Tonal container Tonal icon & label Secondary container On secondary
  container Secondary container On secondary container Secondary On secondary Outline container Outlined icon & label
  Outline variant (outline) On surface variant Outline variant (outline) On surface variant Inverse surface Inverse on
  surface Text icon & label Primary -- --
- High contrast mode is an accessibility feature that aims to maximize legibility by using a limited color palette, like
  black and white. Focus rings are shown when buttons are focused.
- States are visual representations used to communicate the status of a component or interactive element.
- Elevated button states The elevated button style has an elevation of 1 by default and has no elevation when disabled.
- Filled button states
- Tonal button states
- Outlined button states
- The outlined button's container fill is invisible at rest, but the opacity and state layers behave the same as other
  button styles when disabled, hovered, focused, or pressed.
- Text button style states
- The text button's container is invisible at rest, but the opacity and state layers behave the same as other button
  styles when disabled, hovered, focused, or pressed. There is no toggle text button.
- Pressed state When pressed, buttons can morph to become more square. Both round and square buttons should have the
  same pressed shape. The corner radius value differs for each button size. See full button corner measurements
- When selected In addition to changing shape when pressed, toggle buttons also change the resting shape from round (
  unselected) to square (selected). If the resting unselected shape is square, the selected shape should be round.
- Extra small and small icon buttons must have a target size of 48x48dp or larger to be accessible.
- XS S M L XL A. Round button Full Full Full Full Full B. Square button 12dp 12dp 16dp 28dp 28dp C. Pressed state 8dp
  8dp 12dp 16dp 16dp
- Use the table's menu to switch token sets. The baseline button token sets are organized by color.
- Buttons are just one option for representing actions in a product and shouldn't be overused. Too many buttons on a
  screen can disrupt the visual hierarchy. Consider placing additional actions in a navigation rail, set of chips, text
  links, or icon buttons.
- Toggle buttons should be used for binary selections, such as Save or Favorite . When toggle buttons are pressed, they
  can change color, shape, and labels. Toggle buttons should use an outlined icon when unselected, and a filled version
  of the icon when selected. If a filled version doesn't exist, increase the weight instead. By default, toggle buttons
  change from round to square when selected.
- If the label changes on selected or unselected states, be mindful of the character count. Changing the label
  significantly is disruptive to the user and the page layout.
- Label text Label text is the most important element of a button. It describes the action that will occur if someone
  taps a button. It should be very brief, ideally 1-3 words. Use sentence case, which only capitalizes the first word
  and proper nouns. This allows the text to distinguish proper nouns, for example: Book with Flights , not BOOK WITH
  FLIGHTS . Don't truncate or wrap label text. It should always be fully visible on a single line.
- Buttons with the outlined and text color style depend on the colors to be recognizable from other text and elements.
  Use caution when putting these buttons next to visually similar elements, such as chips or large text.
- Container Button containers hold the label text and optional icon. Buttons with the text color style have a visible
  container only when hovered, focused, or pressed. Buttons with a round shape have containers with fully rounded
  corners.
- Square buttons have containers with more subtle rounding that changes based on button size.
- Icon (optional) Icons visually communicate the button's action and help draw attention. They should be placed on the
  leading side of the button, before the label text.
- The elevated button style is the same as the tonal button, but with a shadow. To avoid overusing shadows, use the
  elevated style only when absolutely necessary, such as when the button requires visual separation from a visually
  prominent background.
- Buttons at higher elevations typically have more emphasis in a design, and should be used sparingly. For high
  emphasis, consider the filled style instead.
- The filled button style has the most visual impact after the FAB, and should be used for important, final actions that
  complete a flow, like Save , Join now , or Confirm .
- Since they have such strong emphasis, the filled style should be used sparingly, ideally for only one action on a
  page. In some cases, filled buttons can use tertiary colors.
- The tonal button style is useful in contexts where a lower-priority button requires slightly more emphasis than an
  outline would give, such as Next in an onboarding flow. Tonal buttons use the secondary color mapping.
- The outlined style is ideal for medium-emphasis buttons which contain actions that are important, but aren't the
  primary action in a product. Outlined buttons pair well with filled buttons to indicate alternative, secondary
  actions.
- Outlined buttons display a stroke around the button container, and have no fill by default. They should be placed on
  simple backgrounds, not visually prominent backgrounds such as images or videos.
- Text buttons are often placed within components such as cards, dialogs, and snackbars. Since text buttons don't have a
  visible container in their default state, they don't distract from nearby content. However, since there's no
  container, the label text color must always be recognizable from non-button text and elements.
- In cards, text buttons help maintain an emphasis on card content.
- Dialogs use text buttons because the absence of a container helps unify the action with the dialog text. Align text
  buttons to the trailing edge of dialogs, on the right for left-to-right languages and on the left for right-to-left
  languages.
- When scaling layouts for large screen devices, buttons can adapt their visual presentation, size, alignment, and
  arrangement to fit different contexts and user needs.
- Choose the best button position based on screen size.
- The icon and label text in a button stay centered and grouped as the button's width changes.
- Buttons can be customized to change size and scaling behavior across different window sizes. To avoid creating very
  long buttons in large windows, constrain button width or place buttons beside other elements.
- The size and placement of buttons can change as parent containers, such as cards, adapt for larger screens. Keep
  items, including buttons, in the same order between large and small screens to provide a consistent experience for
  screen readers and keyboard navigation.
- Use a button to perform an action
- Navigate to and activate a button
- Interaction & style
- Color contrast Enabled buttons need a 3:1 contrast ratio with the background to meet accessibility best practices.
  This is measured from the container for elevated, filled, and tonal button styles, and the label text for outlined and
  text button styles.
- 200% text size Avoid excessive text wrapping or truncation by choosing concise strings. On Android, button labels
  should be kept concise enough to fit within two lines after the text size is increased to 200%. If a button label
  exceeds this limit and gets truncated, provide an alternative way to access the full content in a single tap.
- Keys Actions Tab Navigate to a button Space or Enter Activate a button
- The accessibility label for a button should match the visible label text on the button such as Done , Send , or
  Reply . It can contain extra contextual information if necessary.
