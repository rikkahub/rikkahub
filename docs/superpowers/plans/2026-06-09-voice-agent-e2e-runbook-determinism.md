# Voice Agent E2E Runbook Determinism Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the live Voice Agent Hermes/Gbrain E2E runner deterministic, diagnosable, and safe to clean up after a passed pipeline.

**Architecture:** Keep the product voice-agent decision path unchanged. Harden the existing test surfaces: shell runner defaults and reporting, fake-ADB harness coverage, runbook documentation, and a narrow `ACTION_END` foreground-service contract fix in `VoiceAgentCallService`.

**Tech Stack:** Bash, Android ADB/logcat, Kotlin/JVM unit tests, Gradle Android debug unit tests, Markdown docs.

---

## File Structure

- Modify `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallService.kt`
  - Add a pure helper for choosing the foreground notification id used during `ACTION_END`.
  - Call `startForegroundFor(...)` immediately for `ACTION_END` before async drain work.
- Modify `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallContractTest.kt`
  - Add JVM coverage for the `ACTION_END` foreground id helper.
- Modify `scripts/voice-agent-hermes-gbrain-e2e.sh`
  - Change default generated prompt and Flite voice.
  - Add `VOICE_AGENT_E2E_FLITE_VOICE`.
  - Add bounded diagnostic artifact pulls for missing tool-call runs.
  - Write manual-review answer/report before cleanup.
  - Print separate pipeline and cleanup summaries.
- Modify `scripts/test-voice-agent-hermes-gbrain-e2e.sh`
  - Update fake `ffmpeg` to validate configurable Flite voice.
  - Add harness tests for default voice/prompt, voice override, missing tool-call diagnostics, and cleanup failure after pipeline pass.
- Modify `docs/voice-agent-hermes-gbrain-live-e2e.md`
  - Document the new prompt, voice variable, transcript diagnostics, pipeline/cleanup split, and artifact order.

## Task 1: Service END Foreground Contract

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallService.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallContractTest.kt`

- [ ] **Step 1: Add failing JVM tests for END foreground id selection**

Append these tests to `VoiceAgentCallContractTest`:

```kotlin
    @Test
    fun `end foreground id uses active conversation id`() {
        val activeConversationId = "11111111-1111-4111-8111-111111111111"

        assertEquals(activeConversationId, voiceAgentEndForegroundConversationId(activeConversationId))
    }

    @Test
    fun `end foreground id uses stable placeholder when idle`() {
        assertEquals("ending", voiceAgentEndForegroundConversationId(null))
    }
```

- [ ] **Step 2: Run the focused tests and verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentCallContractTest'
```

Expected: FAIL with an unresolved reference to `voiceAgentEndForegroundConversationId`.

- [ ] **Step 3: Add the pure helper**

Add this function near `voiceAgentServiceStartConfig(...)` in `VoiceAgentCallService.kt`:

```kotlin
internal fun voiceAgentEndForegroundConversationId(activeConversationId: String?): String =
    activeConversationId ?: "ending"
```

- [ ] **Step 4: Run the focused tests and verify helper coverage passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentCallContractTest'
```

Expected: PASS for all tests in `VoiceAgentCallContractTest`.

- [ ] **Step 5: Make ACTION_END establish foreground state before async drain**

Replace the start of `endCall()` in `VoiceAgentCallService.kt` with:

```kotlin
    private fun endCall() {
        notificationJob?.cancel()
        notificationJob = null
        val endingConversationId = manager.activeConversationId.value
        startForegroundFor(
            conversationId = voiceAgentEndForegroundConversationId(endingConversationId?.toString()),
            state = manager.state.value.copy(call = VoiceCallStatus.Ending),
        )
        if (endJob?.isActive == true) {
            return
        }
        callGeneration += 1
        val endGeneration = callGeneration
        val session = manager.detachForEndAndDrain()
        endJob = serviceScope.launch {
            if (endGeneration != callGeneration) {
                return@launch
            }
            try {
                telecomConversationId = null
                telecomCallRegistry.disconnectActive()
                session?.endAndDrain()
                VoiceAgentLog.d(TAG, "end completed conversationId=${endingConversationId ?: "none"}")
            } finally {
                if (endGeneration == callGeneration) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    endJob = null
                }
            }
        }
    }
