# Agent Run Determinate Progress Design

## Problem

The active Agent Run card already displays `completedSteps/maxSteps` as metadata, but its bottom progress indicator is
always indeterminate. When the runtime has a valid step budget, this throws away useful progress information and makes
a one-step update look identical to a nearly completed Run.

## Options considered

1. Keep the indeterminate indicator and animate only the step-count text. This is visually safe but does not make
   progress scannable or expose it through progress semantics.
2. Always derive a percentage. This looks precise, but missing, zero, or stale `maxSteps` values would incorrectly
   appear as zero progress.
3. Use a hybrid Material progress indicator. This is selected: valid step budgets produce bounded determinate progress,
   while incomplete telemetry keeps the existing indeterminate indicator.

## Design

Add a pure `agentRunStepProgress` function that returns `null` unless `maxSteps` is positive. Valid values divide
completed steps by the step budget and clamp the result to `0f..1f`, protecting the UI from delayed or inconsistent
telemetry.

While the Run is pending or working, the active card keeps its existing bottom progress region. An `AnimatedContent`
transition crossfades between determinate and indeterminate Material indicators only when telemetry availability
changes. Within determinate mode, `animateFloatAsState` uses the Material expressive spatial motion spec so step
increments move the bar continuously instead of replacing it. Stopping feedback and all non-active visual states keep
the existing exit behavior.

Material progress semantics remain authoritative: determinate mode exposes the normalized range value, while fallback
mode exposes `ProgressBarRangeInfo.Indeterminate`. No new timers, persisted state, localized parsing, or runtime schema
changes are introduced.

## Verification

- JVM tests cover missing, invalid, normal, negative, and over-budget values.
- Compose tests cover determinate semantics for known budgets and indeterminate fallback for unknown budgets.
- Agent presentation and card-host regressions, app compilation, Android test compilation, diff checks, and the
  repository line-length rule must pass.
