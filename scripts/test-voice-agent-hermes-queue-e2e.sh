#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$ROOT_DIR/scripts/voice-agent-hermes-queue-e2e.sh"
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

assert_not_contains() {
  local haystack="$1"
  local needle="$2"
  if [[ "$haystack" == *"$needle"* ]]; then
    printf 'Expected output not to contain: %s\n' "$needle" >&2
    printf 'Actual output:\n%s\n' "$haystack" >&2
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

assert_file_line_count() {
  local path="$1"
  local expected_count="$2"
  if [[ ! -f "$path" ]]; then
    printf 'Expected file to exist: %s\n' "$path" >&2
    exit 1
  fi
  local actual_count
  actual_count="$(awk 'END { print NR + 0 }' "$path")"
  if [[ "$actual_count" != "$expected_count" ]]; then
    printf 'Expected file %s to contain %s lines, got %s\n' "$path" "$expected_count" "$actual_count" >&2
    printf 'Actual contents:\n%s\n' "$(cat "$path")" >&2
    exit 1
  fi
}

assert_file_not_exists() {
  local path="$1"
  if [[ -e "$path" ]]; then
    printf 'Expected file not to exist: %s\n' "$path" >&2
    printf 'Actual contents:\n%s\n' "$(cat "$path")" >&2
    exit 1
  fi
}

write_fake_readiness_script() {
  cat > "$TMP_DIR/adb-ready.sh" <<'FAKE_READY'
#!/usr/bin/env bash
set -euo pipefail
printf 'ADB ready: serial=%s state=device boot_completed=1 bootanim=stopped model=SM-S711B android=16\n' "${1:-RZ}"
FAKE_READY
  chmod +x "$TMP_DIR/adb-ready.sh"
}

write_fake_ffmpeg() {
  cat > "$TMP_DIR/ffmpeg" <<'FAKE_FFMPEG'
#!/usr/bin/env bash
set -euo pipefail
output="${@: -1}"
input="$5"
expected_voice="${FAKE_FFMPEG_EXPECTED_VOICE:-slt}"
if [[ "$input" != flite=textfile=*":voice=$expected_voice" ]]; then
  printf 'unexpected ffmpeg input: %s\n' "$input" >&2
  exit 98
fi
textfile="${input#flite=textfile=}"
textfile="${textfile%:voice=$expected_voice}"
prompt_text="$(cat "$textfile")"
if [[ -n "${FAKE_FFMPEG_PROMPT_LOG:-}" ]]; then
  printf '%s\n' "$prompt_text" >> "$FAKE_FFMPEG_PROMPT_LOG"
fi
if [[ -n "${FAKE_FFMPEG_EXPECTED_PROMPT:-}" && "$prompt_text" != "${FAKE_FFMPEG_EXPECTED_PROMPT:-}" ]]; then
  printf 'unexpected prompt text: %s\n' "$(cat "$textfile")" >&2
  exit 96
fi
printf 'generated queue pcm' > "$output"
FAKE_FFMPEG
  chmod +x "$TMP_DIR/ffmpeg"
}

write_fake_adb() {
  cat > "$TMP_DIR/adb" <<'FAKE_ADB'
#!/usr/bin/env bash
set -euo pipefail

if [[ -n "${FAKE_ADB_ARGS_LOG:-}" ]]; then
  printf '%s\n' "$*" >> "$FAKE_ADB_ARGS_LOG"
fi

require_drain_before_artifact_pull() {
  if [[ ! -f "${FAKE_ADB_DRAINED_MARKER:?}" ]]; then
    printf 'artifact pulled before service drain: %s\n' "$*" >&2
    exit 97
  fi
}

args="$*"
case "$args" in
  "devices -l")
    printf 'List of devices attached\n'
    printf 'RZ device product:r11q model:SM-S711B device:r11q transport_id:1\n'
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
06-11 12:00:00.000 D/VoiceAgentGemini(1): event kind=SetupComplete
06-11 12:00:01.000 I/VoiceAudioDebugInjection(1): debug_audio_injection result delivered=true
06-11 12:00:01.524 D/VoiceAgentGemini(1): event kind=SessionResumptionUpdate
LOGS
    case "${FAKE_QUEUE_SCENARIO:-pass}" in
      delayed-markers)
        sleep 2
        ;;
      missing-tool-call)
        sleep 2
        exit 0
        ;;
      forbidden-before-tool-call)
        printf '06-11 12:00:02.000 E/VoiceAgentCallSession(1): Voice Lab request failed 524\n'
        sleep 1
        exit 0
        ;;
    esac
    cat <<'LOGS'
06-11 12:00:02.000 D/VoiceAgentE2E(1): hermes_tool_call_received callId=call-a promptChars=5
06-11 12:00:03.000 D/VoiceAgentE2E(1): hermes_tool_call_received callId=call-b promptChars=6
06-11 12:00:04.000 D/VoiceAgentE2E(1): hermes_queue_event type=job_created callId=call-a jobId=job-a status=queued sent=n/a
06-11 12:00:05.000 D/VoiceAgentE2E(1): hermes_queue_event type=job_created callId=call-b jobId=job-b status=queued sent=n/a
06-11 12:00:06.000 D/VoiceAgentGemini(1): send kind=toolResponse sent=true
06-11 12:00:07.000 D/VoiceAgentGemini(1): send kind=toolResponse sent=true
06-11 12:00:08.000 D/VoiceAgentGemini(1): event kind=OutputAudio
06-11 12:00:09.000 D/AndroidVoiceAudioEngine(1): Voice playback queued bytes=3200
06-11 12:00:10.000 D/AndroidVoiceAudioEngine(1): Voice playback wrote bytes=3200
06-11 12:00:10.500 D/VoiceAgentGemini(1): event kind=GenerationComplete
06-11 12:00:11.000 I/VoiceAudioDebugInjection(1): debug_audio_injection result delivered=true
LOGS
    case "${FAKE_QUEUE_SCENARIO:-pass}" in
      one-complete)
        cat <<'LOGS'
06-11 12:01:00.000 D/VoiceAgentE2E(1): hermes_tool_response_hash callId=call-a, responseChars=5, normalizedChars=5, actualHash=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa, elapsedMs=1000, serverElapsedMs=900
06-11 12:01:01.000 D/VoiceAgentE2E(1): hermes_queue_event type=job_completed callId=call-a jobId=job-a status=succeeded sent=n/a
06-11 12:01:02.000 D/VoiceAgentE2E(1): hermes_queue_event type=late_text_turn_sent callId=call-a jobId=job-a status=none sent=true
LOGS
        ;;
      duplicate-job)
        cat <<'LOGS'
