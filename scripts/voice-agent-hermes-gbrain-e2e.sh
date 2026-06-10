#!/usr/bin/env bash
set -euo pipefail

PACKAGE="${VOICE_AGENT_E2E_PACKAGE:-me.rerere.rikkahub.debug}"
SERVICE_COMPONENT="$PACKAGE/me.rerere.rikkahub.voiceagent.VoiceAgentCallService"
INJECT_COMPONENT="$PACKAGE/me.rerere.rikkahub.voiceagent.debug.VoiceAudioDebugInjectionReceiver"
INJECT_ACTION="me.rerere.rikkahub.debug.voiceagent.INJECT_PCM"
CALL_START_ACTION="me.rerere.rikkahub.voiceagent.action.START"
CALL_END_ACTION="me.rerere.rikkahub.voiceagent.action.END"
APP_PCM_PATH="voice-e2e/prompt.pcm"
APP_MANUAL_ANSWER_PATH="no_backup/voice-e2e/hermes-answer.txt"
APP_INPUT_TRANSCRIPT_PATH="no_backup/voice-e2e/input-transcript.txt"
APP_OUTPUT_TRANSCRIPT_PATH="no_backup/voice-e2e/output-transcript.txt"
APP_HERMES_CALL_PATH="no_backup/voice-e2e/hermes-call.txt"
DEVICE_TMP_PCM="/data/local/tmp/rikkahub-voice-agent-e2e-prompt.pcm"
LOG_DIR="${VOICE_AGENT_E2E_LOG_DIR:-build/voice-agent-e2e}"
LOG_FILE="$LOG_DIR/logcat.txt"
DEFAULT_PROMPT_TEXT="Ask Hermes. Use the ask Hermes tool now. Ask Hermes: Are you connected to G Brain? Answer yes or no."
FLITE_VOICE="${VOICE_AGENT_E2E_FLITE_VOICE:-slt}"
PROMPT_TEXT="${VOICE_AGENT_E2E_PROMPT_TEXT:-$DEFAULT_PROMPT_TEXT}"
GENERATED_PCM_PATH="${VOICE_AGENT_E2E_GENERATED_PCM_PATH:-$LOG_DIR/generated-prompt.pcm}"
MANUAL_REVIEW_ANSWER_FILE="${VOICE_AGENT_E2E_MANUAL_REVIEW_ANSWER_PATH:-$LOG_DIR/manual-hermes-answer.txt}"
REPORT_FILE="${VOICE_AGENT_E2E_REPORT_PATH:-$LOG_DIR/report.txt}"
MISSING_TOOL_CALL_DIAGNOSTICS_FILE="$LOG_DIR/missing-tool-call-diagnostics.txt"
PROMPT_SOURCE_TEXT_FILE="$LOG_DIR/generated-prompt.txt"
LOG_SEARCH_START_LINE=1
WAIT_FOR_LOG_FAILURE=""
ADB_TIMEOUT_SECONDS="${VOICE_AGENT_E2E_ADB_TIMEOUT_SECONDS:-20}"
ADB_LONG_TIMEOUT_SECONDS="${VOICE_AGENT_E2E_ADB_LONG_TIMEOUT_SECONDS:-120}"
ADB_READY_SCRIPT="${VOICE_AGENT_E2E_ADB_READY_SCRIPT:-scripts/adb-device-ready.sh}"
GEMINI_TOOL_CALL_TIMEOUT_SECONDS="${VOICE_AGENT_E2E_GEMINI_TOOL_CALL_TIMEOUT_SECONDS:-240}"
HERMES_RESPONSE_TIMEOUT_SECONDS="${VOICE_AGENT_E2E_HERMES_RESPONSE_TIMEOUT_SECONDS:-360}"
CALL_STARTED=0
PIPELINE_STATUS="not_started"
CLEANUP_STATUS="not_started"
CLEANUP_DETAIL=""
DEVICE_TMP_PCM_CLEANUP_NEEDED=0
APP_PCM_CLEANUP_NEEDED=0
FFMPEG_PROMPT_TEXT_CLEANUP_PATH=""
ADB_APP_CLEANUP_ENABLED=0
REPORT_TEMP_CLEANUP_PATHS=()
GENERATED_PCM_FROM_PROMPT=0
COMMON_FORBIDDEN_PATTERN='Voice Lab request failed 403|Cloudflare|cf-error|Access denied|FATAL EXCEPTION|Voice playback write failed|AudioTrack write failed|AudioTrack write error'
MANUAL_REVIEW=0

