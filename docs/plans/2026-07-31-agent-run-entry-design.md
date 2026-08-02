# Agent Run Status Entry Design

## Problem

The active Run card disappears when a Run becomes terminal, while the top-bar Run entry remains a static CPU icon. This
breaks visual continuity: the user can open the latest Run, but cannot tell whether it is still working, needs attention,
succeeded, failed, or stopped without opening the sheet.

## Decision

Make the existing top-bar entry a persistent presentation of the latest Run. Reuse `AgentRunVisualState` as the only
state contract, so the entry never infers behavior from translated labels or introduces persisted state.

- Pending and working Runs show a compact Material progress ring around their state icon.
- Attention, success, failure, and stopped states use the corresponding icon and semantic color.
- `AnimatedContent` transitions between states, while `animateColorAsState` preserves color continuity.
- The entry remains an ordinary `IconButton`; motion follows the system animation-duration scale and has no custom timer.
- Each state exposes a localized accessibility description that also communicates the open-details action.

The latest persisted Run drives the entry, while the existing active Run card remains responsible for detailed live
activity. This preserves terminal feedback after the active card exits without permanently occupying chat content.

## Data Flow

`AgentRunVM.latestRun` -> `AgentRunEntity.toPresentation()` -> `ChatPageContent` -> `TopBar` -> `AgentRunEntry`.

The click action stays bound to `presentation.runId`, so a visual state update cannot redirect the user to a replacement
Run. No repository, cancellation, approval, or execution semantics change.

## Verification

- Compose UI test transitions the entry from working to succeeded and checks progress, accessibility, and click behavior.
- Compile Android test sources and the debug app.
- Re-run focused Agent presentation tests and inspect the diff for identity binding and unrelated files.
