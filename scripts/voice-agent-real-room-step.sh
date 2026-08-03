#!/usr/bin/env bash
set -euo pipefail
umask 077
set +x

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
READY="$ROOT_DIR/scripts/adb-device-ready.sh"
ARTIFACT_HELPERS="$ROOT_DIR/scripts/voice-agent-e2e-artifacts.sh"
REAL_ROOM_LIBRARY="$ROOT_DIR/scripts/voice-agent-real-room-lib.sh"
PACKAGE_EXPECTED='me.rerere.rikkahub.debug'
CONTROL_RECEIVER='me.rerere.rikkahub.voiceagent.debug.VoiceAutomationControlReceiver'
FIXTURE_RECEIVER='me.rerere.rikkahub.voiceagent.debug.VoiceCaptureFixtureDebugReceiver'
SERVICE_CLASS='me.rerere.rikkahub.voiceagent.VoiceAgentCallService'
CONTROL_ACTION_PREFIX='me.rerere.rikkahub.voiceagent.automation'
FIXTURE_ARM_ACTION='me.rerere.rikkahub.debug.voiceagent.ARM_CAPTURE_FIXTURE'
FIXTURE_STAGE_ACTION='me.rerere.rikkahub.debug.voiceagent.STAGE_CAPTURE_FIXTURE'
FIXTURE_TRIGGER_ACTION='me.rerere.rikkahub.debug.voiceagent.TRIGGER_CAPTURE_FIXTURE'
CALL_START_ACTION='me.rerere.rikkahub.voiceagent.action.START'
CALL_END_BOUND_ACTION='me.rerere.rikkahub.voiceagent.action.END_BOUND'
APP_ARTIFACT_ROOT='no_backup/voice-e2e'
LATEST_TRACE_PATH="$APP_ARTIFACT_ROOT/latest-trace-id.txt"
TRANSPORT_EXPECTED='livekit_experimental'
FIXTURE_CHUNK_BYTES='3200'
FIXTURE_CHUNK_DELAY_MS='100'

# Only the path joiner is consumed. This preserves the existing artifact helper
# contract without changing its behavior.
source "$ARTIFACT_HELPERS"
source "$REAL_ROOM_LIBRARY"

SERIAL=''
PACKAGE=''
ANDROID_USER_ID=''
PACKAGE_UID=''
RUN_HASH=''
COMPARISON_HASH=''
CONVERSATION_ID=''
FIXTURE_TOKEN=''
TRACE_ID=''
FIXTURE_PARENT_IDENTITY=''
FIXTURE_DIRECTORY_IDENTITY=''
FIXTURE_OWNERSHIP_NONCE=''
REMOTE_FIXTURE_DIR=''
REMOTE_OWNER_HASH=''
LOCAL_TEMP_DIR=''
STATE_PUBLICATION_TEMP=''
ERROR_REPORTED=0
START_CLEANUP_NEEDED=0
START_PREPARE_ATTEMPTED=0
START_CALL_ATTEMPTED=0
START_FIXTURE_DIR_CREATED=0
START_COMMITTED=0
STATE_PUBLICATION_CRITICAL=0
PUBLICATION_SIGNAL=0
HOST_LOCK_FD=''
HOST_LOCK_ROOT_FD=''
PACKAGE_FORCE_STOP_OWNED=0
EXIT_CLEANUP_SIGNAL=0
declare -a OWNED_TEMP_FILES=()
declare -A PARSED=()

raw_start_cleanup() {
  local cleanup_status=0
  local stopped_status
  local quiescence_status
  local broker_status
  if (( START_CALL_ATTEMPTED == 1 )); then
    adb_read shell am start-foreground-service \
      --user "$ANDROID_USER_ID" \
      -n "$PACKAGE/$SERVICE_CLASS" \
      -a "$CALL_END_BOUND_ACTION" \
      --es conversationId "$CONVERSATION_ID" \
      --es transport "$TRANSPORT_EXPECTED" \
      --es run_hash "$RUN_HASH" \
      --es comparison_hash "$COMPARISON_HASH" \
      </dev/null >/dev/null 2>&1 || cleanup_status=1
    START_CALL_ATTEMPTED=0
  fi
  if (( START_PREPARE_ATTEMPTED == 1 )); then
    adb_read shell am broadcast --user "$ANDROID_USER_ID" \
      -n "$PACKAGE/$CONTROL_RECEIVER" \
      -a "$CONTROL_ACTION_PREFIX.FINALIZE_BOUND" \
      --es run_hash "$RUN_HASH" \
      --es comparison_hash "$COMPARISON_HASH" \
      --es transport "$TRANSPORT_EXPECTED" \
      </dev/null >/dev/null 2>&1 || cleanup_status=1
    START_PREPARE_ATTEMPTED=0
  fi
  if (( START_FIXTURE_DIR_CREATED == 1 )); then
    PACKAGE_FORCE_STOP_OWNED=1
    adb_read shell cmd activity force-stop --user "$ANDROID_USER_ID" "$PACKAGE" \
      </dev/null >/dev/null 2>&1 || cleanup_status=1
    if read_package_stopped_state true; then
      stopped_status=0
    else
      stopped_status=$?
    fi
    if (( stopped_status == 0 )); then
      if prove_package_quiescence; then
        quiescence_status=0
      else
        quiescence_status=$?
      fi
      if (( quiescence_status == 0 )); then
        if remove_owned_remote_directory_quiescent "$REMOTE_FIXTURE_DIR"; then
          broker_status=0
        else
          broker_status=$?
        fi
        if (( broker_status == 0 )); then
          START_FIXTURE_DIR_CREATED=0
        else
          cleanup_status=1
        fi
      else
        cleanup_status=1
      fi
    else
      cleanup_status=1
    fi
    restore_force_stopped_package || cleanup_status=1
  fi
  return "$cleanup_status"
}

