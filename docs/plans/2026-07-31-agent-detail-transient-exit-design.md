# Agent Detail Transient Exit Design

## Problem

`AgentRunDetailHeader` contains two transient rows below its identity: a status description and an approval entry.
Their business state can disappear while Compose is still running an exit animation.

The status-description row uses `AnimatedVisibility`, but its content reads the latest nullable description again. When
the value becomes null, the outer container begins shrinking while the text immediately vanishes, so the intended fade
does not visually preserve the outgoing frame. The approval row retains its text during exit, but its `TextButton` still
exposes a click action after the approval callback has become null, producing a short no-op interaction window.

## Options considered

1. Cache the last non-null description in remembered state. This can preserve exit content but creates another source of
   truth and needs explicit cleanup when the Run identity changes.
2. Animate the nullable description value itself. This is selected because each `AnimatedContent` frame owns its text,
   naturally supports description-to-description changes, and requires no retained mutable cache.
3. Remove exit motion and hide both rows immediately. This avoids stale UI but sacrifices continuity and makes the
   header height snap.

## Design

Replace the description's `AnimatedVisibility` with `AnimatedContent(targetState = statusDescription)`. A non-null frame
renders the existing padded text. The incoming transition expands and fades from the top; the outgoing transition
shrinks and fades toward the top. When the target is null, the new frame renders no text while the old frame retains its
captured description until the native transition completes.

Keep `AnimatedVisibility` for the approval row because its visual content is constant. Set `TextButton.enabled` from the
current callback availability, so the button loses its click semantic immediately while its disabled visual frame can
finish shrinking and fading. The callback remains null-safe as a final duplicate-action guard.

No approval routing, persistence, status mapping, header spacing, or haptic behavior changes.

## Verification

- A paused Compose clock removes a failure description and requires it during the first exit frame, then requires its
  absence after the transition.
- A second paused-clock contract removes the approval callback, requires the button to remain visible but non-clickable
  during exit, then requires it to disappear.
- Existing detail-state, progress, navigation, timeline, approval, and ask-user tests remain green.
