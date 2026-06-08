# Voice Agent E2E Report Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a live Voice Agent Hermes/Gbrain E2E report flow that starts from one configurable text prompt, generates PCM when needed, captures Gemini/Hermes transcript artifacts, and writes a local report for manual semantic review.

**Architecture:** The Android app will write app-private E2E artifacts through an injected artifact writer owned by `DefaultVoiceAgentCallFactory`, while `VoiceAgentCoordinator` calls that writer when it sees input transcripts, tool calls, Hermes answers, and output transcripts. The shell runner will generate PCM from `VOICE_AGENT_E2E_PROMPT_TEXT` when no PCM path is supplied, run the existing live E2E markers, pull app-private artifacts, parse safe Hermes timing from logcat, and assemble `build/voice-agent-e2e/report.txt`.

**Tech Stack:** Kotlin/JUnit for app-side behavior, Bash shell tests with fake `adb`, Android `run-as`, `ffmpeg` with `flite` for PCM generation, `jq`/`sqlite3` only for legacy fallback extraction.

**Implementation note:** This document is the historical execution plan. The final implementation intentionally stores
raw app E2E artifacts only under `no_backup/voice-e2e/`, keeps source prompt text as a local runner artifact
(`generated-prompt.txt`), and enables raw artifact capture only in manual-review mode. Use
`docs/voice-agent-hermes-gbrain-live-e2e.md` for the current operator runbook.

---

## File Structure

- Modify `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCoordinator.kt`: call artifact writer hooks for input transcript, tool call prompt, Hermes answer, and output transcript.
- Modify `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallSession.kt`: pass an artifact writer object into `VoiceAgentCoordinator`.
- Modify `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallFactory.kt`: create the app-private artifact writer that writes under `context.filesDir/voice-e2e`.
- Modify `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentRuntimeTest.kt`: add unit tests for artifact writer callbacks.
- Modify `scripts/voice-agent-hermes-gbrain-e2e.sh`: add prompt-text PCM generation, source-text handling, artifact cleanup/pull, report generation, and report-path output.
- Modify `scripts/test-voice-agent-hermes-gbrain-e2e.sh`: add fake ADB coverage for generated PCM mode and report assembly.
- Modify `docs/voice-agent-hermes-gbrain-live-e2e.md`: document prompt text, generated PCM, artifact files, and report behavior.

