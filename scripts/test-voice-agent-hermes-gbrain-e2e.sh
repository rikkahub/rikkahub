#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$ROOT_DIR/scripts/voice-agent-hermes-gbrain-e2e.sh"
TMP_DIR="$(mktemp -d)"

cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

assert_contains() {
  local haystack="$1"
  local needle="$2"
  if [[ "$haystack" != *"$needle"* ]]; then
    printf 'Expected output to contain: %s\n' "$needle" >&2
    printf 'Actual output:\n%s\n' "$haystack" >&2
    exit 1
  fi
}

assert_contains_in_order() {
  local haystack="$1"
  local earlier="$2"
  local later="$3"
  if [[ "$haystack" != *"$earlier"*"$later"* ]]; then
    printf 'Expected output to contain "%s" before "%s"\n' "$earlier" "$later" >&2
    printf 'Actual output:\n%s\n' "$haystack" >&2
    exit 1
  fi
}

assert_file_contains_exactly() {
  local path="$1"
  local expected="$2"
  if [[ ! -f "$path" ]]; then
    printf 'Expected file to exist: %s\n' "$path" >&2
    exit 1
  fi
  local actual
  actual="$(cat "$path")"
  if [[ "$actual" != "$expected" ]]; then
    printf 'Expected file %s to contain exactly %q, got %q\n' "$path" "$expected" "$actual" >&2
    exit 1
  fi
}

assert_file_contains() {
  local path="$1"
  local needle="$2"
  if [[ ! -f "$path" ]]; then
    printf 'Expected file to exist: %s\n' "$path" >&2
    exit 1
  fi
  if ! grep -F -- "$needle" "$path" >/dev/null; then
    printf 'Expected file %s to contain: %s\n' "$path" "$needle" >&2
    printf 'Actual contents:\n%s\n' "$(cat "$path")" >&2
    exit 1
  fi
}

assert_file_does_not_exist() {
  local path="$1"
  if [[ -e "$path" ]]; then
    printf 'Expected file not to exist: %s\n' "$path" >&2
    exit 1
  fi
}

assert_last_line_after() {
  local path="$1"
  local earlier="$2"
  local later="$3"
  local earlier_line
  local later_line
  earlier_line="$(grep -n -F -- "$earlier" "$path" | tail -n 1 | cut -d: -f1)"
  later_line="$(grep -n -F -- "$later" "$path" | tail -n 1 | cut -d: -f1)"
  if [[ -z "$earlier_line" || -z "$later_line" || "$later_line" -le "$earlier_line" ]]; then
    printf 'Expected last "%s" to appear after last "%s" in %s\n' "$later" "$earlier" "$path" >&2
    printf 'Actual contents:\n%s\n' "$(cat "$path")" >&2
    exit 1
  fi
}

assert_first_line_after() {
  local path="$1"
  local earlier="$2"
  local later="$3"
  local earlier_line
  local later_line
  earlier_line="$(grep -n -F -- "$earlier" "$path" | head -n 1 | cut -d: -f1)"
  later_line="$(grep -n -F -- "$later" "$path" | head -n 1 | cut -d: -f1)"
  if [[ -z "$earlier_line" || -z "$later_line" || "$later_line" -le "$earlier_line" ]]; then
    printf 'Expected first "%s" to appear after first "%s" in %s\n' "$later" "$earlier" "$path" >&2
    printf 'Actual contents:\n%s\n' "$(cat "$path")" >&2
    exit 1
  fi
}

assert_no_report_temp_files() {
  local directory="$1"
  local leaked
  leaked="$(find "$directory" -maxdepth 1 -type f \
    \( -name 'report-artifact.*' \
    -o -name 'report-input-transcript.*' \
    -o -name 'report-hermes-call.*' \
    -o -name 'report-output-transcript.*' \
    -o -name 'report-source-text.*' \
    -o -name 'report.??????' \) \
    -print)"
  if [[ -n "$leaked" ]]; then
    printf 'Expected no report temp files in %s, found:\n%s\n' "$directory" "$leaked" >&2
    exit 1
  fi
}

count_report_artifact_pulls() {
  grep -c -E 'exec-out run-as me\.rerere\.rikkahub\.debug cat no_backup/voice-e2e/([A-Za-z0-9._-]+/)?(input-transcript|hermes-call|output-transcript)\.txt' \
    "$FAKE_ADB_ARGS_LOG" || true
}

write_fake_readiness_script() {
  cat > "$TMP_DIR/adb-ready.sh" <<'FAKE_READY'
#!/usr/bin/env bash
set -euo pipefail
printf 'ADB ready: serial=%s state=device boot_completed=1 bootanim=stopped model=SM-S711B android=16\n' "${1:-RZ}"
FAKE_READY
  chmod +x "$TMP_DIR/adb-ready.sh"

  cat > "$TMP_DIR/adb-not-ready.sh" <<'FAKE_NOT_READY'
#!/usr/bin/env bash
set -euo pipefail
printf 'ADB not ready\n' >&2
exit 91
FAKE_NOT_READY
  chmod +x "$TMP_DIR/adb-not-ready.sh"
}

write_fake_ffmpeg() {
  cat > "$TMP_DIR/ffmpeg" <<'FAKE_FFMPEG'
#!/usr/bin/env bash
set -euo pipefail

output="${@: -1}"
input=""
if [[ "$#" -ne 13 || "$1" != "-hide_banner" || "$2" != "-f" || "$3" != "lavfi" ||
  "$4" != "-i" || "$6" != "-ar" || "$7" != "16000" || "$8" != "-ac" ||
  "$9" != "1" || "${10}" != "-f" || "${11}" != "s16le" || "${12}" != "-y" ||
  "${13}" != "$output" ]]; then
  printf 'unexpected ffmpeg argv order: %s\n' "$*" >&2
  exit 99
fi
input="$5"
expected_voice="${FAKE_FFMPEG_EXPECTED_VOICE:-slt}"
if [[ "$input" != flite=textfile=*":voice=$expected_voice" ]]; then
  printf 'unexpected ffmpeg flite input: %s\n' "$input" >&2
  printf 'expected voice: %s\n' "$expected_voice" >&2
  exit 98
fi
if [[ -n "${FAKE_FFMPEG_EXPECTED_OUTPUT:-}" && "$output" != "$FAKE_FFMPEG_EXPECTED_OUTPUT" ]]; then
  printf 'unexpected ffmpeg output path: %s\n' "$output" >&2
  exit 94
fi
textfile="${input#flite=textfile=}"
textfile="${textfile%:voice=$expected_voice}"
if [[ "${FAKE_FFMPEG_REJECT_FILTER_META_PATH:-0}" == "1" && "$textfile" == *[:\']* ]]; then
  printf 'ffmpeg textfile path contains unescaped filter metacharacters: %s\n' "$textfile" >&2
  exit 93
fi
if [[ ! -f "$textfile" ]]; then
  printf 'ffmpeg textfile does not exist: %s\n' "$textfile" >&2
  exit 97
fi
if [[ "$(cat "$textfile")" != "${FAKE_FFMPEG_EXPECTED_PROMPT:-}" ]]; then
  printf 'unexpected ffmpeg prompt text: %s\n' "$(cat "$textfile")" >&2
  exit 96
fi
printf '%s\n' "$textfile" > "${FAKE_FFMPEG_TEXTFILE_LOG:?}"
if [[ "${FAKE_FFMPEG_FAIL:-0}" == "1" ]]; then
  exit 95
fi
printf 'generated pcm' > "$output"
printf 'fake ffmpeg generated %s\n' "$output" >&2
FAKE_FFMPEG
  chmod +x "$TMP_DIR/ffmpeg"
}

write_fake_adb() {
  cat > "$TMP_DIR/adb" <<'FAKE_ADB'
#!/usr/bin/env bash
set -euo pipefail

print_large_artifact() {
  printf '%240sdiagnostic-tail' '' | tr ' ' A
}

print_large_artifact_preview() {
  printf '%240s' '' | tr ' ' A
}

if [[ -n "${FAKE_ADB_ARGS_LOG:-}" ]]; then
  printf '%s\n' "$*" >> "$FAKE_ADB_ARGS_LOG"
fi

args="$*"
case "$args" in
  "devices -l")
    printf 'List of devices attached\n'
    case "${FAKE_ADB_DEVICES_MODE:-single}" in
      none)
        ;;
      multiple)
        printf 'RZ device product:r11q model:SM-S711B device:r11q transport_id:1\n'
        printf 'R2 device product:r11q model:SM-S711B device:r11q transport_id:2\n'
        ;;
      *)
        printf 'RZ device product:r11q model:SM-S711B device:r11q transport_id:1\n'
        ;;
    esac
    ;;
  "-s RZ shell pm path me.rerere.rikkahub.debug")
    printf 'package:/data/app/test/base.apk\n'
    ;;
  "-s RZ shell getprop ro.product.model")
    printf 'SM-S711B\r\n'
    ;;
  "-s RZ shell getprop ro.build.version.release")
    printf '16\r\n'
    ;;
  "-s RZ logcat -c")
    ;;
  "-s RZ logcat -v time "*)
    cat <<'LOGS'
