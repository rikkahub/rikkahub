#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PACKAGE="${VOICE_AGENT_E2E_PACKAGE:-me.rerere.rikkahub.debug}"
SERVICE_COMPONENT="$PACKAGE/me.rerere.rikkahub.voiceagent.VoiceAgentCallService"
INJECT_COMPONENT="$PACKAGE/me.rerere.rikkahub.voiceagent.debug.VoiceAudioDebugInjectionReceiver"
INJECT_ACTION="me.rerere.rikkahub.debug.voiceagent.INJECT_PCM"
CALL_START_ACTION="me.rerere.rikkahub.voiceagent.action.START"
CALL_END_ACTION="me.rerere.rikkahub.voiceagent.action.END"
APP_PCM_PATH="voice-e2e/queue-prompt.pcm"
APP_ARTIFACT_BASE_DIR="no_backup/voice-e2e"
APP_LATEST_TRACE_ID_PATH="$APP_ARTIFACT_BASE_DIR/latest-trace-id.txt"
APP_HERMES_EVENTS_ARTIFACT="hermes-events.ndjson"
APP_INPUT_TRANSCRIPT_ARTIFACT="input-transcript.txt"
APP_OUTPUT_TRANSCRIPT_ARTIFACT="output-transcript.txt"
APP_HERMES_CALL_ARTIFACT="hermes-call.txt"
APP_HERMES_ANSWER_ARTIFACT="hermes-answer.txt"
DEVICE_TMP_PCM="/data/local/tmp/rikkahub-voice-agent-queue-e2e-prompt.pcm"
LOG_DIR="${VOICE_AGENT_QUEUE_E2E_LOG_DIR:-build/voice-agent-queue-e2e}"
LOG_FILE="$LOG_DIR/logcat.txt"
DEFAULT_PROMPT_TEXT="Ask Hermes three separate questions now. First, ask whether he is connected to G Brain. Second, ask him to recall the private queue test fact. Third, ask him to summarize the latest Arthur status. Keep talking with me while those Hermes requests run, and tell me each answer when it is ready."
FLITE_VOICE="${VOICE_AGENT_QUEUE_E2E_FLITE_VOICE:-slt}"
PROMPT_TEXT="${VOICE_AGENT_QUEUE_E2E_PROMPT_TEXT:-$DEFAULT_PROMPT_TEXT}"
GENERATED_PCM_PATH="${VOICE_AGENT_QUEUE_E2E_GENERATED_PCM_PATH:-$LOG_DIR/generated-prompt.pcm}"
PROMPT_SOURCE_TEXT_FILE="$LOG_DIR/generated-prompt.txt"
REPORT_FILE="${VOICE_AGENT_QUEUE_E2E_REPORT_PATH:-$LOG_DIR/report.txt}"
HERMES_EVENTS_FILE="$LOG_DIR/hermes-events.ndjson"
INPUT_TRANSCRIPT_FILE="$LOG_DIR/input-transcript.txt"
OUTPUT_TRANSCRIPT_FILE="$LOG_DIR/output-transcript.txt"
HERMES_CALL_FILE="$LOG_DIR/hermes-call.txt"
HERMES_ANSWER_FILE="$LOG_DIR/hermes-answer.txt"
LOG_SEARCH_START_LINE=1
WAIT_FOR_LOG_FAILURE=""
ADB_TIMEOUT_SECONDS="${VOICE_AGENT_E2E_ADB_TIMEOUT_SECONDS:-20}"
ADB_LONG_TIMEOUT_SECONDS="${VOICE_AGENT_E2E_ADB_LONG_TIMEOUT_SECONDS:-120}"
ADB_READY_SCRIPT="${VOICE_AGENT_E2E_ADB_READY_SCRIPT:-scripts/adb-device-ready.sh}"
TOOL_CALL_TIMEOUT_SECONDS="${VOICE_AGENT_QUEUE_E2E_TOOL_CALL_TIMEOUT_SECONDS:-240}"
COMPLETION_TIMEOUT_SECONDS="${VOICE_AGENT_QUEUE_E2E_COMPLETION_TIMEOUT_SECONDS:-600}"
OUTPUT_TIMEOUT_SECONDS="${VOICE_AGENT_QUEUE_E2E_OUTPUT_TIMEOUT_SECONDS:-120}"
EXPECTED_COMPLETIONS="${VOICE_AGENT_QUEUE_E2E_EXPECTED_COMPLETIONS:-2}"
CALL_STARTED=0
PIPELINE_STATUS="not_started"
CLEANUP_STATUS="not_started"
CLEANUP_DETAIL=""
DEVICE_TMP_PCM_CLEANUP_NEEDED=0
APP_PCM_CLEANUP_NEEDED=0
ADB_APP_CLEANUP_ENABLED=0
GENERATED_PCM_FROM_PROMPT=0
FFMPEG_PROMPT_TEXT_CLEANUP_PATH=""
REPORT_TEMP_CLEANUP_PATHS=()
COMMON_FORBIDDEN_PATTERN='VoiceAgentE2E.*hermes_tool_failed|Voice Lab request failed (403|524)|Cloudflare|cf-error|Access denied|FATAL EXCEPTION|Voice playback write failed|AudioTrack write failed|AudioTrack write error|HTTP[ /]524|status=524|code=524|Hermes job polling timed out|Hermes job was no longer available'