## Task 1: Add App-Side Artifact Writer Contract

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCoordinator.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallSession.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallFactory.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentRuntimeTest.kt`

- [ ] **Step 1: Write the failing coordinator artifact test**

Add this test near the existing Hermes hash/manual review tests in `VoiceAgentRuntimeTest.kt`:

```kotlin
@Test
fun `coordinator writes private e2e artifacts for transcript tool call and Hermes answer`() = runTest {
    val gemini = FakeGeminiLiveVoiceClient()
    val toolApi = FakeVoiceToolApi()
    val artifacts = mutableMapOf<String, String>()
    val coordinator = VoiceAgentCoordinator(
        gemini = gemini,
        toolApi = toolApi,
        audio = FakeVoiceAudioEngine(),
        writeVoiceE2EArtifact = { name, content -> artifacts[name] = content },
        scope = this,
    )

    coordinator.onGeminiEvent(GeminiLiveEvent.InputTranscript("Please ask "))
    coordinator.onGeminiEvent(GeminiLiveEvent.InputTranscript("Hermes."))
    coordinator.onGeminiEvent(
        GeminiLiveEvent.ToolCall(
            callId = "call-report",
            name = "ask_hermes",
            prompt = "Is Hermes connected to G-Brain? Answer yes or no.",
        )
    )
    assertEquals(
        "call-report" to "Is Hermes connected to G-Brain? Answer yes or no.",
        toolApi.awaitRequest("call-report"),
    )
    toolApi.complete(response(callId = "call-report", answer = "Yes.", elapsedMs = 123L))
    coordinator.awaitToolJobsWithTimeout()
    coordinator.onGeminiEvent(GeminiLiveEvent.OutputTranscript("Yes, Hermes is connected."))

    assertEquals("Please ask Hermes.", artifacts["input-transcript.txt"])
    assertEquals("Is Hermes connected to G-Brain? Answer yes or no.", artifacts["hermes-call.txt"])
    assertEquals("Yes.", artifacts["hermes-answer.txt"])
    assertEquals("Yes, Hermes is connected.", artifacts["output-transcript.txt"])
}
```

- [ ] **Step 2: Run the focused failing test**

Run:

```bash
ANDROID_HOME=/home/muly/Android/Sdk ./gradlew :app:testDebugUnitTest --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentRuntimeTest.coordinator writes private e2e artifacts for transcript tool call and Hermes answer'
```

Expected: FAIL at Kotlin compilation because `VoiceAgentCoordinator` does not have a `writeVoiceE2EArtifact` parameter.

- [ ] **Step 3: Replace the narrow answer callback with a general artifact callback**

In `VoiceAgentCoordinator.kt`, change the constructor parameter:

```kotlin
private val writeHermesManualReviewAnswer: (String) -> Unit = {},
```

to:

```kotlin
private val writeVoiceE2EArtifact: (String, String) -> Unit = { _, _ -> },
```

Update `recordHermesToolResponseHash()` to write the answer artifact:

```kotlin
runCatching {
    writeVoiceE2EArtifact("hermes-answer.txt", answer)
}.onFailure { error ->
    val message = error.message ?: error.javaClass.simpleName
    diagnostics.record("voice_e2e_artifact_write_failed", "name=hermes-answer.txt, callId=$callId, message=$message")
}
```

Update `appendInputTranscript()` after `inputTurnTranscript += text`:

```kotlin
writeArtifactSafely(name = "input-transcript.txt", content = inputTurnTranscript)
```

Update `appendOutputTranscript()` after `outputTurnTranscript += text`:

```kotlin
writeArtifactSafely(name = "output-transcript.txt", content = outputTurnTranscript)
```

Update `handleToolCall()` after verifying `call.name == VoiceAgentToolNames.ASK_HERMES` and before `recordHermesToolRequestHash(callId = call.callId, prompt = call.prompt)`:

```kotlin
writeArtifactSafely(name = "hermes-call.txt", content = call.prompt)
```

Add this helper near the existing private diagnostic helpers:

```kotlin
private fun writeArtifactSafely(name: String, content: String, callId: String? = null) {
    runCatching {
        writeVoiceE2EArtifact(name, content)
    }.onFailure { error ->
        val message = error.message ?: error.javaClass.simpleName
        val callDetail = callId?.let { ", callId=$it" } ?: ""
        diagnostics.record("voice_e2e_artifact_write_failed", "name=$name$callDetail, message=$message")
    }
}
```

Then replace the new inline `runCatching` in `recordHermesToolResponseHash()` with:

```kotlin
writeArtifactSafely(name = "hermes-answer.txt", content = answer, callId = callId)
```

- [ ] **Step 4: Thread the general writer through the session**

In `VoiceAgentCallSession.kt`, change the constructor parameter:

```kotlin
writeHermesManualReviewAnswer: (String) -> Unit = {},
```

to:

```kotlin
writeVoiceE2EArtifact: (String, String) -> Unit = { _, _ -> },
```

Change the coordinator construction from:

```kotlin
writeHermesManualReviewAnswer = writeHermesManualReviewAnswer,
```

to:

```kotlin
writeVoiceE2EArtifact = writeVoiceE2EArtifact,
```

- [ ] **Step 5: Implement the app-private file writer**

In `VoiceAgentCallFactory.kt`, replace:

```kotlin
writeHermesManualReviewAnswer = { answer ->
    val directory = File(context.filesDir, "voice-e2e")
    directory.mkdirs()
    File(directory, "hermes-answer.txt").writeText(answer)
},
```

with:

```kotlin
writeVoiceE2EArtifact = { name, content ->
    val directory = File(context.filesDir, "voice-e2e")
    directory.mkdirs()
    File(directory, name).writeText(content)
},
```

- [ ] **Step 6: Update the existing answer writer test**

In `VoiceAgentRuntimeTest.kt`, update the existing test `Hermes response answer writer receives raw answer for private manual review artifact` so the coordinator uses the new callback:

```kotlin
val writtenArtifacts = mutableMapOf<String, String>()
val coordinator = VoiceAgentCoordinator(
    gemini = gemini,
    toolApi = toolApi,
    audio = FakeVoiceAudioEngine(),
    diagnostics = diagnostics,
    writeVoiceE2EArtifact = { name, content -> writtenArtifacts[name] = content },
    scope = this,
)
```

Change the assertion:

```kotlin
assertEquals(listOf("raw private answer"), writtenAnswers)
```

to:

```kotlin
assertEquals("raw private answer", writtenArtifacts["hermes-answer.txt"])
```

- [ ] **Step 7: Run app-side tests**

Run:

```bash
ANDROID_HOME=/home/muly/Android/Sdk ./gradlew :app:testDebugUnitTest --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentRuntimeTest.coordinator writes private e2e artifacts for transcript tool call and Hermes answer' --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentRuntimeTest.Hermes response answer writer receives raw answer for private manual review artifact'
```

Expected: PASS.

- [ ] **Step 8: Commit Task 1**

Run:

```bash
git add app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCoordinator.kt app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallSession.kt app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallFactory.kt app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentRuntimeTest.kt
git commit -m "feat: capture voice e2e artifacts"
```

## Task 2: Generate PCM From One Configurable Text Prompt

**Files:**
- Modify: `scripts/voice-agent-hermes-gbrain-e2e.sh`
- Test: `scripts/test-voice-agent-hermes-gbrain-e2e.sh`

- [ ] **Step 1: Write the failing shell test for generated PCM mode**

In `scripts/test-voice-agent-hermes-gbrain-e2e.sh`, add a fake `ffmpeg` before `write_fake_adb()`:

```bash
write_fake_ffmpeg() {
  cat > "$TMP_DIR/ffmpeg" <<'FAKE_FFMPEG'
#!/usr/bin/env bash
set -euo pipefail

output="${@: -1}"
printf 'generated pcm' > "$output"
printf 'fake ffmpeg generated %s\n' "$output" >&2
FAKE_FFMPEG
  chmod +x "$TMP_DIR/ffmpeg"
}
```

Call it before `write_fake_adb`:

```bash
write_fake_ffmpeg
```

Add a generated-mode run after the current manual-mode assertions:

```bash
generated_log_dir="$TMP_DIR/generated-log"
set +e
generated_output="$(
  PATH="$TMP_DIR:$PATH" \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_E2E_EXPECTED_HASH="$expected_hash" \
  VOICE_AGENT_E2E_PROMPT_TEXT="Please ask Hermes if he is connected to G-Brain. Please answer with yes or no." \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_E2E_LOG_DIR="$generated_log_dir" \
  VOICE_AGENT_E2E_MANUAL_REVIEW=1 \
  VOICE_AGENT_E2E_GEMINI_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_E2E_HERMES_RESPONSE_TIMEOUT_SECONDS=5 \
  "$SCRIPT" 2>&1
)"
generated_status=$?
set -e