```

- [ ] **Step 6: Run VoiceAgent JVM tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests '*VoiceAgent*'
```

Expected: PASS.

- [ ] **Step 7: Commit the service END contract fix**

Run:

```bash
git add app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallService.kt app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallContractTest.kt
git commit -m "fix: make voice agent end foreground-safe"
```

## Task 2: Deterministic Generated PCM Defaults

**Files:**
- Modify: `scripts/voice-agent-hermes-gbrain-e2e.sh`
- Modify: `scripts/test-voice-agent-hermes-gbrain-e2e.sh`

- [ ] **Step 1: Update fake ffmpeg to validate selected voice**

In `scripts/test-voice-agent-hermes-gbrain-e2e.sh`, replace the hardcoded fake-ffmpeg `voice=kal` checks with:

```bash
expected_voice="${FAKE_FFMPEG_EXPECTED_VOICE:-slt}"
if [[ "$input" != flite=textfile=*":voice=$expected_voice" ]]; then
  printf 'unexpected ffmpeg flite input: %s\n' "$input" >&2
  printf 'expected voice: %s\n' "$expected_voice" >&2
  exit 98
fi
textfile="${input#flite=textfile=}"
textfile="${textfile%:voice=$expected_voice}"
```

Keep the existing argument count/order validation and textfile safety checks around this block.

- [ ] **Step 2: Change generated default test expectations**

In the `generated_output` test case, change:

```bash
FAKE_FFMPEG_EXPECTED_PROMPT="Please ask Hermes if he is connected to G-Brain. Please answer with yes or no."
```

to:

```bash
FAKE_FFMPEG_EXPECTED_PROMPT="Ask Hermes. Are you connected to G Brain? Answer yes or no."
FAKE_FFMPEG_EXPECTED_VOICE=slt
```

Change the expected source text assertion to:

```bash
assert_file_contains_exactly "$generated_log_dir/generated-prompt.txt" \
  "Ask Hermes. Are you connected to G Brain? Answer yes or no."
```

- [ ] **Step 3: Add a failing shell test for VOICE_AGENT_E2E_FLITE_VOICE**

After the generated PCM test, add:

```bash
override_voice_log_dir="$TMP_DIR/override-voice-log"
set +e
override_voice_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_FFMPEG_EXPECTED_PROMPT="Prompt with override voice." \
  FAKE_FFMPEG_EXPECTED_VOICE=kal16 \
  FAKE_FFMPEG_EXPECTED_OUTPUT="$override_voice_log_dir/generated-prompt.pcm" \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_E2E_PROMPT_TEXT="Prompt with override voice." \
  VOICE_AGENT_E2E_FLITE_VOICE=kal16 \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_E2E_LOG_DIR="$override_voice_log_dir" \
  VOICE_AGENT_E2E_MANUAL_REVIEW=1 \
  VOICE_AGENT_E2E_GEMINI_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_E2E_HERMES_RESPONSE_TIMEOUT_SECONDS=5 \
  "$SCRIPT" 2>&1
)"
override_voice_status=$?
set -e

if [[ "$override_voice_status" -ne 0 ]]; then
  printf 'Expected generated PCM mode with override voice to pass, got status %s.\n' "$override_voice_status" >&2
  printf 'Actual output:\n%s\n' "$override_voice_output" >&2
  exit 1
fi
assert_file_contains_exactly "$override_voice_log_dir/generated-prompt.pcm" "generated pcm"
```

- [ ] **Step 4: Run the harness and verify it fails on current defaults**

Run:

```bash
scripts/test-voice-agent-hermes-gbrain-e2e.sh
```

Expected: FAIL with fake `ffmpeg` reporting an unexpected Flite input using `voice=kal`.

- [ ] **Step 5: Implement generated PCM defaults and voice override**

In `scripts/voice-agent-hermes-gbrain-e2e.sh`, replace:

```bash
DEFAULT_PROMPT_TEXT="Please ask Hermes if he is connected to G-Brain. Please answer with yes or no."
```

with:

```bash
DEFAULT_PROMPT_TEXT="Ask Hermes. Are you connected to G Brain? Answer yes or no."
FLITE_VOICE="${VOICE_AGENT_E2E_FLITE_VOICE:-slt}"
```

In `generate_pcm_prompt()`, before invoking `ffmpeg`, add:

```bash
  if [[ ! "$FLITE_VOICE" =~ ^[A-Za-z0-9_-]+$ ]]; then
    printf 'VOICE_AGENT_E2E_FLITE_VOICE contains unsupported characters: %s\n' "$FLITE_VOICE" >&2
    return 2
  fi
```

Change the `ffmpeg` input from:

```bash
    -i "flite=textfile=$ffmpeg_prompt_text_file:voice=kal" \
```

to:

```bash
    -i "flite=textfile=$ffmpeg_prompt_text_file:voice=$FLITE_VOICE" \
```

- [ ] **Step 6: Run shell syntax and harness tests**

Run:

```bash
bash -n scripts/voice-agent-hermes-gbrain-e2e.sh scripts/test-voice-agent-hermes-gbrain-e2e.sh
scripts/test-voice-agent-hermes-gbrain-e2e.sh
```

Expected: both commands PASS.

- [ ] **Step 7: Commit deterministic PCM defaults**

Run:

```bash
git add scripts/voice-agent-hermes-gbrain-e2e.sh scripts/test-voice-agent-hermes-gbrain-e2e.sh
git commit -m "test: use deterministic voice e2e prompt audio"
```

## Task 3: Missing Tool-Call Transcript Diagnostics

**Files:**
- Modify: `scripts/voice-agent-hermes-gbrain-e2e.sh`
- Modify: `scripts/test-voice-agent-hermes-gbrain-e2e.sh`

- [ ] **Step 1: Extend fake ADB to support missing tool-call logs**

In fake ADB's `"-s RZ logcat -v time "*` case, wrap the tool-call line and downstream success markers:

```bash
    if [[ "${FAKE_ADB_SKIP_TOOL_CALL:-0}" != "1" ]]; then
      printf '06-08 12:00:02.000 D/VoiceAgentGemini(1): receive kind=toolCall\n'
    fi
```

Then wrap the Hermes hash, tool response, output audio, playback queued, and playback wrote marker block:

```bash
    if [[ "${FAKE_ADB_SKIP_TOOL_CALL:-0}" != "1" ]]; then
      cat <<'LOGS'
06-08 12:00:03.000 D/VoiceAgentE2E(1): hermes_tool_response_hash callId=call-1, actualHash=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa, responseChars=25, normalizedChars=25, elapsedMs=100
06-08 12:00:04.000 D/VoiceAgentGemini(1): send kind=toolResponse sent=true
06-08 12:00:05.000 D/VoiceAgentGemini(1): event kind=OutputAudio
06-08 12:00:06.000 D/AndroidVoiceAudioEngine(1): Voice playback queued bytes=3200
06-08 12:00:07.000 D/AndroidVoiceAudioEngine(1): Voice playback wrote bytes=3200
LOGS
    fi
```

- [ ] **Step 2: Add a failing harness test for ASR diagnostics**

Before the forbidden-marker test, add:

```bash
missing_tool_call_log_dir="$TMP_DIR/missing-tool-call-log"
set +e
missing_tool_call_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_ADB_SKIP_TOOL_CALL=1 \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_E2E_PCM_PATH="$TMP_DIR/prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_E2E_LOG_DIR="$missing_tool_call_log_dir" \
  VOICE_AGENT_E2E_MANUAL_REVIEW=1 \
  VOICE_AGENT_E2E_GEMINI_TOOL_CALL_TIMEOUT_SECONDS=1 \
  VOICE_AGENT_E2E_HERMES_RESPONSE_TIMEOUT_SECONDS=1 \
  "$SCRIPT" 2>&1
)"
missing_tool_call_status=$?
set -e

if [[ "$missing_tool_call_status" -eq 0 ]]; then
  printf 'Expected missing tool-call run to fail.\n' >&2
  printf 'Actual output:\n%s\n' "$missing_tool_call_output" >&2
  exit 1
fi
assert_contains "$missing_tool_call_output" "Missing marker after 1s: Gemini ask_hermes tool call received"
assert_contains "$missing_tool_call_output" "Gemini understood from voice:"
assert_contains "$missing_tool_call_output" "Please ask Hermes if he is connected to G-Brain."
assert_contains "$missing_tool_call_output" "Gemini response to user:"
assert_contains "$missing_tool_call_output" "Yes, Hermes is connected to G-Brain."
assert_contains "$missing_tool_call_output" "Hermes call:"
assert_contains "$missing_tool_call_output" "Is Hermes connected to G-Brain? Answer yes or no."
if grep -F -- "databases/rikka_hub" "$FAKE_ADB_ARGS_LOG" >/dev/null; then
  printf 'Expected no database fallback for missing tool-call diagnostics.\n' >&2
  printf 'Actual ADB log:\n%s\n' "$(cat "$FAKE_ADB_ARGS_LOG")" >&2
  exit 1
fi
```

