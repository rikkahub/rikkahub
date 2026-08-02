# Agent Active Stop Exit Motion Design

## Problem

The active Agent card renders its stop control with a nullable branch. When a live run becomes terminal, the button is
removed in one composition, so the text column jumps wider while the status and card are still animating. The detail
header already uses a native horizontal visibility transition for the equivalent control.

## Options

1. Keep the nullable branch. This preserves current behavior but leaves the terminal transition visually discontinuous.
2. Animate only the row size. This softens the width change, but the button still disappears without an exit frame.
3. Give the stop control its own `AnimatedVisibility`, using horizontal shrink and fade while revoking interaction as
   soon as the stop action is no longer valid.

## Decision

Use option 3. Match the detail header with an end-aligned expand/fade entrance and shrink/fade exit. The outgoing frame
continues to occupy progressively less horizontal space, allowing the text column to settle smoothly.

The button remains visible while a stop request is in progress, so its spinner can finish the local feedback sequence.
Its enabled state depends on both a current stop action and `!isStopping`. When the run becomes terminal, the action is
null immediately; the outgoing visual frame remains for motion continuity but cannot invoke haptics or a stale callback.

## Verification

Add a paused-clock Compose test that renders a live card with a stop action, then switches the run to succeeded and
removes the callback. On the first transition frame, terminal status and the outgoing stop icon must coexist, but the
icon must be disabled. After the Material transition settles, the stop icon must no longer exist.
