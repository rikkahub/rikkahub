# Agent Live Timeline Design

## Problem

The Run detail timeline currently renders steps, tools, and trace events as visually identical static cards. A user has
to read every status label to find the active operation, approval wait, or failure, and all telemetry details remain
expanded even after a long Run.

## Options considered

1. Add status colors and icons only. This is low risk but does not reduce telemetry noise or add inspection controls.
2. Replace the flat list with a nested step/tool tree. This has the richest hierarchy but requires a broader model and
   navigation change than the current experience needs.
3. Use status-aware, expandable flat cards. This preserves stable ordering and persistence while improving live state,
   density, and inspection. This is the selected option.

## Design

`AgentRunTimelineItem` receives an `AgentRunVisualState` derived directly from persisted step, tool, or trace status.
The mapping never inspects localized display labels. Running entries show a native indeterminate progress ring;
attention, success, failure, and stopped entries use the existing semantic icon and Material color vocabulary.

Cards with safe detail fields are expandable. Working, attention, and failure cards start expanded; successful and
stopped cards start compact. Expansion is remembered by stable item identity, so a working card that completes does
not abruptly hide information. Status, icon, tint, arrow rotation, and detail visibility use standard Compose
transitions that respect the system animation-duration scale.

The feature does not persist UI expansion state, change telemetry schemas, expose raw payloads, or introduce timers.
Existing bounded summaries, redacted output metadata, and user-facing error mapping remain authoritative.

## Verification

Unit tests cover persisted-status-to-visual-state mapping for running steps, approval-waiting tools, and denied traces.
Compose UI tests cover default expansion, active progress, terminal transition, manual collapse/expand, and accessible
action/state descriptions. App and Android test source compilation plus diff/line-length checks complete the gate.
