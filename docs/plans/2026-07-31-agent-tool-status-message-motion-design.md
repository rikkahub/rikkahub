# Agent Tool Status Message Motion Design

## Problem

Tool approval errors are currently rendered in two different ways. Regular tools conditionally insert a `Text` inside
their expandable details, while `ask_user` wraps its status in a local fade-only `AnimatedContent`. The regular-tool
branch cannot animate removal when the error is its only detail because the entire content lambda disappears at the
same state change. Neither local error surface owns a stable polite live region, so assistive technology has no explicit
announcement contract for a new or replaced error.

## Options considered

1. Rely only on the existing global Snackbar. This avoids another component but removes the error from the tool that
   caused it and makes later inspection harder.
2. Keep each status inside expandable details and add separate animations. This preserves the current location, but a
   regular tool still loses its animation owner when the details lambda becomes `null`.
3. Render one shared inline status immediately below every tool step. This is selected because the component remains
   composed for nullable-state exits, keeps the error next to its tool, and remains visible even if the details are
   collapsed.

## Design

Add a reusable `ToolStatusMessage` whose `AnimatedContent` target is the nullable message itself. Each animation frame
therefore owns its captured string: replacing an error crossfades old and new text, while clearing it can shrink and
fade the old text without reading a now-null outer value.

The stable animation container owns `LiveRegionMode.Polite`. New or replaced status text can be announced without
interrupting current speech. Null-to-message transitions expand from the top and fade in; message-to-null transitions
shrink toward the top and fade out; message replacement uses a restrained crossfade. The caller supplies the existing
32 dp content indentation so the feedback stays aligned with tool details.

Remove status text from the regular tool details predicate and from the ask-user content column, then compose the shared
status directly after each `ControlledChainOfThoughtStep`. Renderer summaries, denial reasons, images, expansion state,
the global Snackbar, reject haptics, submission callbacks, and approval state remain unchanged.

## Verification

- With a paused Compose clock, replacing an error must retain the old captured text while the new error is already
  present, then remove the old frame after the transition.
- Clearing an error must retain it during the shrink/fade exit and remove it afterward.
- The stable status container must expose a polite live region while feedback is visible.
- Production and Android-test Kotlin compilation, focused Agent/tool JVM tests, the 120-column scan, and
  `git diff --check` must pass.