case "${VOICE_AGENT_E2E_MANUAL_REVIEW:-0}" in
  1|true|TRUE|yes|YES|on|ON)
    MANUAL_REVIEW=1
    ;;
esac

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
    printf 'Missing required command for manual review mode: %s\n' "$name" >&2
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

adb_cmd() {
  adb_cmd_with_timeout "$ADB_TIMEOUT_SECONDS" "$@"
}

adb_long_cmd() {
  adb_cmd_with_timeout "$ADB_LONG_TIMEOUT_SECONDS" "$@"
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
    printf 'Expected exactly one authorized ADB device, found %s. Set VOICE_AGENT_E2E_SERIAL.\n' \
      "$device_count" >&2
    printf '%s\n' "$devices_output" >&2
    return 1
  fi

  printf '%s\n' "$devices_output" |
    awk '$2 == "device" { print $1; exit }'
}

print_preflight_summary() {
  local serial="$1"
  local model
  local android

  model="$(adb_cmd shell getprop ro.product.model | tr -d '\r' || true)"
  android="$(adb_cmd shell getprop ro.build.version.release | tr -d '\r' || true)"

  printf 'E2E preflight:\n'
  printf '  adb server: %s\n' "${ADB_SERVER_SOCKET:-default local adb server}"
  printf '  selected serial: %s\n' "${serial:-unknown}"
  printf '  device: model=%s android=%s\n' "${model:-unknown}" "${android:-unknown}"
  printf '  package: %s installed\n' "$PACKAGE"
}

