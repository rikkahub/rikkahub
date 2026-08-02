# LiveKit Fixture Sample-Rate Design

**Status:** Approved by the user's standing instruction to continue until the transcript boundary is fixed without additional approval gates.

## Problem

The Stage 1 fixture source is canonical 16-bit, 16 kHz, mono PCM. It emits 3,200-byte chunks every 100 ms. The original LiveKit capture post-processor copied those bytes directly into the SDK capture buffer and ignored the sample rate supplied by `AudioProcessorInterface`.

When WebRTC captures at 48 kHz, the original processor consumes fixture bytes three times faster than the fixture produces them. Each 100 ms source chunk becomes roughly 33 ms of accelerated audio followed by zero fill.

The first physical-device transaction after correcting that pacing still produced no final user transcript. Inspection of the exact LiveKit 2.27.0 and prefixed WebRTC 144.7559.09 sources then exposed a second, more fundamental contract: the post-processing `ByteBuffer` is a native view over `float*`, with one normalized 32-bit float per capture frame. It is not a PCM16 byte buffer. Writing little-endian PCM16 into that storage creates invalid capture samples even when pacing is correct.

## Decision

Keep the canonical fixture and its real-time 16 kHz pacing unchanged. Convert it inside `LiveKitInjectedPcmProcessor` to the SDK callback's actual normalized-float format and negotiated sample rate.

The processor will:

- treat fixture samples as little-endian PCM16 at 16 kHz mono;
- retain the negotiated output sample rate and validate the reported channel count from `initializeAudioProcessing`;
- resample with a deterministic streaming phase accumulator, preserving phase across SDK buffers;
- normalize each signed PCM16 sample by 32,768 and write it as a native-endian 32-bit float;
- write one float per callback frame because the WebRTC bridge exposes the first planar capture channel, not an interleaved channel buffer;
- zero-fill with `0.0f` when source audio is not currently queued;
- reset resampling state when the SDK capture format resets or automation ownership ends.

The source queue remains the owner of fixture ordering and automation-run isolation. Production microphone capture remains untouched because the post-processor is enabled only for an active LiveKit automation fixture.

## Dependency Evidence

The callback representation is established by the exact dependency revisions used by the app:

- LiveKit Android 2.27.0 forwards the callback `ByteBuffer` unchanged through `CustomAudioProcessingFactory.AudioProcessingBridge`.
- Prefixed WebRTC 144.7559.09 corresponds to `webrtc-sdk/webrtc` commit `b1800a61db8320af5c14456c13622d8b85b1ed39`.
- At that revision, `ExternalAudioProcessingJni::Process` creates the Java buffer from a `float*` with `buffer_size * sizeof(float)`, and `ExternalAudioProcessor::Process` passes `audio->channels()[0]`.

## Rejected Alternatives

### Pre-convert fixture files to 48 kHz

Rejected because it couples harness input to one SDK capture format and prevents Direct Gemini and LiveKit from consuming the same source evidence.

### Force the WebRTC capture device to 16 kHz

Rejected because it changes production microphone behavior and platform audio configuration to solve an automation-only conversion problem.

### Treat the callback buffer as interleaved PCM16

Rejected because the exact dependency source constructs the Java buffer from `float*` and sizes it as `buffer_size * sizeof(float)`. Channel duplication would also be wrong because the native bridge passes the first planar channel only.

### Add telemetry before correcting the float contract

Rejected for now. The post-pacing physical result and exact dependency source localize a deterministic representation defect without requiring another device call. New telemetry remains justified only if the float-corrected path is still undecidable in a physical transaction.

## Testing

The regression suite first demonstrates the representation bug by asserting that signed PCM16 fixture samples become normalized native-endian floats. It separately initializes the processor at 48 kHz and asserts that 16 kHz source samples expand to the correct number of callback frames rather than being consumed one-for-one.

Focused tests will cover:

- signed PCM16-to-float normalization in a direct native-order buffer;
- 16 kHz mono pass-through as normalized floats;
- 16-to-48 kHz streaming conversion across multiple SDK buffers;
- one planar float stream when the capture format reports multiple channels;
- zero fill before fixture data and after exhaustion;
- phase reset on capture-format reset and automation deactivation.

After unit and existing shell-harness verification, one debug APK will be built and installed through the managed physical-phone lane. Exactly one clean call transaction will reuse the two-source transcript classifier. A final user transcript advances the investigation to `ask_hermes`; covered worker logs without the marker remain a failure at the same boundary.
