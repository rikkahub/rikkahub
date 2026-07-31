# Agent Run Detail Control Design

## Problem

Opening Run details covers the floating active-Run card. The detail header is static text, so the user loses both the
live state signal and the only visible stop control while inspecting telemetry.

## Options considered

1. Pass every selected Run ID to the existing stop API. This is unsafe for child Runs because they do not own the root
   conversation generation lease.
2. Show a "stop all" action in every nested detail. This is technically possible but makes a child-level action affect
   its parent without a clear ownership boundary.
3. Expose stop only when the selected Run is the active root Run. This preserves identity fencing and matches the
   existing generation lifecycle. This is the selected option.

## Design

A pure `detailStopTarget` function returns a Run ID only when the selected detail identity exactly matches the current
active root identity. `ChatPage` freezes that returned ID in the click callback. Child, terminal, stale, and missing Run
details receive no stop action. The existing ViewModel compare-and-set guard remains the duplicate-click authority.

The static detail title becomes a live header. It renders the persisted visual state, status, elapsed duration, and the
current step or waiting reason. Active states use the existing native progress indicator and semantic colors. The stop
button transitions to the same disabled progress feedback used by the floating card. `AnimatedContent`,
`AnimatedVisibility`, color animation, and content-size animation use the Material motion scheme and respect the system
duration scale.

No service contract, persistence schema, child cancellation semantics, or error payload changes are introduced.

## Verification

Unit tests prove root-only stop targeting. Compose UI tests prove a running root exposes stop/progress, stopping
disables duplicate interaction, and a terminal header removes active progress/control. Existing detail navigation,
presentation, and Android test-source compilation remain regression gates.
