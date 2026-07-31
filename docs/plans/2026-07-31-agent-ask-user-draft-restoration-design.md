# Agent Ask-User Draft Restoration Design

## Problem

The interactive `ask_user` form stores text, single-choice, and multiple-choice answers in `mutableStateMapOf` values
created with `remember`. Any Activity recreation, configuration change, or temporary removal of the card from the saved
composition destroys the draft. This is especially frustrating when the Agent asks several questions before it can
continue.

Approval renewal changes `approvalId` while preserving the exact tool execution. Draft identity must therefore avoid
using the replaceable approval ID.

## Options considered

1. Persist drafts in the conversation database. This survives process death but expands the sensitive-data boundary,
   requires cleanup rules, and writes content the user has not submitted.
2. Store drafts in `ChatVM`. This avoids database writes but needs a keyed cache, lifecycle cleanup, and explicit state
   restoration for process death.
3. Use a bounded custom `rememberSaveable` Saver keyed by stable tool identity. This is selected because drafts remain
   UI-only, survive normal Android state restoration, and are automatically discarded with the owning screen state.

## Design

Introduce an immutable, serializable `AskUserAnswerDraft` containing a text/single-answer map and a multi-answer map.
All selection operations return a copied draft, keeping Compose mutation explicit and predictable. JSON encoding allows
arbitrary option text without delimiter collisions and provides a simple corruption fallback to an empty draft.

The Saver accepts drafts only while their encoded form remains at or below 32 KiB. Oversized drafts stay in live memory
but are not placed in Android saved state, avoiding `TransactionTooLargeException` risk. No draft text is written to
Room, logs, analytics, or Agent telemetry.

`rememberAskUserAnswerDraft` keys saved state with `toolExecutionId`, `toolCallId`, `toolName`, and the immutable tool
input. It deliberately excludes `approvalId` and `approvalStatusMessage`, so approval renewal and error feedback do not
erase work. A different tool execution or changed question payload receives a fresh draft.

The existing FilterChip and text-field animations remain unchanged. Restored selection values feed their normal native
selected-state transitions when the composition is reconstructed.

## Verification

- JVM tests cover immutable updates, special-character JSON round trips, corrupted input fallback, and size bounds.
- Compose test-source compilation covers the `rememberSaveable` wiring in the real form.
- Focused approval and Agent Run regressions, app compilation, Android test compilation, and static checks must pass.