- [ ] **Step 3: Run harness and verify failure**

Run:

```bash
scripts/test-voice-agent-hermes-gbrain-e2e.sh
```

Expected: FAIL because the script does not print the transcript diagnostics yet.

- [ ] **Step 4: Add bounded diagnostic artifact helpers**

In `scripts/voice-agent-hermes-gbrain-e2e.sh`, add after `pull_optional_app_artifact()`:

```bash
print_artifact_preview() {
  local label="$1"
  local app_path="$2"
  local temp_path
  temp_path="$(mktemp "$LOG_DIR/report-artifact.XXXXXX")"
  register_report_temp_file "$temp_path"
  chmod 600 "$temp_path"
  if adb_exec_out_to_file "$temp_path" run-as "$PACKAGE" cat "$app_path" &&
    [[ -s "$temp_path" ]]; then
    printf '%s: ' "$label" >&2
    tr '\r\n' ' ' < "$temp_path" | cut -c 1-240 >&2
    printf '\n' >&2
  else
    printf '%s: missing\n' "$label" >&2
  fi
  rm -f "$temp_path"
}

print_missing_tool_call_diagnostics() {
  printf 'Voice Agent E2E diagnostic artifacts after missing tool call:\n' >&2
  print_artifact_preview "Gemini understood from voice" "$APP_INPUT_TRANSCRIPT_PATH"
  print_artifact_preview "Gemini response to user" "$APP_OUTPUT_TRANSCRIPT_PATH"
  print_artifact_preview "Hermes call" "$APP_HERMES_CALL_PATH"
}
```

- [ ] **Step 5: Call diagnostics when tool-call marker is missing**

Replace:

```bash
wait_for_log "Gemini ask_hermes tool call received" 'VoiceAgentGemini.*receive kind=toolCall' "$GEMINI_TOOL_CALL_TIMEOUT_SECONDS"
```

with:

```bash
if ! wait_for_log "Gemini ask_hermes tool call received" \
  'VoiceAgentGemini.*receive kind=toolCall' \
  "$GEMINI_TOOL_CALL_TIMEOUT_SECONDS"; then
  if [[ "$MANUAL_REVIEW" == "1" ]]; then
    print_missing_tool_call_diagnostics
  fi
  exit 1
fi
```

- [ ] **Step 6: Run shell syntax and harness tests**

Run:

```bash
bash -n scripts/voice-agent-hermes-gbrain-e2e.sh scripts/test-voice-agent-hermes-gbrain-e2e.sh
scripts/test-voice-agent-hermes-gbrain-e2e.sh
```

Expected: PASS.

- [ ] **Step 7: Commit missing tool-call diagnostics**

Run:

```bash
git add scripts/voice-agent-hermes-gbrain-e2e.sh scripts/test-voice-agent-hermes-gbrain-e2e.sh
git commit -m "test: report voice e2e transcript diagnostics"
```

## Task 4: Pipeline And Cleanup Result Split

**Files:**
- Modify: `scripts/voice-agent-hermes-gbrain-e2e.sh`
- Modify: `scripts/test-voice-agent-hermes-gbrain-e2e.sh`

- [ ] **Step 1: Add fake cleanup failure control**

In fake ADB's END command case, change:

```bash
  "-s RZ shell am start-foreground-service -n me.rerere.rikkahub.debug/me.rerere.rikkahub.voiceagent.VoiceAgentCallService -a me.rerere.rikkahub.voiceagent.action.END")
    : > "${FAKE_ADB_END_MARKER:?}"
    ;;
```

to:

```bash
  "-s RZ shell am start-foreground-service -n me.rerere.rikkahub.debug/me.rerere.rikkahub.voiceagent.VoiceAgentCallService -a me.rerere.rikkahub.voiceagent.action.END")
    if [[ "${FAKE_ADB_SKIP_END_MARKER:-0}" != "1" ]]; then
      : > "${FAKE_ADB_END_MARKER:?}"
    fi
    ;;
```