06-08 12:00:00.000 D/VoiceAgentGemini(1): event kind=SetupComplete
06-08 12:00:01.000 I/VoiceAudioDebugInjection(1): debug_audio_injection result delivered=true
LOGS
    if [[ "${FAKE_ADB_FORBIDDEN_AFTER_DEBUG:-0}" == "1" ]]; then
      sleep "${FAKE_ADB_FORBIDDEN_AFTER_DEBUG_DELAY_SECONDS:-1}"
      printf '06-08 12:00:01.500 E/VoiceAgentCallService(1): Voice Lab request failed 403\n'
    fi
    if [[ "${FAKE_ADB_SKIP_TOOL_CALL:-0}" != "1" ]]; then
      printf '06-08 12:00:02.000 D/VoiceAgentGemini(1): receive kind=toolCall\n'
    fi
    if [[ "${FAKE_ADB_FORBIDDEN_MARKER:-0}" == "1" ]]; then
      printf '06-08 12:00:02.500 E/VoiceAgentCallService(1): Voice Lab request failed 403\n'
    fi
    if [[ "${FAKE_ADB_SKIP_TOOL_CALL:-0}" != "1" ]]; then
      if [[ "${FAKE_ADB_ASYNC_QUEUE_FLOW:-0}" == "1" ]]; then
        cat <<'LOGS'
06-08 12:00:02.500 D/VoiceAgentE2E(1): hermes_queue_event type=job_created callId=call-1 jobId=job-1 status=queued sent=n/a
06-08 12:00:02.600 D/VoiceAgentGemini(1): send kind=toolResponse sent=true
06-08 12:00:03.000 D/VoiceAgentE2E(1): hermes_tool_response_hash callId=call-1, actualHash=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa, responseChars=25, normalizedChars=25, elapsedMs=100
06-08 12:00:03.500 D/VoiceAgentE2E(1): hermes_queue_event type=job_completed callId=call-1 jobId=job-1 status=succeeded sent=n/a
06-08 12:00:03.600 D/VoiceAgentGemini(1): send kind=clientContent sent=true
06-08 12:00:03.700 D/VoiceAgentE2E(1): hermes_queue_event type=late_text_turn_sent callId=call-1 jobId=none status=none sent=true
06-08 12:00:05.000 D/VoiceAgentGemini(1): event kind=OutputAudio
06-08 12:00:06.000 D/AndroidVoiceAudioEngine(1): Voice playback queued bytes=3200
06-08 12:00:07.000 D/AndroidVoiceAudioEngine(1): Voice playback wrote bytes=3200
LOGS
      else
        cat <<'LOGS'
06-08 12:00:03.000 D/VoiceAgentE2E(1): hermes_tool_response_hash callId=call-1, actualHash=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa, responseChars=25, normalizedChars=25, elapsedMs=100
06-08 12:00:04.000 D/VoiceAgentGemini(1): send kind=toolResponse sent=true
06-08 12:00:05.000 D/VoiceAgentGemini(1): event kind=OutputAudio
06-08 12:00:06.000 D/AndroidVoiceAudioEngine(1): Voice playback queued bytes=3200
06-08 12:00:07.000 D/AndroidVoiceAudioEngine(1): Voice playback wrote bytes=3200
LOGS
      fi
    fi
    deadline=$((SECONDS + 5))
    while [[ ! -f "${FAKE_ADB_END_MARKER:?}" && "$SECONDS" -lt "$deadline" ]]; do
      sleep 0.1
    done
    if [[ -f "${FAKE_ADB_END_MARKER:?}" ]]; then
      printf '06-08 12:00:08.000 D/VoiceAgentCallService(1): end completed conversationId=conversation-1\n'
    fi
    sleep 2
    ;;
  "-s RZ push "*)
    printf '%s: 1 file pushed, 0 skipped.\n' "$2"
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug mkdir -p files/voice-e2e")
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug cp /data/local/tmp/rikkahub-voice-agent-e2e-prompt.pcm files/voice-e2e/prompt.pcm")
    ;;
  "-s RZ shell rm -f /data/local/tmp/rikkahub-voice-agent-e2e-prompt.pcm")
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug rm -f files/voice-e2e/prompt.pcm")
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug rm -f no_backup/voice-e2e/hermes-answer.txt")
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug rm -f no_backup/voice-e2e/input-transcript.txt")
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug rm -f no_backup/voice-e2e/output-transcript.txt")
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug rm -f no_backup/voice-e2e/hermes-call.txt")
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug rm -f no_backup/voice-e2e/latest-trace-id.txt")
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug rm -f no_backup/voice-e2e/trace-gbrain/hermes-answer.txt")
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug rm -f no_backup/voice-e2e/trace-gbrain/input-transcript.txt")
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug rm -f no_backup/voice-e2e/trace-gbrain/output-transcript.txt")
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug rm -f no_backup/voice-e2e/trace-gbrain/hermes-call.txt")
    ;;
  "-s RZ shell am start-foreground-service -n me.rerere.rikkahub.debug/me.rerere.rikkahub.voiceagent.VoiceAgentCallService -a me.rerere.rikkahub.voiceagent.action.START --es conversationId conversation-1")
    rm -f "${FAKE_ADB_END_MARKER:?}"
    ;;
  "-s RZ shell am start-foreground-service -n me.rerere.rikkahub.debug/me.rerere.rikkahub.voiceagent.VoiceAgentCallService -a me.rerere.rikkahub.voiceagent.action.END")
    if [[ "${FAKE_ADB_FAIL_END_COMMAND:-0}" == "1" ]]; then
      exit 73
    fi
    if [[ "${FAKE_ADB_SKIP_END_MARKER:-0}" != "1" ]]; then
      : > "${FAKE_ADB_END_MARKER:?}"
    fi
    ;;
  "-s RZ shell am broadcast "*)
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug cat no_backup/voice-e2e/latest-trace-id.txt")
    case "${FAKE_ADB_LATEST_TRACE_ID:-trace-gbrain}" in
      missing)
        exit 1
        ;;
      late)
        counter_file="${FAKE_ADB_LATE_TRACE_COUNTER:?}"
        counter=0
        if [[ -f "$counter_file" ]]; then
          counter="$(cat "$counter_file")"
        fi
        counter=$((counter + 1))
        printf '%s' "$counter" > "$counter_file"
        if (( counter <= ${FAKE_ADB_LATE_TRACE_MISSING_COUNT:-1} )); then
          exit 1
        fi
        printf 'trace-gbrain'
        ;;
      *)
        printf '%s' "${FAKE_ADB_LATEST_TRACE_ID:-trace-gbrain}"
        ;;
    esac
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug cat no_backup/voice-e2e/trace-gbrain/hermes-answer.txt")
    if [[ "${FAKE_ADB_MISSING_ANSWER:-0}" == "1" ]]; then
      exit 1
    fi
    printf 'manual answer from Hermes'
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug cat no_backup/voice-e2e/trace-gbrain/input-transcript.txt")
    if [[ "${FAKE_ADB_MISSING_REPORT_ARTIFACTS:-0}" == "1" ]]; then
      exit 1
    fi
    if [[ "${FAKE_ADB_LARGE_DIAGNOSTIC_ARTIFACTS:-0}" == "1" ]]; then
      print_large_artifact
      exit 0
    fi
    printf 'Please ask Hermes if he is connected to G-Brain.'
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug cat no_backup/voice-e2e/trace-gbrain/hermes-call.txt")
    if [[ "${FAKE_ADB_MISSING_REPORT_ARTIFACTS:-0}" == "1" ]]; then
      exit 1
    fi
    if [[ "${FAKE_ADB_LARGE_DIAGNOSTIC_ARTIFACTS:-0}" == "1" ]]; then
      print_large_artifact
      exit 0
    fi
    printf 'Is Hermes connected to G-Brain? Answer yes or no.'
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug cat no_backup/voice-e2e/trace-gbrain/output-transcript.txt")
    if [[ "${FAKE_ADB_MISSING_REPORT_ARTIFACTS:-0}" == "1" ]]; then
      exit 1
    fi
    if [[ "${FAKE_ADB_LARGE_DIAGNOSTIC_ARTIFACTS:-0}" == "1" ]]; then
      print_large_artifact
      exit 0
    fi
    printf 'Yes, Hermes is connected to G-Brain.'
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug head -c 240 no_backup/voice-e2e/trace-gbrain/input-transcript.txt")
    if [[ "${FAKE_ADB_MISSING_REPORT_ARTIFACTS:-0}" == "1" ]]; then
      exit 1
    fi
    if [[ -n "${FAKE_ADB_DIAGNOSTIC_DELAY_SECONDS:-}" ]]; then
      sleep "$FAKE_ADB_DIAGNOSTIC_DELAY_SECONDS"
    fi
    if [[ "${FAKE_ADB_LARGE_DIAGNOSTIC_ARTIFACTS:-0}" == "1" ]]; then
      print_large_artifact_preview
      exit 0
    fi
    printf 'Please ask Hermes if he is connected to G-Brain.'
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug head -c 240 no_backup/voice-e2e/trace-gbrain/hermes-call.txt")
    if [[ "${FAKE_ADB_MISSING_REPORT_ARTIFACTS:-0}" == "1" ]]; then
      exit 1
    fi
    if [[ -n "${FAKE_ADB_DIAGNOSTIC_DELAY_SECONDS:-}" ]]; then
      sleep "$FAKE_ADB_DIAGNOSTIC_DELAY_SECONDS"
    fi
    if [[ "${FAKE_ADB_LARGE_DIAGNOSTIC_ARTIFACTS:-0}" == "1" ]]; then
      print_large_artifact_preview
      exit 0
    fi
    printf 'Is Hermes connected to G-Brain? Answer yes or no.'
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug head -c 240 no_backup/voice-e2e/trace-gbrain/output-transcript.txt")
    if [[ "${FAKE_ADB_MISSING_REPORT_ARTIFACTS:-0}" == "1" ]]; then
      exit 1
    fi
    if [[ -n "${FAKE_ADB_DIAGNOSTIC_DELAY_SECONDS:-}" ]]; then
      sleep "$FAKE_ADB_DIAGNOSTIC_DELAY_SECONDS"
    fi
    if [[ "${FAKE_ADB_LARGE_DIAGNOSTIC_ARTIFACTS:-0}" == "1" ]]; then
      print_large_artifact_preview
      exit 0
    fi
    printf 'Yes, Hermes is connected to G-Brain.'
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug cat no_backup/voice-e2e/hermes-answer.txt")
    if [[ "${FAKE_ADB_MISSING_ANSWER:-0}" == "1" ]]; then
      exit 1
    fi
    if [[ "${FAKE_ADB_MISSING_BASE_ANSWER:-0}" == "1" ]]; then
      exit 1
    fi
    printf 'manual answer from Hermes'
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug cat no_backup/voice-e2e/input-transcript.txt")
    if [[ "${FAKE_ADB_MISSING_REPORT_ARTIFACTS:-0}" == "1" ]]; then
      exit 1
    fi
    if [[ "${FAKE_ADB_LARGE_DIAGNOSTIC_ARTIFACTS:-0}" == "1" ]]; then
      print_large_artifact
      exit 0
    fi
    printf 'Please ask Hermes if he is connected to G-Brain.'
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug cat no_backup/voice-e2e/hermes-call.txt")
    if [[ "${FAKE_ADB_MISSING_REPORT_ARTIFACTS:-0}" == "1" ]]; then
      exit 1
    fi
    if [[ "${FAKE_ADB_LARGE_DIAGNOSTIC_ARTIFACTS:-0}" == "1" ]]; then
      print_large_artifact
      exit 0
    fi
    printf 'Is Hermes connected to G-Brain? Answer yes or no.'
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug cat no_backup/voice-e2e/output-transcript.txt")
    if [[ "${FAKE_ADB_MISSING_REPORT_ARTIFACTS:-0}" == "1" ]]; then
      exit 1
    fi
    if [[ "${FAKE_ADB_LARGE_DIAGNOSTIC_ARTIFACTS:-0}" == "1" ]]; then
      print_large_artifact
      exit 0
    fi
    printf 'Yes, Hermes is connected to G-Brain.'
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug head -c 240 no_backup/voice-e2e/input-transcript.txt")
    if [[ "${FAKE_ADB_MISSING_REPORT_ARTIFACTS:-0}" == "1" ]]; then
      exit 1
    fi
    if [[ -n "${FAKE_ADB_DIAGNOSTIC_DELAY_SECONDS:-}" ]]; then
      sleep "$FAKE_ADB_DIAGNOSTIC_DELAY_SECONDS"
    fi
    if [[ "${FAKE_ADB_LARGE_DIAGNOSTIC_ARTIFACTS:-0}" == "1" ]]; then
      print_large_artifact_preview
      exit 0
    fi
    printf 'Please ask Hermes if he is connected to G-Brain.'
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug head -c 240 no_backup/voice-e2e/hermes-call.txt")
    if [[ "${FAKE_ADB_MISSING_REPORT_ARTIFACTS:-0}" == "1" ]]; then
      exit 1
    fi
    if [[ -n "${FAKE_ADB_DIAGNOSTIC_DELAY_SECONDS:-}" ]]; then
      sleep "$FAKE_ADB_DIAGNOSTIC_DELAY_SECONDS"
    fi
    if [[ "${FAKE_ADB_LARGE_DIAGNOSTIC_ARTIFACTS:-0}" == "1" ]]; then
      print_large_artifact_preview
      exit 0
    fi
    printf 'Is Hermes connected to G-Brain? Answer yes or no.'
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug head -c 240 no_backup/voice-e2e/output-transcript.txt")
    if [[ "${FAKE_ADB_MISSING_REPORT_ARTIFACTS:-0}" == "1" ]]; then
      exit 1
    fi
    if [[ -n "${FAKE_ADB_DIAGNOSTIC_DELAY_SECONDS:-}" ]]; then
      sleep "$FAKE_ADB_DIAGNOSTIC_DELAY_SECONDS"
    fi
    if [[ "${FAKE_ADB_LARGE_DIAGNOSTIC_ARTIFACTS:-0}" == "1" ]]; then
      print_large_artifact_preview
      exit 0
    fi
    printf 'Yes, Hermes is connected to G-Brain.'
    ;;
  *)
    printf 'unexpected adb args: %s\n' "$args" >&2
    exit 99
    ;;
