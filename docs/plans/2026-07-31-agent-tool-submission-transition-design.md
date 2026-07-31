# Agent Tool Submission Transition Design

## Problem

`ToolApprovalActions` uses `AnimatedContent` to crossfade approve/deny controls into a progress indicator. Compose
retains the outgoing `false` frame until the transition completes. The two icon buttons derive their existence from it
but do not read the latest outer `isSubmitting`, so they remain clickable for part of the native crossfade. The
single-flight state rejects duplicate submission later, but the UI still exposes misleading interaction and extra
haptic feedback.

Approval and ask-user progress indicators also have content descriptions but are not live regions, so assistive
technology is not explicitly notified when idle controls become submission progress.

## Options considered

1. Remove the crossfade and swap controls immediately. This closes the window but loses the native acknowledgement.
2. Block pointer input on a parent overlay. This can stop touch but leaves stale click semantics and keyboard actions.
3. Keep the crossfade while gating every outgoing control from the latest submission state. This is selected because it
   preserves visual continuity and removes touch, keyboard, accessibility, and haptic duplication at the source.

## Design

Set both approval icon buttons to `enabled = !isSubmitting`. The outgoing animated frame can remain visible, but it
becomes non-interactive in the same recomposition that introduces the progress frame. The existing single-flight guard
remains the backend duplicate-safety boundary.

Mark the approval and ask-user submit `AnimatedContent` containers as `LiveRegionMode.Polite`. The stable container,
rather than an individual entering/exiting child, owns the semantic so the progress description can be
announced without interrupting current speech. Existing content descriptions distinguish approval submission from
answer submission.

No submission coroutine, approval state, haptic type, callback, persistence, or error handling changes.

## Verification

- A paused Compose clock clicks approve, advances one transition frame, and requires both outgoing controls to remain
  visible but disabled while progress is visible.
- The same contract confirms only one callback and one confirmation haptic occur.
- Approval and ask-user submission containers expose polite live-region semantics.
- Existing draft restoration, single-flight, error feedback, and Agent Run tests remain green.
