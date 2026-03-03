# Compose Mapping (M3 Expressive)

Scope
Map common M3 Expressive token families to Jetpack Compose Material3 APIs. Use this as a translation guide; token values
still come from the spec token files.

Token to Compose mapping
| Token family | Compose API | Notes |
|-------------|-------------|-------|
| md.sys.color.* | `MaterialTheme.colorScheme` | Use semantic roles; avoid hardcoded colors. |
| md.comp.*.container.color | `ButtonDefaults`, `CardDefaults`, etc. | Prefer component defaults
with `MaterialTheme.colorScheme`. |
| md.sys.typescale.* | `MaterialTheme.typography` | Map to `title`, `body`, `label`, `headline` styles. |
| md.sys.shape.* | `MaterialTheme.shapes` or `RoundedCornerShape` | Use shape tokens for corner sizes and shape
families. |
| md.sys.elevation.* | `surfaceColorAtElevation`, `tonalElevation` | Use elevation for surfaces and containers. |
| md.sys.state.* | `alpha` for state layers | Apply state layer opacity to overlay colors. |
| md.sys.motion.* | `AnimationSpec`, `tween`, `spring` | Translate duration and easing into Compose specs. |

Motion tokens

- There is no direct one-to-one Compose API for motion tokens.
- Translate durations, easing, and curves into `AnimationSpec` values.
- Honor system animation scale: use instant transitions when animation scale is 0.

Window size classes

- Use `WindowSizeClass` to adapt layouts for Compact, Medium, and Expanded.
- Validate hero moments and expressive motion in all size classes.

Accessibility checks

- Contrast: 4.5:1 for text, 3:1 for non-text.
- Touch targets: 48dp minimum.
- Labels and content descriptions for interactive elements.
- Provide reduced-motion alternatives when animation scale is 0.
