# Agent Run Detail State-Owned Motion Design

## Problem

`AgentRunDetailHeader` animates `presentation.visualState`, but its content lambda reads `showStateProgress` from the
latest outer presentation. During `WORKING → SUCCEEDED`, Compose retains both states for the crossfade, yet the outgoing
working frame immediately sees `false`: its ring disappears and its icon jumps from 16 dp to 22 dp before fading. The
top-bar entry, child cards, and timeline cards already derive this geometry from their animated `state` correctly.

## Options considered

1. Accept the immediate ring removal. This is functionally valid but creates a visible discontinuity at the most
   important lifecycle boundary.
2. Animate ring visibility independently outside `AnimatedContent`. This can overlap a terminal icon but introduces a
   second transition with separate timing and state ownership.
3. Derive ring visibility and icon size from each animated state. This is selected because the outgoing frame retains
   its complete geometry until the native crossfade finishes, using the same pattern as the other Run surfaces.

## Design

Replace the outer `showStateProgress` value with an interaction-level `allowStateProgress = !isStopping`. Inside the
`AnimatedContent` lambda, compute whether that specific state is pending or working, then combine it with the
interaction gate. The result controls both `AgentRunProgressRing` presence and icon size.

A normal telemetry transition therefore fades a complete 32 dp live-state composition—including its progress ring and
16 dp icon—against the complete 22 dp terminal icon. Pressing stop remains immediate: `isStopping` disables the state
ring for all retained frames while the dedicated stop control changes to its own progress feedback.

No duration, progress normalization, lifecycle, persistence, accessibility, or runtime behavior changes. The patch only
corrects animation-state ownership.

## Verification

- A controlled Compose clock starts with known 0.25 progress, changes the presentation to terminal, and requires that
  determinate progress to remain during the first outgoing frame.
- After the Material animation settles, no progress semantics remain and the terminal identity stays visible.
- Agent activity, progress, presentation, navigation, and timeline regressions plus source compilation and static checks
  must pass.