06-11 12:01:00.000 D/VoiceAgentE2E(1): hermes_queue_event type=job_created callId=call-c jobId=job-a status=queued sent=n/a
LOGS
        ;;
      forbidden-524)
        printf '06-11 12:01:00.000 E/VoiceAgentCallSession(1): Voice Lab request failed 524\n'
        ;;
      tool-failed-404)
        printf '06-11 12:01:00.000 W/VoiceAgentE2E(1): hermes_tool_failed callId=call-a, elapsedMs=137, message=Voice Lab request failed 404\n'
        ;;
      extra-created)
        cat <<'LOGS'
06-11 12:00:11.000 D/VoiceAgentE2E(1): hermes_queue_event type=job_created callId=call-c jobId=job-c status=queued sent=n/a
06-11 12:01:00.000 D/VoiceAgentE2E(1): hermes_tool_response_hash callId=call-a, responseChars=5, normalizedChars=5, actualHash=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa, elapsedMs=1000, serverElapsedMs=900
06-11 12:01:01.000 D/VoiceAgentE2E(1): hermes_queue_event type=job_completed callId=call-a jobId=job-a status=succeeded sent=n/a
06-11 12:01:02.000 D/VoiceAgentE2E(1): hermes_queue_event type=late_text_turn_sent callId=call-a jobId=job-a status=none sent=true
06-11 12:01:03.000 D/VoiceAgentE2E(1): hermes_tool_response_hash callId=call-b, responseChars=6, normalizedChars=6, actualHash=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb, elapsedMs=2000, serverElapsedMs=1900
06-11 12:01:04.000 D/VoiceAgentE2E(1): hermes_queue_event type=job_completed callId=call-b jobId=job-b status=succeeded sent=n/a
06-11 12:01:05.000 D/VoiceAgentE2E(1): hermes_queue_event type=late_text_turn_sent callId=call-b jobId=job-b status=none sent=true
06-11 12:01:06.000 D/VoiceAgentGemini(1): event kind=OutputAudio
06-11 12:01:07.000 D/AndroidVoiceAudioEngine(1): Voice playback queued bytes=3200
06-11 12:01:08.000 D/AndroidVoiceAudioEngine(1): Voice playback wrote bytes=3200
LOGS
        ;;
      unknown-completed)
        cat <<'LOGS'
06-11 12:01:00.000 D/VoiceAgentE2E(1): hermes_tool_response_hash callId=call-a, responseChars=5, normalizedChars=5, actualHash=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa, elapsedMs=1000, serverElapsedMs=900
06-11 12:01:01.000 D/VoiceAgentE2E(1): hermes_queue_event type=job_completed callId=call-a jobId=job-a status=succeeded sent=n/a
06-11 12:01:02.000 D/VoiceAgentE2E(1): hermes_queue_event type=late_text_turn_sent callId=call-a jobId=job-a status=none sent=true
06-11 12:01:03.000 D/VoiceAgentE2E(1): hermes_tool_response_hash callId=call-x, responseChars=6, normalizedChars=6, actualHash=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb, elapsedMs=2000, serverElapsedMs=1900
06-11 12:01:04.000 D/VoiceAgentE2E(1): hermes_queue_event type=job_completed callId=call-x jobId=job-x status=succeeded sent=n/a
06-11 12:01:05.000 D/VoiceAgentE2E(1): hermes_queue_event type=late_text_turn_sent callId=call-x jobId=job-x status=none sent=true
06-11 12:01:06.000 D/VoiceAgentGemini(1): event kind=OutputAudio
06-11 12:01:07.000 D/AndroidVoiceAudioEngine(1): Voice playback queued bytes=3200
06-11 12:01:08.000 D/AndroidVoiceAudioEngine(1): Voice playback wrote bytes=3200
LOGS
        ;;
      stale-audio)
        cat <<'LOGS'
06-11 12:00:11.000 D/VoiceAgentGemini(1): event kind=OutputAudio
06-11 12:00:12.000 D/AndroidVoiceAudioEngine(1): Voice playback queued bytes=3200
06-11 12:00:13.000 D/AndroidVoiceAudioEngine(1): Voice playback wrote bytes=3200
06-11 12:01:00.000 D/VoiceAgentE2E(1): hermes_tool_response_hash callId=call-a, responseChars=5, normalizedChars=5, actualHash=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa, elapsedMs=1000, serverElapsedMs=900
06-11 12:01:01.000 D/VoiceAgentE2E(1): hermes_queue_event type=job_completed callId=call-a jobId=job-a status=succeeded sent=n/a
06-11 12:01:02.000 D/VoiceAgentE2E(1): hermes_queue_event type=late_text_turn_sent callId=call-a jobId=job-a status=none sent=true
06-11 12:01:03.000 D/VoiceAgentE2E(1): hermes_tool_response_hash callId=call-b, responseChars=6, normalizedChars=6, actualHash=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb, elapsedMs=2000, serverElapsedMs=1900
06-11 12:01:04.000 D/VoiceAgentE2E(1): hermes_queue_event type=job_completed callId=call-b jobId=job-b status=succeeded sent=n/a
06-11 12:01:05.000 D/VoiceAgentE2E(1): hermes_queue_event type=late_text_turn_sent callId=call-b jobId=job-b status=none sent=true
LOGS
        ;;
      *)
        cat <<'LOGS'