. "$SCRIPT_DIR/voice-agent-e2e-artifacts.sh"

if [[ ! "$PACKAGE" =~ ^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$ ]]; then
  printf 'VOICE_AGENT_E2E_PACKAGE must be an Android package name: %s\n' "$PACKAGE" >&2
  exit 2
fi

require_env() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    printf 'Missing required environment variable: %s\n' "$name" >&2
    exit 2
  fi
}

require_command() {
  local name="$1"
  if ! command -v "$name" >/dev/null 2>&1; then
    printf 'Missing required command: %s\n' "$name" >&2
    exit 2
  fi
}

adb_cmd_with_timeout() {
  local timeout_seconds="$1"
  shift
  set +e
  if [[ -n "${VOICE_AGENT_E2E_SERIAL:-}" ]]; then
    timeout "${timeout_seconds}s" adb -s "$VOICE_AGENT_E2E_SERIAL" "$@"
  else
    timeout "${timeout_seconds}s" adb "$@"
  fi
  local status=$?
  set -e
  if [[ "$status" -eq 124 ]]; then
    printf 'ADB command timed out after %ss.\n' "$timeout_seconds" >&2
  fi
  return "$status"
}

adb_cmd() {
  adb_cmd_with_timeout "$ADB_TIMEOUT_SECONDS" "$@"
}

adb_long_cmd() {
  adb_cmd_with_timeout "$ADB_LONG_TIMEOUT_SECONDS" "$@"
}

adb_logcat() {
  if [[ -n "${VOICE_AGENT_E2E_SERIAL:-}" ]]; then
    adb -s "$VOICE_AGENT_E2E_SERIAL" "$@"
  else
    adb "$@"
  fi
}

adb_exec_out_to_file() {
  local output_file="$1"
  shift
  set +e
  if [[ -n "${VOICE_AGENT_E2E_SERIAL:-}" ]]; then
    timeout "${ADB_LONG_TIMEOUT_SECONDS}s" adb -s "$VOICE_AGENT_E2E_SERIAL" exec-out "$@" > "$output_file"
  else
    timeout "${ADB_LONG_TIMEOUT_SECONDS}s" adb exec-out "$@" > "$output_file"
  fi
  local status=$?
  set -e
  if [[ "$status" -eq 124 ]]; then
    printf 'ADB exec-out command timed out after %ss.\n' "$ADB_LONG_TIMEOUT_SECONDS" >&2
  fi
  return "$status"
}

