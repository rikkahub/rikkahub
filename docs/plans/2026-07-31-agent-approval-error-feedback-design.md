# Agent Approval Error Feedback Design

## Problem

An approval can expire between the user's decision and persistence. The service safely renews the approval binding and
keeps the card pending, but the UI currently communicates this only through a secondary-colored status string. After a
critical action has already produced confirmation haptics, the missing error signal can make the renewed controls look
like a duplicate submission or an ignored tap.

Historical conversations can already contain the persisted renewal message. Emitting an error haptic whenever such a
card enters composition would be surprising, especially while scrolling or reopening a conversation.

## Options considered

1. Trigger `Reject` whenever `approvalStatusMessage` is non-null. This catches renewal but replays haptics for
   historical state on every new composition.
2. Trigger feedback from `ChatService`. That layer knows the failure happened but does not own Android UI feedback and
   can run while the relevant screen is not visible.
3. Track the last displayed message in the card composition and react only to a new non-null value. This is selected
   because it provides timely visible-screen feedback while suppressing initial and repeated persisted state.

## Design

An `ApprovalStatusFeedbackState` is initialized with the first rendered status message. Its `update()` method returns
true only when a different non-null message appears; clearing the message updates the baseline without feedback. The
state is remembered by stable tool execution/call identity and deliberately excludes `approvalId`, because renewal
changes that ID and must still be observed as one continuous card lifecycle.

`LaunchedEffect(approvalStatusMessage)` asks the state whether the update is new. When true it performs
`HapticFeedbackType.Reject` through `LocalHapticFeedback`. Initial composition, identical recompositions, message
clearing, and approval-ID replacement alone remain silent. The visible status message uses the Material error color;
the existing parent `animateContentSize` supplies the layout transition without adding another animation layer.

## Verification

A JVM test covers initial suppression, one new-message signal, duplicate suppression, silent clearing, and a later new
error. Focused approval and Agent Run regressions plus Android test-source compilation remain required. No connected
device is available, so physical haptic execution remains pending.