06-11 12:01:00.000 D/VoiceAgentE2E(1): hermes_tool_response_hash callId=call-a, responseChars=5, normalizedChars=5, actualHash=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa, elapsedMs=1000, serverElapsedMs=900
06-11 12:01:01.000 D/VoiceAgentE2E(1): hermes_queue_event type=job_completed callId=call-a jobId=job-a status=succeeded sent=n/a
06-11 12:01:02.000 D/VoiceAgentE2E(1): hermes_queue_event type=late_text_turn_sent callId=call-a jobId=job-a status=none sent=true
06-11 12:01:03.000 D/VoiceAgentE2E(1): hermes_tool_response_hash callId=call-b, responseChars=6, normalizedChars=6, actualHash=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb, elapsedMs=2000, serverElapsedMs=1900
06-11 12:01:04.000 D/VoiceAgentE2E(1): hermes_queue_event type=job_completed callId=call-b jobId=job-b status=succeeded sent=n/a
06-11 12:01:05.000 D/VoiceAgentE2E(1): hermes_queue_event type=late_text_turn_sent callId=call-b jobId=job-b status=none sent=true
06-11 12:01:06.000 D/VoiceAgentGemini(1): event kind=OutputAudio
06-11 12:01:07.000 D/AndroidVoiceAudioEngine(1): Voice playback queued bytes=3200
06-11 12:01:08.000 D/AndroidVoiceAudioEngine(1): Voice playback wrote bytes=3200
LOGS
        ;;
    esac
    deadline=$((SECONDS + 5))
    while [[ ! -f "${FAKE_ADB_END_MARKER:?}" && "$SECONDS" -lt "$deadline" ]]; do
      sleep 0.1
    done
    if [[ -f "${FAKE_ADB_END_MARKER:?}" && "${FAKE_QUEUE_SCENARIO:-pass}" != "cleanup-timeout" ]]; then
      printf '06-11 12:02:00.000 D/VoiceAgentCallService(1): end completed conversationId=conversation-1\n'
      : > "${FAKE_ADB_DRAINED_MARKER:?}"
    fi
    sleep 1
    ;;
  "-s RZ push "*)
    printf '%s: 1 file pushed, 0 skipped.\n' "$2"
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug mkdir -p files/voice-e2e")
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug cp /data/local/tmp/rikkahub-voice-agent-queue-e2e-prompt.pcm files/voice-e2e/queue-prompt.pcm")
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug cp /data/local/tmp/rikkahub-voice-agent-queue-e2e-prompt.pcm files/voice-e2e/queue-prompt-"*".pcm")
    ;;
  "-s RZ shell rm -f /data/local/tmp/rikkahub-voice-agent-queue-e2e-prompt.pcm")
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug rm -f files/voice-e2e/queue-prompt.pcm")
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug rm -f files/voice-e2e/queue-prompt-"*".pcm")
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug rm -f no_backup/voice-e2e/hermes-events.ndjson")
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug rm -f no_backup/voice-e2e/input-transcript.txt")
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug rm -f no_backup/voice-e2e/output-transcript.txt")
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug rm -f no_backup/voice-e2e/hermes-call.txt")
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug rm -f no_backup/voice-e2e/hermes-answer.txt")
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug rm -f no_backup/voice-e2e/latest-trace-id.txt")
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug rm -f no_backup/voice-e2e/trace-queue/hermes-events.ndjson")
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug rm -f no_backup/voice-e2e/trace-queue/input-transcript.txt")
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug rm -f no_backup/voice-e2e/trace-queue/output-transcript.txt")
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug rm -f no_backup/voice-e2e/trace-queue/hermes-call.txt")
    ;;
  "-s RZ shell run-as me.rerere.rikkahub.debug rm -f no_backup/voice-e2e/trace-queue/hermes-answer.txt")
    ;;
  "-s RZ shell am start-foreground-service -n me.rerere.rikkahub.debug/me.rerere.rikkahub.voiceagent.VoiceAgentCallService -a me.rerere.rikkahub.voiceagent.action.START --es conversationId conversation-1")
    rm -f "${FAKE_ADB_END_MARKER:?}"
    rm -f "${FAKE_ADB_DRAINED_MARKER:?}"
    ;;
  "-s RZ shell am start-foreground-service -n me.rerere.rikkahub.debug/me.rerere.rikkahub.voiceagent.VoiceAgentCallService -a me.rerere.rikkahub.voiceagent.action.END")
    : > "${FAKE_ADB_END_MARKER:?}"
    ;;
  "-s RZ shell am broadcast "*)
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug cat no_backup/voice-e2e/latest-trace-id.txt")
    case "${FAKE_ADB_LATEST_TRACE_ID:-trace-queue}" in
      missing)
        exit 1
        ;;
      *)
        printf '%s' "${FAKE_ADB_LATEST_TRACE_ID:-trace-queue}"
        ;;
    esac
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug cat no_backup/voice-e2e/trace-queue/hermes-events.ndjson")
    require_drain_before_artifact_pull "$args"
    cat <<'EVENTS'
{"type":"job_created","callId":"call-a","jobId":"job-a","status":"queued"}
{"type":"job_created","callId":"call-b","jobId":"job-b","status":"queued"}
{"type":"job_completed","callId":"call-a","jobId":"job-a","status":"succeeded","hash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","elapsedMs":1000,"serverElapsedMs":900,"answerChars":18}
{"type":"late_text_turn_sent","callId":"call-a","jobId":"job-a","sent":true}
{"type":"job_completed","callId":"call-b","jobId":"job-b","status":"succeeded","hash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","elapsedMs":2000,"serverElapsedMs":1900,"answerChars":18}
{"type":"late_text_turn_sent","callId":"call-b","jobId":"job-b","sent":true}
EVENTS
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug cat no_backup/voice-e2e/trace-queue/input-transcript.txt")
    require_drain_before_artifact_pull "$args"
    printf 'Ask Hermes two separate questions now.'
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug cat no_backup/voice-e2e/trace-queue/output-transcript.txt")
    require_drain_before_artifact_pull "$args"
    printf 'I queued the Hermes work and now have two answers.'
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug cat no_backup/voice-e2e/trace-queue/hermes-call.txt")
    require_drain_before_artifact_pull "$args"
    printf 'latest Hermes prompt snapshot'
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug cat no_backup/voice-e2e/trace-queue/hermes-answer.txt")
    require_drain_before_artifact_pull "$args"
    printf 'latest Hermes answer snapshot'
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug cat no_backup/voice-e2e/hermes-events.ndjson")
    require_drain_before_artifact_pull "$args"
    cat <<'EVENTS'
{"type":"job_created","callId":"call-a","jobId":"job-a","status":"queued"}
{"type":"job_created","callId":"call-b","jobId":"job-b","status":"queued"}
{"type":"job_completed","callId":"call-a","jobId":"job-a","status":"succeeded","hash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","elapsedMs":1000,"serverElapsedMs":900,"answerChars":18}
{"type":"late_text_turn_sent","callId":"call-a","jobId":"job-a","sent":true}
{"type":"job_completed","callId":"call-b","jobId":"job-b","status":"succeeded","hash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","elapsedMs":2000,"serverElapsedMs":1900,"answerChars":18}
{"type":"late_text_turn_sent","callId":"call-b","jobId":"job-b","sent":true}
EVENTS
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug cat no_backup/voice-e2e/input-transcript.txt")
    require_drain_before_artifact_pull "$args"
    printf 'Ask Hermes two separate questions now.'
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug cat no_backup/voice-e2e/output-transcript.txt")
    require_drain_before_artifact_pull "$args"
    printf 'I queued the Hermes work and now have two answers.'
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug cat no_backup/voice-e2e/hermes-call.txt")
    require_drain_before_artifact_pull "$args"
    printf 'latest Hermes prompt snapshot'
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug cat no_backup/voice-e2e/hermes-answer.txt")
    require_drain_before_artifact_pull "$args"
    printf 'latest Hermes answer snapshot'
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug head -c 240 no_backup/voice-e2e/trace-queue/input-transcript.txt")
    if [[ "${FAKE_ADB_LARGE_DIAGNOSTIC_ARTIFACTS:-0}" == "1" ]]; then
      printf 'Gemini understood from voice: '
      printf 'A%.0s' {1..260}
      printf 'diagnostic-tail'
    else
      printf 'Ask Hermes two separate questions now.'
    fi
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug head -c 240 no_backup/voice-e2e/trace-queue/output-transcript.txt")
    if [[ "${FAKE_ADB_FORBIDDEN_DURING_DIAGNOSTICS_LOG_FILE:-}" != "" ]]; then
      printf '06-11 12:00:03.000 E/VoiceAgentCallSession(1): Voice Lab request failed 524\n' >> "$FAKE_ADB_FORBIDDEN_DURING_DIAGNOSTICS_LOG_FILE"
    fi
    if [[ "${FAKE_ADB_MISSING_DIAGNOSTIC_ARTIFACT:-}" == "output-transcript" ]]; then
      exit 1
    fi
    printf 'I answered directly instead of using Hermes.'
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug head -c 240 no_backup/voice-e2e/trace-queue/hermes-call.txt")
    printf 'latest diagnostic Hermes prompt snapshot'
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug head -c 240 no_backup/voice-e2e/trace-queue/hermes-events.ndjson")
    printf '{"type":"diagnostic","jobId":"job-diagnostic"}'
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug head -c 240 no_backup/voice-e2e/input-transcript.txt")
    printf 'Ask Hermes two separate questions now.'
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug head -c 240 no_backup/voice-e2e/output-transcript.txt")
    printf 'I answered directly instead of using Hermes.'
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug head -c 240 no_backup/voice-e2e/hermes-call.txt")
    printf 'latest diagnostic Hermes prompt snapshot'
    ;;
  "-s RZ exec-out run-as me.rerere.rikkahub.debug head -c 240 no_backup/voice-e2e/hermes-events.ndjson")
    printf '{"type":"diagnostic","jobId":"job-diagnostic"}'
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
printf 'queue pcm' > "$TMP_DIR/queue-prompt.pcm"
FAKE_ADB_ARGS_LOG="$TMP_DIR/adb-args.log"
FAKE_ADB_END_MARKER="$TMP_DIR/end-requested"
FAKE_ADB_DRAINED_MARKER="$TMP_DIR/end-drained"
export FAKE_ADB_ARGS_LOG
export FAKE_ADB_END_MARKER
export FAKE_ADB_DRAINED_MARKER
: > "$FAKE_ADB_ARGS_LOG"

