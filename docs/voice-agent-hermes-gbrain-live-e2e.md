# Voice Agent Hermes/Gbrain Live E2E

This runbook verifies the real Voice Agent pipeline:

1. Android app starts Gemini Live.
2. Gemini calls `ask_hermes`.
3. The app calls Hermes/MS-agent.
4. Hermes retrieves an existing private Gbrain fact.
5. The app hashes the Hermes response inside the app process.
6. The shell script matches the emitted actual hash to the expected SHA-256 value.
7. The app sends the tool response back to Gemini.
8. Gemini returns output audio.
9. Android playback queues and writes the audio.

This is a live, credentialed, device-backed check. It is not part of CI.
`scripts/test-voice-agent-hermes-gbrain-e2e.sh` only tests the shell harness with fake ADB; it does not replace the live
device-backed verification. Use the live script below when asked to run the Voice Agent Hermes/Gbrain E2E.

Before running this script, the installed Android app must already be configured with the Hermes/MS-agent provider API
key and any Cloudflare Access headers required by the Hermes endpoint. The script validates that installed-app
configuration through the live call. It does not build or install an APK, does not write provider secrets into generated
constants or packaged artifacts, and does not create or update provider settings.

## Secret Inputs

Set these values in your shell or source them from a local file outside the repository:

| Variable | Notes |
| --- | --- |
| `VOICE_AGENT_E2E_EXPECTED_HASH` | SHA-256 hex digest of the normalized expected Hermes answer. Required outside manual review mode. |
| `VOICE_AGENT_E2E_PCM_PATH` | Optional absolute path to a private PCM prompt file. If unset, the script generates PCM from `VOICE_AGENT_E2E_PROMPT_TEXT`. |
| `VOICE_AGENT_E2E_PROMPT_TEXT` | Optional text used to generate PCM when `VOICE_AGENT_E2E_PCM_PATH` is unset. Defaults to asking Hermes whether he is connected to G-Brain. |
| `VOICE_AGENT_E2E_GENERATED_PCM_PATH` | Optional generated PCM output path. Defaults to `build/voice-agent-e2e/generated-prompt.pcm`. |
| `VOICE_AGENT_E2E_REPORT_PATH` | Optional report output path. Defaults to `build/voice-agent-e2e/report.txt`. |
| `VOICE_AGENT_E2E_CONVERSATION_ID` | Existing app conversation id used to start the Voice Agent service. |
| `VOICE_AGENT_E2E_MANUAL_REVIEW` | Optional. Set to `1` to accept any Hermes response hash and write the raw Hermes answer to a private local artifact for manual review. |
| `VOICE_AGENT_E2E_MANUAL_REVIEW_ANSWER_PATH` | Optional manual-review output path. Defaults to `build/voice-agent-e2e/manual-hermes-answer.txt`. |

If the Android device is connected through a remote ADB server, point `adb` at that server before running:

```bash
export ADB_SERVER_SOCKET='tcp:<adb-host>:5037'
```

When more than one authorized device is visible, set the device serial explicitly:

```bash
export VOICE_AGENT_E2E_SERIAL='<adb-device-serial>'
```

Confirm the ADB server and serial against the current operator inventory before running. The selected device must
already have the target package installed and configured for the Hermes/MS-agent provider.

Do not commit credentials, local secret-loading files, the private Gbrain question, the raw expected answer, the PCM
prompt, or logcat artifacts.

Important: this script uses the app already installed on the selected device. Treat that installed app and device state
as credential/private-data-bearing because provider keys and access headers are configured in the app before the run.
The script copies the private PCM prompt first to a public temporary device path, then into app-private files. On the
normal path it removes the public temporary copy immediately after the app-private copy succeeds; the exit trap also
removes any remaining public temporary copy and the app-private copy. It does not clear app provider settings or other
app data. If the device is shared, clear app data, uninstall the app, or manually remove the configured provider
credentials after the run.

## Preparing The Expected Hash

Prepare the expected answer locally without writing it into this repository.

1. Start with the exact answer Hermes should return.
2. Normalize it by trimming leading/trailing whitespace and collapsing every whitespace run to one ASCII space.
3. Hash the normalized UTF-8 text with SHA-256.
4. Use only the hex digest as `VOICE_AGENT_E2E_EXPECTED_HASH`.