esac
FAKE_ADB
  chmod +x "$TMP_DIR/adb"
}

write_fake_readiness_script
write_fake_ffmpeg
write_fake_adb
printf 'pcm' > "$TMP_DIR/prompt.pcm"
FAKE_ADB_ARGS_LOG="$TMP_DIR/adb-args.log"
FAKE_FFMPEG_TEXTFILE_LOG="$TMP_DIR/ffmpeg-textfile.log"
FAKE_ADB_END_MARKER="$TMP_DIR/adb-end-requested"
export FAKE_ADB_ARGS_LOG
export FAKE_FFMPEG_TEXTFILE_LOG
export FAKE_ADB_END_MARKER
: > "$FAKE_ADB_ARGS_LOG"

before_invalid_package_adb_lines="$(wc -l < "$FAKE_ADB_ARGS_LOG" 2>/dev/null || printf '0')"
set +e
invalid_package_output="$(
  PATH="$TMP_DIR:$PATH" \
  VOICE_AGENT_E2E_PACKAGE='me.rerere.rikkahub.debug;id' \
  "$SCRIPT" 2>&1
)"
invalid_package_status=$?
set -e

if [[ "$invalid_package_status" -eq 0 ]]; then
  printf 'Expected invalid package run to fail.\n' >&2
  printf 'Actual output:\n%s\n' "$invalid_package_output" >&2
  exit 1
fi
assert_contains "$invalid_package_output" \
  "VOICE_AGENT_E2E_PACKAGE must be an Android package name: me.rerere.rikkahub.debug;id"
after_invalid_package_adb_lines="$(wc -l < "$FAKE_ADB_ARGS_LOG" 2>/dev/null || printf '0')"
if [[ "$after_invalid_package_adb_lines" != "$before_invalid_package_adb_lines" ]]; then
  printf 'Expected invalid package validation to run before ADB commands.\n' >&2
  printf 'ADB args:\n%s\n' "$(cat "$FAKE_ADB_ARGS_LOG")" >&2
  exit 1
fi

stale_trace_log_dir="$TMP_DIR/stale-trace-log"
mkdir -p "$stale_trace_log_dir"
printf 'old-trace' > "$stale_trace_log_dir/trace-id.txt"
set +e
stale_trace_output="$(
  PATH="$TMP_DIR:$PATH" \
  VOICE_AGENT_E2E_PCM_PATH="$TMP_DIR/prompt.pcm" \
  VOICE_AGENT_E2E_LOG_DIR="$stale_trace_log_dir" \
  VOICE_AGENT_E2E_MANUAL_REVIEW=1 \
  "$SCRIPT" 2>&1
)"
stale_trace_status=$?
set -e

if [[ "$stale_trace_status" -eq 0 ]]; then
  printf 'Expected missing conversation id run to fail.\n' >&2
  printf 'Actual output:\n%s\n' "$stale_trace_output" >&2
  exit 1
fi
assert_contains "$stale_trace_output" "Missing required environment variable: VOICE_AGENT_E2E_CONVERSATION_ID"
assert_file_does_not_exist "$stale_trace_log_dir/trace-id.txt"

expected_hash="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
manual_log_dir="$TMP_DIR/manual-log"

set +e
manual_output="$(
  PATH="$TMP_DIR:$PATH" \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_E2E_EXPECTED_HASH="$expected_hash" \
  VOICE_AGENT_E2E_PCM_PATH="$TMP_DIR/prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_E2E_LOG_DIR="$manual_log_dir" \
  VOICE_AGENT_E2E_MANUAL_REVIEW=1 \
  VOICE_AGENT_E2E_GEMINI_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_E2E_HERMES_RESPONSE_TIMEOUT_SECONDS=5 \
  "$SCRIPT" 2>&1
)"
manual_status=$?
set -e

if [[ "$manual_status" -ne 0 ]]; then
  printf 'Expected manual mode to pass, got status %s.\n' "$manual_status" >&2
  printf 'Actual output:\n%s\n' "$manual_output" >&2
  exit 1
fi