selected_adb_serial() {
  if [[ -n "${VOICE_AGENT_E2E_SERIAL:-}" ]]; then
    printf '%s' "$VOICE_AGENT_E2E_SERIAL"
    return 0
  fi

  local devices_output
  local device_count
  devices_output="$(adb_cmd devices -l)"
  device_count="$(printf '%s\n' "$devices_output" | awk '$2 == "device" { count++ } END { print count + 0 }')"
  if [[ "$device_count" != "1" ]]; then
    printf 'Expected exactly one authorized ADB device, found %s. Set VOICE_AGENT_E2E_SERIAL.\n' "$device_count" >&2
    printf '%s\n' "$devices_output" >&2
    return 1
  fi

  printf '%s\n' "$devices_output" | awk '$2 == "device" { print $1; exit }'
}

print_preflight_summary() {
  local serial="$1"
  local model
  local android
  model="$(adb_cmd shell getprop ro.product.model | tr -d '\r' || true)"
  android="$(adb_cmd shell getprop ro.build.version.release | tr -d '\r' || true)"

  printf 'Queue E2E preflight:\n'
  printf '  adb server: %s\n' "${ADB_SERVER_SOCKET:-default local adb server}"
  printf '  selected serial: %s\n' "${serial:-unknown}"
  printf '  device: model=%s android=%s\n' "${model:-unknown}" "${android:-unknown}"
  printf '  package: %s installed\n' "$PACKAGE"
}

fail_if_log() {
  local label="$1"
  local pattern="$2"
  if grep -E "$pattern" "$LOG_FILE" >/dev/null 2>&1; then
    printf 'Forbidden marker found: %s\n' "$label" >&2
    printf 'Pattern: %s\n' "$pattern" >&2
    return 1
  fi
}

count_log_matches() {
  local pattern="$1"
  awk -v pattern="$pattern" '$0 ~ pattern { count++ } END { print count + 0 }' "$LOG_FILE" 2>/dev/null || printf '0\n'
}

wait_for_log_count() {
  local label="$1"
  local pattern="$2"
  local expected_count="$3"
  local timeout_seconds="${4:-90}"
  local deadline=$((SECONDS + timeout_seconds))
  local actual_count
  WAIT_FOR_LOG_FAILURE=""
  while (( SECONDS < deadline )); do
    if ! fail_if_log "common forbidden marker" "$COMMON_FORBIDDEN_PATTERN"; then
      WAIT_FOR_LOG_FAILURE="forbidden"
      return 1
    fi
    actual_count="$(count_log_matches "$pattern")"
    if (( actual_count >= expected_count )); then
      printf 'PASS marker: %s\n' "$label"
      return 0
    fi
    sleep 1
  done
  printf 'Expected at least %s %s, found %s.\n' "$expected_count" "$label" "${actual_count:-0}" >&2
  printf 'Pattern: %s\n' "$pattern" >&2
  WAIT_FOR_LOG_FAILURE="timeout"
  return 1
}

advance_log_search_after_last_match() {
  local pattern="$1"
  local matched_line
  matched_line="$(awk -v pattern="$pattern" '$0 ~ pattern { line = NR } END { if (line) print line }' "$LOG_FILE" 2>/dev/null || true)"
  if [[ -n "$matched_line" ]]; then
    LOG_SEARCH_START_LINE=$((matched_line + 1))
  fi
}

wait_for_log() {
  local label="$1"
  local pattern="$2"
  local timeout_seconds="${3:-90}"
  local deadline=$((SECONDS + timeout_seconds))
  local matched_line
  WAIT_FOR_LOG_FAILURE=""
  while (( SECONDS < deadline )); do
    if ! fail_if_log "common forbidden marker" "$COMMON_FORBIDDEN_PATTERN"; then
      WAIT_FOR_LOG_FAILURE="forbidden"
      return 1
    fi
    matched_line="$(awk -v start="$LOG_SEARCH_START_LINE" -v pattern="$pattern" \
      'NR >= start && $0 ~ pattern { print NR; exit }' "$LOG_FILE" 2>/dev/null || true)"
    if [[ -n "$matched_line" ]]; then
      LOG_SEARCH_START_LINE=$((matched_line + 1))
      printf 'PASS marker: %s\n' "$label"
      return 0
    fi
    sleep 1
  done
  printf 'Missing marker after %ss: %s\n' "$timeout_seconds" "$label" >&2
  printf 'Pattern: %s\n' "$pattern" >&2
  WAIT_FOR_LOG_FAILURE="timeout"
  return 1
}

