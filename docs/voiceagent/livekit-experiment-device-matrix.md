# LiveKit experimental voice device matrix

Status: planned only. This document specifies the debug-only digital-path
matrix consumed by Agora2's unattended Stage 1 coordinator; it is not evidence
that a physical run occurred.

The authoritative operator contract is in Agora2:

- `docs/superpowers/specs/2026-07-25-stage1-unattended-verification-design.md`
- `docs/superpowers/plans/2026-07-25-stage1-unattended-verification.md`
- `scripts/livekit-experiment/run-unattended-stage1.sh`

The host coordinator runs this matrix on the fixed physical device
`RZCX71NXRPB`. It uses the debug harness's deterministic injected PCM,
structured digital-path events, and app/device readback. It does not assess
acoustic microphone or speaker quality, loudness, echo, noise cancellation,
intelligibility, or any route outside speaker and earpiece.

## Fixed paired matrix

The matrix has exactly two transports: `direct_gemini` and
`livekit_experimental`. The coordinator first performs a Direct canary, runs
all seven Direct rows, validates the Direct checkpoint, then performs a
LiveKit canary and runs the same seven rows.

The aggregate `runs[]` contains exactly 14 matrix runs. Canaries are private
validation gates; they are never added to aggregate `runs[]` or published as
matrix evidence.

| Comparison ID | Network | Route | App state | Lifecycle | Transports | Target |
|---|---|---|---|---|---|---:|
| `wifi-speaker-fg-fast-steady` | Wi-Fi | speaker | foreground | steady | `direct_gemini` + `livekit_experimental` | 20 s |
| `wifi-earpiece-bg-minute-steady` | Wi-Fi | earpiece | background | steady | `direct_gemini` + `livekit_experimental` | 60 s |
| `wifi-speaker-bg-minute-interrupt` | Wi-Fi | speaker | background | interrupt output | `direct_gemini` + `livekit_experimental` | 60 s |
| `cell-speaker-fg-fast-steady` | cellular | speaker | foreground | steady | `direct_gemini` + `livekit_experimental` | 20 s |
| `cell-earpiece-bg-minute-interrupt` | cellular | earpiece | background | interrupt output | `direct_gemini` + `livekit_experimental` | 60 s |
| `handover-speaker-fg-multi-reconnect` | Wi-Fi → cellular → Wi-Fi | speaker | foreground | reconnect | `direct_gemini` + `livekit_experimental` | 180 s |
| `handover-earpiece-bg-minute-reconnect` | Wi-Fi → cellular → Wi-Fi | earpiece | background | reconnect | `direct_gemini` + `livekit_experimental` | 60 s |

Paired runs bind the same device, source, configuration, deployment, fixtures,
account state, and comparison metadata. Direct and LiveKit use separately built
APKs; those APKs differ only through the expected transport-flag dependency,
bytecode, and hash consequences.

## Machine-observed scope

The debug harness proves requested route, lifecycle, network state,
interruption, reconnect, handover, deterministic input consumption, and digital
output draining through its allowlisted event artifact plus Android readback.
A requested state is not inferred from command success. An unavailable or
unproven state invalidates the run; the coordinator does not repeat a valid
slow or failed run to improve a result.

All evidence uses sanitized hashes and numeric/enum values. Do not retain
prompts, answers, transcripts, credentials, tokens, raw device/account
identifiers, or raw correlation payloads.

## Result handling

The Agora2 coordinator owns sidecar validation, aggregate validation, and the
machine reviewer identity `automated-stage1-v1`. It writes `pass`, `stop`, or
`no-decision` according to the authoritative evidence contract. A nonzero or
interrupted deployment or APK installation preserves its mutation marker and
stops without an automatic retry. Reconcile the exact mutation outcome before
another run is authorized.
