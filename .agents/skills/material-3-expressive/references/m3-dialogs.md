# Dialogs (M3 Expressive)

Source URL:
https://m3.material.io/components/dialogs/overview
Generated: 2026-02-10

Extracted guidance

- Two types: basic and full-screen
- Should be dedicated to completing a single task
- Can also display information relevant to the task
- Commonly used to confirm high-risk actions like deleting progress
- Differences from M2
- Color: New color mappings and compatibility with dynamic color
- Layout: Greater padding to account for the increased corner-radius and title size
- Position: Option for custom basic dialog positioning
- Shape: Increased corner-radius
- Typography: Larger and darker headline
- Basic dialog color Color values are implemented through design tokens. For design, this means working with color
  values that correspond with tokens. For implementation, a color value will be a token that references a value. Learn
  more about design tokens
- Basic dialog measurements
- Attribute Value Container shape 28dp corner radius Container height Dynamic Container width Min 280dp; Max 560dp
  Divider height 1dp Icon size 24dp Minimum width 280dp Maximum width 560dp Alignment with icon Center-aligned Alignment
  without icon Start-aligned Top/Left/right/bottom padding 24dp Padding between buttons 8dp Padding between title and
  body 16dp Padding between icon and title 16dp Padding between body and actions 24dp
- Full-screen dialog color Color values are implemented through design tokens. For design, this means working with color
  values that correspond with tokens. For implementation, a color value will be a token that references a value.
- Full-screen dialog measurements
- Attribute Value Container shape 0dp corner radius Container height Dynamic Container width Container width; Max 560dp
  Header height 56dp Header width Container width Headline text alignment Start-aligned Divider height 1dp Icon (close
  affordance) size 24dp Bottom action bar height 56dp Bottom action bar width Container width Top/left/right padding
  24dp Padding between elements 8dp
- A dialog is a modal window that appears in front of app content to provide critical information or ask for a decision.
  Dialogs disable all app functionality when they appear, and remain on screen until confirmed, dismissed, or a required
  action has been taken. Dialogs are purposefully interruptive, so they should be used sparingly. A less disruptive
  alternative is to use a dropdown menu, which provides options without interrupting a user's experience.
- Similar components Snackbars are also designed to show important messages. Choose the right component based on the
  importance of the message. This component messaging strategy helps avoid overusing dialogs.
- Component Importance Action needed Snackbar Low importance Optional: Snackbars may not have a button, and can
  disappear automatically Dialog High importance Required: Dialogs block the main content until an action is confirmed
- Container and scrim Dialog containers appear above other screen elements and hold the dialog's headline, text,
  buttons, and list items. To focus attention on the dialog, surfaces behind the container are scrimmed with a temporary
  overlay to make them less prominent.
- Contain a brief, clear statement or question
- Headlines should always be succinct. They can wrap to a second line if necessary, and be truncated. In full-screen
  dialogs, long headlines or headlines of variable lengths (such as translations), can be placed in the content area
  instead of the app bar.
- Buttons Dialog actions are most often represented as buttons and allow users to confirm, dismiss, or acknowledge
  something. Buttons are aligned to the trailing edge of the dialog for easier interaction. The confirmation button is
  always closest to the edge. Button alignment responds automatically for right-to-left languages, where the
  confirmation button is aligned to the left edge.
- Dialogs should contain a maximum of two actions. If a single action is provided, it must be an acknowledgement action
  If two actions are provided, one must be a confirming action, and the other a dismissing action
- Providing a third action, such as Learn more , is not recommended as it navigates the user away from the dialog,
  leaving the dialog task unfinished. Rather than adding a third action, an inline expansion can display more
  information. If more extensive information is needed, provide it prior to entering the dialog.
- Basic dialog Basic dialogs interrupt users with urgent information, details, or actions. Common use cases for basic
  dialogs include alerts, quick selection, and confirmation.
- Basic dialogs most often appear as alerts or lists, but can have a variety of layouts and component combinations,
  including lists, date pickers, and time pickers.
- Full-screen dialogs fill the entire screen, containing actions that require a series of tasks to complete. One example
  is creating a calendar entry with the event title, date, location, and time. Because they take up the entire screen,
  full-screen dialogs are the only dialogs over which other dialogs can appear. Use a container transform pattern to
  transition a FAB into a full-screen dialog.
- When a full-screen dialog is closed without being saved, a basic dialog appears in front of it to confirm selections
  should be discarded without saving changes.
- Dialogs that include components which require keyboard input, such as form fields
- When changes aren't saved instantly
- When components within the dialog open additional dialogs
- To save a selection in a full-screen dialog, use Save . The close icon or dismissive action, such as Cancel or Back ,
  should close the dialog.
- The confirmation action should be clear about what happens next, like Send or Create . Avoid using vague terms like
  Done , OK , or Close . Only trigger an additional basic dialog if the action fails. Don't disable the confirmation
  button.
- When someone dismisses a full-screen dialog, a basic dialog should appear to confirm that they want to discard the
  unsaved changes.
- Errors about the dialog fields should always appear inline where they occur. Some components like text fields have
  built-in error messaging, while others like checkboxes and radio buttons need error messages to be added next to the
  fields. General errors such as network issues preventing saving or submitting should appear in a basic dialog when the
  confirming action fails. Error messages should clearly but briefly explain the source of the error and how to fix it.
  Show all errors on the page at once so people can fix everything before trying again.
