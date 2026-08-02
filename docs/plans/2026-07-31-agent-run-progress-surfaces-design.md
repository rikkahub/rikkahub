# Agent Run Progress Surfaces Design

## Problem

The active Run card now uses determinate step progress when a valid budget exists, but the compact top-bar entry and
the Run detail header still show indeterminate rings for the same Run. Moving between these surfaces therefore changes
the meaning of the progress affordance even though the underlying telemetry is identical.

## Options considered

1. Keep rings indeterminate because they are compact. This avoids code changes but discards useful progress and leaves
   inconsistent accessibility semantics.
2. Add separate determinate logic to each ring. This fixes the visuals quickly but duplicates animation, fallback, and
   sizing behavior.
3. Introduce one reusable hybrid progress ring. This is selected because both surfaces receive the same normalized
   progress, Material motion, and fallback contract while retaining their existing sizes and colors.

## Design

Create a private `AgentRunProgressRing` composable that accepts nullable normalized progress, color, track color, size,
and stroke width. It animates valid progress with the expressive Material spatial spec. `AnimatedContent` crossfades
only when telemetry changes between determinate and indeterminate modes; ordinary step increments animate the ring arc
without replacing the component.

The top-bar `AgentRunEntry` derives progress from its `AgentRunPresentation` and renders the shared ring for pending and
working states. The detail header does the same while preserving its larger dimensions. Attention and terminal states
continue to remove the looping indicator and rely on the existing semantic icons. Invalid or absent budgets keep the
native indeterminate ring, matching the active-card fallback.

The progress indicators retain Material's built-in range semantics. No new labels, timers, persistence, runtime schema,
or status parsing are introduced.

## Verification

- Compose tests require a known `1/4` Run to expose determinate `0.25` semantics in both the top-bar entry and detail
  header.
- A missing budget must remain indeterminate in both surfaces.
- Existing terminal transitions must still remove progress semantics and display the terminal icon.
- Focused JVM regressions, app compilation, Android test compilation, diff checks, and line-length checks must pass.