on_exit() {
  local status=$?
  local cleanup_status=0
  trap - EXIT
  trap defer_exit_cleanup_signal HUP INT TERM
  set +e
  if (( status != 0 && START_CLEANUP_NEEDED == 1 )); then
    raw_start_cleanup || cleanup_status=1
  fi
  if (( PACKAGE_FORCE_STOP_OWNED == 1 )); then
    restore_force_stopped_package || cleanup_status=1
  fi
  cleanup_local_temps || cleanup_status=1
  if (( EXIT_CLEANUP_SIGNAL == 1 && status == 0 )); then
    status=1
  fi
  if (( status == 0 && cleanup_status != 0 )); then
    status=1
    if (( ERROR_REPORTED == 0 )); then
      printf 'voice-step.error=cleanup failed\n' >&2
    fi
  elif (( status != 0 && ERROR_REPORTED == 0 )); then
    printf 'voice-step.error=operation failed\n' >&2
  fi
  exit "$status"
}

defer_exit_cleanup_signal() {
  EXIT_CLEANUP_SIGNAL=1
}

on_signal() {
  if (( STATE_PUBLICATION_CRITICAL == 1 )); then
    PUBLICATION_SIGNAL=1
    return
  fi
  if (( START_COMMITTED == 1 )); then
    START_CLEANUP_NEEDED=0
  fi
  die 'interrupted'
}

trap on_exit EXIT
trap on_signal HUP INT TERM