Use a high-entropy, unpredictable private fact for this test. A SHA-256 digest of a short, common, or guessable answer
can be brute-forced offline from the shell environment, command output, or logcat artifact. Low-entropy private facts are
not safe for this verification even though the raw answer is never logged.

For example, if the harmless expected text were two words separated by arbitrary whitespace, the normalized input to the
hash function would be `alpha beta`. Do not paste the private expected answer into the runbook, scripts, committed test
data, shell history, or shared logs.

## Preparing The PCM Prompt

If `VOICE_AGENT_E2E_PCM_PATH` is unset, the script generates signed 16-bit little-endian mono PCM at 16 kHz from
`VOICE_AGENT_E2E_PROMPT_TEXT` using local `ffmpeg` with `flite`.

The default generated prompt is:

```text
Please ask Hermes if he is connected to G-Brain. Please answer with yes or no.
```

Set `VOICE_AGENT_E2E_PROMPT_TEXT` to change one string and regenerate the PCM for a different question.

If `VOICE_AGENT_E2E_PCM_PATH` is set, it must point to signed 16-bit little-endian mono PCM at 16 kHz. Keep supplied PCM
files outside the repository.

Do not commit generated or supplied PCM files, generated prompt text, or the real spoken prompt text.

## Running

From the repository root:

```bash
scripts/voice-agent-hermes-gbrain-e2e.sh
```

Strict mode requires `VOICE_AGENT_E2E_EXPECTED_HASH` and `VOICE_AGENT_E2E_CONVERSATION_ID`; manual review mode requires
the conversation id. If `VOICE_AGENT_E2E_PCM_PATH` is unset, the script also requires local `ffmpeg` with `flite` so it
can generate PCM from `VOICE_AGENT_E2E_PROMPT_TEXT`. The script verifies the expected hash format in strict mode,
verifies the PCM file exists after generation or explicit selection, lowercases the expected hash for marker matching,
checks ADB readiness, checks that the target package is already installed, and writes a local scoped log to
`build/voice-agent-e2e/logcat.txt`.

Before running, verify that the selected ADB socket and serial still refer to the intended operator device. After a run,
do not distribute device state or logs because this run exercises configured provider credentials and writes private E2E
data into app storage while it is active.

The current script behavior is:

1. Generates PCM from `VOICE_AGENT_E2E_PROMPT_TEXT` when `VOICE_AGENT_E2E_PCM_PATH` is unset.
2. Checks ADB readiness for the selected device.
3. Verifies the configured package is already installed.
4. Clears logcat and starts scoped log capture.
5. Copies the private PCM prompt to `/data/local/tmp/rikkahub-voice-agent-e2e-prompt.pcm`.
6. Copies that temporary PCM into app-private files at `files/voice-e2e/prompt.pcm` with `run-as`.
7. Removes the public temporary PCM after the app-private copy succeeds.
8. Starts the Voice Agent foreground service for `VOICE_AGENT_E2E_CONVERSATION_ID`.
9. Waits for Gemini setup, injects the app-private PCM prompt in chunks, then waits for the E2E markers.
10. In strict mode, matches the app-emitted `actualHash` to `VOICE_AGENT_E2E_EXPECTED_HASH` in the shell.
11. Checks forbidden markers during each wait and again at the end, so auth, crash, hash mismatch, and playback write
   failures fail fast.
12. On exit, removes any remaining public temporary PCM, ends the foreground service, waits for the service end marker,
    removes the app-private PCM, clears app-private text artifacts under `no_backup/voice-e2e/`, and then stops log
    capture. These trap cleanup actions are best-effort; the service end marker may appear in the scoped log.

## Manual Review Mode

Manual review mode is an explicit fallback for live voice/ASR cases where Gemini reliably calls Hermes but the spoken
private label is transcribed differently across runs, making the expected hash unstable.

Run it with:

```bash
VOICE_AGENT_E2E_MANUAL_REVIEW=1 scripts/voice-agent-hermes-gbrain-e2e.sh
```

Manual mode still requires the real pipeline markers: Gemini setup, debug PCM injection, `ask_hermes` tool call, Hermes
response hash emission, Gemini tool response, Gemini output audio, and Android playback queue/write. The difference is
that the script waits for any Hermes response hash instead of requiring it to equal `VOICE_AGENT_E2E_EXPECTED_HASH`.
After the playback markers pass, it pulls the app-private Hermes answer artifact with `run-as` and writes it to:

