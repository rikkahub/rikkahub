# Agent Detail Identity Content Key Design

## Problem

The Agent detail header renders run ID, status, and duration inside one `AnimatedContent`, but only status belongs to
its target state. When a run becomes terminal, the outgoing status frame immediately reads the new outer duration.
That creates a mixed frame such as an old running status paired with the final terminal duration.

Adding duration directly to the animation key would fix ownership, but the live clock refreshes four times per second.
Animating every tick would leave stale duration nodes in the semantics tree and create constant visual motion.

## Options

1. Split the identity line into separate text nodes. This avoids replaying the status transition on clock ticks, but
   loses atomic frame ownership and changes text and accessibility structure.
2. Animate the complete identity on every value change. This preserves snapshots but animates every clock tick.
3. Use the complete identity as `targetState` and use run ID plus status as `contentKey`.

## Decision

Use option 3. Create an immutable frame containing the short run ID, status, and rendered duration. `AnimatedContent`
receives the whole frame, while `contentKey` returns the run ID/status phase key. Duration-only changes therefore update
the current composition without a transition. A status or run change creates distinct outgoing and incoming subtrees,
and each subtree renders only values captured by its own frame.

The visual transition stays the existing small vertical fade/slide. The data model, clock cadence, and localized string
format remain unchanged.

## Verification

Use paused-clock Compose tests for both paths. A terminal transition must briefly show the complete old and new identity
lines, then remove the old line. A duration-only refresh must replace the old duration immediately without retaining an
outgoing node, proving that clock ticks do not become animation events.