default_prompt_1="Ask Hermes. Use the ask Hermes tool now. Ask Hermes: Are you connected to G Brain? Answer yes or no."
default_prompt_2="Ask Hermes. Use the ask Hermes tool now. Ask Hermes: Recall the private queue test fact. Tell me the answer when it is ready."
default_prompts_file="$TMP_DIR/default-prompts.txt"
printf '%s\n%s\n' "$default_prompt_1" "$default_prompt_2" > "$default_prompts_file"
default_prompt="$(cat "$default_prompts_file")"
default_prompt_preview="Turn 1: ${default_prompt_1}"

pass_log_dir="$TMP_DIR/pass-log"
pass_prompt_log="$TMP_DIR/pass-ffmpeg-prompts.log"
set +e
pass_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_FFMPEG_PROMPT_LOG="$pass_prompt_log" \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_QUEUE_E2E_LOG_DIR="$pass_log_dir" \
  VOICE_AGENT_QUEUE_E2E_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_QUEUE_E2E_COMPLETION_TIMEOUT_SECONDS=5 \
  "$SCRIPT" 2>&1
)"
pass_status=$?
set -e

if [[ "$pass_status" -ne 0 ]]; then
  printf 'Expected queue E2E pass scenario to pass, got %s.\n' "$pass_status" >&2
  printf 'Actual output:\n%s\n' "$pass_output" >&2
  exit 1
fi
assert_contains "$pass_output" "PASS marker: at least 2 ask_hermes tool calls"
assert_contains "$pass_output" "PASS marker: at least 2 queued Hermes jobs"
assert_contains "$pass_output" "PASS marker: at least 2 Hermes jobs completed"
assert_contains "$pass_output" "PASS marker: at least 2 late Gemini text turns sent"
assert_contains "$pass_output" "Voice Agent Hermes queue E2E reached manual review gate."
assert_contains "$pass_output" "PIPELINE: passed"
assert_contains "$pass_output" "CLEANUP: passed"
assert_not_contains "$pass_output" "private answer one"
assert_not_contains "$(cat "$pass_log_dir/report.txt")" "private answer one"
assert_not_contains "$(cat "$pass_log_dir/report.txt")" "private answer two"
assert_file_contains "$pass_log_dir/hermes-events.ndjson" "\"jobId\":\"job-a\""
assert_not_contains "$(cat "$pass_log_dir/hermes-events.ndjson")" "\"answer\""
assert_file_contains "$FAKE_ADB_ARGS_LOG" "cat no_backup/voice-e2e/latest-trace-id.txt"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "cat no_backup/voice-e2e/trace-queue/hermes-events.ndjson"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "cat no_backup/voice-e2e/trace-queue/input-transcript.txt"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "cat no_backup/voice-e2e/trace-queue/output-transcript.txt"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "cat no_backup/voice-e2e/trace-queue/hermes-call.txt"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "cat no_backup/voice-e2e/trace-queue/hermes-answer.txt"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "rm -f no_backup/voice-e2e/hermes-events.ndjson"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "rm -f no_backup/voice-e2e/trace-queue/hermes-events.ndjson"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "rm -f no_backup/voice-e2e/latest-trace-id.txt"
assert_file_line_count "$pass_prompt_log" 2
assert_file_contains_exactly "$pass_prompt_log" "$(cat "$default_prompts_file")"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "files/voice-e2e/queue-prompt-1.pcm"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "files/voice-e2e/queue-prompt-2.pcm"

