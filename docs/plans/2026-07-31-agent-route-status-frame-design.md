# Agent Route Status Frame Design

## Problem

The active Agent card displays routing and run status in one line, but its `AnimatedContent` target contains only
status. If a lightweight presentation is replaced by richer routing data while status remains unchanged, the route
label hard-cuts. If routing and status change together, the outgoing status frame reads the new outer route label
instead of its own route.

## Options

1. Keep status as the only target. This leaves route changes outside the animation lifecycle.
2. Animate the route and status in separate containers. This permits independent movement but can briefly combine an old
   route with a new status or the reverse.
3. Treat the rendered route label and status as one immutable animation frame.

## Decision

Use option 3. Add `AgentRunStatusIdentity(routingLabel, status)` and pass it as the `AnimatedContent` target. The
content lambda renders only values owned by that frame. Any visible change to either field uses the existing small
vertical fade/slide transition, while unrelated activity, progress, metadata, and duration changes do not replay it.

This is a presentation-only identity. The routing domain model and persisted run status remain unchanged.

## Verification

Add a paused-clock Compose test that holds status at `running` while changing routing from the auto execution label to
the unavailable label. On the first frame, both complete route/status lines must coexist. After the transition settles,
the old auto line must be removed and the unavailable line must remain.