adb_logcat() {
  if [[ -n "${VOICE_AGENT_E2E_SERIAL:-}" ]]; then
    adb -s "$VOICE_AGENT_E2E_SERIAL" "$@"
  else
    adb "$@"
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

fail_if_log() {
  local label="$1"
  local pattern="$2"
  if grep -E "$pattern" "$LOG_FILE" >/dev/null 2>&1; then
    printf 'Forbidden marker found: %s\n' "$label" >&2
    printf 'Pattern: %s\n' "$pattern" >&2
    return 1
  fi
}

extract_manual_review_answer() {
  umask 077
  mkdir -p "$(dirname "$MANUAL_REVIEW_ANSWER_FILE")"
  local deadline=$((SECONDS + ${VOICE_AGENT_E2E_MANUAL_ANSWER_TIMEOUT_SECONDS:-10}))
  while (( SECONDS < deadline )); do
    if adb_exec_out_to_file "$MANUAL_REVIEW_ANSWER_FILE" \
      run-as "$PACKAGE" cat "$APP_MANUAL_ANSWER_PATH" &&
      [[ -s "$MANUAL_REVIEW_ANSWER_FILE" ]]; then
      chmod 600 "$MANUAL_REVIEW_ANSWER_FILE"
      printf 'Manual review answer artifact: %s\n' "$MANUAL_REVIEW_ANSWER_FILE"
      return 0
    fi
    rm -f "$MANUAL_REVIEW_ANSWER_FILE"
    sleep 1
  done

  printf 'Failed to pull app-private Hermes answer artifact: %s\n' "$APP_MANUAL_ANSWER_PATH" >&2
  return 1
}

register_report_temp_file() {
  REPORT_TEMP_CLEANUP_PATHS+=("$1")
}

pull_optional_app_artifact() {
  local app_path="$1"
  local local_path="$2"
  umask 077
  mkdir -p "$(dirname "$local_path")"
  local temp_path
  temp_path="$(mktemp "$LOG_DIR/report-artifact.XXXXXX")"
  register_report_temp_file "$temp_path"
  chmod 600 "$temp_path"
  if adb_exec_out_to_file "$temp_path" run-as "$PACKAGE" cat "$app_path" &&
    [[ -s "$temp_path" ]]; then
    mv -f "$temp_path" "$local_path"
    chmod 600 "$local_path"
    return 0
  fi
  rm -f "$temp_path"
  temp_path="$(mktemp "$LOG_DIR/report-artifact.XXXXXX")"
  register_report_temp_file "$temp_path"
  chmod 600 "$temp_path"
  printf 'missing' > "$temp_path"
  mv -f "$temp_path" "$local_path"
  chmod 600 "$local_path"
}

append_artifact_preview_to_file() {
  local label="$1"
  local app_path="$2"
  local output_file="$3"
  local temp_path
  temp_path="$(mktemp "$LOG_DIR/report-artifact.XXXXXX")"
  register_report_temp_file "$temp_path"
  chmod 600 "$temp_path"
  if adb_exec_out_to_file "$temp_path" run-as "$PACKAGE" head -c 240 "$app_path" &&
    [[ -s "$temp_path" ]]; then
    printf '%s: ' "$label" >> "$output_file"
    tr '\r\n' ' ' < "$temp_path" | cut -c 1-240 >> "$output_file"
    printf '\n' >> "$output_file"
  else
    printf '%s: missing\n' "$label" >> "$output_file"
  fi
  rm -f "$temp_path"
}

write_missing_tool_call_diagnostics() {
  umask 077
  mkdir -p "$LOG_DIR" "$(dirname "$MISSING_TOOL_CALL_DIAGNOSTICS_FILE")"
  local temp_path
  temp_path="$(mktemp "$LOG_DIR/missing-tool-call-diagnostics.XXXXXX")"
  register_report_temp_file "$temp_path"
  chmod 600 "$temp_path"
  printf 'Voice Agent E2E diagnostic artifacts after missing tool call:\n' > "$temp_path"
  append_artifact_preview_to_file "Gemini understood from voice" "$APP_INPUT_TRANSCRIPT_PATH" "$temp_path"
  append_artifact_preview_to_file "Gemini response to user" "$APP_OUTPUT_TRANSCRIPT_PATH" "$temp_path"
  append_artifact_preview_to_file "Hermes call" "$APP_HERMES_CALL_PATH" "$temp_path"
  mv -f "$temp_path" "$MISSING_TOOL_CALL_DIAGNOSTICS_FILE"
  chmod 600 "$MISSING_TOOL_CALL_DIAGNOSTICS_FILE"
  printf 'Voice Agent E2E diagnostic artifact: %s\n' "$MISSING_TOOL_CALL_DIAGNOSTICS_FILE" >&2
}

extract_hermes_elapsed_detail() {
  grep -E 'VoiceAgentE2E.*hermes_tool_response_hash .*actualHash=' "$LOG_FILE" |
    tail -n 1 |
    sed -E 's/^.*hermes_tool_response_hash //'
}

write_e2e_report() {
  umask 077
  mkdir -p "$LOG_DIR" "$(dirname "$REPORT_FILE")"
  local source_text_file="$PROMPT_SOURCE_TEXT_FILE"
  local input_transcript_file
  local hermes_call_file
  local output_transcript_file
  local report_temp_file
  input_transcript_file="$(mktemp "$LOG_DIR/report-input-transcript.XXXXXX")"
  hermes_call_file="$(mktemp "$LOG_DIR/report-hermes-call.XXXXXX")"
  output_transcript_file="$(mktemp "$LOG_DIR/report-output-transcript.XXXXXX")"
  report_temp_file="$(mktemp "$LOG_DIR/report.XXXXXX")"
  register_report_temp_file "$input_transcript_file"
  register_report_temp_file "$hermes_call_file"
  register_report_temp_file "$output_transcript_file"
  register_report_temp_file "$report_temp_file"
  chmod 600 "$input_transcript_file" "$hermes_call_file" "$output_transcript_file" "$report_temp_file"

  if [[ ! -s "$source_text_file" ]]; then
    local source_temp_file
    source_temp_file="$(mktemp "$LOG_DIR/report-source-text.XXXXXX")"
    register_report_temp_file "$source_temp_file"
    chmod 600 "$source_temp_file"
    printf 'missing' > "$source_temp_file"
    mv -f "$source_temp_file" "$source_text_file"
    chmod 600 "$source_text_file"
  fi
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
  } > "$report_temp_file"
  mv -f "$report_temp_file" "$REPORT_FILE"
  chmod 600 "$REPORT_FILE"
  rm -f "$input_transcript_file" "$hermes_call_file" "$output_transcript_file"
  rm -f "$LOG_DIR/input-transcript.txt" "$LOG_DIR/hermes-call.txt" "$LOG_DIR/output-transcript.txt"
  printf 'Voice Agent E2E report: %s\n' "$REPORT_FILE"
}