delayed_markers_log_dir="$TMP_DIR/delayed-markers-log"
: > "$FAKE_ADB_ARGS_LOG"
set +e
delayed_markers_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_QUEUE_SCENARIO=delayed-markers \
  FAKE_ADB_LATEST_TRACE_ID=missing \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_QUEUE_E2E_PCM_PATH="$TMP_DIR/queue-prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_QUEUE_E2E_LOG_DIR="$delayed_markers_log_dir" \
  VOICE_AGENT_QUEUE_E2E_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_QUEUE_E2E_COMPLETION_TIMEOUT_SECONDS=5 \
  "$SCRIPT" 2>&1
)"
delayed_markers_status=$?
set -e
if [[ "$delayed_markers_status" -ne 0 ]]; then
  printf 'Expected delayed-markers scenario to pass, got %s.\n' "$delayed_markers_status" >&2
  printf 'Actual output:\n%s\n' "$delayed_markers_output" >&2
  exit 1
fi
assert_contains "$delayed_markers_output" "PASS marker: at least 2 ask_hermes tool calls"
assert_contains "$delayed_markers_output" "Voice Agent Hermes queue E2E reached manual review gate."
assert_file_contains "$delayed_markers_log_dir/report.txt" "Ask Hermes two separate questions now."
assert_file_contains "$delayed_markers_log_dir/report.txt" "\"jobId\":\"job-a\""
assert_file_contains "$delayed_markers_log_dir/report.txt" "latest Hermes prompt snapshot"
assert_file_contains "$delayed_markers_log_dir/report.txt" "latest Hermes answer snapshot"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "cat no_backup/voice-e2e/latest-trace-id.txt"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "cat no_backup/voice-e2e/hermes-events.ndjson"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "cat no_backup/voice-e2e/input-transcript.txt"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "cat no_backup/voice-e2e/output-transcript.txt"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "cat no_backup/voice-e2e/hermes-call.txt"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "cat no_backup/voice-e2e/hermes-answer.txt"
assert_not_contains "$(cat "$FAKE_ADB_ARGS_LOG")" "cat no_backup/voice-e2e/trace-queue"

unsafe_markers_log_dir="$TMP_DIR/unsafe-markers-log"
: > "$FAKE_ADB_ARGS_LOG"
set +e
unsafe_markers_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_ADB_LATEST_TRACE_ID='../trace-queue' \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_QUEUE_E2E_PCM_PATH="$TMP_DIR/queue-prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_QUEUE_E2E_LOG_DIR="$unsafe_markers_log_dir" \
  VOICE_AGENT_QUEUE_E2E_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_QUEUE_E2E_COMPLETION_TIMEOUT_SECONDS=5 \
  "$SCRIPT" 2>&1
)"
unsafe_markers_status=$?
set -e
if [[ "$unsafe_markers_status" -ne 0 ]]; then
  printf 'Expected unsafe-markers scenario to pass, got %s.\n' "$unsafe_markers_status" >&2
  printf 'Actual output:\n%s\n' "$unsafe_markers_output" >&2
  exit 1
fi
assert_contains "$unsafe_markers_output" "Voice Agent Hermes queue E2E reached manual review gate."
assert_file_contains "$unsafe_markers_log_dir/report.txt" "Gemini understood from voice:"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "cat no_backup/voice-e2e/latest-trace-id.txt"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "cat no_backup/voice-e2e/hermes-events.ndjson"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "cat no_backup/voice-e2e/input-transcript.txt"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "cat no_backup/voice-e2e/output-transcript.txt"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "cat no_backup/voice-e2e/hermes-call.txt"
assert_file_contains "$FAKE_ADB_ARGS_LOG" "cat no_backup/voice-e2e/hermes-answer.txt"
assert_not_contains "$(cat "$FAKE_ADB_ARGS_LOG")" "cat no_backup/voice-e2e/trace-queue"

missing_tool_call_log_dir="$TMP_DIR/missing-tool-call-log"
: > "$FAKE_ADB_ARGS_LOG"
set +e
missing_tool_call_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_QUEUE_SCENARIO=missing-tool-call \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_QUEUE_E2E_LOG_DIR="$missing_tool_call_log_dir" \
  VOICE_AGENT_QUEUE_E2E_TOOL_CALL_TIMEOUT_SECONDS=1 \
  VOICE_AGENT_QUEUE_E2E_COMPLETION_TIMEOUT_SECONDS=1 \
  VOICE_AGENT_E2E_SERVICE_END_TIMEOUT_SECONDS=1 \
  "$SCRIPT" 2>&1
)"
missing_tool_call_status=$?
set -e
if [[ "$missing_tool_call_status" -eq 0 ]]; then
  printf 'Expected missing-tool-call scenario to fail.\n' >&2
  printf 'Actual output:\n%s\n' "$missing_tool_call_output" >&2
  exit 1
fi
missing_tool_call_diagnostics="$missing_tool_call_log_dir/missing-tool-call-diagnostics.txt"
assert_contains "$missing_tool_call_output" "ask_hermes tool calls, found 0."
assert_contains "$missing_tool_call_output" \
  "Voice Agent Hermes queue E2E diagnostic artifact: $missing_tool_call_diagnostics"
assert_file_contains "$missing_tool_call_diagnostics" "Text used to generate voice: $default_prompt_preview"
assert_not_contains "$(cat "$missing_tool_call_diagnostics")" "answer when it is ready."
assert_file_contains "$missing_tool_call_diagnostics" "Gemini understood from voice: Ask Hermes two separate questions now."
assert_file_contains "$missing_tool_call_diagnostics" "Gemini response to user: I answered directly instead of using Hermes."
assert_file_contains "$missing_tool_call_diagnostics" "Latest Hermes call: latest diagnostic Hermes prompt snapshot"
assert_file_contains "$missing_tool_call_diagnostics" 'Hermes queue events: {"type":"diagnostic","jobId":"job-diagnostic"}'
if [[ "$missing_tool_call_output" == *"I answered directly instead of using Hermes."* ||
  "$missing_tool_call_output" == *"latest diagnostic Hermes prompt snapshot"* ||
  "$missing_tool_call_output" == *"job-diagnostic"* ||
  "$missing_tool_call_output" == *"$default_prompt"* ||
  "$missing_tool_call_output" == *"Ask Hermes two separate questions now."* ]]; then
  printf 'Expected private diagnostic contents not to be printed to process output.\n' >&2
  printf 'Actual output:\n%s\n' "$missing_tool_call_output" >&2
  exit 1
fi
assert_file_contains "$FAKE_ADB_ARGS_LOG" \
  "exec-out run-as me.rerere.rikkahub.debug head -c 240 no_backup/voice-e2e/trace-queue/input-transcript.txt"