read_status_artifacts() {
  local automation_path
  local private_path
  local sanitized_path
  local presence
  local sanitized_temp
  local counts_temp
  local -a counts=()
  automation_path="$(app_artifact_path "$APP_ARTIFACT_ROOT/${RUN_HASH#sha256:}" automation-events.jsonl)"
  private_path="$(app_artifact_path "$APP_ARTIFACT_ROOT/$TRACE_ID" voice-experience-private.ndjson)"
  sanitized_path="$(app_artifact_path "$APP_ARTIFACT_ROOT/$TRACE_ID" voice-experience-events.ndjson)"
  presence="$(adb_read shell run-as "$PACKAGE" --user "$ANDROID_USER_ID" sh -c '
: voice-step-artifact-presence
for path do
  [ -f "$path" ] && [ ! -L "$path" ] && [ -s "$path" ] || exit 1
  printf "present\n"
done
' sh "$automation_path" "$private_path" "$sanitized_path" 2>/dev/null)" ||
    die 'required artifact unavailable'
  [[ "$presence" == $'present\npresent\npresent' ]] || die 'required artifact unavailable'
  ensure_local_temp_dir
  sanitized_temp="$LOCAL_TEMP_DIR/status-sanitized.ndjson"
  counts_temp="$LOCAL_TEMP_DIR/status-counts"
  : > "$sanitized_temp"
  chmod 600 "$sanitized_temp"
  register_temp_file "$sanitized_temp"
  register_temp_file "$counts_temp"
  adb_read exec-out run-as "$PACKAGE" --user "$ANDROID_USER_ID" cat "$sanitized_path" \
    >"$sanitized_temp" 2>/dev/null || die 'sanitized artifact read failed'
  if ! python3 - "$sanitized_temp" >"$counts_temp" 2>/dev/null <<'PY'
import json
import re
import sys
from datetime import datetime, timezone

IDENTIFIER = re.compile(r"^[A-Za-z0-9_-]{1,128}$")
HASH = re.compile(r"^sha256:[0-9a-f]{64}$")
BASE = ["version", "voiceSessionHash", "eventId", "kind", "observedAt", "eventHash"]
JOB = [
    "userTurnId", "requestHash", "toolCallId", "argumentHash", "jobId",
    "ownerHash", "conversationHash", "roomHash", "traceHash",
]
IDENTIFIERS = {
    "eventId", "userTurnId", "toolCallId", "jobId", "turnId",
    "groundedJobId", "assistantTurnId", "followUpTurnId",
}
HASHES = {
    "voiceSessionHash", "eventHash", "requestHash", "argumentHash", "ownerHash",
    "conversationHash", "roomHash", "traceHash", "resultHash", "groundedResultHash",
}
COUNTS = {
    "promptCharacterCount", "answerCharacterCount", "failureReasonCharacterCount",
    "textCharacterCount",
}
BOOLEANS = {"interrupted", "userSpeaking", "agentSpeaking"}
SCHEMAS = {
    "job_accepted": BASE + JOB + ["promptCharacterCount"],
    "job_running": BASE + JOB,
    "still_working": BASE + JOB,
    "job_succeeded": BASE + JOB + ["resultHash", "answerCharacterCount"],
    "job_failed": BASE + JOB + ["failureReasonCharacterCount"],
    "job_expired": BASE + JOB + ["failureReasonCharacterCount"],
    "job_canceled": BASE + JOB + ["failureReasonCharacterCount"],
    "delivery_eligible": BASE + ["toolCallId", "jobId"],
    "delivery_started": BASE + ["toolCallId", "jobId"],
    "speech_started": BASE + ["toolCallId", "jobId"],
    "delivery_blocked": BASE + ["toolCallId", "jobId", "userSpeaking", "agentSpeaking"],
    "delivery_announced": BASE + ["toolCallId", "jobId", "assistantTurnId"],
    "follow_up_correlation": BASE + ["followUpTurnId", "assistantTurnId", "resultHash"],
}


def timestamp(value):
    if type(value) is not str or value.startswith("0000-") or not value.endswith("Z"):
        return False
    try:
        parsed = datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError:
        return False
    if parsed.tzinfo is None:
        return False
    rendered = parsed.astimezone(timezone.utc).isoformat(timespec=("microseconds" if parsed.microsecond else "seconds"))
    return rendered.replace("+00:00", "Z") == value


def parse_pairs(line):
    pairs = json.loads(line, object_pairs_hook=lambda value: value)
    if type(pairs) is not list or any(type(pair) is not tuple for pair in pairs):
        raise ValueError()
    keys = [key for key, _ in pairs]
    if len(keys) != len(set(keys)):
        raise ValueError()
    return keys, dict(pairs)


with open(sys.argv[1], "rb") as handle:
    content = handle.read()
if not content or len(content) > 16 * 1024 * 1024 or not content.endswith(b"\n"):
    raise SystemExit(1)
try:
    text = content.decode("utf-8")
except UnicodeDecodeError:
    raise SystemExit(1)
rows = []
for line in text.splitlines():
    if not line:
        raise SystemExit(1)
    try:
        keys, row = parse_pairs(line)
    except (TypeError, ValueError, json.JSONDecodeError):
        raise SystemExit(1)
    kind = row.get("kind")
    expected = SCHEMAS.get(kind)
    if kind == "transcript":
        expected = BASE + ["turnId", "role", "interrupted", "textCharacterCount"]
        grounding = ["groundedJobId", "groundedResultHash"]
        if keys == expected + grounding:
            expected += grounding
    if expected is None or keys != expected or "voiceSessionId" in row:
        raise SystemExit(1)
    if json.dumps(row, separators=(",", ":"), ensure_ascii=False) != line:
        raise SystemExit(1)
    if type(row["version"]) is not int or row["version"] != 1 or not timestamp(row["observedAt"]):
        raise SystemExit(1)
    for field in set(row) & IDENTIFIERS:
        if type(row[field]) is not str or IDENTIFIER.fullmatch(row[field]) is None:
            raise SystemExit(1)
    for field in set(row) & HASHES:
        if type(row[field]) is not str or HASH.fullmatch(row[field]) is None:
            raise SystemExit(1)
    for field in set(row) & COUNTS:
        if type(row[field]) is not int or row[field] < 0:
            raise SystemExit(1)
    for field in set(row) & BOOLEANS:
        if type(row[field]) is not bool:
            raise SystemExit(1)
    if kind == "transcript":
        if row["role"] not in {"user", "assistant"}:
            raise SystemExit(1)
        grounded = "groundedJobId" in row and "groundedResultHash" in row
        if (row["role"] == "user" and grounded) or (("groundedJobId" in row) != ("groundedResultHash" in row)):
            raise SystemExit(1)
    rows.append(row)
if not rows:
    raise SystemExit(1)
terminal = {"job_succeeded", "job_failed", "job_expired", "job_canceled"}
print(sum(row["kind"] == "job_accepted" for row in rows))
print(sum(row["kind"] in terminal for row in rows))
print(sum(row["kind"] == "delivery_blocked" for row in rows))
print(sum(row["kind"] == "delivery_announced" for row in rows))
PY
  then
    die 'invalid sanitized artifact'
  fi
  mapfile -t counts < "$counts_temp"
  [[ "${#counts[@]}" == 4 ]] || die 'invalid sanitized artifact'
  local count
  for count in "${counts[@]}"; do
    [[ "$count" =~ ^[0-9]+$ ]] || die 'invalid sanitized artifact'
  done
  STATUS_JOB_ACCEPTED_COUNT="${counts[0]}"
  STATUS_JOB_TERMINAL_COUNT="${counts[1]}"
  STATUS_DELIVERY_BLOCKED_COUNT="${counts[2]}"
  STATUS_DELIVERY_ANNOUNCED_COUNT="${counts[3]}"
}

run_inject() {
  local fixture_path="$1"
  local role="$2"
  local fixture_snapshot
  local fixture_size
  local fixture_hash
  validate_runtime
  snapshot_fixture "$fixture_path" fixture_snapshot fixture_size fixture_hash
  inject_fixture_once "$fixture_snapshot" "$fixture_size" "$fixture_hash" "$role"
  cleanup_local_temps || die 'cleanup failed'
  printf '%s\n' \
    'voice-step.status=ok' \
    'voice-step.operation=inject' \
    'voice-step.fixture=accepted'
}

run_interrupt() {
  local fixture_path="$1"
  local fixture_snapshot
  local fixture_size
  local fixture_hash
  local reply
  validate_runtime
  snapshot_fixture "$fixture_path" fixture_snapshot fixture_size fixture_hash
  reply="$(broadcast_read "$CONTROL_RECEIVER" "$CONTROL_ACTION_PREFIX.MARK" \
    --es boundary interrupt_started \
    --es run_hash "$RUN_HASH")"
  [[ "$reply" == $'status=ok\naction=mark\nboundary=interrupt_started' ]] ||
    die 'unexpected receiver response'
  inject_fixture_once "$fixture_snapshot" "$fixture_size" "$fixture_hash" interruption
  cleanup_local_temps || die 'cleanup failed'
  printf '%s\n' \
    'voice-step.status=ok' \
    'voice-step.operation=interrupt' \
    'voice-step.fixture=accepted'
}

run_status_operation() {
  local status_snapshot
  local -a status=()
  validate_runtime
  status_snapshot="$(read_status)"
  mapfile -t status <<< "$status_snapshot"
  [[ "${status[0]}" == active && "${status[1]}" == "$RUN_HASH" &&
     "${status[2]}" == "$COMPARISON_HASH" &&
     "${status[3]}" == "$TRANSPORT_EXPECTED" ]] || die 'status binding mismatch'
  read_call_service_active
  read_status_artifacts
  cleanup_local_temps || die 'cleanup failed'
  printf '%s\n' \
    'voice-step.status=ok' \
    'voice-step.operation=status' \
    "voice-step.run_state=${status[0]}" \
    'voice-step.call_state=active' \
    "voice-step.event_count=${status[4]}" \
    "voice-step.network=${status[5]}" \
    "voice-step.validated=${status[6]}" \
    'voice-step.voice_events=present' \
    "voice-step.job_accepted_count=$STATUS_JOB_ACCEPTED_COUNT" \
    "voice-step.job_terminal_count=$STATUS_JOB_TERMINAL_COUNT" \
    "voice-step.delivery_blocked_count=$STATUS_DELIVERY_BLOCKED_COUNT" \
    "voice-step.delivery_announced_count=$STATUS_DELIVERY_ANNOUNCED_COUNT"
}

run_finalize() {
  local reply
  local status_snapshot
  local -a status=()
  validate_runtime
  adb_read shell am start-foreground-service \
    --user "$ANDROID_USER_ID" \
    -n "$PACKAGE/$SERVICE_CLASS" \
    -a "$CALL_END_BOUND_ACTION" \
    --es conversationId "$CONVERSATION_ID" \
    --es transport "$TRANSPORT_EXPECTED" \
    --es run_hash "$RUN_HASH" \
    --es comparison_hash "$COMPARISON_HASH" \
    </dev/null >/dev/null 2>&1 || die 'bound call end failed'
  wait_for_durable_call_stopped
  reply="$(broadcast_read "$CONTROL_RECEIVER" "$CONTROL_ACTION_PREFIX.FINALIZE_BOUND" \
    --es run_hash "$RUN_HASH" \
    --es comparison_hash "$COMPARISON_HASH" \
    --es transport "$TRANSPORT_EXPECTED")"
  [[ "$reply" == $'status=ok\naction=finalize_bound' ]] || die 'unexpected receiver response'
  status_snapshot="$(read_status)"
  mapfile -t status <<< "$status_snapshot"
  [[ "${status[0]}" == finalized && "${status[1]}" == "$RUN_HASH" &&
     "${status[2]}" == "$COMPARISON_HASH" &&
     "${status[3]}" == "$TRANSPORT_EXPECTED" ]] || die 'finalized status mismatch'
  read_automation_terminal_snapshot finalized || die 'invalid durable final evidence'
  cleanup_local_temps || die 'cleanup failed'
  printf '%s\n' \
    'voice-step.status=ok' \
    'voice-step.operation=finalize' \
    'voice-step.automation=finalized'
}

run_capture() {
  local automation_output="$1"
  local private_output="$2"
  local sanitized_output="$3"
  local automation_source
  local private_source
  local sanitized_source
  local automation_temp
  local private_temp
  local sanitized_temp
  local status_before
  local status_after
  local -a status=()
  validate_runtime
  status_before="$(read_status)"
  mapfile -t status <<< "$status_before"
  [[ "${status[0]}" == finalized && "${status[1]}" == "$RUN_HASH" &&
     "${status[2]}" == "$COMPARISON_HASH" &&
     "${status[3]}" == "$TRANSPORT_EXPECTED" ]] || die 'finalized status mismatch'
  automation_source="$(app_artifact_path "$APP_ARTIFACT_ROOT/${RUN_HASH#sha256:}" automation-events.jsonl)"
  private_source="$(app_artifact_path "$APP_ARTIFACT_ROOT/$TRACE_ID" voice-experience-private.ndjson)"
  sanitized_source="$(app_artifact_path "$APP_ARTIFACT_ROOT/$TRACE_ID" voice-experience-events.ndjson)"
  read_stable_artifact "$automation_source" "$automation_output" automation_temp
  read_stable_artifact "$private_source" "$private_output" private_temp
  read_stable_artifact "$sanitized_source" "$sanitized_output" sanitized_temp
  status_after="$(read_status)"
  [[ "$status_after" == "$status_before" ]] || die 'status changed during capture'
  validate_absent_destination "$automation_output" || die 'output destination appeared'
  validate_absent_destination "$private_output" || die 'output destination appeared'
  validate_absent_destination "$sanitized_output" || die 'output destination appeared'
  publish_owned_temp "$automation_temp" "$automation_output"
  publish_owned_temp "$private_temp" "$private_output"
  publish_owned_temp "$sanitized_temp" "$sanitized_output"
  cleanup_local_temps || die 'cleanup failed'
  printf '%s\n' \
    'voice-step.status=ok' \
    'voice-step.operation=capture' \
    'voice-step.artifacts=published'
}

classify_device_access() {
  local enumeration
  local classification
  if ! enumeration="$(adb_global_read devices -l 2>/dev/null)"; then
    return 2
  fi
  python3 -c '
import re
import sys

serial = sys.argv[1]
lines = sys.stdin.read().replace("\r", "").splitlines()
if not lines or lines[0] != "List of devices attached":
    raise SystemExit(2)
rows = []
for line in lines[1:]:
    if not line:
        continue
    fields = line.split()
    if (
        len(fields) < 2
        or re.fullmatch(r"\S+", fields[0]) is None
        or re.fullmatch(r"[a-z_]+", fields[1]) is None
        or any(":" not in field for field in fields[2:])
    ):
        raise SystemExit(2)
    rows.append((fields[0], fields[1]))
selected = [state for candidate, state in rows if candidate == serial]
if len(selected) > 1:
    raise SystemExit(2)
if not selected:
    raise SystemExit(1)
if selected[0] == "device":
    raise SystemExit(0)
if selected[0] in {"offline", "unauthorized"}:
    raise SystemExit(1)
raise SystemExit(2)
' "$SERIAL" <<<"$enumeration" 2>/dev/null || {
    classification=$?
    return "$classification"
  }
  return 0
}

complete_failed_end_step() {
  local destination="$1"
  local call_stopped="$2"
  local fixtures_removed="$3"
  local automation_finalized="$4"
  local device_access
  if classify_device_access; then
    device_access=0
  else
    device_access=$?
  fi
  case "$device_access" in
    0)
      if (( PACKAGE_FORCE_STOP_OWNED == 1 )); then
        restore_force_stopped_package || die 'package restoration failed'
      fi
      complete_end_outcome "$destination" product_failure "$call_stopped" \
        "$fixtures_removed" "$automation_finalized"
      ;;
    1)
      complete_end_outcome "$destination" infrastructure_interruption \
        "$call_stopped" "$fixtures_removed" "$automation_finalized"
      ;;
    *) die 'ambiguous cleanup readback' ;;
  esac
}

