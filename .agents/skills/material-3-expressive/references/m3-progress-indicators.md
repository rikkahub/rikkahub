# Progress Indicators (M3 Expressive)

Source URL:
https://m3.material.io/components/progress-indicators/overview
Generated: 2026-02-10

Extracted guidance

- Use the same configuration for all instances of a process (like loading)
- They capture attention through motion
- Option to apply a wave to the active track for use cases that would benefit from increased expressiveness
- Contrast : Higher contrast between track and active indicator to enhance the perception of progress
- Motion : New motion behavior
- Shape : Rounded corners
- Differences from M2
- Color: New color mappings and compatibility with dynamic color
- Type Original M3 M3 Expressive Linear progress indicator Available Available Circular progress indicator Available
  Available
- Category Configuration Original M3 M3 Expressive Behavior Determinate (default), Indeterminate Available Available
  Track thickness Fixed (4dp) Available Available Configurable -- Available Shape Flat (default) Available Available
  Wavy -- Available
- Wavy indicators use amplitude and wavelength to determine the shape of the wave. The height is the overall container
  height.
- The circular and linear progress indicator had separate token sets. These are now deprecated.
- Use progress indicators to show the status of ongoing processes, such as loading an app, submitting a form, or saving
  updates. When multiple items are loading, use a single progress indicator to show progress for the group. Don't add
  progress indicators to every activity.
- Choose a loading or progress indicator that corresponds to the expected wait time and type of process. If the wait is
  very long, consider allowing people to navigate away from the page while the process finishes up.
- Expected wait time Recommendation Instant (under 200ms) No indicator Short (between 200ms and 5s) Loading indicator
  Long (Over 5s) Progress indicator
- Determinate : Known progress and wait time
- Indeterminate : Unknown progress and wait time
- As more information about a process becomes available, a progress indicator should change from indeterminate to
  determinate .
- Active indicator The active indicator shows the progress that has been made so far. In indeterminate processes, it
  grows and shrinks along the track repeatedly.
- The active indicator appears as soon as progress begins. At low percentages where space is limited, this should appear
  as a dot to help people understand that there's progress underway.
- The active indicator has two shape options: flat and wavy . Use the shape that best fits the product's tone. The wavy
  shape can make longer processes feel less static and is best used when a more expressive style is appropriate. When
  using the wavy shape, the overall height of the component changes. At very small sizes, the wavy shape may not be as
  visible.
- Stop indicator The stop indicator is a 4dp circle that marks the end of a linear determinate progress indicator to
  meet Material's accessibility standards. It's not used for indeterminate or circular progress indicators. The stop
  indicator is required if the track has a contrast below 3:1 with its container or the surface behind the container.
- Place a linear progress indicator along the edge of a container that's loading. If the container changes shape, place
  it on the edge that animates. It can also be placed in the middle of a container. Use a single progress indicator at
  the top of a page to show progress of the whole group. Don't add one for every element unless they're activated
  independently.
- Circular progress indicators should be centered directly on the container or page that's loading, such as a button or
  card. When loading more items on a page, place the circular progress indicator in the empty space where the new
  content will appear, not overlapping existing content. However, if the content does not take long to load, consider
  using a loading indicator instead.
- Progress indicators in buttons A circular indicator can be placed in a button to show that the button's action is
  currently in progress. In very small buttons, use the flat shape since the wavy shape is not as visible at that size.
  To ensure a minimum 3:1 contrast ratio, change the active indicator color to be the same color as the button's icon or
  label text, and remove the track.
- Right-to-left languages Linear progress indicators should be mirrored horizontally for products using right-to-left (
  RTL) languages. Circular progress indicators don't need to be mirrored.
- Large screens Circular progress indicators have flexible sizes. They can range from 24dp to 240dp, depending on the
  placement and the window size. Avoid exceeding the minimum and maximum sizes. Reserve very large progress indicators
  for large and extra-large windows, such as desktop.
- Linear progress indicators dynamically adjust to fit the width of the window or element they're placed within, such as
  a card. They shouldn't be used in any elements smaller than 40dp. The padding on each end should be 4dp minimum, but
  can be modified.
- Navigate to the progress indicator
- Understand what progress the indicator is communicating
- Interaction & style
- The active indicator, which displays progress, provides visual contrast of at least 3:1 against most background
  colors.
- When integrated into another component, such as a button, make sure that the active indicator provides visual contrast
  of at least 3:1 against the other component. For the active indicator, use the same color as the label text or icon.
  The track should be removed.
- For linear progress indicators, the stop indicator is required if the track has a contrast below 3:1 with its
  container or the surface behind the container. Essentially, the end of the track must be easy to identify.
- Since the progress indicator is a visual cue, it needs an accessibility label to describe the type and amount of
  progress made. Use the progress bar accessibility role, and write an accessibility label that describes the purpose of
  the progress indicator. The label should include the process, such as "loading," and the affected content, such as a
  page, article, or episode. For example: "Loading news article" or "Refreshing page."