missing_diagnostic_artifact_log_dir="$TMP_DIR/missing-diagnostic-artifact-log"
: > "$FAKE_ADB_ARGS_LOG"
set +e
missing_diagnostic_artifact_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_QUEUE_SCENARIO=missing-tool-call \
  FAKE_ADB_MISSING_DIAGNOSTIC_ARTIFACT=output-transcript \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_QUEUE_E2E_LOG_DIR="$missing_diagnostic_artifact_log_dir" \
  VOICE_AGENT_QUEUE_E2E_TOOL_CALL_TIMEOUT_SECONDS=1 \
  VOICE_AGENT_QUEUE_E2E_COMPLETION_TIMEOUT_SECONDS=1 \
  VOICE_AGENT_E2E_SERVICE_END_TIMEOUT_SECONDS=1 \
  "$SCRIPT" 2>&1
)"
missing_diagnostic_artifact_status=$?
set -e
if [[ "$missing_diagnostic_artifact_status" -eq 0 ]]; then
  printf 'Expected missing diagnostic artifact scenario to fail.\n' >&2
  printf 'Actual output:\n%s\n' "$missing_diagnostic_artifact_output" >&2
  exit 1
fi
missing_diagnostic_artifact_file="$missing_diagnostic_artifact_log_dir/missing-tool-call-diagnostics.txt"
assert_contains "$missing_diagnostic_artifact_output" \
  "Voice Agent Hermes queue E2E diagnostic artifact: $missing_diagnostic_artifact_file"
assert_file_contains "$missing_diagnostic_artifact_file" "Gemini response to user: missing"

forbidden_during_diagnostics_log_dir="$TMP_DIR/forbidden-during-diagnostics-log"
: > "$FAKE_ADB_ARGS_LOG"
set +e
forbidden_during_diagnostics_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_QUEUE_SCENARIO=missing-tool-call \
  FAKE_ADB_FORBIDDEN_DURING_DIAGNOSTICS_LOG_FILE="$forbidden_during_diagnostics_log_dir/logcat.txt" \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_QUEUE_E2E_LOG_DIR="$forbidden_during_diagnostics_log_dir" \
  VOICE_AGENT_QUEUE_E2E_TOOL_CALL_TIMEOUT_SECONDS=1 \
  VOICE_AGENT_QUEUE_E2E_COMPLETION_TIMEOUT_SECONDS=1 \
  VOICE_AGENT_E2E_SERVICE_END_TIMEOUT_SECONDS=1 \
  "$SCRIPT" 2>&1
)"
forbidden_during_diagnostics_status=$?
set -e
if [[ "$forbidden_during_diagnostics_status" -eq 0 ]]; then
  printf 'Expected forbidden-during-diagnostics scenario to fail.\n' >&2
  printf 'Actual output:\n%s\n' "$forbidden_during_diagnostics_output" >&2
  exit 1
fi
assert_contains "$forbidden_during_diagnostics_output" "Forbidden marker found: common forbidden marker"
assert_not_contains "$forbidden_during_diagnostics_output" "Voice Agent Hermes queue E2E diagnostic artifact:"
assert_file_not_exists "$forbidden_during_diagnostics_log_dir/missing-tool-call-diagnostics.txt"

custom_pcm_missing_tool_call_log_dir="$TMP_DIR/custom-pcm-missing-tool-call-log"
mkdir -p "$custom_pcm_missing_tool_call_log_dir"
printf 'stale previous prompt text' > "$custom_pcm_missing_tool_call_log_dir/generated-prompt.txt"
: > "$FAKE_ADB_ARGS_LOG"
set +e
custom_pcm_missing_tool_call_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_QUEUE_SCENARIO=missing-tool-call \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_QUEUE_E2E_PCM_PATH="$TMP_DIR/queue-prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_QUEUE_E2E_LOG_DIR="$custom_pcm_missing_tool_call_log_dir" \
  VOICE_AGENT_QUEUE_E2E_TOOL_CALL_TIMEOUT_SECONDS=1 \
  VOICE_AGENT_QUEUE_E2E_COMPLETION_TIMEOUT_SECONDS=1 \
  VOICE_AGENT_E2E_SERVICE_END_TIMEOUT_SECONDS=1 \
  "$SCRIPT" 2>&1
)"
custom_pcm_missing_tool_call_status=$?
set -e
if [[ "$custom_pcm_missing_tool_call_status" -eq 0 ]]; then
  printf 'Expected custom PCM missing-tool-call scenario to fail.\n' >&2
  printf 'Actual output:\n%s\n' "$custom_pcm_missing_tool_call_output" >&2
  exit 1
fi
custom_pcm_missing_tool_call_diagnostics="$custom_pcm_missing_tool_call_log_dir/missing-tool-call-diagnostics.txt"
assert_contains "$custom_pcm_missing_tool_call_output" \
  "Voice Agent Hermes queue E2E diagnostic artifact: $custom_pcm_missing_tool_call_diagnostics"
assert_file_contains "$custom_pcm_missing_tool_call_diagnostics" "Text used to generate voice: missing"
assert_not_contains "$(cat "$custom_pcm_missing_tool_call_diagnostics")" "stale previous prompt text"
if [[ "$custom_pcm_missing_tool_call_output" == *"$default_prompt"* ||
  "$custom_pcm_missing_tool_call_output" == *"Ask Hermes two separate questions now."* ]]; then
  printf 'Expected custom PCM diagnostic contents not to be printed to process output.\n' >&2
  printf 'Actual output:\n%s\n' "$custom_pcm_missing_tool_call_output" >&2
  exit 1
fi

large_diagnostic_log_dir="$TMP_DIR/large-diagnostic-log"
: > "$FAKE_ADB_ARGS_LOG"
set +e
large_diagnostic_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_QUEUE_SCENARIO=missing-tool-call \
  FAKE_ADB_LARGE_DIAGNOSTIC_ARTIFACTS=1 \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_QUEUE_E2E_PCM_PATH="$TMP_DIR/queue-prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_QUEUE_E2E_LOG_DIR="$large_diagnostic_log_dir" \
  VOICE_AGENT_QUEUE_E2E_TOOL_CALL_TIMEOUT_SECONDS=1 \
  VOICE_AGENT_QUEUE_E2E_COMPLETION_TIMEOUT_SECONDS=1 \
  VOICE_AGENT_E2E_SERVICE_END_TIMEOUT_SECONDS=1 \
  "$SCRIPT" 2>&1
)"
large_diagnostic_status=$?
set -e
if [[ "$large_diagnostic_status" -eq 0 ]]; then
  printf 'Expected large diagnostic artifact scenario to fail.\n' >&2
  printf 'Actual output:\n%s\n' "$large_diagnostic_output" >&2
  exit 1
