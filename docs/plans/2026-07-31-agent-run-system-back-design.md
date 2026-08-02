# Agent Run Nested System Back Design

## Problem

The Run detail sheet supports nested child Runs and exposes an in-content back button. Android system back currently
remains owned only by `ModalBottomSheet`, so pressing or gesturing back while viewing a child can dismiss the entire
sheet instead of returning to its parent. That loses investigation context and bypasses the new reverse hierarchy
transition.

## Options considered

1. Always intercept system back in `ChatPage` and manually dismiss or navigate. This centralizes routing, but replaces
   Material 3's own root-sheet dismissal path and can lose its native hide animation.
2. Replace the sheet callback with a predictive-back progress implementation. This could provide an interactive child
   preview, but requires composing the parent and child simultaneously and coordinating cancellation with saved state.
   It is unnecessary for correcting the navigation contract.
3. Register a nested-only `BackHandler` inside the sheet content after the detail pane. This is selected because it uses
   the dialog window's dispatcher and handles child back first, while a root leaves Material 3's callback untouched.

## Interaction contract

- Child loading, missing, and content states consume Android system back and invoke parent navigation.
- The resulting depth reduction uses the existing reverse horizontal `AnimatedContent` transition.
- Root and closed states do not register an enabled custom callback. Material 3 dismisses the sheet with its native
  animation and invokes `onDismissRequest` afterward.
- Tapping the scrim or swiping the sheet down remains an explicit request to close the complete detail surface, even
  from a child Run.
- The visible header back button continues to use the same parent-navigation callback.

The policy is represented by a small pure selector so root/child behavior can be verified on the JVM. The callback lives
inside the modal window rather than on the host activity; it does not duplicate state or modify the lifecycle.

## Verification

A JVM test covers closed, root, and child back policy. A Compose instrumentation test opens a child sheet, sends Android
back, verifies parent navigation occurs without dismissal, then sends back at the root and verifies native dismissal.
Because no device is currently connected, instrumentation execution remains pending, but its source must compile.
