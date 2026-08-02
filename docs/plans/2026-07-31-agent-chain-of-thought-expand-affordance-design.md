# Agent Chain-of-Thought Expand Affordance Design

## Problem

Agent reasoning and tool steps already animate their container size, but both expansion affordances replace an up arrow
with a down arrow immediately. The discontinuous icon swap makes the control feel detached from the layout motion.
The top “show more steps” row and individual expandable steps are clickable, yet they expose neither a dynamic click
label nor an expanded/collapsed state description to accessibility services.

## Options considered

1. Wrap all step content in `AnimatedVisibility`. This would animate text directly, but retained exit frames would also
   retain AskUser fields, focus, keyboard input, and pointer targets after collapse unless every child interaction were
   separately revoked.
2. Crossfade the two arrow icons. This avoids a hard swap but still represents one continuous physical affordance as
   two unrelated images.
3. Keep immediate content lifecycle revocation and rotate one down arrow through 180 degrees using the Material motion
   scheme. This is selected because it aligns with the existing size animation without introducing stale interactive
   content.

## Design

Use a single `ArrowDown01` for the chain-level and step-level indicators. Animate its rotation from 0 degrees when
collapsed to 180 degrees when expanded with `MaterialTheme.motionScheme.defaultSpatialSpec()`. The icon remains
decorative; the containing clickable row owns the action.

For the chain-level control, use the already visible “Show N more steps” or “Collapse” text as the dynamic click label.
For an individual step, add localized “Expand step” and “Collapse step” action labels. Both controls expose localized
“Expanded” or “Collapsed” state descriptions on the stable clickable owner. This lets TalkBack announce state changes
and gives switch/voice access a meaningful action without duplicating icon descriptions.

The existing `animateContentSize` remains responsible for layout movement. Step content is still removed immediately
when `contentVisible` becomes false, so forms and actions cannot remain focused or interactive during a visual exit.
No reasoning state, tool state, expansion defaults, renderer content, or navigation behavior changes.

## Verification

- A Compose contract expands the chain-level control and verifies collapsed/expanded state descriptions around the
  transition.
- A second contract expands an individual step and verifies both state descriptions and detail visibility.
- Production and Android-test Kotlin compilation must pass.
- Existing Agent/tool JVM tests, the 120-column scan, and `git diff --check` must remain green.