```text
build/voice-agent-e2e/manual-hermes-answer.txt
```

The app-private source artifact is `no_backup/voice-e2e/hermes-answer.txt`. The script clears stale source artifacts
before the run and again after ending the service during cleanup. If the installed app does not write that artifact, the
run fails instead of pulling broader private app data.

The local answer file is created with `0600` permissions and must stay local. The script prints the artifact path, not
the raw answer. Read that file privately and decide whether the run passed. If the answer is correct, the live pipeline
worked; if it is wrong, treat the run as failed even though the script reached the manual review gate.

## E2E Report

Manual review mode writes `build/voice-agent-e2e/report.txt` by default. The report includes the text used to generate
voice, what Gemini understood from the injected voice, the raw Hermes tool call, Hermes timing/hash detail, Hermes's
answer, and Gemini's text response to the user when output transcription is available.

The script prints the report path but does not print report contents. Treat the report as local/private because it may
contain raw prompts, transcripts, and Hermes answers.

## Pass Criteria

The script passes only when all of these markers appear in the same run:

- Gemini setup completes.
- Debug PCM injection delivered.
- Gemini emits tool call.
- App emits `hermes_tool_response_hash` with an `actualHash` equal to `VOICE_AGENT_E2E_EXPECTED_HASH`.
- App sends tool response back to Gemini.
- Gemini emits output audio.
- Android playback queues audio.
- Android playback writes audio.

In manual review mode, the script reaches a manual review gate instead of declaring an automatic pass. The operator must
read the private answer artifact and decide pass/fail.

## Failure Criteria

The script fails when any of these markers or conditions appear:

- `Voice Lab request failed 403`
- Cloudflare/auth access markers such as Cloudflare error or access denied content.
- `FATAL EXCEPTION`
- playback write failure
- installed package missing
- missing required input, invalid expected hash, or missing PCM file
- ADB readiness or command timeout failure
- PCM copy failure
- required marker timeout
- any Hermes response hash whose `actualHash` differs from `VOICE_AGENT_E2E_EXPECTED_HASH`

In manual review mode, a hash mismatch is not a failure by itself. The run still fails on missing pipeline markers,
auth errors, crashes, playback write failures, or inability to extract the completed `ask_hermes` answer.

## Safe Artifacts

`build/voice-agent-e2e/logcat.txt` is a local artifact and must not be committed. It is scoped to app-relevant tags:
`VoiceAgentCallService`, `VoiceAgentCallSession`, `VoiceAgentGemini`, `VoiceAgentE2E`, `VoiceAudioDebugInjection`,
`AndroidVoiceAudioEngine`, and `AndroidRuntime`, but still treat it as local only.

`build/voice-agent-e2e/manual-hermes-answer.txt` is created only in manual review mode. It contains the raw Hermes answer
and must not be committed, pasted into shared logs, or distributed.

`build/voice-agent-e2e/report.txt` is created only in manual review mode. It contains raw prompt, transcript, Hermes
call, Hermes answer, and Gemini response text and must stay local/private.

`build/voice-agent-e2e/generated-prompt.pcm` and `build/voice-agent-e2e/generated-prompt.txt` are created when the script
generates PCM from text. They must not be committed, pasted into shared logs, or distributed.

The E2E hash diagnostic log only includes call id, raw and normalized character counts, SHA-256 hash, and timing. It
does not log the Hermes answer, but the hash can still reveal low-entropy answers through offline guessing.

The Hermes failure E2E log preserves bounded failure summaries such as `Voice Lab request failed 403` and redacts or
drops response previews. Do not paste unredacted runtime logs into issues, commits, docs, or chat.

Post-run script cleanup removes the public temporary PCM and app-private PCM. It does not remove the installed app, clear
app data, or clear provider/settings that were configured before the run. If the device should not retain those
credentials, clear app data, uninstall the app, or manually remove the configured provider credentials.

## Security And Privacy

Do not commit or paste any of the following:

- private Gbrain question
- raw expected answer
- real credentials
- private PCM prompt or PCM file
- logcat artifact
- unredacted log snippets

If a local run fails, share only the bounded marker name, the script step that failed, and any sanitized command output
that does not include secrets or private prompt/answer content.
