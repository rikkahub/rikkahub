# Voice Agent Hermes Queue Live E2E

This runbook verifies the live queued Hermes voice flow:

1. The script converts two short text prompts to generated voice.
2. Each generated voice prompt is injected as a separate turn into the installed Android app.
3. Gemini calls `ask_hermes` at least two times across those turns.
4. RikkaHub submits background Hermes jobs through Voice Lab.
5. Gemini receives queued acknowledgements quickly.
6. Hermes finishes the jobs later.
7. RikkaHub sends late Gemini text turns with completed answers.
8. Gemini speaks or explains at least one completed answer.
9. The script writes a private local report for manual review.

This is a live, credentialed, device-backed check. It is not part of CI.

## When To Use This

Use this queue E2E when validating the queued Hermes job behavior. Use
`scripts/voice-agent-hermes-gbrain-e2e.sh` when validating the simpler
single-request baseline.

## Secret Inputs

The installed Android app must already be the latest queue-capable debug build
and must already be configured with the Voice Lab base URL, Gemini voice model,
mobile Hermes credentials, Hermes profile settings, and any Cloudflare Access
headers required by the Voice Lab or Hermes endpoint.

Set these values in your shell or source them from a local file outside the
repository:

| Variable | Notes |
| --- | --- |
| `VOICE_AGENT_E2E_CONVERSATION_ID` | Existing app conversation id used to start the Voice Agent service. Required. |
| `VOICE_AGENT_QUEUE_E2E_PROMPT_TEXT_1`, `VOICE_AGENT_QUEUE_E2E_PROMPT_TEXT_2`, ... | Optional generated prompt text per injected voice turn. Set entries `1..VOICE_AGENT_QUEUE_E2E_EXPECTED_COMPLETIONS` together when overriding the default multi-turn prompts. |
| `VOICE_AGENT_QUEUE_E2E_PROMPT_TEXT` | Optional legacy single-turn text used to generate PCM. This is mainly useful with `VOICE_AGENT_QUEUE_E2E_EXPECTED_COMPLETIONS=1`. |
| `VOICE_AGENT_QUEUE_E2E_PCM_PATH` | Optional signed 16-bit little-endian mono PCM at 16 kHz. |
| `VOICE_AGENT_QUEUE_E2E_FLITE_VOICE` | Optional Flite voice for generated PCM. Defaults to `slt`. |
| `VOICE_AGENT_QUEUE_E2E_EXPECTED_COMPLETIONS` | Optional completed Hermes jobs required for pass. Defaults to `2`. |
| `VOICE_AGENT_QUEUE_E2E_LOG_DIR` | Optional local report/log directory. Defaults to `build/voice-agent-queue-e2e`. |
| `VOICE_AGENT_QUEUE_E2E_TOOL_CALL_TIMEOUT_SECONDS` | Optional timeout for tool-call and queue creation markers. |
| `VOICE_AGENT_QUEUE_E2E_COMPLETION_TIMEOUT_SECONDS` | Optional timeout for Hermes job completion markers. |
| `VOICE_AGENT_E2E_SERIAL` | Optional ADB serial. Required when more than one authorized device is visible. |
| `VOICE_AGENT_E2E_PACKAGE` | Optional app package override. Defaults to `me.rerere.rikkahub.debug`. |

If the Android device is connected through a remote ADB server:

```bash
export ADB_SERVER_SOCKET='tcp:<adb-host>:5037'
```

When more than one authorized device is visible:

```bash
export VOICE_AGENT_E2E_SERIAL='<adb-device-serial>'
```

## Install Latest Debug Build

From the RikkaHub repository root:

```bash
ANDROID_HOME=/home/muly/Android/Sdk ANDROID_SDK_ROOT=/home/muly/Android/Sdk ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Keep the configured provider credentials on the phone. Do not clear app data
unless you are ready to reconfigure Voice Lab and Hermes settings.

## Default Generated Prompt

If `VOICE_AGENT_QUEUE_E2E_PCM_PATH` is unset and no prompt override is supplied,
the script generates two separate PCM prompts:

```text
Ask Hermes. Use the ask Hermes tool now. Ask Hermes: Are you connected to G Brain? Answer yes or no.

