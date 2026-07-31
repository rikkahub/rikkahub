# Agent Run Terminal Acknowledgement Design

## Problem

The active Agent Run card currently disappears as soon as the repository no longer reports an active root Run. Its exit
animation retains the last active presentation, so the user can see a running or stopping card collapse without ever
seeing whether the Run succeeded, failed, or stopped.

The latest-Run stream already contains the terminal entity. The missing piece is a UI-only handoff between the active
presentation and the matching terminal presentation.

## Options considered

1. Keep the current immediate exit. This is simple but hides the most important state transition.
2. Show a Snackbar or add another haptic. This communicates completion, but duplicates notifications and disconnects
   the result from the card the user was following.
3. Replace the visible card with the matching terminal snapshot, hold it briefly, then run the native collapse motion.
   This is selected because it preserves spatial continuity and keeps the result attached to the existing surface.

## Design

Introduce a small presentation state machine with four stages: active, awaiting a terminal snapshot, acknowledging a
terminal snapshot, and hidden. A terminal snapshot is eligible only when its Run ID matches a Run that was visibly
active in the current composition. This prevents historical terminal Runs from replaying when a conversation opens.

When the active stream clears before the latest stream publishes its terminal update, the host waits for a short grace
period. A matching terminal update cancels that pending exit, updates the card's status, icon, colors, duration, and
summary-derived step count, and keeps the card visible long enough for the Material transitions to settle. It then uses
the existing fade and vertical shrink exit. A new active Run cancels all pending exit work immediately.

The lifecycle state moves into an `AgentRunActiveCardHost` composable next to the card itself. `ChatPage` supplies
active and latest presentations plus callbacks. The card also filters its stop action by live visual state so the
terminal acknowledgement cannot briefly expose a stale stop button.

## Timing and accessibility

Use a 150 ms grace period for independently collected Room flows and a 650 ms terminal acknowledgement. The actual
status, color, icon, and collapse transitions continue to use Compose and Material motion, so platform animation scale
still governs the motion. No automatic haptic is added, avoiding duplicate feedback with background completion
notifications. Screen-reader descriptions update with the terminal visual state.

## Verification

- JVM tests cover matching terminal handoff, stale Run rejection, and historical terminal suppression.
- Compose tests cover removal of the stop action for terminal cards and the active-to-terminal-to-hidden lifecycle.
- Focused Agent Run regression tests and Android test-source compilation must pass.
- Static checks must report no whitespace errors or lines longer than 120 characters in touched files.
