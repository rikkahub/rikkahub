# Agent Run Directional Detail Motion Design

## Problem

The Run detail pane currently uses the same short vertical transition for loading updates, opening a child Run, and
returning to its parent. That keeps state changes smooth, but it does not communicate the parent-child hierarchy and
makes a back action feel visually identical to drilling deeper.

## Options considered

1. Record the last clicked action inside the composable. This is small, but a no-op or delayed navigation can leave a
   stale direction that affects a later destination.
2. Infer direction only from `canNavigateBack`. This distinguishes a root from its first child, but cannot distinguish
   moving between deeper levels where both destinations can navigate back.
3. Project the navigation path depth into each detail state and compare source and target depths. This is selected
   because it derives motion from durable navigation state and remains correct for arbitrary nesting and ancestor jumps.

## Design

`AgentRunNavigation` exposes its path size as `navigationDepth`. Loading, missing, and content detail states carry that
depth alongside the existing Run identity and back capability. A pure transition selector compares the initial and
target states:

- The same Run identity, an opening/closing state, or two destinations at the same depth uses the existing subtle
  vertical phase transition.
- A destination at a greater depth enters from the right while the parent exits left.
- A destination at a shallower depth enters from the left while the child exits right.

The horizontal travel is deliberately partial rather than full-screen, preserving spatial meaning without making a
bottom-sheet detail change feel like a new top-level screen. `AnimatedContent` remains keyed by phase and Run identity,
the physical direction follows the current LTR/RTL layout, and the Run-keyed `SaveableStateHolder` continues restoring
parent scroll and expanded-card state.

This change does not add mutable navigation flags, alter the execution lifecycle, or animate telemetry-only updates.
Compose animation continues to respect the platform animator duration scale.

## Verification

JVM tests cover same-Run phase changes, forward child navigation, reverse parent navigation, and same-depth replacement.
Existing navigation/presentation/timeline regressions and Android test-source compilation remain required gates.