publish_end_record() {
  local destination="$1"
  local outcome="$2"
  local call_stopped="$3"
  local fixtures_removed="$4"
  local automation_finalized="$5"
  local parent
  local temporary
  parent="$(dirname -- "$destination")" || die 'cleanup record publication failed'
  temporary="$(mktemp "$parent/.voice-step-cleanup.XXXXXX" 2>/dev/null)" ||
    die 'cleanup record publication failed'
  register_temp_file "$temporary"
  chmod 600 -- "$temporary" 2>/dev/null || die 'cleanup record publication failed'
  printf '{"schemaVersion":1,"outcome":"%s","callStopped":%s,"fixturesRemoved":%s,"automationFinalized":%s}\n' \
    "$outcome" "$call_stopped" "$fixtures_removed" "$automation_finalized" > "$temporary" ||
    die 'cleanup record publication failed'
  publish_owned_temp "$temporary" "$destination"
}

complete_end_outcome() {
  local destination="$1"
  local outcome="$2"
  local call_stopped="$3"
  local fixtures_removed="$4"
  local automation_finalized="$5"
  publish_end_record "$destination" "$outcome" "$call_stopped" "$fixtures_removed" "$automation_finalized"
  cleanup_local_temps || die 'cleanup failed'
  printf '%s\n' \
    'voice-step.status=ok' \
    'voice-step.operation=end' \
    "voice-step.outcome=$outcome"
}

