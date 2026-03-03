# Carousel (M3 Expressive)

Source URL:
https://m3.material.io/components/carousel/overview
Generated: 2026-02-10

Extracted guidance

- Contain visual items like images or video, along with optional label text
- Four layouts: Multi-browse, uncontained, hero, and full-screen
- Layouts can be start-aligned or center-aligned
- Item visuals have a parallax effect when scrolled
- Items change size as they move through the carousel
- Uncontained multi-aspect ratio
- Hero carousel layout
- Differences from M2
- This component is new in Material 3. Shape : Dynamic carousel items change shape when scrolled Motion : Carousel items
  move at a different speed than their content, creating a parallax effect Interaction : When scrolled, carousel items
  snap into place to maintain the same layout. Hero carousels swipe through one item at a time. Multi-browse carousels
  scroll through many items at once.
- A previewed or squished item strongly indicated that there was more content to swipe through
- Participants expected around 10 items in a carousel that scrolled multiple items at once
- While some contexts were considered better for some carousel designs, all designs were considered similarly usable
- Select a component type below to see its elements, attributes, tokens, and their values.
- Color Color values are implemented through design tokens. For design, this means working with color values that
  correspond with tokens. For implementation, a color value will be a token that references a value. Learn more about
  design tokens
- States States are visual representations used to communicate the status of a component or interactive element. Learn
  more about interaction states
- Carousel item dynamic widths
- All types of carousel items dynamically adapt to the width of the container. Large items have a customizable maximum
  width that's used to optimally fit carousel items into the available space. Small carousel items have a minimum width
  of 40dp and a maximum width of 56dp. Items change size as they move through the carousel layout.
- Multi-browse The multi-browse layout shows at least one large, medium, and small carousel item.
- Attribute Value Alignment Vertically centered Leading/trailing padding 16dp Top/bottom padding 8dp Padding between
  elements 8dp Large item width Dynamic, or user-set Medium item width Dynamic Small item width 40-56dp, dynamic Item
  corner radius 28dp
- Uncontained The uncontained layout shows items that scroll to the edge of the container.
- Attribute Value Alignment Vertically centered Leading padding 16dp Top/bottom padding 8dp Padding between elements 8dp
  Item corner radius 28dp
- Uncontained mutli-aspect ratio The uncontained multi-aspect ratio layout shows carousel items of various widths.
- Hero The hero layout shows at least one large item and one small item.
- Attribute Value Alignment Vertically centered Leading/Trailing padding 16dp Top/bottom padding 8dp Padding between
  elements 8dp Large item width Dynamic Small item width 40-56dp, dynamic Item corner radius 28dp
- Center-aligned hero The center-aligned hero layout shows at least one large item and two small items.
- Full-screen The full-screen layout shows one edge-to-edge large item.
- Attribute Value Alignment Centered Leading/Trailing padding 0dp Top/bottom padding 0dp Padding between elements 16dp
- Default : Standard scrolling. Recommended for uncontained layouts.
- Snap-scrolling : Scrolled items snap to the carousel's layout. Recommended for multi-browse, hero, and full-screen
  layouts
- Choose the best carousel layout for your product. Some layouts are more visual-focused, while others are more
  customizable.
- Layout Best used for Multi-browse Browsing many visual items at once (like photos), dynamic designs Uncontained
  Highly-customized or text-heavy carousels, stacked imaged and text, traditional carousel behavior Hero Spotlighting
  very large visual items (like a movie or featured app) Center-aligned hero Centered, large visual items Full-screen
  Vertically-scrolling video or image feeds, immersive experiences
- Carousel items must be fully visible on-screen (except for the uncontained layout). When scrolled, items automatically
  change size and snap into place to maintain the same layout.