if [[ "$generated_status" -ne 0 ]]; then
  printf 'Expected generated PCM mode to pass, got status %s.\n' "$generated_status" >&2
  printf 'Actual output:\n%s\n' "$generated_output" >&2
  exit 1
fi
assert_contains "$generated_output" "Generating PCM prompt from VOICE_AGENT_E2E_PROMPT_TEXT."
assert_contains "$generated_output" "Voice Agent Hermes/Gbrain live E2E reached manual review gate."
assert_file_contains_exactly "$generated_log_dir/generated-prompt.pcm" "generated pcm"
```

- [ ] **Step 2: Run the failing shell test**

Run:

```bash
scripts/test-voice-agent-hermes-gbrain-e2e.sh
```

Expected: FAIL with `Missing required environment variable: VOICE_AGENT_E2E_PCM_PATH`, because the runner does not generate PCM yet.

- [ ] **Step 3: Add prompt-text defaults and generated PCM path**

In `scripts/voice-agent-hermes-gbrain-e2e.sh`, add these constants after `LOG_FILE`:

```bash
DEFAULT_PROMPT_TEXT="Please ask Hermes if he is connected to G-Brain. Please answer with yes or no."
PROMPT_TEXT="${VOICE_AGENT_E2E_PROMPT_TEXT:-$DEFAULT_PROMPT_TEXT}"
GENERATED_PCM_PATH="${VOICE_AGENT_E2E_GENERATED_PCM_PATH:-$LOG_DIR/generated-prompt.pcm}"
```

Add this helper before `cleanup()`:

```bash
generate_pcm_prompt() {
  require_command ffmpeg
  umask 077
  mkdir -p "$LOG_DIR" "$(dirname "$GENERATED_PCM_PATH")"
  local prompt_text_file="$LOG_DIR/generated-prompt.txt"
  printf '%s' "$PROMPT_TEXT" > "$prompt_text_file"
  chmod 600 "$prompt_text_file"
  printf 'Generating PCM prompt from VOICE_AGENT_E2E_PROMPT_TEXT.\n'
  ffmpeg -hide_banner \
    -f lavfi \
    -i "flite=textfile='$prompt_text_file':voice=kal" \
    -ar 16000 \
    -ac 1 \
    -f s16le \
    -y "$GENERATED_PCM_PATH" >/dev/null
  chmod 600 "$GENERATED_PCM_PATH"
}
```

- [ ] **Step 4: Make `VOICE_AGENT_E2E_PCM_PATH` optional**

Replace:

```bash
require_env VOICE_AGENT_E2E_PCM_PATH
```

with:

```bash
if [[ -z "${VOICE_AGENT_E2E_PCM_PATH:-}" ]]; then
  generate_pcm_prompt
  VOICE_AGENT_E2E_PCM_PATH="$GENERATED_PCM_PATH"