run_end() {
  local cleanup_output="$1"
  local remote_fixture_dir
  local automation_source
  local baseline_temp
  local post_cleanup_temp
  local metadata_before
  local metadata_after_baseline
  local metadata_after_cleanup
  local metadata_after_post_read
  local stopped_status
  local quiescence_status
  local cleanup_status
  validate_runtime
  remote_fixture_dir="files/voice-real-room/${RUN_HASH#sha256:}"
  automation_source="$(app_artifact_path "$APP_ARTIFACT_ROOT/${RUN_HASH#sha256:}" automation-events.jsonl)"

  read_automation_terminal_snapshot finalized || die 'invalid durable final evidence'
  metadata_before="$(read_source_metadata "$automation_source")"
  read_stable_artifact "$automation_source" "$cleanup_output" baseline_temp
  metadata_after_baseline="$(read_source_metadata "$automation_source")"
  [[ "$metadata_before" == "$metadata_after_baseline" ]] || die 'artifact source changed'

  PACKAGE_FORCE_STOP_OWNED=1
  if ! adb_read shell cmd activity force-stop --user "$ANDROID_USER_ID" "$PACKAGE" \
      </dev/null >/dev/null 2>&1; then
    complete_failed_end_step "$cleanup_output" true false true
    return
  fi
  if read_package_stopped_state true; then
    stopped_status=0
  else
    stopped_status=$?
  fi
  case "$stopped_status" in
    0) ;;
    1)
      complete_failed_end_step "$cleanup_output" true false true
      return
      ;;
    *) die 'ambiguous package stopped-state readback' ;;
  esac
  if prove_package_quiescence; then
    quiescence_status=0
  else
    quiescence_status=$?
  fi
  case "$quiescence_status" in
    0) ;;
    1) complete_failed_end_step "$cleanup_output" true false true; return ;;
    2)
      restore_force_stopped_package || die 'package restoration failed'
      complete_end_outcome "$cleanup_output" product_failure true false true
      return
      ;;
    *) die 'ambiguous package quiescence readback' ;;
  esac

  if remove_owned_remote_directory_quiescent "$remote_fixture_dir"; then
    cleanup_status=0
  else
    cleanup_status=$?
  fi
  case "$cleanup_status" in
    0) ;;
    1)
      complete_failed_end_step "$cleanup_output" true false true
      return
      ;;
    2) die 'ambiguous fixture cleanup readback' ;;
    *) die 'ambiguous fixture cleanup readback' ;;
  esac

  metadata_after_cleanup="$(read_source_metadata "$automation_source")"
  read_stable_artifact "$automation_source" "$cleanup_output" post_cleanup_temp
  metadata_after_post_read="$(read_source_metadata "$automation_source")"
  [[ "$metadata_before" == "$metadata_after_cleanup" &&
     "$metadata_before" == "$metadata_after_post_read" ]] || die 'artifact source changed'
  cmp -s -- "$baseline_temp" "$post_cleanup_temp" || die 'artifact source changed'
  read_automation_terminal_snapshot finalized || die 'invalid durable final evidence'
  read_package_stopped_state true || die 'ambiguous package stopped-state readback'

  restore_force_stopped_package || die 'package restoration failed'
  complete_end_outcome "$cleanup_output" complete true true true
}

