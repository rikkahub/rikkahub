# Agent Run Live Duration Design

## Problem

The Run presentation currently formats elapsed time from persisted timestamps. During a long model or tool operation,
Room receives no update, so an active Run appears to stop counting even though execution is still progressing.

## Options considered

1. Persist a heartbeat every second. This keeps every observer synchronized, but creates unnecessary database writes,
   invalidations, and battery cost for presentation-only state.
2. Drive the label from a frame animation. This is visually continuous, but elapsed time does not need frame precision
   and the extra recomposition work would be wasteful.
3. Run a local one-second clock only while an active Run is visible. This is the selected option: it is accurate enough,
   lifecycle-aware through Compose disposal, and leaves durable execution state unchanged.

## Design

`AgentRunPresentation` exposes the persisted duration start timestamp in addition to its frozen duration label. A pure
formatter selects the local clock for pending, working, and approval-waiting states; terminal states always retain the
persisted duration. It also clamps clock skew so an elapsed label cannot become negative.

The active card and visible detail header each start a one-second coroutine only while their presentation is live. The
effect is cancelled when the composable leaves the composition or the Run becomes terminal. Duration changes use a
native fade transition rather than vertical movement, avoiding distracting motion once per second. Status and activity
transitions keep their existing spatial motion.

No database heartbeat, service contract, schema, background timer, child list timer, or timeline timer is introduced.

## Verification

JVM tests prove that active states use the supplied local time, approval waits continue counting, terminal states stay
frozen, and clock skew clamps to zero. Focused JVM regressions and Android test-source compilation remain the build
gate; visual device execution is reported separately because no device is attached.
