# LiveKit Fixture Sample-Rate Design

**Status:** Approved by the user's standing instruction to continue until the transcript boundary is fixed without additional approval gates.

## Problem

The Stage 1 fixture source is canonical 16-bit, 16 kHz, mono PCM. It emits 3,200-byte chunks every 100 ms. The LiveKit capture post-processor currently copies those bytes directly into the SDK capture buffer and ignores the sample rate and channel count supplied by `AudioProcessorInterface`.

When WebRTC captures at 48 kHz, the processor consumes fixture bytes three times faster than the fixture produces them. Each 100 ms source chunk becomes roughly 33 ms of accelerated audio followed by zero fill. A valid physical-device transaction consequently reaches an active worker session but does not form a final user transcript.

## Decision

Keep the canonical fixture and its real-time 16 kHz pacing unchanged. Convert it inside `LiveKitInjectedPcmProcessor` to the SDK-negotiated capture format.

The processor will:

- treat fixture samples as little-endian PCM16 at 16 kHz mono;
- retain the negotiated output sample rate and channel count from `initializeAudioProcessing`;
- resample with a deterministic streaming phase accumulator, preserving phase across SDK buffers;
- duplicate each mono sample across negotiated output channels;
- zero-fill when source audio is not currently queued;
- reset resampling state when the SDK capture format resets or automation ownership ends.

The source queue remains the owner of fixture ordering and automation-run isolation. Production microphone capture remains untouched because the post-processor is enabled only for an active LiveKit automation fixture.

## Rejected Alternatives

### Pre-convert fixture files to 48 kHz

Rejected because it couples harness input to one SDK capture format and prevents Direct Gemini and LiveKit from consuming the same source evidence.

### Force the WebRTC capture device to 16 kHz

Rejected because it changes production microphone behavior and platform audio configuration to solve an automation-only conversion problem.

### Add telemetry before fixing conversion

Rejected for now. Existing evidence already shows the fixture source completed while the transcript did not form, and source inspection proves the capture processor ignores the negotiated format. New telemetry remains justified only if the corrected path is still undecidable in a physical transaction.

## Testing

The regression suite will first demonstrate the bug by initializing the processor at 48 kHz and asserting that 16 kHz source samples expand to the correct number of output frames rather than being consumed one-for-one.

Focused tests will cover:

- 16 kHz mono pass-through;
- 16-to-48 kHz streaming conversion across multiple SDK buffers;
- channel duplication when more than one output channel is negotiated;
- zero fill before fixture data and after exhaustion;
- phase reset on capture-format reset and automation deactivation.

After unit and existing shell-harness verification, one debug APK will be built and installed through the managed physical-phone lane. Exactly one clean call transaction will reuse the two-source transcript classifier. A final user transcript advances the investigation to `ask_hermes`; covered worker logs without the marker remain a failure at the same boundary.
