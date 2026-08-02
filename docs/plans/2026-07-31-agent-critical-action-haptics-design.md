# Agent Critical Action Haptics Design

## Problem

Stopping a Run and resolving a tool approval materially change Agent execution, but they currently provide only visual
press feedback. On touch devices the subsequent UI change can be delayed by coroutine or persistence work, so the user
may not immediately know that the action was accepted. Applying haptics indiscriminately to every card and navigation
tap would be noisy and reduce the value of the signal.

## Options considered

1. Add the same keyboard-tap haptic to every Agent interaction. This is simple but makes routine expansion, scrolling,
   and navigation vibrate and does not express action meaning.
2. Trigger haptics when asynchronous execution reaches its final state. This confirms persistence, but can happen long
   after the initiating touch or after recomposition from restored data, producing surprising feedback.
3. Apply semantic haptics only at critical user-action boundaries. This is selected because it gives immediate,
   deterministic feedback while leaving routine interactions quiet.

## Interaction contract

- Stopping an active Run uses `HapticFeedbackType.Confirm` immediately before invoking the stop callback.
- Approving a tool uses `Confirm` immediately before submitting approval.
- Opening the deny-reason dialog uses `ContextClick`; no denial has been committed at that point.
- Confirming the deny dialog uses `Confirm` immediately before submitting the denial.
- Disabled or submitting controls cannot invoke their callbacks and therefore cannot produce duplicate haptics.
- Opening Run details, navigating children, returning, expanding timeline cards, and scrolling remain haptic-free.

Feedback goes through `LocalHapticFeedback`, so Android system settings remain authoritative. No direct vibrator API or
permission is introduced. Recomposition and asynchronous state changes never trigger haptics.

## Verification

Compose instrumentation tests inject a recording `HapticFeedback` through `CompositionLocalProvider`. Approval tests
assert `Confirm` for approve and `ContextClick` for opening deny. Run Center tests require one `Confirm` with one stop
callback and preserve the duplicate-safe submitting state. Android test source must compile without a connected device;
device execution remains pending.