fi
large_diagnostic_file="$large_diagnostic_log_dir/missing-tool-call-diagnostics.txt"
assert_contains "$large_diagnostic_output" \
  "Voice Agent Hermes queue E2E diagnostic artifact: $large_diagnostic_file"
assert_file_contains "$large_diagnostic_file" "Gemini understood from voice: Gemini understood from voice: AAAAAAAAAAAAAAAA"
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

forbidden_before_tool_call_log_dir="$TMP_DIR/forbidden-before-tool-call-log"
: > "$FAKE_ADB_ARGS_LOG"
set +e
forbidden_before_tool_call_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_QUEUE_SCENARIO=forbidden-before-tool-call \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_QUEUE_E2E_PCM_PATH="$TMP_DIR/queue-prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_QUEUE_E2E_LOG_DIR="$forbidden_before_tool_call_log_dir" \
  VOICE_AGENT_QUEUE_E2E_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_QUEUE_E2E_COMPLETION_TIMEOUT_SECONDS=1 \
  VOICE_AGENT_E2E_SERVICE_END_TIMEOUT_SECONDS=1 \
  "$SCRIPT" 2>&1
)"
forbidden_before_tool_call_status=$?
set -e
if [[ "$forbidden_before_tool_call_status" -eq 0 ]]; then
  printf 'Expected forbidden-before-tool-call scenario to fail.\n' >&2
  printf 'Actual output:\n%s\n' "$forbidden_before_tool_call_output" >&2
  exit 1
fi
assert_contains "$forbidden_before_tool_call_output" "Forbidden marker found: common forbidden marker"
assert_not_contains "$forbidden_before_tool_call_output" "Voice Agent Hermes queue E2E diagnostic artifact:"
assert_file_not_exists "$forbidden_before_tool_call_log_dir/missing-tool-call-diagnostics.txt"

one_complete_log_dir="$TMP_DIR/one-complete-log"
set +e
one_complete_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_QUEUE_SCENARIO=one-complete \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_QUEUE_E2E_PCM_PATH="$TMP_DIR/queue-prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_QUEUE_E2E_LOG_DIR="$one_complete_log_dir" \
  VOICE_AGENT_QUEUE_E2E_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_QUEUE_E2E_COMPLETION_TIMEOUT_SECONDS=5 \
  "$SCRIPT" 2>&1
)"
one_complete_status=$?
set -e
if [[ "$one_complete_status" -eq 0 ]]; then
  printf 'Expected one-complete scenario to fail.\n' >&2
  printf 'Actual output:\n%s\n' "$one_complete_output" >&2
  exit 1
fi
assert_contains "$one_complete_output" "Expected at least 2 completed Hermes jobs, found 1."

duplicate_log_dir="$TMP_DIR/duplicate-log"
set +e
duplicate_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_QUEUE_SCENARIO=duplicate-job \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_QUEUE_E2E_PCM_PATH="$TMP_DIR/queue-prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_QUEUE_E2E_LOG_DIR="$duplicate_log_dir" \
  VOICE_AGENT_QUEUE_E2E_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_QUEUE_E2E_COMPLETION_TIMEOUT_SECONDS=5 \
  "$SCRIPT" 2>&1
)"
duplicate_status=$?
set -e
if [[ "$duplicate_status" -eq 0 ]]; then
  printf 'Expected duplicate-job scenario to fail.\n' >&2
  printf 'Actual output:\n%s\n' "$duplicate_output" >&2
  exit 1
fi
assert_contains "$duplicate_output" "Duplicate queued Hermes job id found: job-a"

forbidden_log_dir="$TMP_DIR/forbidden-log"
set +e
forbidden_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_QUEUE_SCENARIO=forbidden-524 \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_QUEUE_E2E_PCM_PATH="$TMP_DIR/queue-prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_QUEUE_E2E_LOG_DIR="$forbidden_log_dir" \
  VOICE_AGENT_QUEUE_E2E_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_QUEUE_E2E_COMPLETION_TIMEOUT_SECONDS=5 \
  "$SCRIPT" 2>&1
)"
forbidden_status=$?
set -e
if [[ "$forbidden_status" -eq 0 ]]; then
  printf 'Expected forbidden 524 scenario to fail.\n' >&2
  printf 'Actual output:\n%s\n' "$forbidden_output" >&2
  exit 1
fi
assert_contains "$forbidden_output" "Forbidden marker found: common forbidden marker"

tool_failed_log_dir="$TMP_DIR/tool-failed-log"
set +e
tool_failed_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_QUEUE_SCENARIO=tool-failed-404 \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_QUEUE_E2E_PCM_PATH="$TMP_DIR/queue-prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_QUEUE_E2E_LOG_DIR="$tool_failed_log_dir" \
  VOICE_AGENT_QUEUE_E2E_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_QUEUE_E2E_COMPLETION_TIMEOUT_SECONDS=5 \
  "$SCRIPT" 2>&1
)"
tool_failed_status=$?
set -e
if [[ "$tool_failed_status" -eq 0 ]]; then
  printf 'Expected tool-failed-404 scenario to fail.\n' >&2
  printf 'Actual output:\n%s\n' "$tool_failed_output" >&2
  exit 1
fi
assert_contains "$tool_failed_output" "Forbidden marker found: common forbidden marker"

extra_created_log_dir="$TMP_DIR/extra-created-log"
set +e
extra_created_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_QUEUE_SCENARIO=extra-created \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_QUEUE_E2E_PCM_PATH="$TMP_DIR/queue-prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_QUEUE_E2E_LOG_DIR="$extra_created_log_dir" \
  VOICE_AGENT_QUEUE_E2E_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_QUEUE_E2E_COMPLETION_TIMEOUT_SECONDS=5 \
  "$SCRIPT" 2>&1
)"
extra_created_status=$?
set -e
if [[ "$extra_created_status" -ne 0 ]]; then
  printf 'Expected extra-created scenario to pass, got %s.\n' "$extra_created_status" >&2
  printf 'Actual output:\n%s\n' "$extra_created_output" >&2
  exit 1
fi
assert_contains "$extra_created_output" "Voice Agent Hermes queue E2E reached manual review gate."

