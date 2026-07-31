# Agent Ask-User Submission Feedback Design

## Problem

Registered tool approvals already return their real submission `Job`, reject duplicate taps locally, and replace their
actions with native progress feedback. The interactive `ask_user` tool still invokes a `Unit` callback. Until the
conversation update arrives, the answer button and every input remain interactive, so repeated taps can launch repeated
submission jobs and the user receives no visible acknowledgement.

The `ask_user` early return also skips the shared approval-renewal error observer. A renewed or expired interactive
approval can therefore miss the red error message and semantic `Reject` haptic used by other tool cards.

## Options considered

1. Rely only on repository and service idempotency. This protects persisted state but leaves duplicate work and unclear
   UI feedback.
2. Hide the form only after `ToolApprovalState.Answered` arrives. This still leaves a network and persistence latency
   window in which the user can tap repeatedly.
3. Return the actual submission `Job`, lock the card immediately, and keep it locked until that Job completes. This is
   selected because UI state follows the real asynchronous operation and naturally recovers when submission fails.

## Design

Introduce `ToolAnswerHandler`, parallel to `ToolApprovalHandler`, returning `Job`. Propagate it through `ChatMessage`,
`ChatList`, `ChatPage`, and `ChatVM`; `ChatVM.handleToolAnswer` returns the Job already created by `ChatService` instead
of discarding it.

`AskUserToolStep` reuses `ToolApprovalSubmissionState`. A successful `tryStart()` immediately disables every chip and
text field. It then invokes the handler once and joins the returned Job in the composition scope. Completion, failure,
or cancellation clears the local submitting state unless the persisted card has already changed identity and replaced
the composition.

The submit button uses `AnimatedContent` to exchange its icon and label for a small `CircularProgressIndicator`. A
semantic submitting label makes the transition understandable to accessibility services. The successful tap emits one
`Confirm` haptic only after the duplicate guard accepts the submission.

Move the approval-status error observer before the `ask_user` dispatch so every approval-backed tool receives the same
new-error-only `Reject` feedback. The interactive form also renders `approvalStatusMessage` in the error color. Initial
historical errors remain silent because `ApprovalStatusFeedbackState` is initialized with the current message.

## Verification

- JVM tests keep the duplicate-submission and historical-error transition contracts covered.
- Compose tests cover answer-button progress replacement, disabled duplicate taps, and `Confirm` haptic semantics.
- Kotlin and Android test-source compilation prove the callback contract is propagated through every layer.
- Focused Agent approval and Run regressions plus static checks must pass.
