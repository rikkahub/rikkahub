# Agent Timeline Smart Follow Design

## Problem

Run details receive live Room updates, but the timeline is a stateless `LazyColumn`. New steps and tools can appear
below the viewport without feedback. Always forcing the list to the end would solve visibility, but interrupt a user
who deliberately scrolled upward to inspect an earlier failure or tool call.

## Options considered

1. Always animate to the newest item. This is simple, but makes historical inspection unstable during an active Run.
2. Never move the list and only animate inserted cards. This preserves reading position, but off-screen insertions
   remain invisible and the user cannot tell that execution advanced.
3. Follow while the user is at the end, then pause and count unseen items after an intentional upward scroll. This is
   the selected option because it matches native chat and log viewers without hiding new activity.

## Design

The detail content owns a `LazyListState` scoped to the selected Run. After its first real layout, it records whether
the latest items are visible. An intentional scroll away disables following; returning near the end reenables it and
clears the unseen count. Timeline updates compare stable item keys, so status changes or recomposition do not create
false notifications.

When following is enabled, newly inserted telemetry animates the list to the final spacer after layout. When following
is paused, the list stays fixed and a Material extended floating action button enters from the bottom with the number of
new activities. Tapping it clears the count, reenables following, and smoothly moves to the latest item. The existing
`animateItem` transition continues to animate each inserted card.

The button leaves enough bottom space so it cannot obscure the final timeline card. No execution state, database
contract, event ordering, or persistence schema changes are introduced.

## Verification

JVM tests cover stable-key insertion counting, paused-count accumulation, follow-mode behavior, and final list-index
calculation. Existing presentation/navigation tests plus Android test-source compilation remain regression gates. A
connected-device gesture test is not claimed when no Android device is available.
