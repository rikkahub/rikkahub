# Agent Approval Snackbar Design

## Problem

After Run details navigate to a pending approval card, ChatPage renders a plain text announcement over the top of the
conversation. The announcement never clears, manually takes focus, and can overlap the active Run card. If the
conversation has no message nodes, no result is announced at all.

## Options considered

1. Keep the custom overlay and add delayed dismissal plus enter/exit animation. This duplicates timing, accessibility,
   and queue behavior already supplied by Material.
2. Use the existing app toast system. It sits outside Scaffold layout and is less tightly coupled to the navigation
   result being reported.
3. Use a Material `SnackbarHostState` in the existing Scaffold. It provides native motion, bottom-bar avoidance,
   accessibility announcements, recommended timeout behavior, and serialized messages. This is the selected option.

## Design

ChatPage owns one remembered Snackbar host state and supplies it to Scaffold. Approval navigation always computes a
result, even for an empty conversation. A successful lookup first scrolls to the frozen message index, then shows a
short localized confirmation. A missing lookup shows a longer localized explanation with a dismiss action.

The custom nullable announcement state, focus requester, focusable modifier, semantic override, and overlay text are
removed. Snackbar lifecycle is scoped to ChatPage and no persistence, service, approval identity, or message mutation
contract changes.

## Verification

A pure result-to-resource mapping is covered by a JVM test. Android test-source compilation proves the Material API
and resources are valid. Source inspection verifies both success and empty/missing paths call `showSnackbar`, the old
overlay has no references, and existing Agent Run navigation/presentation tests remain green.