- [ ] **Step 2: Add failing harness test for pipeline pass with cleanup failure**

After the manual no-hash pass test, add:

```bash
cleanup_failure_log_dir="$TMP_DIR/cleanup-failure-log"
set +e
cleanup_failure_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_ADB_SKIP_END_MARKER=1 \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_E2E_PCM_PATH="$TMP_DIR/prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_E2E_LOG_DIR="$cleanup_failure_log_dir" \
  VOICE_AGENT_E2E_MANUAL_REVIEW=1 \
  VOICE_AGENT_E2E_SERVICE_END_TIMEOUT_SECONDS=1 \
  VOICE_AGENT_E2E_GEMINI_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_E2E_HERMES_RESPONSE_TIMEOUT_SECONDS=5 \
  "$SCRIPT" 2>&1
)"
cleanup_failure_status=$?
set -e

if [[ "$cleanup_failure_status" -eq 0 ]]; then
  printf 'Expected cleanup failure run to exit nonzero.\n' >&2
  printf 'Actual output:\n%s\n' "$cleanup_failure_output" >&2
  exit 1
fi
assert_contains "$cleanup_failure_output" "PASS marker: Voice playback wrote"
assert_contains "$cleanup_failure_output" "Manual review answer artifact: $cleanup_failure_log_dir/manual-hermes-answer.txt"
assert_contains "$cleanup_failure_output" "Voice Agent E2E report: $cleanup_failure_log_dir/report.txt"
assert_contains "$cleanup_failure_output" "PIPELINE: passed"
assert_contains "$cleanup_failure_output" "CLEANUP: failed - service end marker not observed"
assert_file_contains_exactly "$cleanup_failure_log_dir/manual-hermes-answer.txt" "manual answer from Hermes"
assert_file_contains "$cleanup_failure_log_dir/report.txt" "Hermes answer:"
```

- [ ] **Step 3: Run harness and verify failure**

Run:

```bash
scripts/test-voice-agent-hermes-gbrain-e2e.sh
```

Expected: FAIL because the runner exits during cleanup before printing split result status.

- [ ] **Step 4: Add summary state and cleanup helper**

In `scripts/voice-agent-hermes-gbrain-e2e.sh`, add near the state variables:

```bash
PIPELINE_STATUS="not_started"
CLEANUP_STATUS="not_started"
CLEANUP_DETAIL=""
```

Replace `end_voice_agent_call_and_wait()` with:

```bash
end_voice_agent_call_and_wait() {
  if [[ "$CALL_STARTED" != "1" ]]; then
    CLEANUP_STATUS="skipped"
    CLEANUP_DETAIL="call was not started"
    return 0
  fi
  set +e
  adb_cmd shell am start-foreground-service \
    -n "$SERVICE_COMPONENT" \
    -a "$CALL_END_ACTION" >/dev/null
  local end_command_status=$?
  set -e
  if [[ "$end_command_status" -ne 0 ]]; then
    CLEANUP_STATUS="failed"
    CLEANUP_DETAIL="service end command failed"
    return 1
  fi
  if wait_for_log \
    "Voice Agent service ended" \
    'VoiceAgentCallService.*end completed conversationId=' \
    "${VOICE_AGENT_E2E_SERVICE_END_TIMEOUT_SECONDS:-30}"; then
    CALL_STARTED=0
    CLEANUP_STATUS="passed"
    CLEANUP_DETAIL="service end marker observed"
    return 0
  fi
  CLEANUP_STATUS="failed"
  CLEANUP_DETAIL="service end marker not observed"
  return 1
}

print_result_summary() {
  printf 'PIPELINE: %s\n' "$PIPELINE_STATUS"
  case "$CLEANUP_STATUS" in
    passed)
      printf 'CLEANUP: passed\n'
      ;;
    failed)
      printf 'CLEANUP: failed - %s\n' "$CLEANUP_DETAIL"
      ;;
    skipped)
      printf 'CLEANUP: skipped - %s\n' "$CLEANUP_DETAIL"
      ;;
    *)
      printf 'CLEANUP: %s\n' "$CLEANUP_STATUS"
      ;;
  esac
}
```

- [ ] **Step 5: Reorder manual mode artifacts before cleanup**