assert_contains "$manual_output" "PASS marker: Hermes response hash observed for manual review"
assert_contains "$manual_output" "Manual review answer artifact: $manual_log_dir/manual-hermes-answer.txt"
assert_contains "$manual_output" "PIPELINE: passed"
assert_contains "$manual_output" "CLEANUP: passed"
assert_contains "$manual_output" "Voice Agent Hermes/Gbrain live E2E reached manual review gate."
assert_file_contains_exactly "$manual_log_dir/manual-hermes-answer.txt" "manual answer from Hermes"
assert_file_contains_exactly "$manual_log_dir/trace-id.txt" "trace-gbrain"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "rm -f no_backup/voice-e2e/hermes-answer.txt"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "rm -f no_backup/voice-e2e/input-transcript.txt"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "rm -f no_backup/voice-e2e/output-transcript.txt"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "rm -f no_backup/voice-e2e/hermes-call.txt"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "cat no_backup/voice-e2e/latest-trace-id.txt"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "rm -f no_backup/voice-e2e/trace-gbrain/hermes-answer.txt"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "rm -f no_backup/voice-e2e/trace-gbrain/input-transcript.txt"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "rm -f no_backup/voice-e2e/trace-gbrain/output-transcript.txt"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "rm -f no_backup/voice-e2e/trace-gbrain/hermes-call.txt"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "rm -f no_backup/voice-e2e/latest-trace-id.txt"
assert_last_line_after "$FAKE_ADB_ARGS_LOG" \
  "exec-out run-as me.rerere.rikkahub.debug cat no_backup/voice-e2e/trace-gbrain/hermes-answer.txt" \
  "-a me.rerere.rikkahub.voiceagent.action.END"
assert_last_line_after "$FAKE_ADB_ARGS_LOG" \
  "-a me.rerere.rikkahub.voiceagent.action.END" \
  "rm -f no_backup/voice-e2e/trace-gbrain/hermes-answer.txt"
assert_last_line_after "$FAKE_ADB_ARGS_LOG" \
  "-a me.rerere.rikkahub.voiceagent.action.END" \
  "rm -f no_backup/voice-e2e/trace-gbrain/input-transcript.txt"
assert_last_line_after "$FAKE_ADB_ARGS_LOG" \
  "-a me.rerere.rikkahub.voiceagent.action.END" \
  "rm -f no_backup/voice-e2e/trace-gbrain/output-transcript.txt"
assert_last_line_after "$FAKE_ADB_ARGS_LOG" \
  "-a me.rerere.rikkahub.voiceagent.action.END" \
  "rm -f no_backup/voice-e2e/trace-gbrain/hermes-call.txt"
assert_last_line_after "$FAKE_ADB_ARGS_LOG" \
  "rm -f no_backup/voice-e2e/trace-gbrain/hermes-call.txt" \
  "rm -f no_backup/voice-e2e/latest-trace-id.txt"

fallback_manual_log_dir="$TMP_DIR/manual-fallback-log"
: > "$FAKE_ADB_ARGS_LOG"
set +e
fallback_manual_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_ADB_LATEST_TRACE_ID=missing \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_E2E_EXPECTED_HASH="$expected_hash" \
  VOICE_AGENT_E2E_PCM_PATH="$TMP_DIR/prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_E2E_LOG_DIR="$fallback_manual_log_dir" \
  VOICE_AGENT_E2E_MANUAL_REVIEW=1 \
  VOICE_AGENT_E2E_GEMINI_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_E2E_HERMES_RESPONSE_TIMEOUT_SECONDS=5 \
  "$SCRIPT" 2>&1
)"
fallback_manual_status=$?
set -e

if [[ "$fallback_manual_status" -ne 0 ]]; then
  printf 'Expected missing latest trace manual fallback to pass, got status %s.\n' "$fallback_manual_status" >&2
  printf 'Actual output:\n%s\n' "$fallback_manual_output" >&2
  exit 1
fi
assert_contains "$fallback_manual_output" "Manual review answer artifact: $fallback_manual_log_dir/manual-hermes-answer.txt"
assert_contains "$fallback_manual_output" "Voice Agent Hermes/Gbrain live E2E reached manual review gate."
assert_file_contains_exactly "$fallback_manual_log_dir/manual-hermes-answer.txt" "manual answer from Hermes"
assert_file_does_not_exist "$fallback_manual_log_dir/trace-id.txt"
assert_file_contains "$fallback_manual_log_dir/report.txt" "Please ask Hermes if he is connected to G-Brain."
assert_file_contains "$fallback_manual_log_dir/report.txt" "Is Hermes connected to G-Brain? Answer yes or no."
assert_file_contains "$fallback_manual_log_dir/report.txt" "Yes, Hermes is connected to G-Brain."
assert_file_contains "$FAKE_ADB_ARGS_LOG" "cat no_backup/voice-e2e/latest-trace-id.txt"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "cat no_backup/voice-e2e/hermes-answer.txt"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "cat no_backup/voice-e2e/input-transcript.txt"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "cat no_backup/voice-e2e/hermes-call.txt"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "cat no_backup/voice-e2e/output-transcript.txt"
if grep -F -- "exec-out run-as me.rerere.rikkahub.debug cat no_backup/voice-e2e/trace-gbrain/" "$FAKE_ADB_ARGS_LOG" >/dev/null; then
  printf 'Expected missing latest trace fallback not to pull trace-keyed GBrain artifacts.\n' >&2
  printf 'ADB args:\n%s\n' "$(cat "$FAKE_ADB_ARGS_LOG")" >&2
  exit 1
fi

unsafe_fallback_manual_log_dir="$TMP_DIR/manual-unsafe-fallback-log"
: > "$FAKE_ADB_ARGS_LOG"
set +e
unsafe_fallback_manual_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_ADB_LATEST_TRACE_ID='../trace-gbrain' \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_E2E_EXPECTED_HASH="$expected_hash" \
  VOICE_AGENT_E2E_PCM_PATH="$TMP_DIR/prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_E2E_LOG_DIR="$unsafe_fallback_manual_log_dir" \
  VOICE_AGENT_E2E_MANUAL_REVIEW=1 \
  VOICE_AGENT_E2E_GEMINI_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_E2E_HERMES_RESPONSE_TIMEOUT_SECONDS=5 \
  "$SCRIPT" 2>&1
)"
unsafe_fallback_manual_status=$?
set -e

if [[ "$unsafe_fallback_manual_status" -ne 0 ]]; then
  printf 'Expected unsafe latest trace manual fallback to pass, got status %s.\n' "$unsafe_fallback_manual_status" >&2
  printf 'Actual output:\n%s\n' "$unsafe_fallback_manual_output" >&2
  exit 1
fi
assert_contains "$unsafe_fallback_manual_output" "Manual review answer artifact: $unsafe_fallback_manual_log_dir/manual-hermes-answer.txt"
assert_file_does_not_exist "$unsafe_fallback_manual_log_dir/trace-id.txt"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "cat no_backup/voice-e2e/latest-trace-id.txt"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "cat no_backup/voice-e2e/hermes-answer.txt"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "cat no_backup/voice-e2e/input-transcript.txt"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "cat no_backup/voice-e2e/hermes-call.txt"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "cat no_backup/voice-e2e/output-transcript.txt"
if grep -F -- "exec-out run-as me.rerere.rikkahub.debug cat no_backup/voice-e2e/trace-gbrain/" "$FAKE_ADB_ARGS_LOG" >/dev/null; then
  printf 'Expected unsafe latest trace fallback not to pull trace-keyed GBrain artifacts.\n' >&2
  printf 'ADB args:\n%s\n' "$(cat "$FAKE_ADB_ARGS_LOG")" >&2
  exit 1
fi

manual_no_hash_log_dir="$TMP_DIR/manual-no-hash-log"
set +e
manual_no_hash_output="$(
  PATH="$TMP_DIR:$PATH" \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_E2E_PCM_PATH="$TMP_DIR/prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_E2E_LOG_DIR="$manual_no_hash_log_dir" \
  VOICE_AGENT_E2E_MANUAL_REVIEW=1 \
  VOICE_AGENT_E2E_GEMINI_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_E2E_HERMES_RESPONSE_TIMEOUT_SECONDS=5 \
  "$SCRIPT" 2>&1
)"
manual_no_hash_status=$?
set -e

if [[ "$manual_no_hash_status" -ne 0 ]]; then
  printf 'Expected manual mode without expected hash to pass, got status %s.\n' "$manual_no_hash_status" >&2
  printf 'Actual output:\n%s\n' "$manual_no_hash_output" >&2
  exit 1
fi
assert_contains "$manual_no_hash_output" "Voice Agent Hermes/Gbrain live E2E reached manual review gate."
assert_contains "$manual_no_hash_output" "E2E preflight:"
assert_contains "$manual_no_hash_output" "  adb server: ${ADB_SERVER_SOCKET:-default local adb server}"
assert_contains "$manual_no_hash_output" "  selected serial: RZ"
assert_contains "$manual_no_hash_output" "  device: model=SM-S711B android=16"
assert_contains "$manual_no_hash_output" "  package: me.rerere.rikkahub.debug installed"

async_queue_log_dir="$TMP_DIR/async-queue-log"
set +e
async_queue_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_ADB_ASYNC_QUEUE_FLOW=1 \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_E2E_PCM_PATH="$TMP_DIR/prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_E2E_LOG_DIR="$async_queue_log_dir" \
  VOICE_AGENT_E2E_MANUAL_REVIEW=1 \
  VOICE_AGENT_E2E_GEMINI_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_E2E_HERMES_RESPONSE_TIMEOUT_SECONDS=5 \
  "$SCRIPT" 2>&1
)"
async_queue_status=$?
set -e

if [[ "$async_queue_status" -ne 0 ]]; then
  printf 'Expected async queue manual mode to pass, got status %s.\n' "$async_queue_status" >&2
  printf 'Actual output:\n%s\n' "$async_queue_output" >&2
  exit 1
