# Motion Physics Specs (M3 Expressive)

Source URL:
https://m3.material.io/styles/motion/overview/specs
Generated: 2026-02-10

Extracted guidance

- The basics: Motion schemes The physics system has two preset motion schemes: expressive and standard . The motion
  scheme you choose defines how your product feels. While most motion in a product should use the same scheme, products
  can make advanced customizations to swap the scheme to emphasize key moments.
- Expressive is Material's opinionated motion scheme, and should be used for most situations, particularly hero moments
  and key interactions.
- Standard feels more functional with minimal bounce, and should be used for utilitarian products.
- Need something other than the preset schemes? Create your own! The physics system makes it easy to create custom
  motion schemes beyond expressive and standard, while still leveraging theming. Schemes can be easily switched between
  expressive, standard, or custom as needed.
- How it works: Springs Motion schemes use springs . A spring is a combination of three attributes which control all
  motion behavior: stiffness, damping, and initial velocity.
- Springs are versatile . One spring can apply to many situations, such as transitions, button effects, or gestures.
  This makes the motion and expression feel consistent throughout the product. Springs feel natural . Springs are
  designed to be predictable, like how objects move and bounce. They handle gestures, interruptions, and retargeting
  animations seamlessly.
- Spring tokens On Jetpack Compose and MDC-Android, these springs are available as spring tokens . Use tokens to easily
  apply motion to any element, making all motion feel predictable and consistent across multiple platforms. See specs
  for how to convert springs to other platforms like Web.
- There are tokens for spatial movement and effects , with three durations each: default , fast , and slow . For
  example, to apply fast, spatial, expressive motion, call the "expressive" motion scheme, then use the token:
  md.sys.motion.spring.fast.spatial . Notice that the "expressive" scheme isn't part of the token itself. Rather, it's
  called at the product level and applied to all tokens. This makes it easier to swap schemes without changing assigned
  tokens.
- Style Spatial spring tokens are used for animations that move something on screen, for example the x and y position,
  rotation, size, rounded corners. This spring overshoots the final value and bounces into place.
- Effects spring tokens are used to animate properties such as color and opacity animations, where there shouldn't be
  any overshoot.
- Speed Spatial and effect spring tokens come in three speeds: default , fast , and slow . Most motion should use the
  default speed, while smaller elements may use fast and larger elements may use slow.
- Speed Spatial example Effects example Default Animations that partially cover the screen, such as bottom sheet and
  expanded navigation rail Opacity of the content within a navigation rail Fast Small components, such as switches and
  buttons Color change of the switch handle Slow Full-screen animations Full-screen content refresh
- Spring tokens work across devices. For example, the spatial fast token will always be faster than default or slow, but
  the exact values of each token differ depending on if the device is a wearable, phone, or tablet. This ensures the
  movement feels fast in the context of the device.
- Components On Jetpack Compose, 21 Material components use the motion physics system by default. MDC-Android support is
  coming soon. To add the motion physics system to other components, including custom-built components, use spring
  tokens. View full specs
- Advanced customizations There are a few different levels for applying motion. Choose the level that applies best to
  your product or specific component.
- Level 1: Use a default motion scheme The expressive and standard schemes should be sufficient for all motion needs. On
  Jetpack Compose, components use these schemes by default.
- Level 2: Create a custom motion scheme On Jetpack Compose, to change the default motion scheme that all components and
  transitions use, create a custom MotionScheme object, and return different AnimationSpec for each property of the
  motion scheme.
- Level 3: Swap the default motion scheme per element Why use just one scheme when you can use multiple? On Jetpack
  Compose, to use one scheme for most of the product, such as expressive , but on certain elements swap it for another
  scheme, like standard , override the CompositionLocal for that particular composable, screen, or element.
- Cross-platform experiences The motion physics system is available on Jetpack Compose and MDC-Android, and can be
  easily adapted to other platforms.
- Platform Status How to apply Jetpack Compose Available Use built-in components and spring tokens. MDC-Android
  Available. Not yet added to components. Use built-in spring tokens. Web Compatible Use springs when possible,
  otherwise use curves that mimic the springs for animations without interruptions or gestures. View web conversion
  table
- Tokens and specs The spring composite tokens are used in the motion physics system. These composites combine two
  spring tokens (damping and stiffness) into a single token for ease of use. The easing , duration , and path tokens are
  used by the legacy system, so can be ignored.
- Web: Convert springs to curves
- Spring Curve Expressive fast spatial 0.42, 1.67, 0.21, 0.90. Duration = 350ms Expressive default spatial 0.38, 1.21,
  0.22, 1.00. Duration = 500ms Expressive slow spatial 0.39, 1.29, 0.35, 0.98. Duration = 650ms Expressive fast effects
  0.31, 0.94, 0.34, 1.00. Duration = 150ms Expressive default effects 0.34, 0.80, 0.34, 1.00. Duration = 200ms
  Expressive slow effects 0.34, 0.88, 0.34, 1.00. Duration = 300ms Standard fast spatial 0.27, 1.06, 0.18, 1.00.
  Duration = 350ms Standard default spatial 0.27, 1.06, 0.18, 1.00. Duration = 500ms Standard slow spatial 0.27, 1.06,
  0.18, 1.00. Duration = 750ms Standard fast effects 0.31, 0.94, 0.34, 1.00. Duration = 150ms Standard default effects
  0.34, 0.80, 0.34, 1.00. Duration = 200ms Standard slow effects 0.34, 0.88, 0.34, 1.00. Duration = 300ms
- Easing and duration The original easing and duration tokens are still available to use as a fallback, and are
  currently used for animating transitions. View easing and duration system