wait_for_cleanup_log() {
  local pattern="$1"
  local timeout_seconds="${2:-30}"
  local deadline=$((SECONDS + timeout_seconds))
  while (( SECONDS < deadline )); do
    if awk -v pattern="$pattern" '$0 ~ pattern { found = 1 } END { exit found ? 0 : 1 }' "$LOG_FILE" 2>/dev/null; then
      return 0
    fi
    sleep 1
  done
  return 1
}

extract_log_value() {
  local key="$1"
  sed -nE "s/.*${key}=([^, ]+).*/\\1/p"
}

assert_distinct_created_jobs() {
  local minimum="$1"
  local ids
  local total_count
  local unique_count
  ids="$(grep -E 'hermes_queue_event type=job_created|hermes_job_created|diagnostic name=hermes_job_created' "$LOG_FILE" 2>/dev/null | extract_log_value jobId || true)"
  total_count="$(printf '%s\n' "$ids" | awk 'NF { count++ } END { print count + 0 }')"
  unique_count="$(printf '%s\n' "$ids" | awk 'NF && seen[$0]++ == 0 { count++ } END { print count + 0 }')"
  if (( total_count < minimum )); then
    printf 'Expected at least %s queued Hermes jobs, found %s.\n' "$minimum" "$total_count" >&2
    return 1
  fi
  if (( unique_count < total_count )); then
    local duplicate
    duplicate="$(printf '%s\n' "$ids" | awk 'NF { seen[$0]++ } END { for (id in seen) if (seen[id] > 1) { print id; exit } }')"
    printf 'Duplicate queued Hermes job id found: %s\n' "$duplicate" >&2
    return 1
  fi
}

assert_completed_jobs_match_created_jobs() {
  local created_ids
  local completed_ids
  created_ids="$(grep -E 'hermes_queue_event type=job_created|hermes_job_created|diagnostic name=hermes_job_created' "$LOG_FILE" 2>/dev/null | extract_log_value jobId || true)"
  completed_ids="$(grep -E 'hermes_queue_event type=job_completed|hermes_job_completed|diagnostic name=hermes_job_completed' "$LOG_FILE" 2>/dev/null | extract_log_value jobId || true)"
  local unknown_id
  unknown_id="$(comm -13 \
    <(printf '%s\n' "$created_ids" | awk 'NF' | sort -u) \
    <(printf '%s\n' "$completed_ids" | awk 'NF' | sort -u) |
    head -n 1)"
  if [[ -n "$unknown_id" ]]; then
    printf 'Completed Hermes job was not queued: %s\n' "$unknown_id" >&2
    return 1
  fi
}

wait_for_completed_jobs() {
  local minimum="$1"
  local timeout_seconds="$2"
  local deadline=$((SECONDS + timeout_seconds))
  local completed_count
  WAIT_FOR_LOG_FAILURE=""
  while (( SECONDS < deadline )); do
    if ! fail_if_log "common forbidden marker" "$COMMON_FORBIDDEN_PATTERN"; then
      WAIT_FOR_LOG_FAILURE="forbidden"
      return 1
    fi
    completed_count="$(count_log_matches 'hermes_queue_event type=job_completed|hermes_job_completed|diagnostic name=hermes_job_completed')"
    if (( completed_count >= minimum )); then
      printf 'PASS marker: at least %s Hermes jobs completed\n' "$minimum"
      return 0
    fi
    sleep 1
  done
  printf 'Expected at least %s completed Hermes jobs, found %s.\n' "$minimum" "${completed_count:-0}" >&2
  return 1
}

register_report_temp_file() {
  REPORT_TEMP_CLEANUP_PATHS+=("$1")
}