unknown_completed_log_dir="$TMP_DIR/unknown-completed-log"
set +e
unknown_completed_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_QUEUE_SCENARIO=unknown-completed \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_QUEUE_E2E_PCM_PATH="$TMP_DIR/queue-prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_QUEUE_E2E_LOG_DIR="$unknown_completed_log_dir" \
  VOICE_AGENT_QUEUE_E2E_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_QUEUE_E2E_COMPLETION_TIMEOUT_SECONDS=5 \
  "$SCRIPT" 2>&1
)"
unknown_completed_status=$?
set -e
if [[ "$unknown_completed_status" -eq 0 ]]; then
  printf 'Expected unknown-completed scenario to fail.\n' >&2
  printf 'Actual output:\n%s\n' "$unknown_completed_output" >&2
  exit 1
fi
assert_contains "$unknown_completed_output" "Completed Hermes job was not queued: job-x"

cleanup_timeout_log_dir="$TMP_DIR/cleanup-timeout-log"
set +e
cleanup_timeout_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_QUEUE_SCENARIO=cleanup-timeout \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_QUEUE_E2E_PCM_PATH="$TMP_DIR/queue-prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_QUEUE_E2E_LOG_DIR="$cleanup_timeout_log_dir" \
  VOICE_AGENT_QUEUE_E2E_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_QUEUE_E2E_COMPLETION_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_E2E_SERVICE_END_TIMEOUT_SECONDS=1 \
  "$SCRIPT" 2>&1
)"
cleanup_timeout_status=$?
set -e
if [[ "$cleanup_timeout_status" -eq 0 ]]; then
  printf 'Expected cleanup-timeout scenario to fail.\n' >&2
  printf 'Actual output:\n%s\n' "$cleanup_timeout_output" >&2
  exit 1
fi
assert_contains "$cleanup_timeout_output" "Skipping report pull because service drain was not observed."
assert_not_contains "$cleanup_timeout_output" "Voice Agent Hermes queue E2E report:"
if [[ -f "$cleanup_timeout_log_dir/report.txt" ]]; then
  printf 'Expected cleanup-timeout scenario not to write report.txt.\n' >&2
  exit 1
fi

stale_audio_log_dir="$TMP_DIR/stale-audio-log"
set +e
stale_audio_output="$(
  PATH="$TMP_DIR:$PATH" \
  FAKE_QUEUE_SCENARIO=stale-audio \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_QUEUE_E2E_PCM_PATH="$TMP_DIR/queue-prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_QUEUE_E2E_LOG_DIR="$stale_audio_log_dir" \
  VOICE_AGENT_QUEUE_E2E_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_QUEUE_E2E_COMPLETION_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_QUEUE_E2E_OUTPUT_TIMEOUT_SECONDS=2 \
  "$SCRIPT" 2>&1
)"
stale_audio_status=$?
set -e
if [[ "$stale_audio_status" -eq 0 ]]; then
  printf 'Expected stale-audio scenario to fail.\n' >&2
  printf 'Actual output:\n%s\n' "$stale_audio_output" >&2
  exit 1
fi
assert_contains "$stale_audio_output" "Missing marker after 2s: second Gemini output audio received"

supplied_log_dir="$TMP_DIR/supplied-log"
set +e
supplied_output="$(
  PATH="$TMP_DIR:$PATH" \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_QUEUE_E2E_PCM_PATH="$TMP_DIR/queue-prompt.pcm" \
  VOICE_AGENT_QUEUE_E2E_PROMPT_TEXT="Supplied prompt text for report." \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_QUEUE_E2E_LOG_DIR="$supplied_log_dir" \
  VOICE_AGENT_QUEUE_E2E_TOOL_CALL_TIMEOUT_SECONDS=5 \
  VOICE_AGENT_QUEUE_E2E_COMPLETION_TIMEOUT_SECONDS=5 \
  "$SCRIPT" 2>&1
)"
supplied_status=$?
set -e
if [[ "$supplied_status" -ne 0 ]]; then
  printf 'Expected supplied PCM scenario to pass, got %s.\n' "$supplied_status" >&2
  printf 'Actual output:\n%s\n' "$supplied_output" >&2
  exit 1
fi
assert_file_contains_exactly "$supplied_log_dir/generated-prompt.txt" "Turn 1: Supplied prompt text for report."

early_validation_log_dir="$TMP_DIR/early-validation-log"
mkdir -p "$early_validation_log_dir"
printf 'stale diagnostic' > "$early_validation_log_dir/missing-tool-call-diagnostics.txt"
set +e
early_validation_output="$(
  PATH="$TMP_DIR:$PATH" \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_QUEUE_E2E_PCM_PATH="$TMP_DIR/missing-prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_QUEUE_E2E_LOG_DIR="$early_validation_log_dir" \
  "$SCRIPT" 2>&1
)"
early_validation_status=$?
set -e
if [[ "$early_validation_status" -eq 0 ]]; then
  printf 'Expected early validation scenario to fail.\n' >&2
  printf 'Actual output:\n%s\n' "$early_validation_output" >&2
  exit 1
fi
assert_contains "$early_validation_output" "VOICE_AGENT_QUEUE_E2E_PCM_PATH does not exist: $TMP_DIR/missing-prompt.pcm"
assert_file_not_exists "$early_validation_log_dir/missing-tool-call-diagnostics.txt"

invalid_expected_log_dir="$TMP_DIR/invalid-expected-log"
mkdir -p "$invalid_expected_log_dir"
printf 'stale diagnostic' > "$invalid_expected_log_dir/missing-tool-call-diagnostics.txt"
set +e
invalid_expected_output="$(
  PATH="$TMP_DIR:$PATH" \
  VOICE_AGENT_E2E_SERIAL=RZ \
  VOICE_AGENT_E2E_ADB_READY_SCRIPT="$TMP_DIR/adb-ready.sh" \
  VOICE_AGENT_QUEUE_E2E_PCM_PATH="$TMP_DIR/queue-prompt.pcm" \
  VOICE_AGENT_E2E_CONVERSATION_ID=conversation-1 \
  VOICE_AGENT_QUEUE_E2E_EXPECTED_COMPLETIONS=0 \
  VOICE_AGENT_QUEUE_E2E_LOG_DIR="$invalid_expected_log_dir" \
  "$SCRIPT" 2>&1
)"
invalid_expected_status=$?
set -e
if [[ "$invalid_expected_status" -eq 0 ]]; then
  printf 'Expected invalid expected-completions scenario to fail.\n' >&2
  printf 'Actual output:\n%s\n' "$invalid_expected_output" >&2
  exit 1
fi
assert_contains "$invalid_expected_output" "VOICE_AGENT_QUEUE_E2E_EXPECTED_COMPLETIONS must be a positive integer: 0"
assert_file_not_exists "$invalid_expected_log_dir/missing-tool-call-diagnostics.txt"

printf 'Queue E2E shell harness passed.\n'
