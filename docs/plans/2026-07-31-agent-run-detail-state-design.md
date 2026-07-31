# Agent Run Detail State Preservation Design

## Problem

The Run detail pane uses `AnimatedContent` to move between a root Run and nested child Runs. Once an outgoing pane
finishes its exit animation, its composition is disposed. Returning to the parent can therefore reset the list position
and collapse timeline cards the user had opened while investigating telemetry.

## Options considered

1. Store list positions and expanded item keys in `AgentRunVM`. This would survive navigation, but mixes transient UI
   state into the Room-backed execution ViewModel and requires manual cleanup.
2. Keep every visited Run composable alive in a stack. This preserves state directly, but retains layouts, effects, and
   live duration clocks for pages that are no longer visible.
3. Use Compose `SaveableStateHolder` with the Run ID as the state key. This is the selected option because it retains
   only saveable UI values, disposes inactive compositions, and integrates with the existing native transition.

## Design

`AgentRunDetailPane` owns one `rememberSaveableStateHolder` for the lifetime of the open detail sheet. Each content Run
is rendered inside `SaveableStateProvider(runId)`. `rememberLazyListState` and timeline-card `rememberSaveable` values
are automatically captured when a Run leaves composition and restored if that Run returns through back navigation.

Loading, missing, and closed states remain outside the holder because they have no durable interaction state. Different
Run IDs cannot share saved values, and the entire holder is discarded when the detail sheet leaves composition. The
existing `AnimatedContent` phase/identity key and enter/exit motion remain unchanged.

This does not persist UI state to Room, does not keep invisible timing or scroll effects alive, and does not alter the
Run navigation path or execution lifecycle.

## Verification

A Compose instrumentation test expands a completed parent timeline card, navigates to a child Run, then returns to a
new parent detail instance with the same Run ID. The expanded summary must remain visible. Existing navigation,
timeline-follow, presentation tests, and Android test-source compilation remain regression gates.
