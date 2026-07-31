# Agent Result Frame Ownership Design

## Problem

Agent result content can be replaced or cleared by a later database/telemetry snapshot while its Compose container is
still animating. The detail-header description now owns its outgoing data, but two other surfaces do not.

`AgentChildRunCard` drives `AnimatedVisibility` from `findings.isNotBlank()` while its `Text` reads the latest findings.
When findings become blank, the text disappears before the card finishes shrinking. `AgentRunTimelineCard` behaves the
same way for summary, output, failure, and approval fields: when the last field disappears, the visibility container
exits with an already-empty body.

## Options considered

1. Remember the last non-empty payload per card. This adds mutable cache lifetime and identity-reset concerns.
2. Animate every nullable field independently. This preserves values but can create five overlapping height animations
   in a single timeline card and makes the details block visually fragmented.
3. Make the child finding and the complete timeline-details snapshot animation states. This is selected because each
   outgoing frame captures exactly what it rendered and the details block remains one coherent unit.

## Design

Normalize child findings with `takeIf(String::isNotBlank)` and make that nullable string the `AnimatedContent` target.
For timeline content, create a private immutable `AgentRunTimelineDetails` snapshot containing the five optional fields.
Return null when all fields are absent, and animate `details.takeIf { expanded }` as one target state.

Use one nullable-content transition helper across the detail header, child card, and timeline card. Null to content
expands and fades from the top, content to null shrinks and fades toward the top, and content replacement crossfades.
Each lambda renders only its target-state value, never the latest outer model.

Arrow visibility, expand/collapse semantics, and click availability continue to use the latest `hasDetails`. Thus stale
data can finish a visual exit but cannot preserve obsolete interaction. Run navigation, persistence, and telemetry
mapping remain unchanged.

## Verification

- A paused-clock Compose contract clears child findings and requires the outgoing finding through the first exit frame.
- A second contract clears all expanded timeline details, requires the old summary through the first exit frame, and
  confirms that the card is no longer expandable.
- Both contracts require the stale content to be absent after the transition.
- Existing Agent progress, timeline, navigation, approval, and ask-user regressions remain green.