fi
```

Keep the existing file-exists check unchanged after this block.

- [ ] **Step 5: Run shell test for generated PCM**

Run:

```bash
scripts/test-voice-agent-hermes-gbrain-e2e.sh
```

Expected: PASS.

- [ ] **Step 6: Commit Task 2**

Run:

```bash
git add scripts/voice-agent-hermes-gbrain-e2e.sh scripts/test-voice-agent-hermes-gbrain-e2e.sh
git commit -m "feat: generate voice e2e pcm from text"
```

## Task 3: Pull Artifacts And Build The Local Report

**Files:**
- Modify: `scripts/voice-agent-hermes-gbrain-e2e.sh`
- Modify: `scripts/test-voice-agent-hermes-gbrain-e2e.sh`

- [ ] **Step 1: Write the failing shell test for report contents**

Extend the fake ADB in `scripts/test-voice-agent-hermes-gbrain-e2e.sh` with artifact reads:

```bash
  "-s RZ shell run-as me.rerere.rikkahub.debug cp /data/local/tmp/rikkahub-voice-agent-e2e-source-text.txt files/voice-e2e/source-text.txt")
    ;;
  "-s RZ shell rm -f /data/local/tmp/rikkahub-voice-agent-e2e-source-text.txt")
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug cat files/voice-e2e/source-text.txt")
    printf 'Please ask Hermes if he is connected to G-Brain. Please answer with yes or no.'
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug cat files/voice-e2e/input-transcript.txt")
    printf 'Please ask Hermes if he is connected to G-Brain.'
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug cat files/voice-e2e/hermes-call.txt")
    printf 'Is Hermes connected to G-Brain? Answer yes or no.'
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug cat files/voice-e2e/output-transcript.txt")
    printf 'Yes, Hermes is connected to G-Brain.'
    ;;
```

After the generated-mode assertions, add:

```bash
report_path="$generated_log_dir/report.txt"
if [[ ! -f "$report_path" ]]; then
  printf 'Expected report file to exist: %s\n' "$report_path" >&2
  exit 1
