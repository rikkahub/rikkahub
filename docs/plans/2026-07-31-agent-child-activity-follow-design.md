# Agent Child Activity Follow Design

## Problem

The Agent Run detail pane tracks new timeline keys to decide whether to auto-scroll or show its “new activity” button.
Child runs are rendered above the timeline with stable keys and `animateItem`, but their creation is absent from that
activity stream. When an Agent delegates work, a following user receives no follow animation, while a user reading
older entries receives no unseen-activity count. The first child also makes the child-section heading appear without a
lazy-item animation.

## Options considered

1. Add a second child-run counter and follow effect. This duplicates scroll ownership and can race the timeline effect.
2. Treat only child status changes as timeline events. The presentation layer does not guarantee a matching timeline
   item for every newly persisted child, and it would delay feedback.
3. Build one namespaced activity-key sequence from child IDs and timeline stable keys. This is selected because the
   existing follow algorithm already handles insertions, repeated updates, paused reading, and unseen accumulation.

## Design

Add `agentRunActivityKeys(childRunIds, timelineKeys)`. Prefix child keys with `child:` and timeline keys with
`timeline:` before concatenating them. Namespaces prevent a child run ID from colliding with an identical timeline key;
stable status-only changes keep the same activity key and do not increment the count.

In `AgentRunDetailContent`, remember this combined sequence instead of timeline keys alone. Rename the local retained
set to `knownActivityKeys`, and feed the sequence into the existing `agentTimelineUpdate` reducer. A new child therefore
uses exactly the same behavior as a new timeline entry: users following the latest animate to the final item; paused
users see the generic “new activity” count. The existing last-item index already includes child sections.

Add `animateItem()` to the child-section heading. The heading and first child then enter through the same native lazy
layout animation rather than the heading popping before the card settles. Existing child-card keys, timeline-card keys,
scroll gesture ownership, status updates, and duration clocks remain unchanged.

## Verification

- JVM tests require namespaced child/timeline keys to remain distinct even when their raw IDs match.
- A paused update with one new child and one new timeline entry must add two unseen activities; repeating the same keys
  must add none.
- Existing follow-mode and last-index contracts remain green.
- Production and Android-test Kotlin compilation, focused Agent/tool JVM tests, changed-line length checks, and
  `git diff --check` must pass.
