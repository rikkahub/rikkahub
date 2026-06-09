# Voice Agent E2E Runbook Determinism Design

## Goal

Make the live Voice Agent Hermes/Gbrain E2E runbook deterministic and diagnosable enough for repeated local use, while
keeping normal product routing and voice-agent behavior unchanged.

The live run on June 9, 2026 showed that the pipeline can pass end to end, but also exposed two testing reliability
problems:

- The default generated Flite `kal` voice was transcribed poorly by Gemini Live, causing missing `ask_hermes` tool calls.
- The E2E runner's cleanup path invoked `ACTION_END` through `am start-foreground-service`, and the app service could hit
  Android's foreground-service deadline before logging the normal end marker.

## Scope

In scope:

- Update the shell E2E runner, its fake-ADB harness test, and the live E2E runbook.
- Make generated PCM use a clearer deterministic default prompt and Flite voice.
- Improve failure diagnostics when Gemini does not emit the expected `ask_hermes` tool call.
- Write manual-review artifacts and the report before cleanup can erase or invalidate them.
- Report pipeline result separately from cleanup result.
- Make the app's existing `ACTION_END` service command safe when invoked as a foreground service for test cleanup.

Out of scope:

- No change to normal voice-agent routing, Gemini system instructions, tool selection policy, Hermes behavior, provider
  configuration, or UI behavior.
- No CI integration for the live credentialed device-backed test.
- No broad refactor of `VoiceAgentCallService` or the voice-agent runtime.

## Current Behavior

`scripts/voice-agent-hermes-gbrain-e2e.sh` currently:

1. Generates PCM with `ffmpeg` + Flite voice `kal` when `VOICE_AGENT_E2E_PCM_PATH` is unset.
2. Uses this default prompt:
   ```text
   Please ask Hermes if he is connected to G-Brain. Please answer with yes or no.
   ```
3. Starts `VoiceAgentCallService`, injects the PCM prompt, and waits for the live pipeline markers.
4. In manual-review mode, ends the service before pulling the Hermes answer and writing the report.
5. Uses `am start-foreground-service ... ACTION_END` for normal and trap cleanup.

This means a bad ASR transcription usually appears only as:

```text
Missing marker after 240s: Gemini ask_hermes tool call received
```

It also means a passed pipeline can still exit with failure if the cleanup `ACTION_END` invocation does not log
`VoiceAgentCallService.*end completed conversationId=` quickly enough.

## Desired Runner Behavior

The runner should make the run phases explicit:

```text
setup -> start -> inject -> pipeline markers -> artifacts/report -> cleanup -> final summary
```

Default generated audio:

- Default prompt:
  ```text
  Ask Hermes. Are you connected to G Brain? Answer yes or no.
  ```
- Default Flite voice: `slt`.
- Add `VOICE_AGENT_E2E_FLITE_VOICE`, defaulting to `slt`.
- Keep accepting `VOICE_AGENT_E2E_PCM_PATH` for supplied PCM.
- Preserve the source prompt text in `generated-prompt.txt` when text is known, including supplied-PCM runs with
  `VOICE_AGENT_E2E_PROMPT_TEXT`.

Pipeline diagnostics:

- Keep the existing marker waits for setup, debug injection, tool call, Hermes hash, tool response, output audio,
  playback queue, and playback write.
- If the `ask_hermes` tool-call marker is missing, pull bounded E2E artifacts when they are available:
  - `input-transcript.txt`
  - `output-transcript.txt`
  - `hermes-call.txt`
- Print a short diagnostic without dumping unrelated app data:
  ```text
  Missing marker: Gemini ask_hermes tool call received
  Gemini understood from voice: <transcript or missing>
  Gemini response to user: <transcript or missing>
  Hermes call: <tool call or missing>
  ```
- Continue to treat missing required markers as pipeline failure.

Manual-review artifact order:

- After all pipeline markers pass in manual mode, pull the manual Hermes answer and write the report before attempting
  service cleanup.
- Do not broaden artifact extraction beyond the existing allowlisted app-private files.
- Keep report and answer files mode `0600`.
- Keep temporary report files cleaned up.

Final result reporting:

- Track pipeline and cleanup independently.
- Print a final summary that distinguishes them, for example:
  ```text
  PIPELINE: passed
  CLEANUP: failed - service end marker not observed
  ```
- If the pipeline fails, exit nonzero.
- If the pipeline passes but cleanup fails, exit nonzero, but preserve and print the pipeline pass and report paths so
  the operator can understand what happened.
- If both pass, exit zero.

## Service END Contract

`VoiceAgentCallService` already supports `ACTION_END`, and the runner invokes it via:

```bash
adb shell am start-foreground-service -n "$SERVICE_COMPONENT" -a "$CALL_END_ACTION"
```

Android treats this as a foreground-service start. If the service is created or restarted for this command, it must call
`startForeground()` promptly. Current `ACTION_END` handling calls `endCall()` directly and may drain asynchronously
without first establishing foreground state.

The service change should be narrow:

- Keep `ACTION_START` behavior unchanged.
- For `ACTION_END`, make the service safe when invoked through `start-foreground-service`.
- Before async drain work, call `startForegroundFor(...)` with:
  - the active conversation id when one is available, or
  - the stable placeholder id `ending` when no active conversation id is available.
- Preserve the existing `end completed conversationId=...` log marker.
- Keep repeated END commands idempotent.
- Keep `stopForeground(STOP_FOREGROUND_REMOVE)` and `stopSelf()` after drain completes.

This is a service-command robustness fix for existing notification/ADB/test cleanup paths, not a change to normal voice
agent decision-making.

## Harness Tests

Update `scripts/test-voice-agent-hermes-gbrain-e2e.sh` to cover:

- Generated PCM defaults to prompt `Ask Hermes. Are you connected to G Brain? Answer yes or no.` and voice `slt`.
- `VOICE_AGENT_E2E_FLITE_VOICE` overrides the Flite voice in the `ffmpeg` filter.
- Manual mode still enables raw E2E artifacts only when expected.
- Pipeline pass with cleanup marker failure:
  - pulls the manual answer and writes the report before cleanup,
  - prints `PIPELINE: passed`,
  - prints `CLEANUP: failed`,
  - exits nonzero.
- Missing `toolCall` with fake input/output artifacts:
  - prints the missing marker,
  - prints the bounded input transcript,
  - prints the bounded output transcript,
  - does not attempt any database fallback.
- Strict mode still does not enable raw manual artifacts or write a report.

The fake `ffmpeg` helper should parse the selected voice instead of hardcoding `kal`, while still validating argument
shape and safe textfile handling.

## JVM Tests

Add or update focused JVM tests for the service END helper behavior if a small testable seam exists or can be extracted.
The intended seam is a pure helper that chooses the foreground label/state for an END command:

- active conversation id present -> use that id.
- no active conversation id -> use the placeholder id `ending`.

Avoid Android instrumentation tests for this change. If the service behavior cannot be tested directly in JVM without
heavy Android scaffolding, keep the Kotlin change minimal and cover the contract through shell harness behavior plus the
small extracted helper.

## Runbook Updates

Update `docs/voice-agent-hermes-gbrain-live-e2e.md` with:

- The new default prompt.
- `VOICE_AGENT_E2E_FLITE_VOICE`.
- A short note that `slt` is the default because the previous `kal` voice produced unreliable ASR in live testing.
- The distinction between pipeline result and cleanup result.
- Where transcript diagnostics appear when the tool-call marker is missing.
- The fact that manual-review reports are written before cleanup is attempted.

## Validation

Implementation should be verified with:

```bash
bash -n scripts/voice-agent-hermes-gbrain-e2e.sh scripts/test-voice-agent-hermes-gbrain-e2e.sh
scripts/test-voice-agent-hermes-gbrain-e2e.sh
./gradlew :app:testDebugUnitTest --tests '*VoiceAgent*'
```

If a live device is available, a final manual run should use the generated default prompt and verify that the script
prints separate pipeline and cleanup results.

## Privacy And Safety

- Do not commit generated PCM, prompt artifacts, logcat output, reports, raw Hermes answers, credentials, or private
  Gbrain facts.
- Do not add database fallback extraction to the shell runner.
- Keep all local manual-review artifacts under `build/voice-agent-e2e/` or caller-selected local paths with restrictive
  permissions.