fi
assert_contains "$(cat "$report_path")" "Text used to generate voice:"
assert_contains "$(cat "$report_path")" "Please ask Hermes if he is connected to G-Brain. Please answer with yes or no."
assert_contains "$(cat "$report_path")" "Gemini understood from voice:"
assert_contains "$(cat "$report_path")" "Please ask Hermes if he is connected to G-Brain."
assert_contains "$(cat "$report_path")" "Hermes call:"
assert_contains "$(cat "$report_path")" "Is Hermes connected to G-Brain? Answer yes or no."
assert_contains "$(cat "$report_path")" "Hermes elapsed time:"
assert_contains "$(cat "$report_path")" "elapsedMs=100"
assert_contains "$(cat "$report_path")" "Hermes answer:"
assert_contains "$(cat "$report_path")" "manual answer from Hermes"
assert_contains "$(cat "$report_path")" "Gemini response to user:"
assert_contains "$(cat "$report_path")" "Yes, Hermes is connected to G-Brain."
```

- [ ] **Step 2: Run the failing shell test**

Run:

```bash
scripts/test-voice-agent-hermes-gbrain-e2e.sh
```

Expected: FAIL because `build/voice-agent-e2e/report.txt` is not generated.

- [ ] **Step 3: Add report file constants and app artifact list**

In `scripts/voice-agent-hermes-gbrain-e2e.sh`, add after `MANUAL_REVIEW_ANSWER_FILE`:

```bash
REPORT_FILE="${VOICE_AGENT_E2E_REPORT_PATH:-$LOG_DIR/report.txt}"
APP_SOURCE_TEXT_PATH="voice-e2e/source-text.txt"
APP_INPUT_TRANSCRIPT_PATH="voice-e2e/input-transcript.txt"
APP_HERMES_CALL_PATH="voice-e2e/hermes-call.txt"
APP_OUTPUT_TRANSCRIPT_PATH="voice-e2e/output-transcript.txt"
```

- [ ] **Step 4: Add artifact pull and report helpers**

Add these helpers before `cleanup()`:

```bash
pull_optional_app_artifact() {
  local app_path="$1"
  local local_path="$2"
  if adb_exec_out_to_file "$local_path" run-as "$PACKAGE" cat "files/$app_path" &&
    [[ -s "$local_path" ]]; then
    chmod 600 "$local_path"
    return 0
  fi
  rm -f "$local_path"
  printf 'missing' > "$local_path"
  chmod 600 "$local_path"
}

extract_hermes_elapsed_detail() {
  grep -E 'VoiceAgentE2E.*hermes_tool_response_hash .*actualHash=' "$LOG_FILE" |
    tail -n 1 |
    sed -E 's/^.*hermes_tool_response_hash //'
}

write_e2e_report() {
  umask 077
  mkdir -p "$LOG_DIR"
  local source_text_file="$LOG_DIR/source-text.txt"
  local input_transcript_file="$LOG_DIR/input-transcript.txt"
  local hermes_call_file="$LOG_DIR/hermes-call.txt"
  local output_transcript_file="$LOG_DIR/output-transcript.txt"

  pull_optional_app_artifact "$APP_SOURCE_TEXT_PATH" "$source_text_file"
  pull_optional_app_artifact "$APP_INPUT_TRANSCRIPT_PATH" "$input_transcript_file"
  pull_optional_app_artifact "$APP_HERMES_CALL_PATH" "$hermes_call_file"
  pull_optional_app_artifact "$APP_OUTPUT_TRANSCRIPT_PATH" "$output_transcript_file"

  {
    printf 'Text used to generate voice:\n'
    cat "$source_text_file"
    printf '\n\nGemini understood from voice:\n'
    cat "$input_transcript_file"
    printf '\n\nHermes call:\n'
    cat "$hermes_call_file"
    printf '\n\nHermes elapsed time:\n'
    extract_hermes_elapsed_detail || printf 'missing'
    printf '\n\nHermes answer:\n'
    cat "$MANUAL_REVIEW_ANSWER_FILE"
    printf '\n\nGemini response to user:\n'
    cat "$output_transcript_file"
    printf '\n'
  } > "$REPORT_FILE"
  chmod 600 "$REPORT_FILE"
  printf 'Voice Agent E2E report: %s\n' "$REPORT_FILE"
}
```

- [ ] **Step 5: Write source text to the app before starting the service**

After copying the PCM into `files/$APP_PCM_PATH`, add:

```bash
if [[ -n "${VOICE_AGENT_E2E_PROMPT_TEXT:-}" || "$VOICE_AGENT_E2E_PCM_PATH" == "$GENERATED_PCM_PATH" ]]; then
  printf '%s' "$PROMPT_TEXT" > "$LOG_DIR/source-text.txt"
  chmod 600 "$LOG_DIR/source-text.txt"
  adb_long_cmd push "$LOG_DIR/source-text.txt" "$DEVICE_TMP_SOURCE_TEXT" >/dev/null
  adb_cmd shell "run-as $PACKAGE cp $DEVICE_TMP_SOURCE_TEXT files/$APP_SOURCE_TEXT_PATH"
  adb_cmd shell rm -f "$DEVICE_TMP_SOURCE_TEXT" >/dev/null 2>&1 || true