fi
assert_contains "$async_queue_output" "PASS marker: queued acknowledgement sent"
assert_contains "$async_queue_output" "PASS marker: Hermes response hash observed for manual review"
assert_contains "$async_queue_output" "PASS marker: completed Hermes answer sent to Gemini"
assert_contains "$async_queue_output" "Voice Agent Hermes/Gbrain live E2E reached manual review gate."

explicit_serial_multiple_devices_log_dir="$TMP_DIR/explicit-serial-multiple-devices-log"
: > "$FAKE_ADB_ARGS_LOG"
set +e
explicit_serial_multiple_devices_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_ADB_DEVICES_MODE=multiple \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_E2E_PCM_PATH="$TMP_DIR/prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_E2E_LOG_DIR="$explicit_serial_multiple_devices_log_dir" \
  VOICE_AGENT_E2E_MANUAL_REVIEW=1 \
  VOICE_AGENT_E2E_GEMINI_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_E2E_HERMES_RESPONSE_TIMEOUT_SECONDS=5 \
  "$SCRIPT" 2>&1
)"
explicit_serial_multiple_devices_status=$?
set -e

if [[ "$explicit_serial_multiple_devices_status" -ne 0 ]]; then
  printf 'Expected explicit serial run with multiple authorized devices to pass.\n' >&2
  printf 'Actual output:\n%s\n' "$explicit_serial_multiple_devices_output" >&2
  printf 'ADB args:\n%s\n' "$(cat "$FAKE_ADB_ARGS_LOG")" >&2
  exit 1
fi
if grep -F -- "devices -l" "$FAKE_ADB_ARGS_LOG" >/dev/null; then
  printf 'Expected explicit serial run to skip ADB device discovery.\n' >&2
  printf 'ADB args:\n%s\n' "$(cat "$FAKE_ADB_ARGS_LOG")" >&2
  exit 1
fi

unset_serial_log_dir="$TMP_DIR/unset-serial-log"
: > "$FAKE_ADB_ARGS_LOG"
set +e
unset_serial_output="$(
  PATH="$TMP_DIR:$PATH" \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_E2E_PCM_PATH="$TMP_DIR/prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_E2E_LOG_DIR="$unset_serial_log_dir" \
  VOICE_AGENT_E2E_MANUAL_REVIEW=1 \
  VOICE_AGENT_E2E_GEMINI_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_E2E_HERMES_RESPONSE_TIMEOUT_SECONDS=5 \
  "$SCRIPT" 2>&1
)"
unset_serial_status=$?
set -e

if [[ "$unset_serial_status" -ne 0 ]]; then
  printf 'Expected unset serial manual mode to pass, got status %s.\n' "$unset_serial_status" >&2
  printf 'Actual output:\n%s\n' "$unset_serial_output" >&2
  printf 'ADB args:\n%s\n' "$(cat "$FAKE_ADB_ARGS_LOG")" >&2
  exit 1
fi
assert_contains "$unset_serial_output" "E2E preflight:"
assert_contains "$unset_serial_output" "  selected serial: RZ"
assert_contains "$unset_serial_output" "  package: me.rerere.rikkahub.debug installed"
assert_contains_in_order "$unset_serial_output" \
  "  package: me.rerere.rikkahub.debug installed" \
  "Clearing previous app-private E2E artifacts..."
assert_file_contains "$FAKE_ADB_ARGS_LOG" "devices -l"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "-s RZ shell getprop ro.product.model"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "-s RZ shell pm path me.rerere.rikkahub.debug"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "-s RZ logcat -c"
assert_first_line_after "$FAKE_ADB_ARGS_LOG" \
  "-s RZ shell getprop ro.build.version.release" \
  "-s RZ shell run-as me.rerere.rikkahub.debug rm -f no_backup/voice-e2e/hermes-answer.txt"

multiple_serials_log_dir="$TMP_DIR/multiple-serials-log"
: > "$FAKE_ADB_ARGS_LOG"
set +e
multiple_serials_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_ADB_DEVICES_MODE=multiple \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_E2E_PCM_PATH="$TMP_DIR/prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_E2E_LOG_DIR="$multiple_serials_log_dir" \
  VOICE_AGENT_E2E_MANUAL_REVIEW=1 \
  "$SCRIPT" 2>&1
)"
multiple_serials_status=$?
set -e

if [[ "$multiple_serials_status" -eq 0 ]]; then
  printf 'Expected unset serial run with multiple authorized devices to fail.\n' >&2
  printf 'Actual output:\n%s\n' "$multiple_serials_output" >&2
  printf 'ADB args:\n%s\n' "$(cat "$FAKE_ADB_ARGS_LOG")" >&2
  exit 1
fi
assert_contains "$multiple_serials_output" \
  "Expected exactly one authorized ADB device, found 2. Set VOICE_AGENT_E2E_SERIAL."
if grep -F -- "shell pm path" "$FAKE_ADB_ARGS_LOG" >/dev/null; then
  printf 'Expected multiple-device serial failure before package checks.\n' >&2
  printf 'ADB args:\n%s\n' "$(cat "$FAKE_ADB_ARGS_LOG")" >&2
  exit 1
fi
if grep -F -- "logcat" "$FAKE_ADB_ARGS_LOG" >/dev/null; then
  printf 'Expected multiple-device serial failure before logcat.\n' >&2
  printf 'ADB args:\n%s\n' "$(cat "$FAKE_ADB_ARGS_LOG")" >&2
  exit 1
fi

missing_serial_log_dir="$TMP_DIR/missing-serial-log"
: > "$FAKE_ADB_ARGS_LOG"
set +e
missing_serial_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_ADB_DEVICES_MODE=none \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_E2E_PCM_PATH="$TMP_DIR/prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_E2E_LOG_DIR="$missing_serial_log_dir" \
  VOICE_AGENT_E2E_MANUAL_REVIEW=1 \
  "$SCRIPT" 2>&1
)"
missing_serial_status=$?
set -e

if [[ "$missing_serial_status" -eq 0 ]]; then
  printf 'Expected unset serial run without authorized devices to fail.\n' >&2
  printf 'Actual output:\n%s\n' "$missing_serial_output" >&2
  printf 'ADB args:\n%s\n' "$(cat "$FAKE_ADB_ARGS_LOG")" >&2
  exit 1
fi
assert_contains "$missing_serial_output" \
  "Expected exactly one authorized ADB device, found 0. Set VOICE_AGENT_E2E_SERIAL."
if grep -F -- "shell pm path" "$FAKE_ADB_ARGS_LOG" >/dev/null; then
  printf 'Expected missing-device serial failure before package checks.\n' >&2
  printf 'ADB args:\n%s\n' "$(cat "$FAKE_ADB_ARGS_LOG")" >&2
  exit 1
fi
if grep -F -- "logcat" "$FAKE_ADB_ARGS_LOG" >/dev/null; then
  printf 'Expected missing-device serial failure before logcat.\n' >&2
  printf 'ADB args:\n%s\n' "$(cat "$FAKE_ADB_ARGS_LOG")" >&2
  exit 1
fi

cleanup_failure_log_dir="$TMP_DIR/cleanup-failure-log"
rm -f "$FAKE_ADB_END_MARKER"
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
assert_contains_in_order "$cleanup_failure_output" \
  "PASS marker: Voice playback wrote" \
  "Manual review answer artifact: $cleanup_failure_log_dir/manual-hermes-answer.txt"
assert_contains_in_order "$cleanup_failure_output" \
  "Manual review answer artifact: $cleanup_failure_log_dir/manual-hermes-answer.txt" \
  "Voice Agent E2E report: $cleanup_failure_log_dir/report.txt"
assert_contains_in_order "$cleanup_failure_output" \
  "Voice Agent E2E report: $cleanup_failure_log_dir/report.txt" \
  "CLEANUP: failed - service end marker not observed"
assert_file_contains_exactly "$cleanup_failure_log_dir/manual-hermes-answer.txt" "manual answer from Hermes"
assert_file_contains "$cleanup_failure_log_dir/report.txt" "Hermes answer:"

cleanup_command_failure_log_dir="$TMP_DIR/cleanup-command-failure-log"
rm -f "$FAKE_ADB_END_MARKER"
set +e
cleanup_command_failure_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_ADB_FAIL_END_COMMAND=1 \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_E2E_PCM_PATH="$TMP_DIR/prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_E2E_LOG_DIR="$cleanup_command_failure_log_dir" \
  VOICE_AGENT_E2E_MANUAL_REVIEW=1 \
  VOICE_AGENT_E2E_GEMINI_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_E2E_HERMES_RESPONSE_TIMEOUT_SECONDS=5 \
  "$SCRIPT" 2>&1
)"
cleanup_command_failure_status=$?
set -e

if [[ "$cleanup_command_failure_status" -eq 0 ]]; then
  printf 'Expected cleanup command failure run to exit nonzero.\n' >&2
  printf 'Actual output:\n%s\n' "$cleanup_command_failure_output" >&2
  exit 1
fi
assert_contains "$cleanup_command_failure_output" "PASS marker: Voice playback wrote"
assert_contains "$cleanup_command_failure_output" "Manual review answer artifact: $cleanup_command_failure_log_dir/manual-hermes-answer.txt"
assert_contains "$cleanup_command_failure_output" "Voice Agent E2E report: $cleanup_command_failure_log_dir/report.txt"
assert_contains "$cleanup_command_failure_output" "PIPELINE: passed"
assert_contains "$cleanup_command_failure_output" "CLEANUP: failed - service end command failed"
assert_file_contains_exactly "$cleanup_command_failure_log_dir/manual-hermes-answer.txt" "manual answer from Hermes"
assert_file_contains "$cleanup_command_failure_log_dir/report.txt" "Hermes answer:"

