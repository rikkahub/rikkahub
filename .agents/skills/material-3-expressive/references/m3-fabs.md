# Floating Action Button (M3 Expressive)

Source URL:
https://m3.material.io/components/floating-action-button/overview
Generated: 2026-02-10

Extracted guidance

- Use a FAB for the most common or important action on a screen
- Make sure the icon in a FAB is clear and understandable
- FABs persist on the screen when content is scrolling
- Three variants: FAB, medium FAB, large FAB
- Added medium FAB size
- Small FAB size is no longer recommended
- FAB and large FAB sizes are unchanged
- FAB variants are based on size, not color
- Added tone color styles: Primary
- Renamed existing tonal color styles to match their token names: Primary to Primary container
- Secondary to Secondary container
- Tertiary to Tertiary container
- The values haven't changed
- Surface color FABs are no longer recommended
- Differences from M2
- Baseline variants The small FAB is still available, but no longer recommended. Jump to baseline specs
- Variant M3 M3 Expressive FAB Available Available Medium FAB -- Available Large FAB Available Available Small FAB
  Available Not recommended. Use a larger size.
- In the expressive update, the primary , secondary , and tertiary set colors were renamed to primary container ,
  secondary container , and tertiary container to match the actual color roles used. New primary, secondary, and
  tertiary color styles were created to match the corresponding color roles. View details in the color styles section
- Category Configuration M3 M3 Expressive Color Primary container, secondary container, tertiary container Available as
  primary, secondary, tertiary Available Primary. secondary, tertiary -- Available
- Color Color values are implemented through design tokens. For design, this means working with color values that
  correspond with tokens. In implementation, a color value will be a token that references a value. Learn more about
  design tokens
- Color styles FABs can use several combinations of color and on-color styles, such as primary and on-primary . The
  following color mappings provide the same legibility and functionality, so the color mapping you use depends on style
  alone.
- Baseline color styles
- Surface FAB color styles are still available, but no longer recommended.
- States States are visual representations used to communicate the status of a component or interactive element. When
  using a non-default color mapping for FABs, make sure the state layer color is the same as the icon color. For
  example, the state layer color for the primary color style should be md.sys.color.primary .
- Use the table's menu to select a token set. This only includes baseline tokens, including small and surface FABs. It
  doesn't include large or regular FABs, since those are still currently used.
- Use a FAB for the most important action on a screen; it appears in front of all other content. The FAB can be aligned
  left, center, or right. It can be positioned above the navigation bar, or nested within it.
- Medium FAB (most recommended)
- The FAB is the smallest size, and is best used in compact windows where other actions may be present on screen. The
  medium FAB is recommended for most situations, and works best in compact and medium windows. Use it for important
  actions without taking up too much space. A large FAB is useful in any window size when the layout calls for a clear
  and prominent primary action, but is best suited for expanded and larger window sizes, where its size helps draw
  attention.
- Start a process
- Archive or trash
- Alerts or errors
- Limited tasks like cutting text
- Controls better suited to a toolbar, like to adjust volume or font color
- Container The FAB is typically displayed in a square container. The container shouldn't be covered by other elements,
  such as badges. The container must have sufficient color contrast with the surface it's placed on.
- Icon An icon in a FAB should be clear and understandable. When hovering over a FAB on web products, FABs should
  display a tooltip with an accompanying icon text label. Use a filled icon instead of an outlined icon. A FAB shouldn't
  contain notifications or actions found elsewhere on a screen.
- In compact and medium window sizes, the best place for the FAB is typically the lower right corner of a screen, since
  it's easy to reach and is less likely to cover important content. In expanded window sizes, consider placing the FAB
  in the upper left corner, like in the navigation rail. This positions it as one of the first interactive elements
  people see when they land on the page. Adjust the size of the FAB based on the context. Use a medium FAB for mobile
  layouts, and large FAB for tablets and large screens.
- Appearing When a FAB animates on screen, it expands outward from a central point. The icon within it can be animated
  as well. While FABs should be relevant to screen content, they aren't attached to the surface on which content
  appears. FABs move separately from other UI elements because of their relative importance. Screen transitions FABs can
  morph to launch related actions. When a screen changes its layout, the FAB should disappear and reappear during the
  transition. Reappearance The FAB should only reappear if it's relevant to the new screen. It should reappear in the
  same position, if possible.
- Scrolling FABs remain in place on scroll. Extended FABs can collapse into a FAB on scroll and expand on reaching the
  bottom of the view.
- Moving across tabs When tabs are present, the FAB should briefly disappear, then reappear when the new content moves
  into place. This shows that the FAB is not connected to any particular tab.
- Don't animate the FAB with body content.
- Navigate to and activate the FAB
- Perform an action with the FAB
- Expand and minimize an extended FAB
- Interaction & style
- Don't disable the FAB. If the action represented in the FAB is unavailable, the FAB shouldn't appear. Ensure the icon
  has a minimum 3:1 contrast ratio with the container.
- Ensure the FAB is prioritized in the overall focus order to create an efficient experience for people who navigate UIs
  with assistive tech. On mobile, the focus order may start with the app bar, move to the navigation bar, and then skip
  past any other content on the page to land on the FAB. Consider displaying a tooltip when the FAB is focused. This is
  supported on web.
- Layout & position
- To make it easier for users of screen readers to reach a primary action such as a FAB on expanded window sizes,
  consider placing the FAB in the upper left region. However, it's critical to test placement options with users to see
  if the upper left region is the best position in all browser windows. For compact and medium window sizes, the best
  place for the FAB is the lower right corner of a screen.
- To ensure accessibility for keyboard users on the web, avoid positioning the FAB in a way that completely obscures the
  focus indicator of an actionable element. It's okay to partially cover the desired element, as long as the focus
  indicators are still visible.
- Keys Actions Tab Focus lands on the FAB Space or Enter Perform the default action on an item
- The accessibility label should describe the action that the button is performing, such as Compose a new message .
