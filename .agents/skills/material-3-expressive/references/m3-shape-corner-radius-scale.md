# Shape Corner Radius Scale (M3 Expressive)

Source URL:
https://m3.material.io/styles/shape/corner-radius-scale
Generated: 2026-02-10

Extracted guidance

- Use abstract shapes thoughtfully to add emphasis and decorative flair
- Leverage Material shapes for built-in shape morphing
- Rectangular shapes are fully rounded in all corners by default
- Individual corners can be adjusted to create asymmetrical rectangular shapes
- Type Resource Status Design Shape library (Figma Design Kit) Available Implementation Jetpack Compose (Shape Library)
  Available MDC-Android Available
- Large increased (20dp)
- Extra large increased (32dp)
- Extra extra large (48dp)
- Updated fully rounded corners to use full . Previously, this was defined using 50% of the component size.
- Use shapes and text in harmony Shapes are expressive elements of Material 3 that echo key visual attributes of M3
  typography . Use shape and type together for products to feel cohesive and polished.
- Interaction states, like when a button is selected
- Actions in progress, like a friend typing, or a page loading
- Changes in the environment, like sound, temperature, or time of day
- Be bold and dare to embrace tension Tension happens when the shape story changes unexpectedly, such as when
  contrasting shapes are used. This can be created using both square and rounded shapes, unconventional shapes, and
  other contrasting elements. Material historically focused on rounded shapes. However, using sharp shapes, thereby
  adding tension, creates more dynamic design, one that's more memorable and expressive. This tension can be used in
  many ways, like conveying states, drawing attention to an element, or to improve the visual aesthetic.
- Shape is versatile, not semantic Avoid making shapes literal or assigning a specific function or meaning to a single
  shape. For example, the loading indicator can be wavy, but the waveform is not a strict symbol of progression.
  Progress could just as easily be shown using rotating shapes or shape morph. Plus, waveforms could be used in other
  places unrelated to progress, like button containers.
- Use abstract shapes sparingly Be intentional when using shapes in product UI. Don't compromise clarity for the sake of
  visual design. When incorporating diverse shapes, think about how they fit into the overall design and consider how
  they balance with the entire composition. Ensure that shapes resonate with the product's narrative. Consider the 'why'
  behind their inclusion and the value they contribute to the overall user experience.
- Emphasize aesthetic moments with shape Get creative when using shape in graphics, for photography cropping,
  personalized avatar masking, and other non-interactive elements. Decorative moments offer the most flexible and
  creative uses of shape.
- Shape can be 2.5D When effectively used, shape and motion can make 2D visuals feel 3D. They provide the illusion of
  depth and volume, making visuals more eye-catching and natural.
- Material components use a corner radius scale to define all rectangular shapes, such as buttons, carousels, and
  dialogs.
- Shape tokens Material has shape corner tokens to define all corners, and corner-value tokens for individual corners.
  Learn more about design tokens
- Corner radius scale
- None - 0dp
- Extra small - 4dp
- Small - 8dp
- Medium - 12dp
- Large - 16dp
- Large increased - 20dp
- Extra large - 28dp
- Extra large increased - 32dp
- Extra extra large - 48dp
- Full - fully rounded corners
- Components can have either symmetric or asymmetric corner shapes. Symmetric shapes have the same values for all
  corners, while asymmetric shapes can have corners with different values. Both symmetric and asymmetric shapes use the
  same 10-step scale. Asymmetrical shapes are used in M3 components with closely-grouped items, such as menus and split
  buttons. These are called inner corners .
- Generally, products should consistently use the Material 3 shape styles. However, customization is sometimes
  necessary, and even encouraged, for hero moments or custom components. Shapes can be customized at the style or
  component level.
- Style changes The corner radius shape style, like medium , can be customized to be a different size. This applies the
  change to all components mapped to that shape style, unless they have an override.
- Component changes The style of a specific component, such as a button, can be changed by customizing which corner
  radius shape style it maps to. For example, by default, buttons are mapped to the full corner radius shape style. If
  your product needs a less rounded shape, remap the token to another style in the shape scale, such as small or
  medium .
- The shape style family can be customized from rounded to cut . This makes the corner a straight line instead of
  curved. Add extra padding to avoid cutting off content in information-dense components. For example, a large cut
  corner on a card will clip content and images in the area more than a rounded corner of the same size.
- Outer radius - padding = inner radius
- For example: 48dp - 14dp = 34dp
- Using the shape library The Material 3 shape library can be used to create more interesting containers. Use the shape
  library for mostly visual elements. Avoid applying unconventional shapes to text-heavy containers. Shapes should be
  used sparingly to provide a stronger emphasis and moments of delight.
- The Material shape library supports easy transitioning, or morphing, between shapes. Shape morph is leveraged in the
  standard button group and loading indicator components.
- Using shape morph
- Access to the Material shape library and the shape morph functionality are available through a platform-specific API.
  For Android, use the Shapes in Compose API Web is not currently available Shape morphing uses the expressive motion
  scheme by default. This can be switched to the standard motion scheme as needed.
- Material uses shape morphing in the standard button group and loading indicator components.