write_e2e_report() {
  umask 077
  mkdir -p "$LOG_DIR" "$(dirname "$REPORT_FILE")"
  local report_temp_file
  local artifact_dir
  report_temp_file="$(mktemp "$LOG_DIR/report.XXXXXX")"
  register_report_temp_file "$report_temp_file"
  chmod 600 "$report_temp_file"
  artifact_dir="$(resolve_app_artifact_dir)"

  pull_optional_app_artifact "$artifact_dir" "$APP_HERMES_EVENTS_ARTIFACT" "$HERMES_EVENTS_FILE"
  pull_optional_app_artifact "$artifact_dir" "$APP_INPUT_TRANSCRIPT_ARTIFACT" "$INPUT_TRANSCRIPT_FILE"
  pull_optional_app_artifact "$artifact_dir" "$APP_OUTPUT_TRANSCRIPT_ARTIFACT" "$OUTPUT_TRANSCRIPT_FILE"
  pull_optional_app_artifact "$artifact_dir" "$APP_HERMES_CALL_ARTIFACT" "$HERMES_CALL_FILE"
  pull_optional_app_artifact "$artifact_dir" "$APP_HERMES_ANSWER_ARTIFACT" "$HERMES_ANSWER_FILE"

  if [[ ! -s "$PROMPT_SOURCE_TEXT_FILE" ]]; then
    printf 'missing' > "$PROMPT_SOURCE_TEXT_FILE"
    chmod 600 "$PROMPT_SOURCE_TEXT_FILE"
  fi

  {
    printf 'Text used to generate voice:\n'
    cat "$PROMPT_SOURCE_TEXT_FILE"
    printf '\n\nGemini understood from voice:\n'
    cat "$INPUT_TRANSCRIPT_FILE"
    printf '\n\nHermes queue events:\n'
    cat "$HERMES_EVENTS_FILE"
    printf '\n\nLatest Hermes call artifact:\n'
    cat "$HERMES_CALL_FILE"
    printf '\n\nLatest Hermes answer artifact:\n'
    cat "$HERMES_ANSWER_FILE"
    printf '\n\nGemini response to user:\n'
    cat "$OUTPUT_TRANSCRIPT_FILE"
    printf '\n'
  } > "$report_temp_file"
  mv -f "$report_temp_file" "$REPORT_FILE"
  chmod 600 "$REPORT_FILE"
  printf 'Voice Agent Hermes queue E2E report: %s\n' "$REPORT_FILE"
}

generate_pcm_prompt() {
  require_command ffmpeg
  if [[ ! "$FLITE_VOICE" =~ ^[A-Za-z0-9_-]+$ ]]; then
    printf 'VOICE_AGENT_QUEUE_E2E_FLITE_VOICE contains unsupported characters: %s\n' "$FLITE_VOICE" >&2
    return 2
  fi
  umask 077
  mkdir -p "$LOG_DIR" "$(dirname "$GENERATED_PCM_PATH")"
  local ffmpeg_prompt_text_file
  ffmpeg_prompt_text_file="$(mktemp /tmp/rikkahub-voice-agent-queue-e2e-prompt.XXXXXX)"
  FFMPEG_PROMPT_TEXT_CLEANUP_PATH="$ffmpeg_prompt_text_file"
  printf '%s' "$PROMPT_TEXT" > "$PROMPT_SOURCE_TEXT_FILE"
  printf '%s' "$PROMPT_TEXT" > "$ffmpeg_prompt_text_file"
  chmod 600 "$PROMPT_SOURCE_TEXT_FILE" "$ffmpeg_prompt_text_file"
  printf 'Generating PCM prompt from VOICE_AGENT_QUEUE_E2E_PROMPT_TEXT.\n'
  set +e
  ffmpeg -hide_banner \
    -f lavfi \
    -i "flite=textfile=$ffmpeg_prompt_text_file:voice=$FLITE_VOICE" \
    -ar 16000 \
    -ac 1 \
    -f s16le \
    -y "$GENERATED_PCM_PATH" >/dev/null
  local ffmpeg_status=$?
  set -e
  rm -f "$ffmpeg_prompt_text_file"
  FFMPEG_PROMPT_TEXT_CLEANUP_PATH=""
  if [[ "$ffmpeg_status" -ne 0 ]]; then
    return "$ffmpeg_status"
  fi
  chmod 600 "$GENERATED_PCM_PATH"
  GENERATED_PCM_FROM_PROMPT=1
}