wait_for_call_active() {
  local events_path
  local events_file
  local attempt=0
  local started=$SECONDS
  local check_status
  events_path="$(app_artifact_path "$APP_ARTIFACT_ROOT/${RUN_HASH#sha256:}" automation-events.jsonl)"
  ensure_local_temp_dir
  events_file="$LOCAL_TEMP_DIR/automation.wait"
  : > "$events_file"
  chmod 600 "$events_file"
  register_temp_file "$events_file"
  while (( attempt < ${VOICE_STEP_MAX_WAIT_ATTEMPTS:-120} )); do
    attempt=$((attempt + 1))
    : > "$events_file"
    if adb_read exec-out run-as "$PACKAGE" --user "$ANDROID_USER_ID" cat "$events_path" \
      >"$events_file" 2>/dev/null; then
      set +e
      python3 - "$events_file" "$RUN_HASH" "$COMPARISON_HASH" "$TRANSPORT_EXPECTED" 2>/dev/null <<'PY'
import json
import sys

path, run_hash, comparison_hash, transport = sys.argv[1:]
try:
    with open(path, encoding="utf-8") as handle:
        rows = [json.loads(line) for line in handle if line.strip()]
except (OSError, ValueError):
    raise SystemExit(2)
if not rows or any(type(row) is not dict for row in rows):
    raise SystemExit(2)
for row in rows:
    if (
        row.get("runHash") != run_hash
        or row.get("comparisonHash") != comparison_hash
        or row.get("requestedTransport") != transport
    ):
        raise SystemExit(2)
matching = [row for row in rows if row.get("name") == "call_active"]
if any(row.get("observedTransport") != transport for row in matching):
    raise SystemExit(2)
raise SystemExit(0 if matching else 1)
PY
      check_status=$?
      set -e
      case "$check_status" in
        0) return 0 ;;
        1) ;;
        *) die 'ambiguous call readback' ;;
      esac
    fi
    if (( SECONDS - started >= ${VOICE_STEP_WAIT_TIMEOUT_SECONDS:-120} )); then
      break
    fi
    sleep "${VOICE_STEP_POLL_SECONDS:-1}"
  done
  die 'call activation timed out'
}

