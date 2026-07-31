# Agent Run Activity Lifecycle Design

## Problem

The active Run card retains a terminal state for 650 ms so users can acknowledge completion. Its activity line uses
`waitingReason ?: currentStep ?: "正在等待运行遥测"` for every lifecycle state. A successful Run that clears its current
step therefore briefly displays “completed” and “waiting for telemetry” at the same time. A terminal payload that
retains its last step can similarly present stale work as current.

The animated status and activity text also lack a targeted accessibility live region. Applying a live region to the
whole card would include the one-second duration clock and create noisy announcements.

## Options considered

1. Retain the last live activity until the card exits. This avoids layout movement but leaves stale work visible.
2. Replace every terminal activity with the status label. This removes the contradiction but duplicates nearby text
   and wastes the acknowledgement interval.
3. Make activity lifecycle-aware and announce only the dynamic status block. This is selected because successful Runs
   compact naturally, failure guidance remains visible, and duration updates stay quiet.

## Design

Add a pure `agentRunActivityText` function. Stopping feedback has highest priority. Pending, working, and attention
states use a nonblank waiting reason, then a nonblank current step, then the existing telemetry fallback. Terminal
states ignore both live-only fields: they show a nonblank `statusDescription` for failure or interruption guidance and
otherwise return `null`.

The active card changes its activity `AnimatedContent` target to nullable. When success removes the activity, the old
text completes its native fade/vertical exit while the card's existing expressive `animateContentSize` compacts the
layout. Failure text enters through the same transition. No fixed delay is added beyond the existing terminal
acknowledgement window.

Status and activity are placed in a nested semantic group marked `LiveRegionMode.Polite`. Model, step count, and live
duration remain outside that group, preventing one-second timer announcements. The visible layout, stop controls,
progress behavior, persistence, and telemetry schemas remain unchanged.

## Verification

- JVM tests cover stopping precedence, live waiting/current/fallback order, terminal stale-step suppression, successful
  null activity, and terminal failure guidance.
- Compose tests cover terminal removal of both stale activity and the telemetry fallback, retained failure guidance,
  and the polite live-region semantic.
- Agent card-host, presentation, progress, navigation, and timeline regressions plus compilation and static checks must
  pass.