Ask Hermes. Use the ask Hermes tool now. Ask Hermes: Recall the private queue test fact. Tell me the answer when it is ready.
```

The prompts are separate turns because live generated speech can cause Gemini
to merge multiple questions from one utterance into one `ask_hermes` call.
Override the indexed prompts for real private facts. Do not commit or paste the
real private prompts.

## Running

From the RikkaHub repository root:

```bash
scripts/voice-agent-hermes-queue-e2e.sh
```

From the parent Agora repository root:

```bash
external/rikkahub/scripts/voice-agent-hermes-queue-e2e.sh
```

The script prints preflight details, marker pass/fail lines, artifact paths, and
pipeline/cleanup status. It does not print raw Hermes answers.

## Pass Criteria

The script passes only when all of these conditions are observed in one run:

- Gemini setup completes.
- Debug PCM injection is delivered.
- At least `VOICE_AGENT_QUEUE_E2E_EXPECTED_COMPLETIONS` `ask_hermes` tool calls are received.
- At least that many queued Hermes jobs are created with distinct job ids.
- Queued acknowledgement tool responses are sent.
- Gemini emits output audio after acknowledgements.
- At least that many Hermes jobs complete.
- At least that many Hermes response hashes are emitted.
- At least that many late Gemini text turns are sent.
- Gemini emits output audio after completed Hermes answers are available.
- Android playback queues and writes audio.
- The service end cleanup marker is observed.

After script-level pass, read the private report and confirm the answers are
correct and understandable.

## Failure Criteria

The script fails on:

- missing ADB device or wrong installed package;
- missing Gemini setup, tool-call, queued-job, completion, follow-up, output audio, or playback markers;
- duplicate queued Hermes job ids;
- completed job ids that were not observed as queued jobs;
- fewer completed Hermes jobs than `VOICE_AGENT_QUEUE_E2E_EXPECTED_COMPLETIONS`;
- any `hermes_tool_failed` marker;
- `Voice Lab request failed 403`;
- Cloudflare or access-denied content;
- `Voice Lab request failed 524`, `HTTP 524`, `status=524`, or `code=524`;
- Hermes job polling timeout;
- Hermes job expiration;
- `FATAL EXCEPTION`;
- playback write failure.

## Private Artifacts

The default local artifact directory is:

```text
build/voice-agent-queue-e2e/
```

Important files:

- `logcat.txt`: scoped logcat.
- `generated-prompt.txt`: prompt text used for generated PCM.
- `generated-prompt.pcm`: generated PCM when used.
- `hermes-events.ndjson`: queue event rows pulled from app-private storage. These rows contain ids, status, hashes, timing, and answer sizes, not raw Hermes answers.
- `input-transcript.txt`: what Gemini understood from the injected voice.
- `output-transcript.txt`: Gemini response text when available.
- `hermes-call.txt`: latest Hermes call artifact snapshot.
- `hermes-answer.txt`: latest Hermes answer artifact snapshot.
- `report.txt`: private manual-review report.

Treat these artifacts as local private data. Do not commit them, paste them into
issues, or share unredacted copies.

## Common Diagnosis

If tool calls are missing, inspect `input-transcript.txt` in the private report
to see what Gemini heard from the generated voice.

If queued jobs are missing, inspect scoped logs for `hermes_job_created` and
verify the installed app is the queue-capable build.

If completions are missing, inspect `hermes-events.ndjson` and Voice Lab logs for
job timeout, expiry, or provider errors.

If late answer playback is missing, inspect `hermes_completion_follow_up_sent`,
Gemini output audio markers, and playback markers.

If a real 524 failure appears, the queued path is not being used end to end or
an unexpected synchronous path is still active.

## Privacy Rules

Do not commit or paste:

- real generated prompts;
- PCM files;
- credentials;
- raw Hermes answers;
- private Gbrain facts;
- report files;
- unredacted logcat.