wait_for_new_trace() {
  local old_present="$1"
  local old_value="$2"
  local attempt=0
  local started=$SECONDS
  while (( attempt < ${VOICE_STEP_MAX_WAIT_ATTEMPTS:-120} )); do
    attempt=$((attempt + 1))
    read_trace_pointer
    if (( TRACE_POINTER_PRESENT == 1 )) &&
      { (( old_present == 0 )) || [[ "$TRACE_POINTER_VALUE" != "$old_value" ]]; }; then
      TRACE_ID="$TRACE_POINTER_VALUE"
      return 0
    fi
    if (( SECONDS - started >= ${VOICE_STEP_WAIT_TIMEOUT_SECONDS:-120} )); then
      break
    fi
    sleep "${VOICE_STEP_POLL_SECONDS:-1}"
  done
  die 'trace activation timed out'
}

run_preflight() {
  local status_snapshot
  local -a status=()
  validate_runtime
  ensure_device_and_package
  resolve_package_identity
  verify_package_contract
  status_snapshot="$(read_status)"
  mapfile -t status <<< "$status_snapshot"
  [[ "${status[0]}" == idle || "${status[0]}" == finalized ]] ||
    die 'automation is not ready'
  printf '%s\n' \
    'voice-step.status=ok' \
    'voice-step.operation=preflight' \
    'voice-step.device=ready' \
    'voice-step.package=ready' \
    'voice-step.automation=ready' \
    'voice-step.protected_path=ready'
}

run_start() {
  local state_path="$1"
  local fixture_path="$2"
  local old_trace_present
  local old_trace_value
  local reply
  local status_snapshot
  local -a status=()
  local fixture_snapshot
  local fixture_size
  local fixture_hash
  local remote_fixture_path
  validate_runtime
  acquire_host_operation_lock
  snapshot_fixture "$fixture_path" fixture_snapshot fixture_size fixture_hash
  REMOTE_FIXTURE_DIR="files/voice-real-room/${RUN_HASH#sha256:}"
  remote_fixture_path="$REMOTE_FIXTURE_DIR/request-${fixture_hash#sha256:}.pcm"
  ensure_device_and_package
  resolve_package_identity
  verify_package_contract
  status_snapshot="$(read_status)"
  mapfile -t status <<< "$status_snapshot"
  [[ "${status[0]}" == idle || "${status[0]}" == finalized ]] ||
    die 'automation is not ready'
  read_trace_pointer
  old_trace_present="$TRACE_POINTER_PRESENT"
  old_trace_value="$TRACE_POINTER_VALUE"

  START_CLEANUP_NEEDED=1
  stage_snapshot "$REMOTE_FIXTURE_DIR" "$remote_fixture_path" \
    "$fixture_snapshot" "$fixture_size" "$fixture_hash"
  START_PREPARE_ATTEMPTED=1
  reply="$(broadcast_read "$CONTROL_RECEIVER" "$CONTROL_ACTION_PREFIX.PREPARE" \
    --es run_hash "$RUN_HASH" \
    --es comparison_hash "$COMPARISON_HASH" \
    --es transport "$TRANSPORT_EXPECTED" \
    --es lifecycle foreground)"
  [[ "$reply" == $'status=ok\naction=prepare' ]] || die 'unexpected receiver response'

  reply="$(broadcast_read "$FIXTURE_RECEIVER" "$FIXTURE_ARM_ACTION" \
    --es initial_path "$remote_fixture_path" \
    --el expected_size "$fixture_size" \
    --es expected_sha256 "$fixture_hash" \
    --ei chunk_bytes "$FIXTURE_CHUNK_BYTES" \
    --el chunk_delay_ms "$FIXTURE_CHUNK_DELAY_MS")"
  if [[ "$reply" =~ ^status=ok$'\n'action=arm$'\n'token=(fixture-[1-9][0-9]*)$ ]]; then
    FIXTURE_TOKEN="${BASH_REMATCH[1]}"
  else
    die 'unexpected receiver response'
  fi
  validate_identifier "$FIXTURE_TOKEN" 'fixture token'

  START_CALL_ATTEMPTED=1
  adb_read shell am start-foreground-service \
    --user "$ANDROID_USER_ID" \
    -n "$PACKAGE/$SERVICE_CLASS" \
    -a "$CALL_START_ACTION" \
    --es conversationId "$CONVERSATION_ID" \
    --es transport "$TRANSPORT_EXPECTED" \
    --es captureFixtureToken "$FIXTURE_TOKEN" \
    --es run_hash "$RUN_HASH" \
    --es comparison_hash "$COMPARISON_HASH" \
    </dev/null >/dev/null 2>&1 || die 'call start failed'
  wait_for_call_active
  wait_for_new_trace "$old_trace_present" "$old_trace_value"
  publish_state "$state_path"
  START_CLEANUP_NEEDED=0
  START_CALL_ATTEMPTED=0
  START_PREPARE_ATTEMPTED=0
  START_FIXTURE_DIR_CREATED=0
  cleanup_local_temps || die 'cleanup failed'
  printf '%s\n' \
    'voice-step.status=ok' \
    'voice-step.operation=start' \
    'voice-step.call=active'
}