readiness_failure_log_dir="$TMP_DIR/readiness-failure-log"
before_readiness_failure_adb_lines="$(wc -l < "$FAKE_ADB_ARGS_LOG")"
set +e
readiness_failure_output="$(
  PATH="$TMP_DIR:$PATH" \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-not-ready.sh" \
  VOICE_AGENT_E2E_PCM_PATH="$TMP_DIR/prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_E2E_LOG_DIR="$readiness_failure_log_dir" \
  VOICE_AGENT_E2E_MANUAL_REVIEW=1 \
  "$SCRIPT" 2>&1
)"
readiness_failure_status=$?
set -e

if [[ "$readiness_failure_status" -eq 0 ]]; then
  printf 'Expected readiness failure run to fail.\n' >&2
  printf 'Actual output:\n%s\n' "$readiness_failure_output" >&2
  exit 1
fi
after_readiness_failure_adb_lines="$(wc -l < "$FAKE_ADB_ARGS_LOG")"
if [[ "$after_readiness_failure_adb_lines" != "$before_readiness_failure_adb_lines" ]]; then
  printf 'Expected readiness failure to skip ADB cleanup before readiness.\n' >&2
  printf 'ADB args:\n%s\n' "$(cat "$FAKE_ADB_ARGS_LOG")" >&2
  exit 1
fi

generated_log_dir="$TMP_DIR/generated-log"
mkdir -p "$generated_log_dir"
printf 'stale report' > "$generated_log_dir/report.txt"
printf 'stale input' > "$generated_log_dir/input-transcript.txt"
printf 'stale call' > "$generated_log_dir/hermes-call.txt"
printf 'stale output' > "$generated_log_dir/output-transcript.txt"
chmod 644 \
  "$generated_log_dir/report.txt" \
  "$generated_log_dir/input-transcript.txt" \
  "$generated_log_dir/hermes-call.txt" \
  "$generated_log_dir/output-transcript.txt"
set +e
generated_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_FFMPEG_EXPECTED_PROMPT="Call the ask Hermes tool. Ask Hermes: are you connected to G Brain? Do not answer yourself." \
  FAKE_FFMPEG_EXPECTED_VOICE=slt \
  FAKE_FFMPEG_EXPECTED_OUTPUT="$generated_log_dir/generated-prompt.pcm" \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_E2E_EXPECTED_HASH="$expected_hash" \
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
assert_file_contains_exactly "$generated_log_dir/generated-prompt.txt" \
  "Call the ask Hermes tool. Ask Hermes: are you connected to G Brain? Do not answer yourself."
report_path="$generated_log_dir/report.txt"
if [[ ! -f "$report_path" ]]; then
  printf 'Expected report file to exist: %s\n' "$report_path" >&2
  exit 1
fi
report_contents="$(cat "$report_path")"
assert_contains "$report_contents" "Text used to generate voice:"
assert_contains "$report_contents" "Call the ask Hermes tool. Ask Hermes: are you connected to G Brain? Do not answer yourself."
assert_contains "$report_contents" "Gemini understood from voice:"
assert_contains "$report_contents" "Please ask Hermes if he is connected to G-Brain."
assert_contains "$report_contents" "Hermes call:"
assert_contains "$report_contents" "Is Hermes connected to G-Brain? Answer yes or no."
assert_contains "$report_contents" "Hermes elapsed time:"
assert_contains "$report_contents" "elapsedMs=100"
assert_contains "$report_contents" "Hermes answer:"
assert_contains "$report_contents" "manual answer from Hermes"
assert_contains "$report_contents" "Gemini response to user:"
assert_contains "$report_contents" "Yes, Hermes is connected to G-Brain."
assert_contains "$generated_output" "Voice Agent E2E report: $report_path"
assert_last_line_after "$FAKE_ADB_ARGS_LOG" \
  "exec-out run-as me.rerere.rikkahub.debug cat no_backup/voice-e2e/trace-gbrain/input-transcript.txt" \
  "-a me.rerere.rikkahub.voiceagent.action.END"
assert_last_line_after "$FAKE_ADB_ARGS_LOG" \
  "exec-out run-as me.rerere.rikkahub.debug cat no_backup/voice-e2e/trace-gbrain/hermes-call.txt" \
  "-a me.rerere.rikkahub.voiceagent.action.END"
assert_last_line_after "$FAKE_ADB_ARGS_LOG" \
  "exec-out run-as me.rerere.rikkahub.debug cat no_backup/voice-e2e/trace-gbrain/output-transcript.txt" \
  "-a me.rerere.rikkahub.voiceagent.action.END"
assert_no_report_temp_files "$generated_log_dir"
report_mode="$(stat -c '%a' "$report_path")"
if [[ "$report_mode" != "600" ]]; then
  printf 'Expected report mode 600, got %s\n' "$report_mode" >&2
  exit 1
fi
for extra_artifact in input-transcript.txt hermes-call.txt output-transcript.txt; do
  if [[ -e "$generated_log_dir/$extra_artifact" ]]; then
    printf 'Expected report assembly to remove extra private artifact: %s\n' "$generated_log_dir/$extra_artifact" >&2
    exit 1
  fi
done

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

invalid_voice_log_dir="$TMP_DIR/invalid-voice-log"
adb_log_lines_before_invalid_voice="$(wc -l < "$FAKE_ADB_ARGS_LOG")"
set +e
invalid_voice_output="$(
  PATH="$TMP_DIR:$PATH" \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_E2E_PROMPT_TEXT="Prompt with invalid voice." \
  VOICE_AGENT_E2E_FLITE_VOICE='kal:bad' \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_E2E_LOG_DIR="$invalid_voice_log_dir" \
  VOICE_AGENT_E2E_MANUAL_REVIEW=1 \
  "$SCRIPT" 2>&1
)"
invalid_voice_status=$?
set -e

if [[ "$invalid_voice_status" -eq 0 ]]; then
  printf 'Expected generated PCM mode with invalid override voice to fail.\n' >&2
  printf 'Actual output:\n%s\n' "$invalid_voice_output" >&2
  exit 1
fi
assert_contains "$invalid_voice_output" "VOICE_AGENT_E2E_FLITE_VOICE contains unsupported characters: kal:bad"
adb_log_lines_after_invalid_voice="$(wc -l < "$FAKE_ADB_ARGS_LOG")"
if [[ "$adb_log_lines_after_invalid_voice" != "$adb_log_lines_before_invalid_voice" ]]; then
  printf 'Expected invalid voice failure before ADB calls.\n' >&2
  printf 'Actual ADB log:\n%s\n' "$(cat "$FAKE_ADB_ARGS_LOG")" >&2
  exit 1
fi
if [[ -e "$invalid_voice_log_dir/generated-prompt.pcm" ]]; then
  printf 'Expected invalid voice failure not to write PCM output.\n' >&2
  exit 1
fi

missing_report_log_dir="$TMP_DIR/missing-report-log"
missing_report_path="$TMP_DIR/custom-report.txt"
mkdir -p "$missing_report_log_dir"
printf 'stale generated source' > "$missing_report_log_dir/generated-prompt.txt"
set +e
missing_report_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_ADB_MISSING_REPORT_ARTIFACTS=1 \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_E2E_PCM_PATH="$TMP_DIR/prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_E2E_LOG_DIR="$missing_report_log_dir" \
  VOICE_AGENT_E2E_REPORT_PATH="$missing_report_path" \
  VOICE_AGENT_E2E_MANUAL_REVIEW=1 \
  VOICE_AGENT_E2E_GEMINI_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_E2E_HERMES_RESPONSE_TIMEOUT_SECONDS=5 \
  "$SCRIPT" 2>&1
)"
missing_report_status=$?
set -e

if [[ "$missing_report_status" -ne 0 ]]; then
  printf 'Expected missing optional report artifact run to pass, got status %s.\n' "$missing_report_status" >&2
  printf 'Actual output:\n%s\n' "$missing_report_output" >&2
  exit 1
fi
if [[ ! -f "$missing_report_path" ]]; then
  printf 'Expected custom report file to exist: %s\n' "$missing_report_path" >&2
  exit 1
fi
missing_report_contents="$(cat "$missing_report_path")"
assert_contains "$missing_report_output" "Voice Agent E2E report: $missing_report_path"
assert_contains "$missing_report_contents" "Text used to generate voice:"
assert_contains "$missing_report_contents" "missing"
if [[ "$missing_report_contents" == *"stale generated source"* ]]; then
  printf 'Expected explicit PCM report not to reuse stale generated prompt text.\n' >&2
  printf 'Actual report:\n%s\n' "$missing_report_contents" >&2
  exit 1
fi
assert_contains "$missing_report_contents" "Gemini understood from voice:"
assert_contains "$missing_report_contents" "Hermes call:"
assert_contains "$missing_report_contents" "Gemini response to user:"
missing_count="$(grep -c '^missing$' "$missing_report_path")"
if [[ "$missing_count" -lt 4 ]]; then
  printf 'Expected missing source/input/call/output markers, got %s.\n' "$missing_count" >&2
  printf 'Actual report:\n%s\n' "$missing_report_contents" >&2
  exit 1