clear_app_artifacts() {
  clear_app_artifact_files \
    "$APP_HERMES_EVENTS_ARTIFACT" \
    "$APP_INPUT_TRANSCRIPT_ARTIFACT" \
    "$APP_OUTPUT_TRANSCRIPT_ARTIFACT" \
    "$APP_HERMES_CALL_ARTIFACT" \
    "$APP_HERMES_ANSWER_ARTIFACT"
}

end_voice_agent_call_and_wait() {
  if [[ "$CALL_STARTED" != "1" ]]; then
    CLEANUP_STATUS="skipped"
    CLEANUP_DETAIL="call was not started"
    return 0
  fi
  if ! adb_cmd shell am start-foreground-service \
    -n "$SERVICE_COMPONENT" \
    -a "$CALL_END_ACTION" >/dev/null; then
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

cleanup() {
  local status=$?
  for temp_path in "${REPORT_TEMP_CLEANUP_PATHS[@]}"; do
    rm -f "$temp_path"
  done
  if [[ "$DEVICE_TMP_PCM_CLEANUP_NEEDED" == "1" ]]; then
    adb_cmd shell rm -f "$DEVICE_TMP_PCM" >/dev/null 2>&1 || true
  fi
  if [[ -n "$FFMPEG_PROMPT_TEXT_CLEANUP_PATH" ]]; then
    rm -f "$FFMPEG_PROMPT_TEXT_CLEANUP_PATH"
    FFMPEG_PROMPT_TEXT_CLEANUP_PATH=""
  fi
  if [[ "$CALL_STARTED" == "1" ]]; then
    adb_cmd shell am start-foreground-service \
      -n "$SERVICE_COMPONENT" \
      -a "$CALL_END_ACTION" >/dev/null 2>&1 || true
    wait_for_cleanup_log \
      'VoiceAgentCallService.*end completed conversationId=' \
      "${VOICE_AGENT_E2E_SERVICE_END_TIMEOUT_SECONDS:-30}" >/dev/null 2>&1 || true
  fi
  if [[ "$APP_PCM_CLEANUP_NEEDED" == "1" ]]; then
    adb_cmd shell "run-as $PACKAGE rm -f files/$APP_PCM_PATH" >/dev/null 2>&1 || true
  fi
  if [[ "$ADB_APP_CLEANUP_ENABLED" == "1" ]]; then
    clear_app_artifacts
  fi
  if [[ -n "${LOGCAT_PID:-}" ]]; then
    kill "$LOGCAT_PID" >/dev/null 2>&1 || true
    wait "$LOGCAT_PID" >/dev/null 2>&1 || true
  fi
  exit "$status"
}
trap cleanup EXIT

require_env VOICE_AGENT_E2E_CONVERSATION_ID

if [[ ! "$EXPECTED_COMPLETIONS" =~ ^[1-9][0-9]*$ ]]; then
  printf 'VOICE_AGENT_QUEUE_E2E_EXPECTED_COMPLETIONS must be a positive integer: %s\n' "$EXPECTED_COMPLETIONS" >&2
  exit 2
fi

if [[ -z "${VOICE_AGENT_QUEUE_E2E_PCM_PATH:-}" ]]; then
  generate_pcm_prompt
  VOICE_AGENT_QUEUE_E2E_PCM_PATH="$GENERATED_PCM_PATH"
elif [[ -n "${VOICE_AGENT_QUEUE_E2E_PROMPT_TEXT:-}" ]]; then
  umask 077
  mkdir -p "$LOG_DIR"
  printf '%s' "$PROMPT_TEXT" > "$PROMPT_SOURCE_TEXT_FILE"
  chmod 600 "$PROMPT_SOURCE_TEXT_FILE"
fi

if [[ ! -f "$VOICE_AGENT_QUEUE_E2E_PCM_PATH" ]]; then
  printf 'VOICE_AGENT_QUEUE_E2E_PCM_PATH does not exist: %s\n' "$VOICE_AGENT_QUEUE_E2E_PCM_PATH" >&2
  exit 2
fi

mkdir -p "$LOG_DIR"
rm -f "$LOG_FILE" "$REPORT_FILE" "$HERMES_EVENTS_FILE" "$INPUT_TRANSCRIPT_FILE" \
  "$OUTPUT_TRANSCRIPT_FILE" "$HERMES_CALL_FILE" "$HERMES_ANSWER_FILE"

printf 'Checking ADB device readiness...\n'
if [[ -x "$ADB_READY_SCRIPT" ]]; then
  if [[ -n "${VOICE_AGENT_E2E_SERIAL:-}" ]]; then
    "$ADB_READY_SCRIPT" "$VOICE_AGENT_E2E_SERIAL"
  else
    "$ADB_READY_SCRIPT"
  fi
else
  printf 'ADB readiness helper is not executable: %s\n' "$ADB_READY_SCRIPT" >&2
  exit 2
fi

SELECTED_SERIAL="$(selected_adb_serial)"
if [[ -z "$SELECTED_SERIAL" ]]; then
  printf 'Could not determine selected ADB serial after readiness check.\n' >&2
  exit 6
fi
VOICE_AGENT_E2E_SERIAL="$SELECTED_SERIAL"

printf 'Checking installed app package...\n'
if ! adb_cmd shell pm path "$PACKAGE" >/dev/null; then
  printf 'Installed app package was not found: %s\n' "$PACKAGE" >&2
  printf 'Install and configure the app on the phone before running this E2E.\n' >&2
  exit 2
fi
print_preflight_summary "$SELECTED_SERIAL"

ADB_APP_CLEANUP_ENABLED=1
printf 'Clearing previous app-private queue E2E artifacts...\n'
clear_app_artifacts

printf 'Starting scoped log capture...\n'
adb_cmd logcat -c
adb_logcat logcat -v time \
  VoiceAgentCallService:D \
  VoiceAgentCallSession:D \
  VoiceAgentGemini:D \
  VoiceAgentE2E:D \
  VoiceAudioDebugInjection:I \
  AndroidVoiceAudioEngine:D \
  AndroidRuntime:E \
  '*:S' > "$LOG_FILE" &
LOGCAT_PID=$!

printf 'Copying private PCM prompt into app-private files...\n'
adb_cmd shell "run-as $PACKAGE mkdir -p files/voice-e2e"
DEVICE_TMP_PCM_CLEANUP_NEEDED=1
adb_long_cmd push "$VOICE_AGENT_QUEUE_E2E_PCM_PATH" "$DEVICE_TMP_PCM" >/dev/null
APP_PCM_CLEANUP_NEEDED=1
adb_cmd shell "run-as $PACKAGE cp $DEVICE_TMP_PCM files/$APP_PCM_PATH"
if adb_cmd shell rm -f "$DEVICE_TMP_PCM" >/dev/null 2>&1; then
  DEVICE_TMP_PCM_CLEANUP_NEEDED=0
fi

printf 'Starting Voice Agent foreground service with E2E artifacts enabled...\n'
adb_cmd shell am start-foreground-service \
  -n "$SERVICE_COMPONENT" \
  -a "$CALL_START_ACTION" \
  --es conversationId "$VOICE_AGENT_E2E_CONVERSATION_ID" \
  --ez enableVoiceE2EArtifacts true >/dev/null
CALL_STARTED=1

wait_for_log "Gemini setup complete" 'VoiceAgentGemini.*event kind=SetupComplete' 120

printf 'Injecting private PCM prompt...\n'
adb_cmd shell am broadcast \
  -n "$INJECT_COMPONENT" \
  -a "$INJECT_ACTION" \
  --es path "$APP_PCM_PATH" \
  --ei chunk_bytes "${VOICE_AGENT_QUEUE_E2E_CHUNK_BYTES:-3200}" \
  --el chunk_delay_ms "${VOICE_AGENT_QUEUE_E2E_CHUNK_DELAY_MS:-20}" \
  --el leading_silence_ms "${VOICE_AGENT_QUEUE_E2E_LEADING_SILENCE_MS:-100}" \
  --el trailing_silence_ms "${VOICE_AGENT_QUEUE_E2E_TRAILING_SILENCE_MS:-200}" >/dev/null

wait_for_log "debug PCM delivered" 'VoiceAudioDebugInjection.*debug_audio_injection result delivered=true' 30
wait_for_log_count "at least $EXPECTED_COMPLETIONS ask_hermes tool calls" 'VoiceAgentE2E.*hermes_tool_call_received' "$EXPECTED_COMPLETIONS" "$TOOL_CALL_TIMEOUT_SECONDS"
wait_for_log_count "at least $EXPECTED_COMPLETIONS queued Hermes jobs" 'hermes_queue_event type=job_created|hermes_job_created|diagnostic name=hermes_job_created' "$EXPECTED_COMPLETIONS" "$TOOL_CALL_TIMEOUT_SECONDS"
assert_distinct_created_jobs "$EXPECTED_COMPLETIONS"
wait_for_log_count "at least $EXPECTED_COMPLETIONS queued Hermes tool responses" 'VoiceAgentGemini.*send kind=toolResponse sent=true' "$EXPECTED_COMPLETIONS" 60
wait_for_log "first Gemini output audio received" 'VoiceAgentGemini.*event kind=OutputAudio' "$OUTPUT_TIMEOUT_SECONDS"
wait_for_log "first voice playback queued" 'AndroidVoiceAudioEngine.*Voice playback queued' 60
wait_for_log "first voice playback wrote" 'AndroidVoiceAudioEngine.*Voice playback wrote' 60
wait_for_completed_jobs "$EXPECTED_COMPLETIONS" "$COMPLETION_TIMEOUT_SECONDS"
assert_completed_jobs_match_created_jobs
wait_for_log_count "at least $EXPECTED_COMPLETIONS Hermes response hashes" 'VoiceAgentE2E.*hermes_tool_response_hash .*actualHash=[0-9a-f]+' "$EXPECTED_COMPLETIONS" "$COMPLETION_TIMEOUT_SECONDS"
wait_for_log_count "at least $EXPECTED_COMPLETIONS Hermes completion follow-ups" 'hermes_queue_event type=late_text_turn_sent|hermes_completion_follow_up_sent|late_text_turn_sent' "$EXPECTED_COMPLETIONS" 120
wait_for_log_count "at least $EXPECTED_COMPLETIONS late Gemini text turns sent" 'hermes_queue_event type=late_text_turn_sent|late_text_turn_sent|hermes_completion_follow_up_sent' "$EXPECTED_COMPLETIONS" 120
advance_log_search_after_last_match 'hermes_queue_event type=late_text_turn_sent|late_text_turn_sent|hermes_completion_follow_up_sent'
wait_for_log "second Gemini output audio received" 'VoiceAgentGemini.*event kind=OutputAudio' "$OUTPUT_TIMEOUT_SECONDS"
wait_for_log "second voice playback queued" 'AndroidVoiceAudioEngine.*Voice playback queued' 60
wait_for_log "second voice playback wrote" 'AndroidVoiceAudioEngine.*Voice playback wrote' 60
fail_if_log "common forbidden marker" "$COMMON_FORBIDDEN_PATTERN"

PIPELINE_STATUS="passed"
cleanup_status=0
end_voice_agent_call_and_wait || cleanup_status=$?
if [[ "$cleanup_status" -ne 0 ]]; then
  ADB_APP_CLEANUP_ENABLED=0
  print_result_summary
  printf 'Skipping report pull because service drain was not observed.\n' >&2
  printf 'Voice Agent Hermes queue E2E pipeline passed but cleanup failed. Safe log: %s\n' "$LOG_FILE"
  exit "$cleanup_status"
fi
write_e2e_report
print_result_summary
printf 'Voice Agent Hermes queue E2E reached manual review gate. Safe log: %s\n' "$LOG_FILE"