run_with_decoded_state() {
  local requested_operation="$1"
  local state_path="$2"
  shift 2
  local state_snapshot
  local -a state=()
  state_snapshot="$(decode_state "$state_path")"
  mapfile -t state <<< "$state_snapshot"
  [[ "${#state[@]}" == 12 ]] || die 'invalid state'
  local SERIAL="${state[0]}"
  local PACKAGE="${state[1]}"
  local ANDROID_USER_ID="${state[2]}"
  local PACKAGE_UID="${state[3]}"
  local CONVERSATION_ID="${state[4]}"
  local RUN_HASH="${state[5]}"
  local COMPARISON_HASH="${state[6]}"
  local FIXTURE_TOKEN="${state[7]}"
  local FIXTURE_PARENT_IDENTITY="${state[8]}"
  local FIXTURE_DIRECTORY_IDENTITY="${state[9]}"
  local FIXTURE_OWNERSHIP_NONCE="${state[10]}"
  local TRACE_ID="${state[11]}"
  case "$requested_operation" in
    inject|interrupt|status|finalize|capture|end)
      validate_runtime
      acquire_host_operation_lock
      ;;
  esac
  case "$requested_operation" in
    inject) run_inject "$@" ;;
    interrupt) run_interrupt "$@" ;;
    status) run_status_operation ;;
    finalize) run_finalize ;;
    capture) run_capture "$@" ;;
    end) run_end "$@" ;;
    *) die 'invalid operation' ;;
  esac
}

operation="${1:-}"
[[ -n "$operation" ]] || die 'usage: voice-agent-real-room-step.sh OPERATION [options]'
shift

case "$operation" in
  preflight)
    parse_options '--serial --package' "$@"
    require_options --serial --package
    SERIAL="${PARSED[--serial]}"
    PACKAGE="${PARSED[--package]}"
    validate_identifier "$SERIAL" 'serial'
    validate_package "$PACKAGE"
    run_preflight
    ;;
  start)
    parse_options '--state --serial --package --conversation-id --run-hash --comparison-hash --fixture' "$@"
    require_options --state --serial --package --conversation-id --run-hash --comparison-hash --fixture
    SERIAL="${PARSED[--serial]}"
    PACKAGE="${PARSED[--package]}"
    CONVERSATION_ID="${PARSED[--conversation-id]}"
    RUN_HASH="${PARSED[--run-hash]}"
    COMPARISON_HASH="${PARSED[--comparison-hash]}"
    validate_identifier "$SERIAL" 'serial'
    validate_package "$PACKAGE"
    validate_identifier "$CONVERSATION_ID" 'conversation id'
    validate_hash "$RUN_HASH" 'run hash'
    validate_hash "$COMPARISON_HASH" 'comparison hash'
    validate_absent_destination "${PARSED[--state]}" || die 'invalid state destination'
    run_start "${PARSED[--state]}" "${PARSED[--fixture]}"
    ;;
  inject)
    parse_options '--state --fixture --role' "$@"
    if [[ -n "${PARSED[--role]+present}" ]]; then
      case "${PARSED[--role]}" in
        request|follow_up|interruption) ;;
        *) die 'invalid fixture role' ;;
      esac
    fi
    require_options --state --fixture --role
    run_with_decoded_state inject "${PARSED[--state]}" \
      "${PARSED[--fixture]}" "${PARSED[--role]}"
    ;;
  interrupt)
    parse_options '--state --fixture' "$@"
    require_options --state --fixture
    run_with_decoded_state interrupt "${PARSED[--state]}" "${PARSED[--fixture]}"
    ;;
  status)
    parse_options '--state' "$@"
    require_options --state
    run_with_decoded_state status "${PARSED[--state]}"
    ;;
  finalize)
    parse_options '--state' "$@"
    require_options --state
    run_with_decoded_state finalize "${PARSED[--state]}"
    ;;
  capture)
    parse_options '--state --automation-output --private-voice-output --sanitized-voice-output' "$@"
    require_options --state --automation-output --private-voice-output --sanitized-voice-output
    validate_absent_destination "${PARSED[--automation-output]}" || die 'invalid output destination'
    validate_absent_destination "${PARSED[--private-voice-output]}" || die 'invalid output destination'
    validate_absent_destination "${PARSED[--sanitized-voice-output]}" || die 'invalid output destination'
    validate_distinct_destinations \
      "${PARSED[--automation-output]}" \
      "${PARSED[--private-voice-output]}" \
      "${PARSED[--sanitized-voice-output]}" || die 'output destinations must be distinct'
    run_with_decoded_state capture "${PARSED[--state]}" \
      "${PARSED[--automation-output]}" \
      "${PARSED[--private-voice-output]}" \
      "${PARSED[--sanitized-voice-output]}"
    ;;
  end)
    parse_options '--state --cleanup-output' "$@"
    require_options --state --cleanup-output
    validate_absent_destination "${PARSED[--cleanup-output]}" || die 'invalid cleanup destination'
    run_with_decoded_state end "${PARSED[--state]}" "${PARSED[--cleanup-output]}"
    ;;
  *)
    die 'invalid operation'
    ;;
esac