Replace the final manual-mode block:

```bash
if [[ "$MANUAL_REVIEW" == "1" ]]; then
  end_voice_agent_call_and_wait
  extract_manual_review_answer
  write_e2e_report
  printf 'Voice Agent Hermes/Gbrain live E2E reached manual review gate. Safe log: %s\n' "$LOG_FILE"
else
  printf 'Voice Agent Hermes/Gbrain live E2E passed. Safe log: %s\n' "$LOG_FILE"
fi
```

with:

```bash
PIPELINE_STATUS="passed"
if [[ "$MANUAL_REVIEW" == "1" ]]; then
  extract_manual_review_answer
  write_e2e_report
  cleanup_status=0
  end_voice_agent_call_and_wait || cleanup_status=$?
  print_result_summary
  if [[ "$cleanup_status" -ne 0 ]]; then
    printf 'Voice Agent Hermes/Gbrain live E2E pipeline passed but cleanup failed. Safe log: %s\n' "$LOG_FILE"
    exit "$cleanup_status"
  fi
  printf 'Voice Agent Hermes/Gbrain live E2E reached manual review gate. Safe log: %s\n' "$LOG_FILE"
else
  CLEANUP_STATUS="skipped"
  CLEANUP_DETAIL="strict mode leaves cleanup to exit trap"
  print_result_summary
  printf 'Voice Agent Hermes/Gbrain live E2E passed. Safe log: %s\n' "$LOG_FILE"
fi
```

- [ ] **Step 6: Keep trap cleanup best-effort**

Do not make the `cleanup()` trap print result summaries. Keep its current best-effort cleanup behavior so early failures remain focused on the failing marker. Confirm the trap still removes:

```bash
files/voice-e2e/prompt.pcm
no_backup/voice-e2e/hermes-answer.txt
no_backup/voice-e2e/input-transcript.txt
no_backup/voice-e2e/output-transcript.txt
no_backup/voice-e2e/hermes-call.txt
```

- [ ] **Step 7: Run shell syntax and harness tests**

Run:

```bash
bash -n scripts/voice-agent-hermes-gbrain-e2e.sh scripts/test-voice-agent-hermes-gbrain-e2e.sh
scripts/test-voice-agent-hermes-gbrain-e2e.sh
```

Expected: PASS.

- [ ] **Step 8: Commit pipeline/cleanup split**

Run:

```bash
git add scripts/voice-agent-hermes-gbrain-e2e.sh scripts/test-voice-agent-hermes-gbrain-e2e.sh
git commit -m "test: split voice e2e pipeline and cleanup results"
```

## Task 5: Runbook Documentation

**Files:**
- Modify: `docs/voice-agent-hermes-gbrain-live-e2e.md`

- [ ] **Step 1: Update secret inputs table**

Add this row after `VOICE_AGENT_E2E_PROMPT_TEXT`:

```markdown
| `VOICE_AGENT_E2E_FLITE_VOICE` | Optional Flite voice used when generating PCM. Defaults to `slt`. |
```

- [ ] **Step 2: Update default prompt text**

Replace the default generated prompt block with:

```markdown
The default generated prompt is:

```text
Ask Hermes. Are you connected to G Brain? Answer yes or no.
```

The generated prompt uses Flite voice `slt` by default because live testing showed the previous `kal` voice was
transcribed unreliably by Gemini Live. Set `VOICE_AGENT_E2E_FLITE_VOICE` to another installed Flite voice when comparing
ASR behavior.
```
```

- [ ] **Step 3: Document missing tool-call diagnostics**

Add this paragraph to the failure criteria section:

```markdown
When the `ask_hermes` tool-call marker is missing in manual-review mode, the script prints bounded diagnostics from the
app-private E2E artifacts when available: what Gemini understood from voice, Gemini's response to the user, and the
Hermes call artifact. The script does not fall back to reading the app database.
```

- [ ] **Step 4: Document pipeline versus cleanup status**

Add this subsection before `## Pass Criteria`:

```markdown
## Pipeline And Cleanup Result

The script reports the live pipeline result separately from cleanup:

```text
PIPELINE: passed
CLEANUP: passed
```

If the pipeline passes but cleanup cannot observe the service end marker, the script exits nonzero and prints:

```text
PIPELINE: passed
CLEANUP: failed - service end marker not observed
```

Treat that as a successful live pipeline with a cleanup failure that still needs investigation.
```
```

- [ ] **Step 5: Document manual report ordering**

In `## Manual Review Mode`, add:

```markdown
After the required pipeline markers pass, manual mode pulls the Hermes answer and writes the report before attempting
service cleanup. This preserves the diagnostic artifacts even if cleanup later fails.
```

- [ ] **Step 6: Verify docs mention the new variable and prompt**

Run:

```bash
rg -n 'VOICE_AGENT_E2E_FLITE_VOICE|Ask Hermes\. Are you connected to G Brain\?|PIPELINE: passed|CLEANUP:' docs/voice-agent-hermes-gbrain-live-e2e.md
```

Expected: all four patterns are found.

- [ ] **Step 7: Commit runbook docs**

Run:

```bash
git add docs/voice-agent-hermes-gbrain-live-e2e.md
git commit -m "docs: clarify deterministic voice e2e runbook"
```

## Task 6: Final Verification

**Files:**
- Verify working tree and changed behavior across scripts, Kotlin tests, and docs.

- [ ] **Step 1: Run shell syntax checks**

Run:

```bash
bash -n scripts/voice-agent-hermes-gbrain-e2e.sh scripts/test-voice-agent-hermes-gbrain-e2e.sh scripts/adb-device-ready.sh scripts/test-adb-device-ready.sh
```

Expected: exits `0` with no output.

- [ ] **Step 2: Run shell harness tests**

Run:

```bash
scripts/test-voice-agent-hermes-gbrain-e2e.sh
scripts/test-adb-device-ready.sh
```

Expected:

```text
voice-agent-hermes-gbrain-e2e tests passed.
adb-device-ready tests passed.
```

- [ ] **Step 3: Run VoiceAgent JVM tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests '*VoiceAgent*'
```

Expected: Gradle reports `BUILD SUCCESSFUL`.

- [ ] **Step 4: Confirm no private artifacts are staged**

Run:

```bash
git status --short
git diff --stat
```

Expected: only source, script, test, and documentation files modified. No files under `build/voice-agent-e2e/`, no PCM files, no logcat files, no report files.

- [ ] **Step 5: Commit any final verification-only corrections**

If Task 6 reveals small corrections, commit them with:

```bash
git add app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallService.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallContractTest.kt \
  scripts/voice-agent-hermes-gbrain-e2e.sh \
  scripts/test-voice-agent-hermes-gbrain-e2e.sh \
  docs/voice-agent-hermes-gbrain-live-e2e.md
git commit -m "test: finalize voice agent e2e determinism"
```

If there are no final corrections, do not create an empty commit.

- [ ] **Step 6: Optional live-device smoke run**

Run only when a configured device and conversation id are available:

```bash
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 \
VOICE_AGENT_E2E_SERIAL=RZCX71NXRPB \
VOICE_AGENT_E2E_CONVERSATION_ID=2c718e59-c251-4b14-b0a0-d5191b2397f9 \
VOICE_AGENT_E2E_MANUAL_REVIEW=1 \
VOICE_AGENT_E2E_LOG_DIR=build/voice-agent-e2e/final-smoke \
scripts/voice-agent-hermes-gbrain-e2e.sh
```

Expected if the live services behave as they did on June 9, 2026:

```text
PASS marker: Gemini ask_hermes tool call received
PASS marker: Hermes response hash observed for manual review
PASS marker: Gemini tool response sent
PASS marker: Gemini output audio received
PASS marker: Voice playback queued
PASS marker: Voice playback wrote
PIPELINE: passed
```

Do not commit any files generated by this optional live run.

## Self-Review Notes

- Spec coverage: Task 1 covers the service END foreground contract. Tasks 2-4 cover runner defaults, transcript diagnostics, report ordering, and pipeline/cleanup split. Task 5 covers runbook updates. Task 6 covers validation and artifact safety.
- Type consistency: the plan uses `voiceAgentEndForegroundConversationId(activeConversationId: String?)`, `VOICE_AGENT_E2E_FLITE_VOICE`, `PIPELINE_STATUS`, `CLEANUP_STATUS`, and `CLEANUP_DETAIL` consistently.
- Scope check: the plan does not change Gemini routing, Hermes behavior, provider config, UI behavior, or CI.