- Accessibility requirements on scrolling pages On vertically-scrolling pages, carousels require an accessible way to
  view all the items without horizontally scrolling. (This requirement doesn't apply to full-screen carousels.) Material
  recommends adding a Show all button below the carousel, which opens a dedicated vertically-scrolling page of all
  carousel items. If the carousel has a header, you can use an arrow icon button instead. View the accessibility tab for
  more details and alternate solutions.
- Multi-browse The multi-browse layout is best for browsing many items at once, like photos or event feeds.
  Snap-scrolling is recommended to ensure items are recognizable and consistently sized. On larger screens, more large
  and medium items are visible in this layout. Avoid using this layout if the carousel items need lots of text or have
  complicated imagery.
- In compact windows, only show up to three carousel items if they have text. If you need to show more than three items,
  make sure the images and content are easy to understand and recognize.
- Uncontained The uncontained layout is most similar to a traditional carousel, where items are a single size and flow
  past the edge of the screen. Both default scrolling and snap-scrolling work well with this layout. Since items don't
  change size, this layout can be customized to show more text or other UI above or below each item without the text
  being masked or cropped.
- Uncontained multi-aspect ratio The uncontained multi-aspect ratio carousel has the same layout as the uncontained
  carousel but contains items of various sizes.Item widths can range anywhere between 9:16 for min width size to 16:9
  for max width. Only use this carousel variant if the items have various widths.
- Hero The hero layout is best for spotlighting content that needs more attention, like movie, shows, or other media
  thumbnails. It highlights one large image to focus on, while providing a sneak peek of what's next. On larger screens,
  more large items are visible. Use snap-scrolling so users can easily cycle through items one at a time.
- Center-aligned hero When the hero layout is center-aligned, it adds an additional previewed item on the leading edge,
  making the large carousel item centered.
- Full-screen The full-screen layout is best used for immersive experiences like video articles, featured headlines, or
  items that are visually rich. It can contain text and other UI elements on top of the image. This layout works best
  with content that is taller than it is wide, and scrolls vertically. It only works in portrait orientation in compact
  and medium windows. Don't use this layout in landscape orientation. Use snap-scrolling for full-screen layouts. Don't
  use default scrolling.
- Container The carousel container holds all carousel items. The number of carousel items visible in the container can
  change based on the carousel layout and the window size class.
- Carousel items Carousel items hold content. A carousel item has no fixed width; it changes width depending on the
  window size or its position in the carousel's layout. There are three dynamic widths an item can be: large , medium ,
  and small .
- Large items have a max width that can be adjusted. This changes how all other large, medium, and small carousel items
  fit on screen. Large items must remain big enough to be easy to understand and recognize. Medium items adjust width
  dynamically to the carousel size and available space. Small items have a width range of 40-56dp.
- When the large item's max width is narrow enough, more items can be shown on screen at once. In compact windows, this
  is only recommended for carousels with simple imagery.
- Item text (optional) Carousel items are primarily visual. Item text is optional and should be brief. If your carousel
  items require a lot of text, consider using the uncontained layout , which can show more information, or use a series
  of cards instead.
- Content within a carousel item can adapt dynamically based on the container and window size. Text should always be
  understandable at each size. Consider adapting the text to use brief labels on smaller carousel items.
- As the carousel container size increases, so do the number of carousel items visible at a time. In compact window
  sizes, carousels can comfortably show up to three carousel items at once. As the window size grows, more carousel
  items are added and scale in size. Full-screen carousels only ever show one item at once.
- Scrolling Carousel items have a parallax effect when they're scrolled. The default scrolling behavior doesn't snap
  items to a layout grid. They can stop anywhere in the container. Only use this for the uncontained layout. The
  snap-scrolling behavior aligns carousel items to the layout grid after they're scrolled. Items snap to the grid when
  released. Use this for multi-browse, hero, and full-screen layouts.
- In full-screen layouts, make sure the carousel items snap to the edges of the carousel container. Avoid letting them
  scroll freely or stop halfway. Full-screen layouts must use snap-scrolling.
- Navigate to the carousel container
- Navigate between different carousel items
- Activate a carousel item
- Skip over the carousel items
- Requirements on scrolling pages
- On vertically-scrolling pages, carousels require an accessible way to view all the items without horizontally
  scrolling. (This requirement doesn't apply to full-screen carousels.) Material recommends adding a Show all button
  below the carousel, which opens a dedicated vertically-scrolling page of all carousel items.
- If the carousel has a header, you can use an arrow icon button instead. Place the arrow icon directly next to the
  header or in the same row. Make sure the header is also displayed on the page of all carousel items.
- Avoid customizing the accessibility solution when possible. However, if your product needs an alternative solution,
  consider adding a Show all button in nearby navigation, or add alternative control buttons close to the carousel.
  Avoid adding UI elements, like arrows or other icons, within or beside the carousel.
- Interaction & style
- Touch Tapping on a carousel item changes the shape slightly, and creates a touch ripple for interaction feedback.
- Cursor The hover state provides a visual cue that the carousel item is interactive. When the carousel item is
  clicked (in both active and inactive states), a ripple appears for interaction feedback.
- Initial focus When navigating to a carousel using assistive technology, use Tab to place initial focus on the first
  carousel item. Then, use Tab or the arrow keys to navigate the carousel items. Use the up and down arrow keys to leave
  the carousel and focus on the next element on the page, like the Show all button.
- Keys Actions Tab or Arrows Moves to the previous or next carousel item Space or Enter Activates the focused carousel
  item
- The carousel container has the container role.
- Each carousel may have a different number of items, so the label reads out the total amount of items and the current
  item in focus.
- When reduced motion settings are turned on, the parallax effect should be removed and carousel items should no longer
  expand as they come into view. All items are the same size. Make sure carousels with reduced motion reach the edges of
  the window to avoid clipping visuals.
- For hero carousels with reduced motion, the small carousel item is only partially shown on screen.
