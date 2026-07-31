# Agent Ask-User Completion Motion Design

## Problem

The ask-user tool replaces editable chips and text fields with the recorded answer as soon as its approval state moves
from `Pending` to `Answered`. The surrounding chain-of-thought step does not animate content changes, so the form jumps
between two layouts. Adding a naive `AnimatedContent` would create a second problem: Compose retains the outgoing editor
while it animates, and that retained frame could still accept input after the answer has already been committed.

## Options considered

1. Animate the whole question list. This is simple, but duplicates and moves question labels that have not changed.
2. Crossfade each response without an interaction contract. This improves appearance but leaves stale controls active.
3. Keep question labels stable and animate only each response, while deriving outgoing-frame interaction from the latest
   response mode. This is selected because it preserves spatial context and revokes stale touch, keyboard, and semantic
   actions at the state boundary.

## Design

Represent the rendered response as an immutable frame containing its mode and, for an answered frame, the captured
answer text. `AnimatedContent` owns that frame so both forward completion and a later renewal can render their outgoing
content safely without casting the latest tool state.

Completion moves the saved answer slightly upward into place while the editor leaves upward; renewal uses the inverse
direction. Other state changes use a fade. The question text stays outside this transition and therefore remains a
stable visual anchor.

The response transition passes an `interactionEnabled` value to its editor content. It is true only when the latest
target frame is editable and submission is not active. Thus an old editor may remain visible during its native exit,
but it is disabled immediately. The submit row expands/fades in and shrinks/fades out, and reads the same latest-mode
gate during exit.

No answer serialization, draft persistence, submission callback, error handling, or approval-state behavior changes.

## Verification

- A paused Compose clock changes an editable response to answered and requires the outgoing editor to remain visible
  but disabled while the saved answer is already present.
- After the transition duration, the old editor must no longer exist.
- Production and Android-test Kotlin compilation must pass.
- Existing ask-user draft, tool approval, Agent activity, progress, presentation, timeline, and navigation JVM tests
  must remain green.