fi
```

Also add the constant near `DEVICE_TMP_PCM`:

```bash
DEVICE_TMP_SOURCE_TEXT="/data/local/tmp/rikkahub-voice-agent-e2e-source-text.txt"
```

- [ ] **Step 6: Clear stale source/transcript artifacts before the run and during cleanup**

In the manual-review cleanup block and the pre-run stale cleanup block, remove all report artifacts:

```bash
for app_path in \
  "$APP_MANUAL_ANSWER_PATH" \
  "$APP_SOURCE_TEXT_PATH" \
  "$APP_INPUT_TRANSCRIPT_PATH" \
  "$APP_HERMES_CALL_PATH" \
  "$APP_OUTPUT_TRANSCRIPT_PATH"; do
  adb_cmd shell "run-as $PACKAGE rm -f files/$app_path" >/dev/null 2>&1 || true
done
```

Use this loop instead of removing only `APP_MANUAL_ANSWER_PATH`.

- [ ] **Step 7: Call report generation after answer extraction**

In the final manual-review block, change:

```bash
extract_manual_review_answer
printf 'Voice Agent Hermes/Gbrain live E2E reached manual review gate. Safe log: %s\n' "$LOG_FILE"
```

to:

```bash
extract_manual_review_answer
write_e2e_report
printf 'Voice Agent Hermes/Gbrain live E2E reached manual review gate. Safe log: %s\n' "$LOG_FILE"
```

- [ ] **Step 8: Run report shell test**

Run:

```bash
scripts/test-voice-agent-hermes-gbrain-e2e.sh
```

Expected: PASS.

- [ ] **Step 9: Commit Task 3**

Run:

```bash
git add scripts/voice-agent-hermes-gbrain-e2e.sh scripts/test-voice-agent-hermes-gbrain-e2e.sh
git commit -m "feat: write voice e2e report"
```

## Task 4: Update Runbook Documentation

**Files:**
- Modify: `docs/voice-agent-hermes-gbrain-live-e2e.md`

- [ ] **Step 1: Update input variable table**

Add these rows to the Secret Inputs table:

```markdown
| `VOICE_AGENT_E2E_PROMPT_TEXT` | Optional text used to generate PCM when `VOICE_AGENT_E2E_PCM_PATH` is unset. Defaults to asking Hermes whether he is connected to Gbrain. |
| `VOICE_AGENT_E2E_GENERATED_PCM_PATH` | Optional generated PCM output path. Defaults to `build/voice-agent-e2e/generated-prompt.pcm`. |
| `VOICE_AGENT_E2E_REPORT_PATH` | Optional report output path. Defaults to `build/voice-agent-e2e/report.txt`. |
```

Change the `VOICE_AGENT_E2E_PCM_PATH` row to:

```markdown
| `VOICE_AGENT_E2E_PCM_PATH` | Optional absolute path to a private PCM prompt file. If unset, the script generates PCM from `VOICE_AGENT_E2E_PROMPT_TEXT`. |
```

- [ ] **Step 2: Update PCM prompt section**

Replace the current "Preparing The PCM Prompt" section body with:

````markdown
If `VOICE_AGENT_E2E_PCM_PATH` is unset, the script generates signed 16-bit little-endian mono PCM at 16 kHz from
`VOICE_AGENT_E2E_PROMPT_TEXT` using local `ffmpeg` with `flite`.

The default generated prompt is:

```text
Please ask Hermes if he is connected to G-Brain. Please answer with yes or no.
```

Set `VOICE_AGENT_E2E_PROMPT_TEXT` to change one string and regenerate the PCM for a different question.

If `VOICE_AGENT_E2E_PCM_PATH` is set, it must point to signed 16-bit little-endian mono PCM at 16 kHz. Keep supplied PCM
files outside the repository.
````

- [ ] **Step 3: Add report section**

Add this section after Manual Review Mode:

```markdown
## E2E Report

