# Agent Step Metadata Motion Design

## Problem

The active Agent card animates its determinate progress bar, but the matching `1/4 steps` metadata changes immediately.
This makes the numerical signal feel disconnected from the native progress movement. Animating the existing complete
metadata string directly is unsuitable because its elapsed-duration segment changes every second.

## Options

1. Animate the complete metadata string. This is simple but creates continuous motion on every duration tick.
2. Rebuild the metadata as several independently measured row children. This gives fine control but complicates the
   current single-line ellipsis behavior.
3. Use a stable metadata identity containing only model and step values, while rendering current duration inside the
   animated frame. Step and model changes animate; duration ticks recompose without changing animation identity.

## Decision

Use option 3. Introduce a small `AgentRunMetadataIdentity` value containing `model`, `completedSteps`, and `maxSteps`.
Render the current one-line metadata text inside `AnimatedContent` keyed by that identity.

Reuse `agentRunCountMotion` to select direction. A higher completed-step count enters from below while the old value
exits upward. A lower count reverses that direction. Model-only or budget-only changes use a crossfade. All spatial
movement uses the expressive Material motion scheme, retaining the existing tabular-number typography.

## Frame Ownership

The outgoing frame receives its own model and step values through the `AnimatedContent` target argument, so it cannot
read the new step count while fading. Elapsed duration deliberately remains outer continuous state because it describes
the same run and should not restart an animation every second.

## Verification

Add a paused-clock Compose test that settles an active card at one of four steps, changes it to two of four, and
advances one frame. Both `1/4 steps` and `2/4 steps` must coexist during the transition. After it settles, only the new
step label remains. Existing progress semantics continue to verify the corresponding bar value.
