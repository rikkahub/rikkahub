# Agent Metadata Frame Ownership Design

## Problem

The active Agent card animates a metadata line containing model, step count, and elapsed duration. Its
`AnimatedContent` target owns model and step count, but the content lambda reads duration from the outer composition.
When a step and duration change together, the outgoing old-step frame immediately displays the new duration.

Adding duration to the default animation identity would make every live clock refresh replay the metadata transition.
That would retain stale duration nodes and add constant motion to a low-priority information line.

## Options

1. Render model, steps, and duration as separate text nodes. This isolates updates but changes truncation, spacing, and
   accessibility structure.
2. Animate a complete metadata frame on every value change. This owns all values but animates clock ticks.
3. Use a complete metadata frame as `targetState` and model/step values as `contentKey`.

## Decision

Use option 3. Extend the metadata presentation value with the rendered duration and expose a stable key containing
model, completed steps, and maximum steps. Directional step changes create distinct old and new subtrees, each rendered
only from its own frame. Duration-only changes reuse the current subtree and update in place.

The existing count-direction calculation and expressive vertical motion remain unchanged. This is a presentation-only
change and does not affect duration calculation or persisted run data.

## Verification

Use terminal presentations with deterministic duration strings. A simultaneous step and duration change must retain
the complete old and new metadata lines during the transition, then remove the old line. A duration-only change must
replace the old line on the first frame without leaving an outgoing node.