- Dialog windows Launching a full-screen dialog temporarily resets the app's perceived elevation, allowing simple menus
  or dialogs to appear above the full-screen dialog. They cover the screen and don't appear as a floating modal window.
  Navigation Because full-screen dialogs can only be completed, dismissed, or closed, the close "X" icon button should
  be the only navigation option in the app bar.
- Dialogs can swap types as the window size class changes. For example, a full-screen dialog can change into a basic
  dialog at larger breakpoints.
- Medium window size Basic dialogs appear in a center position by default. Their position can be overridden to provide a
  more ergonomic experience.
- Expanded window size Dialogs on expanded window sizes, like desktop, are modal windows above a scrim. This puts the
  dialog at the forefront of a person's view, calling attention to the action prompted in the dialog.
- Basic dialogs can be custom-positioned anywhere on larger screens, respecting margins to prevent edge collision.
- Appearing Dialogs appear without warning, requiring users to stop their current task. They should be used sparingly,
  as not every choice or setting warrants interruption. Dialogs use an enter and exit transition pattern to appear on
  screen.
- Position Dialogs retain focus until dismissed or an action has been taken, such as choosing a setting. They shouldn't
  be obscured by other elements or appear partially on screen, with the exception of full-screen dialogs.
- Scrolling Most dialog content should avoid scrolling. Even when scrolling is required, the dialog title is pinned at
  the top, with buttons pinned at the bottom. This ensures selected content remains visible alongside the title and
  buttons, even upon scroll. Dialogs don't scroll with elements outside of the dialog, such as the background.
- Tapping a Cancel button, if one is shown
- Selecting an actionable list item
- Pressing the keyboard Esc key
- Tapping the scrim (Android, iOS)
- Tapping the Android system Back button
- Using another standard cancel or escape action, such as iOS VoiceOver escape gesture
- When a dialog box is dismissed, the following transitions occur: The dialog box fades out The background scrim fades
  out
- star Note: This is a rapidly changing space. Guidelines are primarily intended for designers at this time. Find what's
  implemented in code in the design kit .
- Extended reality (XR) introduces spatial capabilities, such as using depth to make dialogs stand out from the
  background. Currently, spatial dialogs are only available in full space. For home space, follow Material's general
  dialog guidance .
- Color & elevation
- XR uses color roles to communicate the elevation of UI elements. Dialogs can use two color options: surface container
  high or surface container highest .
- star Note: Color and elevation for spatial dialogs can be customized by makers and are not available in Jetpack
  Compose yet.
- For effective visual hierarchy, a dialog should be the most prominent element. Add a scrim behind a dialog to improve
  its visibility. Scrims prevent other content from being selected until the dialog action is complete.
- The dialog should have the highest elevation in the product. For example, if a dialog is surface container high ,
  don't use surface container highest for any other elements.
- Basic dialogs are recommended when designing for XR's expanded window sizes. This keeps the required action in the
  person's field of view . Limit use of full-screen dialogs to compact window sizes, like mobile devices.
- In full space, dialogs can be elevated spatially via overrides . This helps dialogs stand out from their background in
  XR.
- Effect The spatial dialog should scale uniformly. It also fades in when appearing, and fades out when disappearing.
  The dialog's scrim only fades in and out.
- Movement When activated, the spatial dialog rises from the app to the highest resting level on the Z-axis. When the
  action is complete, it returns to a normal resting level. The dialog's scrim stays at the app content level at all
  times. To prevent motion sickness, use standard easing and long duration motion tokens.
- Consider factors like field of view, viewing distance, and possible interactions when deciding where to place dialogs
  in XR.
- Center spatial dialogs in field of view Spatial dialogs should be centered in a person's field of view . If the dialog
  can't track head movements, position it in the center of the app's content. If the dialog can track head movements,
  configure it with a lazy follow behavior. This keeps the dialog anchored to the center of a person's field of view
  until an action is taken.
- XR accessibility guidelines are still evolving. Spatial dialogs should follow applicable Material dialog accessibility
  standards .
- Open and close a dialog
- Provide and submit other inputs if the dialog is interactive, such as a text field or selectable list
- Scroll the dialog to access all of its contents if that content extends beyond the container of the dialog
- Interaction & style
- Use sparingly Dialogs are purposefully interruptive. This means they appear in front of app content and disrupt the
  flow of content for people who may, for example, be using a screen reader to navigate the page. As such, dialogs
  should be used sparingly and only to provide critical information. Less critical information should be presented in a
  non-blocking way within the flow of app content.
- 200% text size Avoid excessive text wrapping or truncation by choosing concise strings. On Android, headlines should
  be kept concise enough to fit within four lines after the text size is increased to 200%. If a headline exceeds this
  limit and gets truncated, provide an alternative way to access the full content in a single tap.
- When a dialog appears, focus should automatically land on the first interactive element within the dialog.
- Keys Actions Tab Focus lands on the next interactive element contained in the dialog, or the first element if focus is
  currently on the last element Shift + Tab Focus lands on the previous interactive element contained in the dialog, or
  the last element if focus is currently on the first element Space or Enter Triggers or commits the action of the
  focused element Escape Closes the dialog
- The accessibility label for a dialog is typically the same as the dialog's title or headline. On web, basic dialogs
  should have the alert dialog role.
