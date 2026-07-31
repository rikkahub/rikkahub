# Agent Linear Progress Phase Design

## Problem

The active Agent card nests a visibility transition around a determinate/indeterminate mode transition. Both subtrees
read animated progress from the outer composition. When a 1/4 live run becomes 4/4 terminal, the fading old progress
bar can start moving toward 100% before it disappears.

The nested booleans also represent three actual UI phases indirectly: hidden, determinate, and indeterminate.

## Options

1. Freeze the outer animated value when visibility becomes false. This fixes terminal exit but adds special state and
   does not unify mode transitions.
2. Keep the nested animations and pass retained values through both levels. This preserves behavior but increases
   coordination between two transition lifecycles.
3. Model hidden, determinate, and indeterminate as one immutable progress frame and animate by phase.

## Decision

Use option 3. Add an explicit phase enum and a frame containing phase plus raw determinate progress. A single
`AnimatedContent` uses phase as its content key. Phase changes retain complete outgoing and incoming subtrees, while
progress-only updates reuse the determinate subtree and continue through `animateFloatAsState`.

The hidden phase renders no indicator. Determinate and indeterminate phases keep the existing 3 dp Material 3 linear
indicator styling. The parent card's content-size animation continues handling the small height change.

## Verification

Add paused-clock tests for terminal and stopping exits. During a terminal transition that also advances from 1/4 to
4/4, the outgoing determinate indicator must remain exactly 25% and no 100% indicator may appear. A stopping transition
must also retain 25% for its first frame. After either transition settles, no determinate indicator remains. Existing
mode-retention tests continue covering determinate-to-indeterminate crossfades.