Manual review mode writes `build/voice-agent-e2e/report.txt`. The report includes the text used to generate voice, what
Gemini understood from the injected voice, the raw Hermes tool call, Hermes timing, Hermes's answer, and Gemini's text
response to the user when output transcription is available.

The script prints the report path but does not print report contents. Treat the report as local/private because it may
contain raw prompts, transcripts, and Hermes answers.
```

- [ ] **Step 4: Commit Task 4**

Run:

```bash
git add docs/voice-agent-hermes-gbrain-live-e2e.md
git commit -m "docs: describe voice e2e report"
```

## Task 5: Full Verification And Live Retry

**Files:**
- No planned source edits.

- [ ] **Step 1: Run shell syntax and shell tests**

Run:

```bash
bash -n scripts/voice-agent-hermes-gbrain-e2e.sh scripts/test-voice-agent-hermes-gbrain-e2e.sh scripts/adb-device-ready.sh scripts/test-adb-device-ready.sh
scripts/test-adb-device-ready.sh
scripts/test-voice-agent-hermes-gbrain-e2e.sh
```

Expected: all commands PASS.

- [ ] **Step 2: Run relevant app unit tests**

Run:

```bash
ANDROID_HOME=/home/muly/Android/Sdk ./gradlew :app:testDebugUnitTest --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentRuntimeTest'
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Assemble debug APK**

Run:

```bash
ANDROID_HOME=/home/muly/Android/Sdk ./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL and `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` exists.

- [ ] **Step 4: Install on connected device**

Run:

```bash
source /home/muly/.config/rikkahub/voice-agent-e2e.env
adb -s "$VOICE_AGENT_E2E_SERIAL" install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

Expected: `Success`.

- [ ] **Step 5: Run live report E2E using generated PCM**

Run:

```bash
source /home/muly/.config/rikkahub/voice-agent-e2e.env
VOICE_AGENT_E2E_MANUAL_REVIEW=1 \
VOICE_AGENT_E2E_PCM_PATH= \
VOICE_AGENT_E2E_PROMPT_TEXT="Please ask Hermes if he is connected to G-Brain. Please answer with yes or no." \
scripts/voice-agent-hermes-gbrain-e2e.sh
```

Expected:

```text
Generating PCM prompt from VOICE_AGENT_E2E_PROMPT_TEXT.
PASS marker: Gemini setup complete
PASS marker: debug PCM delivered
PASS marker: Gemini ask_hermes tool call received
PASS marker: Hermes response hash observed for manual review
PASS marker: Gemini tool response sent
PASS marker: Gemini output audio received
PASS marker: Voice playback queued
PASS marker: Voice playback wrote
Voice Agent E2E report: build/voice-agent-e2e/report.txt
Voice Agent Hermes/Gbrain live E2E reached manual review gate. Safe log: build/voice-agent-e2e/logcat.txt
```

- [ ] **Step 6: Inspect report locally without printing it in shared output**

Run:

```bash
stat -c '%a %s %n' build/voice-agent-e2e/report.txt build/voice-agent-e2e/generated-prompt.pcm
rg -n 'Text used to generate voice:|Gemini understood from voice:|Hermes call:|Hermes elapsed time:|Hermes answer:|Gemini response to user:' build/voice-agent-e2e/report.txt
```

Expected: report and PCM permissions are `600`; all six report section headers are present.

- [ ] **Step 7: Commit final verification notes only if docs changed**

If no files changed during verification, do not commit. If documentation was corrected during verification, run:

```bash
git add docs/voice-agent-hermes-gbrain-live-e2e.md
git commit -m "docs: clarify voice e2e report verification"
```

## Self-Review

- Spec coverage: Tasks cover configurable prompt text, generated PCM, app-private source/input/tool/answer/output artifacts, local report generation, privacy, and live verification.
- Placeholder scan: No placeholder markers or incomplete implementation steps are present.
- Type consistency: The plan consistently uses `writeVoiceE2EArtifact`, `VOICE_AGENT_E2E_PROMPT_TEXT`, `VOICE_AGENT_E2E_GENERATED_PCM_PATH`, `VOICE_AGENT_E2E_REPORT_PATH`, and the app artifact filenames from the spec.