generate_pcm_prompt() {
  require_command ffmpeg
  if [[ ! "$FLITE_VOICE" =~ ^[A-Za-z0-9_-]+$ ]]; then
    printf 'VOICE_AGENT_E2E_FLITE_VOICE contains unsupported characters: %s\n' "$FLITE_VOICE" >&2
    return 2
  fi
  umask 077
  mkdir -p "$LOG_DIR" "$(dirname "$GENERATED_PCM_PATH")"
  local prompt_text_file="$PROMPT_SOURCE_TEXT_FILE"
  local ffmpeg_prompt_text_file
  ffmpeg_prompt_text_file="$(mktemp /tmp/rikkahub-voice-agent-e2e-prompt.XXXXXX)"
  FFMPEG_PROMPT_TEXT_CLEANUP_PATH="$ffmpeg_prompt_text_file"
  printf '%s' "$PROMPT_TEXT" > "$prompt_text_file"
  printf '%s' "$PROMPT_TEXT" > "$ffmpeg_prompt_text_file"
  chmod 600 "$prompt_text_file"
  chmod 600 "$ffmpeg_prompt_text_file"
  printf 'Generating PCM prompt from VOICE_AGENT_E2E_PROMPT_TEXT.\n'
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

clear_app_text_artifacts() {
  for app_path in \
    "$APP_MANUAL_ANSWER_PATH" \
    "$APP_INPUT_TRANSCRIPT_PATH" \
    "$APP_OUTPUT_TRANSCRIPT_PATH" \
    "$APP_HERMES_CALL_PATH"; do
    adb_cmd shell "run-as $PACKAGE rm -f $app_path" >/dev/null 2>&1 || true
  done
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
    clear_app_text_artifacts
  fi
  if [[ -n "${LOGCAT_PID:-}" ]]; then
    kill "$LOGCAT_PID" >/dev/null 2>&1 || true
    wait "$LOGCAT_PID" >/dev/null 2>&1 || true
  fi
  exit "$status"
}
trap cleanup EXIT

require_env VOICE_AGENT_E2E_CONVERSATION_ID

if [[ -z "${VOICE_AGENT_E2E_PCM_PATH:-}" ]]; then
  generate_pcm_prompt
  VOICE_AGENT_E2E_PCM_PATH="$GENERATED_PCM_PATH"
fi

if [[ "$MANUAL_REVIEW" == "0" ]]; then
  require_env VOICE_AGENT_E2E_EXPECTED_HASH
  EXPECTED_HASH_LOWER="$(printf '%s' "$VOICE_AGENT_E2E_EXPECTED_HASH" | tr '[:upper:]' '[:lower:]')"
  if [[ ! "$EXPECTED_HASH_LOWER" =~ ^[0-9a-f]{64}$ ]]; then
    printf 'VOICE_AGENT_E2E_EXPECTED_HASH must be a 64-character SHA-256 hex digest.\n' >&2
    exit 2
  fi
fi

if [[ ! -f "$VOICE_AGENT_E2E_PCM_PATH" ]]; then
  printf 'VOICE_AGENT_E2E_PCM_PATH does not exist: %s\n' "$VOICE_AGENT_E2E_PCM_PATH" >&2
  exit 2
fi

mkdir -p "$LOG_DIR"
rm -f "$LOG_FILE"
rm -f "$MANUAL_REVIEW_ANSWER_FILE"
if [[ -n "${VOICE_AGENT_E2E_PCM_PATH:-}" && "$GENERATED_PCM_FROM_PROMPT" == "0" ]]; then
  if [[ -n "${VOICE_AGENT_E2E_PROMPT_TEXT:-}" ]]; then
    printf '%s' "$PROMPT_TEXT" > "$PROMPT_SOURCE_TEXT_FILE"
    chmod 600 "$PROMPT_SOURCE_TEXT_FILE"
  else
    rm -f "$PROMPT_SOURCE_TEXT_FILE"
  fi
fi

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
printf 'Clearing previous app-private E2E artifacts...\n'
clear_app_text_artifacts

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
adb_long_cmd push "$VOICE_AGENT_E2E_PCM_PATH" "$DEVICE_TMP_PCM" >/dev/null
APP_PCM_CLEANUP_NEEDED=1
adb_cmd shell "run-as $PACKAGE cp $DEVICE_TMP_PCM files/$APP_PCM_PATH"
if adb_cmd shell rm -f "$DEVICE_TMP_PCM" >/dev/null 2>&1; then
  DEVICE_TMP_PCM_CLEANUP_NEEDED=0
fi

printf 'Starting Voice Agent foreground service...\n'
if [[ "$MANUAL_REVIEW" == "1" ]]; then
  adb_cmd shell am start-foreground-service \
    -n "$SERVICE_COMPONENT" \
    -a "$CALL_START_ACTION" \
    --es conversationId "$VOICE_AGENT_E2E_CONVERSATION_ID" \
    --ez enableVoiceE2EArtifacts true >/dev/null
else
  adb_cmd shell am start-foreground-service \
    -n "$SERVICE_COMPONENT" \
    -a "$CALL_START_ACTION" \
    --es conversationId "$VOICE_AGENT_E2E_CONVERSATION_ID" >/dev/null
fi
CALL_STARTED=1

wait_for_log "Gemini setup complete" 'VoiceAgentGemini.*event kind=SetupComplete' 120

printf 'Injecting private PCM prompt...\n'
adb_cmd shell am broadcast \
  -n "$INJECT_COMPONENT" \
  -a "$INJECT_ACTION" \
  --es path "$APP_PCM_PATH" \
  --ei chunk_bytes "${VOICE_AGENT_E2E_CHUNK_BYTES:-3200}" \
  --el chunk_delay_ms "${VOICE_AGENT_E2E_CHUNK_DELAY_MS:-20}" \
  --el leading_silence_ms "${VOICE_AGENT_E2E_LEADING_SILENCE_MS:-100}" \
  --el trailing_silence_ms "${VOICE_AGENT_E2E_TRAILING_SILENCE_MS:-200}" >/dev/null

wait_for_log "debug PCM delivered" 'VoiceAudioDebugInjection.*debug_audio_injection result delivered=true' 30
if ! wait_for_log "Gemini ask_hermes tool call received" \
  'VoiceAgentGemini.*receive kind=toolCall' \
  "$GEMINI_TOOL_CALL_TIMEOUT_SECONDS"; then
  if [[ "$MANUAL_REVIEW" == "1" && "$WAIT_FOR_LOG_FAILURE" == "timeout" ]]; then
    fail_if_log "common forbidden marker" "$COMMON_FORBIDDEN_PATTERN" || exit 1
    write_missing_tool_call_diagnostics
    fail_if_log "common forbidden marker" "$COMMON_FORBIDDEN_PATTERN" || exit 1
  fi
  exit 1
fi
if [[ "$MANUAL_REVIEW" == "1" ]]; then
  wait_for_log "Hermes response hash observed for manual review" \
    'VoiceAgentE2E.*hermes_tool_response_hash .*actualHash=[0-9a-f]+(,|$)' \
    "$HERMES_RESPONSE_TIMEOUT_SECONDS"
else
  wait_for_log "Hermes response hash matched" \
    "VoiceAgentE2E.*hermes_tool_response_hash .*actualHash=$EXPECTED_HASH_LOWER(,|$)" \
    "$HERMES_RESPONSE_TIMEOUT_SECONDS"
fi
wait_for_log "Gemini tool response sent" 'VoiceAgentGemini.*send kind=toolResponse sent=true' 60
wait_for_log "Gemini output audio received" 'VoiceAgentGemini.*event kind=OutputAudio' 120
wait_for_log "Voice playback queued" 'AndroidVoiceAudioEngine.*Voice playback queued' 60
wait_for_log "Voice playback wrote" 'AndroidVoiceAudioEngine.*Voice playback wrote' 60

fail_if_log "Voice Lab 403" 'Voice Lab request failed 403'
fail_if_log "Cloudflare auth HTML" 'Cloudflare|cf-error|Access denied'
fail_if_log "fatal exception" 'FATAL EXCEPTION'
fail_if_log "playback write failure" 'Voice playback write failed|AudioTrack write failed|AudioTrack write error'
if [[ "$MANUAL_REVIEW" == "0" ]] &&
  grep -E 'VoiceAgentE2E.*hermes_tool_response_hash .*actualHash=' "$LOG_FILE" |
  grep -Ev "actualHash=$EXPECTED_HASH_LOWER(,|$)" >/dev/null 2>&1; then
  printf 'Forbidden marker found: Hermes hash mismatch\n' >&2
  printf 'Expected actualHash=%s\n' "$EXPECTED_HASH_LOWER" >&2
  exit 1
fi

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
