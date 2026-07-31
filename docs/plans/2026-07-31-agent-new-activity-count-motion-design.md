# Agent New Activity Count Motion Design

## Problem

When the user scrolls away from the latest Agent timeline item, `AgentRunNewActivityButton` displays an unseen-item
count. Count changes currently use a symmetric crossfade. An increase has no directional relationship to newly arriving
items, and the changing button label is not marked as a live region for accessibility services.

The button itself already enters from the bottom and scrolls the timeline to the newest item. Its count should share
that vertical vocabulary without moving or resizing the button's click target.

## Options considered

1. Scale or bounce the whole floating button for every new item. This is noticeable but repeatedly moves a persistent
   navigation target and competes with the timeline content.
2. Animate only the count label vertically and expose the button as a polite live region. This is selected because it
   communicates direction while preserving button geometry and touch stability.
3. Keep the symmetric crossfade and only add accessibility semantics. This improves announcements but leaves the visual
   transition without an arrival direction.

## Design

Classify count changes as `INCREASE`, `DECREASE`, or `STEADY`. An increase brings the new label upward from below while
the old label exits upward. A decrease uses the inverse direction. A steady recomposition keeps a subtle fade fallback.
The transition uses the Material expressive spatial specification for displacement and retains the existing fade.

Apply `LiveRegionMode.Polite` to the floating button so a count update can be announced without interrupting current
speech. Continue coercing the displayed count to at least one because the parent removes the button when the real unseen
count reaches zero. The icon, click callback, visibility animation, unseen-count bookkeeping, and auto-scroll behavior
remain unchanged.

## Verification

- A JVM test covers increasing, decreasing, and steady count classifications.
- A controlled Compose clock requires both old and new labels during the native transition, then only the new label
  after completion.
- The Compose contract verifies the polite live-region semantic and existing click behavior.
- Existing timeline-follow, navigation, progress, approval, and ask-user JVM tests remain green.
