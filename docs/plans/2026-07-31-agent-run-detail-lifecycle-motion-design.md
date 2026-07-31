# Agent Run Detail Lifecycle Motion Design

## Problem

The active Run card now suppresses stale live activity after a Run becomes terminal, but the detail header still uses
`waitingReason ?: currentStep` for every state. A completed detail can therefore continue showing the last operation.
The header identity combines Run ID, status, and a one-second duration clock in one static `Text`, so lifecycle status
changes are abrupt while the frequently changing duration must remain visually quiet.

## Options considered

1. Leave the detail header unchanged because the active card is fixed. This keeps contradictory behavior when users
   inspect the Run directly.
2. Copy the active card's terminal failure description into the activity row. The existing detail description already
   renders that guidance below the header, so this would duplicate content.
3. Reuse the lifecycle activity function with detail-specific terminal behavior and animate only the identity status.
   This is selected because it preserves one source of lifecycle truth without duplicating failure guidance.

## Design

`AgentRunDetailHeader` calls `agentRunActivityText` with its real live fields and stopping state, but passes no terminal
description. Live waiting/current/fallback behavior therefore matches the active card, stopping keeps priority, and all
terminal activity becomes `null`. The existing status-description section remains the sole failure guidance surface.

Wrap the identity line in `AnimatedContent` keyed only by `presentation.status`. Phase changes use the same native
fade-plus-vertical-slide vocabulary as the active card. `liveDuration` is captured inside the content, so its one-second
updates refresh the visible text without changing the animation target or repeatedly sliding the row.

The nullable activity continues through its existing `AnimatedContent`; the header's expressive `animateContentSize`
handles compaction. Mark only this activity node as `LiveRegionMode.Polite`, excluding the duration-bearing identity and
preventing timer-driven announcements. Progress, stop controls, back navigation, status-description motion, and data
schemas remain unchanged.

## Verification

- Compose tests keep a stale current step in the terminal payload and require it to disappear after the native exit.
- A controlled clock test requires the old identity status to remain during the transition and disappear afterward.
- The activity node must expose polite live-region semantics while the known progress and terminal controls retain their
  existing contracts.
- Focused JVM regressions, app compilation, Android test compilation, diff checks, and line-length checks must pass.