fi
assert_no_report_temp_files "$missing_report_log_dir"

apostrophe_log_dir="$TMP_DIR/log-with-apostrophe's"
set +e
apostrophe_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_FFMPEG_EXPECTED_PROMPT="Prompt with apostrophe log dir." \
  FAKE_FFMPEG_EXPECTED_OUTPUT="$apostrophe_log_dir/generated-prompt.pcm" \
  FAKE_FFMPEG_REJECT_FILTER_META_PATH=1 \
  TMPDIR="$TMP_DIR/tmp:with'apostrophe" \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_E2E_PROMPT_TEXT="Prompt with apostrophe log dir." \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_E2E_LOG_DIR="$apostrophe_log_dir" \
  VOICE_AGENT_E2E_MANUAL_REVIEW=1 \
  VOICE_AGENT_E2E_GEMINI_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_E2E_HERMES_RESPONSE_TIMEOUT_SECONDS=5 \
  "$SCRIPT" 2>&1
)"
apostrophe_status=$?
set -e

if [[ "$apostrophe_status" -ne 0 ]]; then
  printf 'Expected generated PCM mode with apostrophe log dir to pass, got status %s.\n' "$apostrophe_status" >&2
  printf 'Actual output:\n%s\n' "$apostrophe_output" >&2
  exit 1
fi
assert_file_contains_exactly "$apostrophe_log_dir/generated-prompt.pcm" "generated pcm"

failing_ffmpeg_log_dir="$TMP_DIR/failing-ffmpeg-log"
before_failing_ffmpeg_adb_lines="$(wc -l < "$FAKE_ADB_ARGS_LOG")"
set +e
failing_ffmpeg_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_FFMPEG_EXPECTED_PROMPT="Prompt that fails ffmpeg." \
  FAKE_FFMPEG_EXPECTED_OUTPUT="$failing_ffmpeg_log_dir/generated-prompt.pcm" \
  FAKE_FFMPEG_FAIL=1 \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_E2E_PROMPT_TEXT="Prompt that fails ffmpeg." \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_E2E_LOG_DIR="$failing_ffmpeg_log_dir" \
  VOICE_AGENT_E2E_MANUAL_REVIEW=1 \
  "$SCRIPT" 2>&1
)"
failing_ffmpeg_status=$?
set -e

if [[ "$failing_ffmpeg_status" -eq 0 ]]; then
  printf 'Expected failing ffmpeg run to fail.\n' >&2
  printf 'Actual output:\n%s\n' "$failing_ffmpeg_output" >&2
  exit 1
fi
ffmpeg_temp_textfile="$(tail -n 1 "$FAKE_FFMPEG_TEXTFILE_LOG")"
if [[ -e "$ffmpeg_temp_textfile" ]]; then
  printf 'Expected ffmpeg temp prompt file to be removed: %s\n' "$ffmpeg_temp_textfile" >&2
  exit 1
fi
after_failing_ffmpeg_adb_lines="$(wc -l < "$FAKE_ADB_ARGS_LOG")"
if [[ "$after_failing_ffmpeg_adb_lines" != "$before_failing_ffmpeg_adb_lines" ]]; then
  printf 'Expected ffmpeg failure to skip ADB cleanup before readiness.\n' >&2
  printf 'ADB args:\n%s\n' "$(cat "$FAKE_ADB_ARGS_LOG")" >&2
  exit 1
fi

manual_missing_answer_log_dir="$TMP_DIR/manual-missing-answer-log"
set +e
manual_missing_answer_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_ADB_MISSING_ANSWER=1 \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_E2E_PCM_PATH="$TMP_DIR/prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_E2E_LOG_DIR="$manual_missing_answer_log_dir" \
  VOICE_AGENT_E2E_MANUAL_REVIEW=1 \
  VOICE_AGENT_E2E_MANUAL_ANSWER_TIMEOUT_SECONDS=1 \
  VOICE_AGENT_E2E_GEMINI_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_E2E_HERMES_RESPONSE_TIMEOUT_SECONDS=5 \
  "$SCRIPT" 2>&1
)"
manual_missing_answer_status=$?
set -e

if [[ "$manual_missing_answer_status" -eq 0 ]]; then
  printf 'Expected manual mode missing answer artifact to fail.\n' >&2
  printf 'Actual output:\n%s\n' "$manual_missing_answer_output" >&2
  exit 1
fi
assert_contains "$manual_missing_answer_output" \
  "Failed to pull app-private Hermes answer artifact: no_backup/voice-e2e/trace-gbrain/hermes-answer.txt"
assert_last_line_after "$FAKE_ADB_ARGS_LOG" \
  "-a me.rerere.rikkahub.voiceagent.action.END" \
  "rm -f no_backup/voice-e2e/trace-gbrain/hermes-answer.txt"
if grep -F -- "databases/rikka_hub" "$FAKE_ADB_ARGS_LOG" >/dev/null; then
  printf 'Expected no database fallback when answer artifact is missing.\n' >&2
  printf 'Actual ADB log:\n%s\n' "$(cat "$FAKE_ADB_ARGS_LOG")" >&2
  exit 1
fi

late_trace_answer_log_dir="$TMP_DIR/late-trace-answer-log"
late_trace_adb_log="$TMP_DIR/late-trace-adb-args.log"
late_trace_counter="$TMP_DIR/late-trace-counter"
set +e
late_trace_answer_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_ADB_ARGS_LOG="$late_trace_adb_log" \
  FAKE_ADB_LATEST_TRACE_ID=late \
  FAKE_ADB_LATE_TRACE_COUNTER="$late_trace_counter" \
  FAKE_ADB_LATE_TRACE_MISSING_COUNT=2 \
  FAKE_ADB_MISSING_BASE_ANSWER=1 \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_E2E_PCM_PATH="$TMP_DIR/prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_E2E_LOG_DIR="$late_trace_answer_log_dir" \
  VOICE_AGENT_E2E_MANUAL_REVIEW=1 \
  VOICE_AGENT_E2E_MANUAL_ANSWER_TIMEOUT_SECONDS=3 \
  VOICE_AGENT_E2E_GEMINI_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_E2E_HERMES_RESPONSE_TIMEOUT_SECONDS=5 \
  "$SCRIPT" 2>&1
)"
late_trace_answer_status=$?
set -e

if [[ "$late_trace_answer_status" -ne 0 ]]; then
  printf 'Expected manual mode to re-resolve a late trace id and pull the answer.\n' >&2
  printf 'Actual output:\n%s\n' "$late_trace_answer_output" >&2
  exit 1
fi
assert_contains "$late_trace_answer_output" "Manual review answer artifact:"
assert_file_contains_exactly "$late_trace_answer_log_dir/manual-hermes-answer.txt" "manual answer from Hermes"
assert_file_contains "$late_trace_adb_log" "cat no_backup/voice-e2e/hermes-answer.txt"
assert_file_contains "$late_trace_adb_log" "cat no_backup/voice-e2e/trace-gbrain/hermes-answer.txt"

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
missing_tool_call_diagnostics="$missing_tool_call_log_dir/missing-tool-call-diagnostics.txt"
assert_contains "$missing_tool_call_output" "Missing marker after 1s: Gemini ask_hermes tool call received"
assert_contains "$missing_tool_call_output" \
  "Voice Agent E2E diagnostic artifact: $missing_tool_call_diagnostics"
assert_file_contains "$missing_tool_call_diagnostics" "Gemini understood from voice:"
assert_file_contains "$missing_tool_call_diagnostics" "Please ask Hermes if he is connected to G-Brain."
assert_file_contains "$missing_tool_call_diagnostics" "Gemini response to user:"
assert_file_contains "$missing_tool_call_diagnostics" "Yes, Hermes is connected to G-Brain."
assert_file_contains "$missing_tool_call_diagnostics" "Hermes call:"
assert_file_contains "$missing_tool_call_diagnostics" "Is Hermes connected to G-Brain? Answer yes or no."
if [[ "$missing_tool_call_output" == *"Please ask Hermes if he is connected to G-Brain."* ||
  "$missing_tool_call_output" == *"Yes, Hermes is connected to G-Brain."* ||
  "$missing_tool_call_output" == *"Is Hermes connected to G-Brain? Answer yes or no."* ]]; then
  printf 'Expected private diagnostic contents not to be printed to process output.\n' >&2
  printf 'Actual output:\n%s\n' "$missing_tool_call_output" >&2
  exit 1
fi
if grep -F -- "databases/rikka_hub" "$FAKE_ADB_ARGS_LOG" >/dev/null; then
  printf 'Expected no database fallback for missing tool-call diagnostics.\n' >&2
  printf 'Actual ADB log:\n%s\n' "$(cat "$FAKE_ADB_ARGS_LOG")" >&2
  exit 1
fi

large_diagnostic_log_dir="$TMP_DIR/large-diagnostic-log"
set +e
large_diagnostic_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_ADB_SKIP_TOOL_CALL=1 \
  FAKE_ADB_LARGE_DIAGNOSTIC_ARTIFACTS=1 \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_E2E_PCM_PATH="$TMP_DIR/prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_E2E_LOG_DIR="$large_diagnostic_log_dir" \
  VOICE_AGENT_E2E_MANUAL_REVIEW=1 \
  VOICE_AGENT_E2E_GEMINI_TOOL_CALL_TIMEOUT_SECONDS=1 \
  VOICE_AGENT_E2E_HERMES_RESPONSE_TIMEOUT_SECONDS=1 \
  "$SCRIPT" 2>&1
)"
large_diagnostic_status=$?
set -e

