# Agent Live Duration Stability Design

## Problem

Run cards and detail rows update live durations once per second. Those values currently participate in
`AnimatedContent`, so every clock tick crossfades the old and new text. A continuously running Agent therefore creates
permanent low-level flicker across the active card, detail header, child Runs, and live timeline items. The animation
does not communicate a state transition and competes with meaningful status and activity motion.

## Options considered

1. Keep crossfading every tick. This is visually smooth in isolation, but multiple simultaneous clocks make the entire
   surface pulse once per second.
2. Animate individual digits vertically. This can look polished in timers, but draws even more attention to telemetry
   and adds per-character layout and accessibility complexity.
3. Update durations directly and use tabular numerals. This is selected because clock changes remain legible without
   overlapping text, while equal-width digits reduce horizontal jitter when values change.

## Design

Status, activity, icon, navigation, progress, and item insertion transitions remain unchanged. Only transitions whose
target changes solely because the one-second clock advanced are removed:

- The active Run metadata line updates directly.
- The detail header identity line updates directly while the icon and activity retain their own transitions.
- Child Run and timeline duration labels update directly.

All affected text styles enable the OpenType `tnum` feature. This keeps numeric glyphs at a stable width where the font
supports tabular figures and gracefully falls back when it does not. Terminal durations remain frozen by the existing
presentation model, and the single shared detail clock remains the only timing source.

## Verification

A Compose instrumentation test pauses the test animation clock, advances a supplied duration timestamp, and requires
the old child/timeline duration nodes to disappear immediately. With the existing crossfade both old and new nodes are
present during the paused transition, so the test detects the flicker. Existing duration, presentation, navigation, and
timeline-follow regressions remain required. No device is currently connected, so instrumentation execution may remain
pending while its source compilation is enforced.
