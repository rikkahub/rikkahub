# Agent Processing Status Motion Design

## Problem

The loading row displays `processingStatus` inside `AnimatedVisibility`, but the text expression becomes an empty string
as soon as the status clears. The visibility exit therefore animates an empty child instead of the message the user was
reading. Non-null phase changes also replace text immediately, making routing, preparation, and tool phases feel like
unrelated flashes rather than one continuous Agent operation.

## Options considered

1. Keep default `AnimatedVisibility`. This cannot preserve outgoing text and does not animate phase changes.
2. Add only a crossfade around the current nullable text. This improves replacement but still loses spatial direction
   and can animate an empty value during exit.
3. Retain the latest non-null label, animate phase changes vertically, and animate the whole label horizontally when it
   first appears or finally disappears. This is selected because each motion has one clear semantic purpose.

## Design

Add a pure presentation selector that receives the current status and the previously retained status. It returns
whether the label should be visible, which text should be rendered during this frame, and the next retained value. A
null current status hides the label but continues rendering the retained text until the visibility exit finishes. An
initial null status remains empty and never creates a phantom label.

Wrap this behavior in an internal `AgentProcessingStatus` composable. `AnimatedVisibility` uses horizontal expand and
shrink with fade for the label lifecycle. Nested `AnimatedContent` uses a short vertical slide and fade for non-null
phase replacement. Motion specs come from the current Material motion scheme, preserving platform animation-scale
behavior. The existing `RabbitLoadingIndicator` remains stable and does not restart for each text update.

The label exposes `LiveRegionMode.Polite`, allowing accessibility services to announce new phases without interrupting
the user's current speech. No haptic is added because processing phase changes are routine and can be frequent.

## Verification

- JVM tests prove initial-null suppression, non-null replacement, and retained text during exit.
- Compose tests freeze the animation clock to verify the outgoing label remains during exit and disappears afterward.
- Focused Agent regressions, app compilation, Android test-source compilation, and static checks must pass.