if [[ "$large_diagnostic_status" -eq 0 ]]; then
  printf 'Expected large diagnostic artifact run to fail.\n' >&2
  printf 'Actual output:\n%s\n' "$large_diagnostic_output" >&2
  exit 1
fi
large_diagnostic_file="$large_diagnostic_log_dir/missing-tool-call-diagnostics.txt"
assert_contains "$large_diagnostic_output" \
  "Voice Agent E2E diagnostic artifact: $large_diagnostic_file"
assert_file_contains "$large_diagnostic_file" "Gemini understood from voice: AAAAAAAAAAAAAAAA"
if [[ "$large_diagnostic_output" == *"AAAAAAAAAAAAAAAA"* ||
  "$large_diagnostic_output" == *"diagnostic-tail"* ]]; then
  printf 'Expected diagnostic preview contents not to print to process output.\n' >&2
  printf 'Actual output:\n%s\n' "$large_diagnostic_output" >&2
  exit 1
fi
if [[ "$(cat "$large_diagnostic_file")" == *"diagnostic-tail"* ]]; then
  printf 'Expected diagnostic artifact not to contain content past 240 bytes.\n' >&2
  printf 'Actual diagnostic file:\n%s\n' "$(cat "$large_diagnostic_file")" >&2
  exit 1
fi
assert_file_contains "$FAKE_ADB_ARGS_LOG" \
  "exec-out run-as me.rerere.rikkahub.debug head -c 240 no_backup/voice-e2e/trace-gbrain/input-transcript.txt"

tool_wait_forbidden_log_dir="$TMP_DIR/tool-wait-forbidden-log"
set +e
tool_wait_forbidden_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_ADB_SKIP_TOOL_CALL=1 \
  FAKE_ADB_FORBIDDEN_AFTER_DEBUG=1 \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_E2E_PCM_PATH="$TMP_DIR/prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_E2E_LOG_DIR="$tool_wait_forbidden_log_dir" \
  VOICE_AGENT_E2E_MANUAL_REVIEW=1 \
  VOICE_AGENT_E2E_GEMINI_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_E2E_HERMES_RESPONSE_TIMEOUT_SECONDS=1 \
  "$SCRIPT" 2>&1
)"
tool_wait_forbidden_status=$?
set -e

if [[ "$tool_wait_forbidden_status" -eq 0 ]]; then
  printf 'Expected forbidden marker during tool-call wait to fail.\n' >&2
  printf 'Actual output:\n%s\n' "$tool_wait_forbidden_output" >&2
  exit 1
fi
assert_contains "$tool_wait_forbidden_output" "PASS marker: debug PCM delivered"
assert_contains "$tool_wait_forbidden_output" "Forbidden marker found: common forbidden marker"
if [[ "$tool_wait_forbidden_output" == *"Voice Agent E2E diagnostic artifact:"* ]]; then
  printf 'Expected forbidden marker during tool-call wait not to print missing tool-call diagnostics.\n' >&2
  printf 'Actual output:\n%s\n' "$tool_wait_forbidden_output" >&2
  exit 1
fi

diagnostic_boundary_forbidden_log_dir="$TMP_DIR/diagnostic-boundary-forbidden-log"
set +e
diagnostic_boundary_forbidden_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_ADB_SKIP_TOOL_CALL=1 \
  FAKE_ADB_FORBIDDEN_AFTER_DEBUG=1 \
  FAKE_ADB_FORBIDDEN_AFTER_DEBUG_DELAY_SECONDS=3 \
  FAKE_ADB_DIAGNOSTIC_DELAY_SECONDS=4 \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_E2E_PCM_PATH="$TMP_DIR/prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_E2E_LOG_DIR="$diagnostic_boundary_forbidden_log_dir" \
  VOICE_AGENT_E2E_MANUAL_REVIEW=1 \
  VOICE_AGENT_E2E_GEMINI_TOOL_CALL_TIMEOUT_SECONDS=1 \
  VOICE_AGENT_E2E_HERMES_RESPONSE_TIMEOUT_SECONDS=1 \
  "$SCRIPT" 2>&1
)"
diagnostic_boundary_forbidden_status=$?
set -e

if [[ "$diagnostic_boundary_forbidden_status" -eq 0 ]]; then
  printf 'Expected forbidden marker during diagnostics to fail.\n' >&2
  printf 'Actual output:\n%s\n' "$diagnostic_boundary_forbidden_output" >&2
  exit 1
fi
assert_contains "$diagnostic_boundary_forbidden_output" "Missing marker after 1s: Gemini ask_hermes tool call received"
diagnostic_boundary_forbidden_file="$diagnostic_boundary_forbidden_log_dir/missing-tool-call-diagnostics.txt"
assert_contains "$diagnostic_boundary_forbidden_output" \
  "Voice Agent E2E diagnostic artifact: $diagnostic_boundary_forbidden_file"
assert_file_contains "$diagnostic_boundary_forbidden_file" "Gemini understood from voice:"
assert_contains "$diagnostic_boundary_forbidden_output" "Forbidden marker found: common forbidden marker"

forbidden_marker_log_dir="$TMP_DIR/forbidden-marker-log"
set +e
forbidden_marker_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_ADB_FORBIDDEN_MARKER=1 \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_E2E_PCM_PATH="$TMP_DIR/prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_E2E_LOG_DIR="$forbidden_marker_log_dir" \
  VOICE_AGENT_E2E_MANUAL_REVIEW=1 \
  VOICE_AGENT_E2E_GEMINI_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_E2E_HERMES_RESPONSE_TIMEOUT_SECONDS=5 \
  "$SCRIPT" 2>&1
)"
forbidden_marker_status=$?
set -e

if [[ "$forbidden_marker_status" -eq 0 ]]; then
  printf 'Expected forbidden-marker run to fail.\n' >&2
  printf 'Actual output:\n%s\n' "$forbidden_marker_output" >&2
  exit 1
fi
assert_contains "$forbidden_marker_output" "Forbidden marker found: common forbidden marker"
if [[ "$forbidden_marker_output" == *"Voice Agent E2E diagnostic artifact:"* ]]; then
  printf 'Expected forbidden-marker failure not to print missing tool-call diagnostics.\n' >&2
  printf 'Actual output:\n%s\n' "$forbidden_marker_output" >&2
  exit 1
fi
assert_last_line_after "$FAKE_ADB_ARGS_LOG" \
  "-a me.rerere.rikkahub.voiceagent.action.END" \
  "rm -f no_backup/voice-e2e/trace-gbrain/hermes-answer.txt"

strict_log_dir="$TMP_DIR/strict-log"
strict_success_log_dir="$TMP_DIR/strict-success-log"
strict_success_report_path="$strict_success_log_dir/report.txt"
before_strict_success_report_pulls="$(
  count_report_artifact_pulls
)"
set +e
strict_success_output="$(
  PATH="$TMP_DIR:$PATH" \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_E2E_EXPECTED_HASH="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" \
  VOICE_AGENT_E2E_PCM_PATH="$TMP_DIR/prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_E2E_LOG_DIR="$strict_success_log_dir" \
  VOICE_AGENT_E2E_GEMINI_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_E2E_HERMES_RESPONSE_TIMEOUT_SECONDS=5 \
  "$SCRIPT" 2>&1
)"
strict_success_status=$?
set -e

if [[ "$strict_success_status" -ne 0 ]]; then
  printf 'Expected strict success mode to pass, got status %s.\n' "$strict_success_status" >&2
  printf 'Actual output:\n%s\n' "$strict_success_output" >&2
  exit 1
fi
assert_contains "$strict_success_output" "Voice Agent Hermes/Gbrain live E2E passed."
assert_contains "$strict_success_output" "CLEANUP: skipped - strict mode leaves cleanup to exit trap"
if [[ -e "$strict_success_report_path" ]]; then
  printf 'Expected strict success mode not to write report: %s\n' "$strict_success_report_path" >&2
  exit 1
fi
after_strict_success_report_pulls="$(
  count_report_artifact_pulls
)"
if [[ "$after_strict_success_report_pulls" != "$before_strict_success_report_pulls" ]]; then
  printf 'Expected strict success mode not to pull report artifacts.\n' >&2
  printf 'ADB args:\n%s\n' "$(cat "$FAKE_ADB_ARGS_LOG")" >&2
  exit 1
fi
set +e
strict_output="$(
  PATH="$TMP_DIR:$PATH" \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_E2E_EXPECTED_HASH="$expected_hash" \
  VOICE_AGENT_E2E_PCM_PATH="$TMP_DIR/prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_E2E_LOG_DIR="$strict_log_dir" \
  VOICE_AGENT_E2E_GEMINI_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_E2E_HERMES_RESPONSE_TIMEOUT_SECONDS=5 \
  "$SCRIPT" 2>&1
)"
strict_status=$?
set -e

if [[ "$strict_status" -eq 0 ]]; then
  printf 'Expected strict mode to fail on hash mismatch.\n' >&2
  printf 'Actual output:\n%s\n' "$strict_output" >&2
  exit 1
fi
assert_contains "$strict_output" "Missing marker after 5s: Hermes response hash matched"
printf 'voice-agent-hermes-gbrain-e2e tests passed.\n'
