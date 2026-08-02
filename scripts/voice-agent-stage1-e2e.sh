#!/usr/bin/env bash
set -euo pipefail

umask 077

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB_READY_SCRIPT="$ROOT_DIR/scripts/adb-device-ready.sh"
ARTIFACT_HELPERS="$ROOT_DIR/scripts/voice-agent-e2e-artifacts.sh"

# Only app_artifact_path is used. Stage 1 deliberately does not pull any of the
# older transcript artifacts exposed by this helper file.
source "$ARTIFACT_HELPERS"

CONTROL_RECEIVER_CLASS="me.rerere.rikkahub.voiceagent.debug.VoiceAutomationControlReceiver"
FIXTURE_RECEIVER_CLASS="me.rerere.rikkahub.voiceagent.debug.VoiceCaptureFixtureDebugReceiver"
SERVICE_CLASS="me.rerere.rikkahub.voiceagent.VoiceAgentCallService"
ROUTE_ACTIVITY_CLASS="me.rerere.rikkahub.RouteActivity"
CONTROL_ACTION_PREFIX="me.rerere.rikkahub.voiceagent.automation"
FIXTURE_ARM_ACTION="me.rerere.rikkahub.debug.voiceagent.ARM_CAPTURE_FIXTURE"
FIXTURE_TRIGGER_ACTION="me.rerere.rikkahub.debug.voiceagent.TRIGGER_CAPTURE_FIXTURE"
CALL_START_ACTION="me.rerere.rikkahub.voiceagent.action.START"
CALL_END_ACTION="me.rerere.rikkahub.voiceagent.action.END"
EXPECTED_PHYSICAL_SERIAL="RZCX71NXRPB"
APP_ARTIFACT_BASE_DIR="no_backup/voice-e2e"
LATEST_VOICE_TRACE_PATH="$APP_ARTIFACT_BASE_DIR/latest-trace-id.txt"
SANITIZED_VOICE_EVENTS_NAME="voice-experience-events.ndjson"
PRIVATE_FIXTURE_DIR="files/voice-stage1"
PRIVATE_PROMPT_PATH="$PRIVATE_FIXTURE_DIR/prompt.pcm"
PRIVATE_INTERRUPT_PATH="$PRIVATE_FIXTURE_DIR/interrupt.pcm"
PRIVATE_STARTUP_PATH="$PRIVATE_FIXTURE_DIR/startup.pcm"
INJECTION_PROMPT_PATH="voice-stage1/prompt.pcm"
INJECTION_INTERRUPT_PATH="voice-stage1/interrupt.pcm"
INJECTION_STARTUP_PATH="voice-stage1/startup.pcm"

ADB_TIMEOUT_SECONDS="${VOICE_STAGE1_ADB_TIMEOUT_SECONDS:-10}"
WAIT_TIMEOUT_SECONDS="${VOICE_STAGE1_WAIT_TIMEOUT_SECONDS:-120}"
POLL_SECONDS="${VOICE_STAGE1_POLL_SECONDS:-1}"
CLOCK_COMMAND="${VOICE_STAGE1_CLOCK_COMMAND:-}"
MAX_WAIT_ATTEMPTS="${VOICE_STAGE1_MAX_WAIT_ATTEMPTS:-600}"
LOCK_DIR="${VOICE_STAGE1_LOCK_DIR:-${TMPDIR:-/tmp}/rikkahub-voice-stage1-locks}"
PROMPT_TRIGGER="${VOICE_STAGE1_PROMPT_TRIGGER:-initial_fixture}"
STARTUP_TRUTH_PROBE="${VOICE_STAGE1_STARTUP_TRUTH_PROBE:-0}"
TRANSCRIPT_PROBE="${VOICE_STAGE1_TRANSCRIPT_PROBE:-0}"
STARTUP_PRE_ROLL_SECONDS=5

START_ATTEMPTED=0
END_ATTEMPTED=0
AUTOMATION_ACTIVE=0
FINALIZE_ATTEMPTED=0
FIXTURES_STAGED=0
WIFI_RESTORE_STATE="proven"
CLEANUP_RUNNING=0
RUN_LOCK_FD=""
PREFLIGHT_STAGE_STATE="not_attempted"
CONTROL_DATA=""
STATUS_RUN_STATE=""
STATUS_RUN_HASH=""
STATUS_COMPARISON_HASH=""
STATUS_TRANSPORT=""
STATUS_EVENT_COUNT=""
STATUS_NETWORK=""
STATUS_VALIDATED=""
FIXTURE_TOKEN=""
FIXTURE_DATA=""
INITIAL_VOICE_TRACE_ID=""

fail() {
  printf 'stage1: %s\n' "$*" >&2
  return 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "$1 was not found in PATH"
}

require_env() {
  local name="$1"
  [[ -n "${!name:-}" ]] || fail "$name is required"
}

validate_positive_integer() {
  local name="$1"
  local value="$2"
  [[ "$value" =~ ^[1-9][0-9]*$ ]] || fail "$name must be a positive integer"
}

validate_nonnegative_number() {
  local name="$1"
  local value="$2"
  [[ "$value" =~ ^([0-9]+)(\.[0-9]+)?$ ]] || fail "$name must be a nonnegative number"
}

classify_transcript_evidence() {
  require_env VOICE_STAGE1_TRANSCRIPT_EVENT_OUTPUT
  require_env VOICE_STAGE1_WORKER_LOG_PATH
  require_env VOICE_STAGE1_WORKER_LOG_CAPTURE_STATUS
  case "$VOICE_STAGE1_WORKER_LOG_CAPTURE_STATUS" in
    complete|unavailable)
      ;;
    *)
      fail "VOICE_STAGE1_WORKER_LOG_CAPTURE_STATUS must be complete or unavailable"
      ;;
  esac
  require_command python3
  python3 - \
    "$VOICE_STAGE1_TRANSCRIPT_EVENT_OUTPUT" \
    "$VOICE_STAGE1_WORKER_LOG_PATH" \
    "$VOICE_STAGE1_WORKER_LOG_CAPTURE_STATUS" 2>/dev/null <<'PY'
import json
import os
import re
import stat
import sys
from datetime import datetime, timezone

BASE = {"version", "voiceSessionId", "eventId", "kind", "observedAt", "eventHash"}
JOB = {"userTurnId", "requestHash", "toolCallId", "argumentHash", "jobId"}
GROUNDING = {"groundedJobId", "groundedResultHash"}
IDENTIFIER = re.compile(r"^[A-Za-z0-9_-]{1,128}$")
HASH = re.compile(r"^sha256:[0-9a-f]{64}$")
UTC_TIMESTAMP = re.compile(
    r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}"
    r"(?:\.[0-9]{3}|\.[0-9]{6}|\.[0-9]{9})?Z$"
)

SCHEMAS = {
    "job_accepted": BASE | JOB | {"promptCharacterCount"},
    "job_running": BASE | JOB,
    "still_working": BASE | JOB,
    "job_succeeded": BASE | JOB | {"resultHash", "answerCharacterCount"},
    "job_failed": BASE | JOB | {"failureReasonCharacterCount"},
    "job_expired": BASE | JOB | {"failureReasonCharacterCount"},
    "job_canceled": BASE | JOB | {"failureReasonCharacterCount"},
    "transcript": BASE | {"turnId", "role", "interrupted", "textCharacterCount"},
    "delivery_eligible": BASE | {"toolCallId", "jobId"},
    "delivery_started": BASE | {"toolCallId", "jobId"},
    "speech_started": BASE | {"toolCallId", "jobId"},
    "delivery_blocked": BASE | {"toolCallId", "jobId", "userSpeaking", "agentSpeaking"},
    "delivery_announced": BASE | {"toolCallId", "jobId", "assistantTurnId"},
    "follow_up_correlation": BASE | {"followUpTurnId", "assistantTurnId", "resultHash"},
}

IDENTIFIER_FIELDS = {
    "voiceSessionId",
    "eventId",
    "userTurnId",
    "toolCallId",
    "jobId",
    "turnId",
    "groundedJobId",
    "assistantTurnId",
    "followUpTurnId",
}
HASH_FIELDS = {
    "eventHash",
    "requestHash",
    "argumentHash",
    "resultHash",
    "groundedResultHash",
}
COUNT_FIELDS = {
    "promptCharacterCount",
    "answerCharacterCount",
    "failureReasonCharacterCount",
    "textCharacterCount",
}
BOOLEAN_FIELDS = {"interrupted", "userSpeaking", "agentSpeaking"}
TARGET_AGENT = "rikka-livekit-experimental"
JOB_REQUEST = "received job request"
READY_MARKER = "voice_session_ready"
TRANSCRIPT_MARKER = "voice_user_transcript_final"


class InvalidEvidence(Exception):
    pass


def reject_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise InvalidEvidence()
        result[key] = value
    return result


def reject_nonstandard_number(_value):
    raise InvalidEvidence()


def canonical_utc_timestamp(value):
    if type(value) is not str or UTC_TIMESTAMP.fullmatch(value) is None:
        return False
    fractional_digits = len(value.rsplit(".", 1)[1][:-1]) if "." in value else 0
    if fractional_digits > 6:
        return False
    try:
        parsed = datetime.fromisoformat(value[:-1] + "+00:00")
    except (TypeError, ValueError):
        return False
    timespec = {0: "seconds", 3: "milliseconds", 6: "microseconds"}[fractional_digits]
    canonical = parsed.astimezone(timezone.utc).isoformat(timespec=timespec)
    return canonical.replace("+00:00", "Z") == value


def validate_row(row):
    if type(row) is not dict or any(value is None for value in row.values()):
        raise InvalidEvidence()
    kind = row.get("kind")
    try:
        expected_keys = SCHEMAS[kind]
    except (KeyError, TypeError):
        raise InvalidEvidence() from None
    actual_keys = set(row)
    if kind == "transcript":
        if frozenset(actual_keys) not in {
            frozenset(expected_keys),
            frozenset(expected_keys | GROUNDING),
        }:
            raise InvalidEvidence()
    elif actual_keys != expected_keys:
        raise InvalidEvidence()
    if type(row["version"]) is not int or row["version"] != 1:
        raise InvalidEvidence()
    if not canonical_utc_timestamp(row["observedAt"]):
        raise InvalidEvidence()
    for field in actual_keys & IDENTIFIER_FIELDS:
        value = row[field]
        if type(value) is not str or IDENTIFIER.fullmatch(value) is None:
            raise InvalidEvidence()
    for field in actual_keys & HASH_FIELDS:
        value = row[field]
        if type(value) is not str or HASH.fullmatch(value) is None:
            raise InvalidEvidence()
    for field in actual_keys & COUNT_FIELDS:
        value = row[field]
        if type(value) is not int or value < 0:
            raise InvalidEvidence()
    for field in actual_keys & BOOLEAN_FIELDS:
        if type(row[field]) is not bool:
            raise InvalidEvidence()
    if kind == "transcript":
        role = row["role"]
        if type(role) is not str or role not in {"user", "assistant"}:
            raise InvalidEvidence()
        has_grounding = GROUNDING <= actual_keys
        if role == "user" and has_grounding:
            raise InvalidEvidence()


def emit_record(prefix, payload):
    print(prefix + json.dumps(payload, separators=(",", ":")))


def file_record(classification, covered, rows, qualifying):
    payload = {
        "version": 1,
        "classification": classification,
        "collectionCovered": covered,
        "rowCount": rows,
        "qualifyingUserTranscriptCount": qualifying,
    }
    emit_record("stage1.final_user_transcript_file=", payload)


def worker_record(classification, activity_count, marker_count):
    payload = {
        "version": 1,
        "classification": classification,
        "sessionActivityCount": activity_count,
        "markerCount": marker_count,
    }
    emit_record("stage1.worker_transcript_log=", payload)


def combined_record(classification, boundary):
    payload = {
        "version": 1,
        "classification": classification,
        "firstMissingBoundary": boundary,
    }
    emit_record("stage1.final_user_transcript=", payload)


def classify_file(path):
    try:
        file_stat = os.lstat(path)
        if stat.S_ISLNK(file_stat.st_mode) or not stat.S_ISREG(file_stat.st_mode):
            raise InvalidEvidence()
        if not os.access(path, os.R_OK):
            raise InvalidEvidence()
        session_id = None
        event_ids = set()
        row_count = 0
        qualifying_count = 0
        with open(path, "r", encoding="utf-8") as source:
            for raw_line in source:
                if not raw_line.strip():
                    continue
                row_count += 1
                row = json.loads(
                    raw_line,
                    object_pairs_hook=reject_duplicate_keys,
                    parse_constant=reject_nonstandard_number,
                )
                validate_row(row)
                if session_id is None:
                    session_id = row["voiceSessionId"]
                elif row["voiceSessionId"] != session_id:
                    raise InvalidEvidence()
                if row["eventId"] in event_ids:
                    raise InvalidEvidence()
                event_ids.add(row["eventId"])
                if (
                    row["kind"] == "transcript"
                    and row["role"] == "user"
                    and row["interrupted"] is False
                    and row["textCharacterCount"] > 0
                ):
                    qualifying_count += 1
        if row_count == 0:
            return "unknown", True, 0, 0
        elif qualifying_count:
            return "present", True, row_count, qualifying_count
        else:
            return "absent", True, row_count, 0
    except BaseException:
        return "unknown", False, 0, 0


def worker_snapshot_metadata(file_stat):
    if (
        stat.S_ISLNK(file_stat.st_mode)
        or not stat.S_ISREG(file_stat.st_mode)
        or stat.S_IMODE(file_stat.st_mode) != 0o600
    ):
        raise InvalidEvidence()
    return (
        file_stat.st_dev,
        file_stat.st_ino,
        file_stat.st_mode,
        file_stat.st_nlink,
        file_stat.st_uid,
        file_stat.st_gid,
        file_stat.st_size,
        file_stat.st_mtime_ns,
        file_stat.st_ctime_ns,
    )


def worker_rows(path):
    path_metadata = worker_snapshot_metadata(os.lstat(path))
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(path, flags)
    try:
        opened_stat = os.fstat(descriptor)
        opened_metadata = worker_snapshot_metadata(opened_stat)
        if opened_metadata != path_metadata:
            raise InvalidEvidence()
        source = os.fdopen(descriptor, "rb")
        descriptor = -1
        with source:
            content = source.read()
            post_read_metadata = worker_snapshot_metadata(os.fstat(source.fileno()))
            post_read_path_metadata = worker_snapshot_metadata(os.lstat(path))
            if (
                post_read_metadata != opened_metadata
                or post_read_path_metadata != opened_metadata
                or len(content) != opened_stat.st_size
                or (content and not content.endswith(b"\n"))
            ):
                raise InvalidEvidence()
            rows = []
            for raw_line in content.decode("utf-8").split("\n"):
                if not raw_line.strip():
                    continue
                row = json.loads(
                    raw_line,
                    object_pairs_hook=reject_duplicate_keys,
                    parse_constant=reject_nonstandard_number,
                )
                if type(row) is not dict:
                    raise InvalidEvidence()
                for field in {"message", "agent_name", "job_id", "room"} & set(row):
                    if type(row[field]) is not str:
                        raise InvalidEvidence()
                if not row.get("message"):
                    raise InvalidEvidence()
                rows.append(row)
            return rows
    finally:
        if descriptor >= 0:
            os.close(descriptor)


def classify_worker(path, capture_status):
    if capture_status != "complete":
        return "unknown", 0, 0
    try:
        rows = worker_rows(path)
        requests = []
        children = []
        for index, row in enumerate(rows):
            message = row["message"]
            if message == JOB_REQUEST:
                if row.get("agent_name") != TARGET_AGENT:
                    continue
                job_id, room = row.get("job_id"), row.get("room")
                if not job_id or not room:
                    raise InvalidEvidence()
                requests.append((index, job_id, room))
            elif message in {READY_MARKER, TRANSCRIPT_MARKER}:
                job_id, room = row.get("job_id"), row.get("room")
                if not job_id or not room:
                    raise InvalidEvidence()
                children.append((index, message, job_id, room))
        if len(requests) != 1:
            raise InvalidEvidence()
        request_index, selected_job, selected_room = requests[0]
        selected = [
            (index, message)
            for index, message, job_id, room in children
            if job_id == selected_job and room == selected_room
        ]
        if any(index <= request_index for index, _ in selected):
            raise InvalidEvidence()
        ready_indices = [index for index, message in selected if message == READY_MARKER]
        marker_indices = [index for index, message in selected if message == TRANSCRIPT_MARKER]

        if marker_indices:
            classification = "marker-present"
        elif len(ready_indices) == 1:
            classification = "marker-absent"
        else:
            raise InvalidEvidence()
        activity_count = 1 + len(selected)
        marker_count = len(marker_indices)
        return classification, activity_count, marker_count
    except BaseException:
        return "unknown", 0, 0


file_result = classify_file(sys.argv[1])
worker_result = classify_worker(sys.argv[2], sys.argv[3])
file_state, file_covered, row_count, qualifying_count = file_result
worker_state, activity_count, marker_count = worker_result

if file_state == "present":
    verdict, boundary, status = "present", "ask-hermes", 0
elif worker_state == "marker-present" and file_covered:
    verdict, boundary, status = "bridge-breakage", "android-transcript-evidence", 2
elif worker_state == "marker-absent":
    verdict, boundary, status = "absent-at-worker", "final-user-transcript", 2
else:
    verdict, boundary, status = "unknown", "evidence-collection", 3

file_record(file_state, file_covered, row_count, qualifying_count)
worker_record(worker_state, activity_count, marker_count)
combined_record(verdict, boundary)
raise SystemExit(status)

PY
}

adb_command() {
  timeout "${ADB_TIMEOUT_SECONDS}s" adb -s "$VOICE_STAGE1_SERIAL" "$@"
}

private_mkdir() { (umask 077; mkdir -p -- "$1") 2>/dev/null; }
private_mktemp() { mktemp "$1" 2>/dev/null; }
private_chmod() { chmod 600 -- "$1" 2>/dev/null; }
private_move() { mv -f -- "$1" "$2" 2>/dev/null; }
private_remove_temp() { rm -f -- "$1" 2>/dev/null || true; }
private_dirname() { dirname -- "$1" 2>/dev/null; }
private_fixture_size() { (wc -c < "$1" | tr -d '[:space:]') 2>/dev/null; }

safe_voice_trace_id() {
  [[ "$1" =~ ^[A-Za-z0-9_-]{1,128}$ ]]
}

read_latest_voice_trace_id() {
  adb_command exec-out run-as "$VOICE_STAGE1_PACKAGE" \
    cat "$LATEST_VOICE_TRACE_PATH" 2>/dev/null
}

probe_remote_regular_file() {
  local remote_path="$1"
  local result
  if ! result="$(adb_command exec-out run-as "$VOICE_STAGE1_PACKAGE" sh -c '
if [ -L "$1" ]; then
  printf invalid
elif [ -e "$1" ]; then
  if [ -f "$1" ]; then
    printf present
  else
    printf invalid
  fi
else
  printf absent
fi
' sh "$remote_path" 2>/dev/null)"; then
    return 1
  fi
  case "$result" in
    present|absent|invalid)
      printf '%s' "$result"
      ;;
    *)
      return 1
      ;;
  esac
}

validate_evidence_output_paths() {
  local status=0
  if python3 - \
    "$VOICE_STAGE1_EVENT_OUTPUT" \
    "$TRANSCRIPT_PROBE" \
    "${VOICE_STAGE1_TRANSCRIPT_EVENT_OUTPUT:-}" 2>/dev/null <<'PY'
import os
import stat
import sys

AUTOMATION_INVALID = 10
TRANSCRIPT_INVALID = 11
ALIASED = 12
UNEXPECTED = 13


def identity(path, invalid_status):
    try:
        canonical = os.path.realpath(os.path.abspath(path))
        try:
            value = os.lstat(path)
        except FileNotFoundError:
            inode = None
        else:
            if stat.S_ISLNK(value.st_mode) or not stat.S_ISREG(value.st_mode):
                raise SystemExit(invalid_status)
            inode = (value.st_dev, value.st_ino)
        return canonical, inode
    except SystemExit:
        raise
    except BaseException:
        raise SystemExit(UNEXPECTED) from None


automation = identity(sys.argv[1], AUTOMATION_INVALID)
if sys.argv[2] == "1":
    transcript = identity(sys.argv[3], TRANSCRIPT_INVALID)
    if automation[0] == transcript[0]:
        raise SystemExit(ALIASED)
    if automation[1] is not None and automation[1] == transcript[1]:
        raise SystemExit(ALIASED)
PY
  then
    return 0
  else
    status=$?
  fi

  case "$status" in
    10) fail "VOICE_STAGE1_EVENT_OUTPUT must be absent or a regular non-symlink file" ;;
    11) fail "VOICE_STAGE1_TRANSCRIPT_EVENT_OUTPUT must be absent or a regular non-symlink file" ;;
    12) fail "Stage1 evidence outputs must be distinct" ;;
    *) fail "unable to validate Stage1 evidence outputs" ;;
  esac
}

snapshot_initial_voice_trace() {
  local pointer_state
  local trace_id
  pointer_state="$(probe_remote_regular_file "$LATEST_VOICE_TRACE_PATH")" || return 1
  case "$pointer_state" in
    absent)
      INITIAL_VOICE_TRACE_ID=""
      return 0
      ;;
    present)
      ;;
    *)
      return 1
      ;;
  esac
  trace_id="$(read_latest_voice_trace_id)" || return 1
  safe_voice_trace_id "$trace_id" || return 1
  INITIAL_VOICE_TRACE_ID="$trace_id"
}

fetch_voice_transcript_events() {
  local pointer_state
  local trace_id
  local events_path
  local events_state
  local output_parent
  local temp_output=""
  pointer_state="$(probe_remote_regular_file "$LATEST_VOICE_TRACE_PATH")" || return 1
  [[ "$pointer_state" == "present" ]] || return 1
  trace_id="$(read_latest_voice_trace_id)" || return 1
  safe_voice_trace_id "$trace_id" || return 1
  [[ "$trace_id" != "$INITIAL_VOICE_TRACE_ID" ]] || return 1
  events_path="$APP_ARTIFACT_BASE_DIR/$trace_id/$SANITIZED_VOICE_EVENTS_NAME"
  events_state="$(probe_remote_regular_file "$events_path")" || return 1
  [[ "$events_state" != "invalid" ]] || return 1

  output_parent="$(private_dirname "$VOICE_STAGE1_TRANSCRIPT_EVENT_OUTPUT")" || return 1
  private_mkdir "$output_parent" || return 1
  temp_output="$(private_mktemp "$output_parent/.voice-stage1-transcript.XXXXXX")" ||
    return 1
  private_chmod "$temp_output" || {
    private_remove_temp "$temp_output"
    return 1
  }
  if [[ "$events_state" == "present" ]]; then
    if ! adb_command exec-out run-as "$VOICE_STAGE1_PACKAGE" cat "$events_path" \
      >"$temp_output" 2>/dev/null; then
      private_remove_temp "$temp_output"
      return 1
    fi
  elif [[ "$events_state" != "absent" ]]; then
    private_remove_temp "$temp_output"
    return 1
  fi
  private_move "$temp_output" "$VOICE_STAGE1_TRANSCRIPT_EVENT_OUTPUT" || {
    private_remove_temp "$temp_output"
    return 1
  }
  temp_output=""
}

clock_now() {
  local value
  if [[ -n "$CLOCK_COMMAND" ]]; then
    value="$(timeout "${ADB_TIMEOUT_SECONDS}s" "$CLOCK_COMMAND")" || fail "clock command failed"
  else
    value="$(python3 -c 'import time; print(time.monotonic_ns() // 1_000_000_000)')" ||
      fail "monotonic clock failed"
  fi
  [[ "$value" =~ ^[0-9]+$ ]] || fail "clock returned a non-integer value"
  printf '%s' "$value"
}

sleep_poll() {
  sleep "$POLL_SECONDS"
}

require_expected_serial() {
  [[ "$VOICE_STAGE1_SERIAL" == "$EXPECTED_PHYSICAL_SERIAL" ]] ||
    fail "Stage1 requires physical device $EXPECTED_PHYSICAL_SERIAL"
}

acquire_run_lock() {
  local lock_path
  [[ ! -L "$LOCK_DIR" ]] || fail "device lock directory must not be a symlink"
  mkdir -p "$LOCK_DIR"
  chmod 700 "$LOCK_DIR"
  lock_path="$LOCK_DIR/voice-agent-stage1-$VOICE_STAGE1_SERIAL.lock"
  [[ ! -L "$lock_path" ]] || fail "device lock path must not be a symlink"
  exec {RUN_LOCK_FD}> "$lock_path"
  chmod 600 "$lock_path"
  flock -n "$RUN_LOCK_FD" || fail "another Stage1 runner owns $VOICE_STAGE1_SERIAL"
}

select_device() {
  local devices_output
  local selected_state
  devices_output="$(timeout "${ADB_TIMEOUT_SECONDS}s" adb devices -l)" ||
    fail "unable to enumerate ADB devices"
  selected_state="$(printf '%s\n' "$devices_output" | awk -v serial="$VOICE_STAGE1_SERIAL" '
    $1 == serial { count++; state = $2 }
    END { if (count == 1) print state }
  ')"
  [[ "$selected_state" == "device" ]] ||
    fail "selected device $VOICE_STAGE1_SERIAL is not uniquely authorized"

  VOICE_AGENT_E2E_SERIAL="$VOICE_STAGE1_SERIAL" \
    ADB_DEVICE_READY_TIMEOUT_SECONDS="$ADB_TIMEOUT_SECONDS" \
    "$ADB_READY_SCRIPT" "$VOICE_STAGE1_SERIAL" >/dev/null

  local qemu
  local hardware
  qemu="$(adb_command shell getprop ro.kernel.qemu | tr -d '\r[:space:]')"
  hardware="$(adb_command shell getprop ro.hardware | tr -d '\r[:space:]')"
  hardware="${hardware,,}"
  [[ "$qemu" == "" || "$qemu" == "0" || "$qemu" == "false" ]] ||
    fail "physical device verification failed: qemu=$qemu"
  case "$hardware" in
    *ranchu*|*goldfish*|*cuttlefish*)
      fail "physical device verification failed: emulator hardware"
      ;;
  esac
}

require_package() {
  adb_command shell pm path "$VOICE_STAGE1_PACKAGE" >/dev/null ||
    fail "package $VOICE_STAGE1_PACKAGE is not installed"
}

decode_broadcast_data() {
  local raw="$1"
  raw="${raw//\\n/$'\n'}"
  raw="${raw//\\r/$'\r'}"
  raw="${raw//\\\\/\\}"
  printf '%s' "$raw"
}

control_broadcast() {
  local action="$1"
  shift
  local output
  local completed_output
  local result_code
  local raw_data
  if ! output="$(adb_command shell am broadcast --user 0 \
    -n "$VOICE_STAGE1_PACKAGE/$CONTROL_RECEIVER_CLASS" \
    -a "$CONTROL_ACTION_PREFIX.$action" "$@")"; then
    fail "automation ${action,,} broadcast failed"
    return 1
  fi
  completed_output="$(printf '%s\n' "$output" | awk '
    capture { print; next }
    /^Broadcast completed:/ { capture=1; print }
  ')"
  if [[ ! "$completed_output" =~ ^Broadcast\ completed:\ result=([-0-9]+),\ data=\"(.*)\"$ ]]; then
    fail "automation ${action,,} returned malformed broadcast output"
    return 1
  fi
  result_code="${BASH_REMATCH[1]}"
  raw_data="${BASH_REMATCH[2]}"
  if [[ "$result_code" != "0" ]]; then
    fail "automation ${action,,} was rejected"
    return 1
  fi
  CONTROL_DATA="$(decode_broadcast_data "$raw_data")"
}

expect_control_data() {
  local expected="$1"
  [[ "$CONTROL_DATA" == "$expected" ]] || fail "automation receiver returned an unexpected response"
}

read_status() {
  local -a lines=()
  control_broadcast STATUS || return 1
  mapfile -t lines <<< "$CONTROL_DATA"
  [[ "${#lines[@]}" == "9" ]] || { fail "automation status field count mismatch"; return 1; }
  [[ "${lines[0]}" == "status=ok" ]] || { fail "automation status marker mismatch"; return 1; }
  [[ "${lines[1]}" == "action=status" ]] || { fail "automation status action mismatch"; return 1; }
  [[ "${lines[2]}" == run_state=* ]] || { fail "automation status run_state field mismatch"; return 1; }
  [[ "${lines[3]}" == run_hash=* ]] || { fail "automation status run_hash field mismatch"; return 1; }
  [[ "${lines[4]}" == comparison_hash=* ]] || { fail "automation status comparison_hash field mismatch"; return 1; }
  [[ "${lines[5]}" == requested_transport=* ]] || { fail "automation status transport field mismatch"; return 1; }
  [[ "${lines[6]}" == event_count=* ]] || { fail "automation status event_count field mismatch"; return 1; }
  [[ "${lines[7]}" == network=* ]] || { fail "automation status network field mismatch"; return 1; }
  [[ "${lines[8]}" == validated=* ]] || { fail "automation status validated field mismatch"; return 1; }
  STATUS_RUN_STATE="${lines[2]#run_state=}"
  STATUS_RUN_HASH="${lines[3]#run_hash=}"
  STATUS_COMPARISON_HASH="${lines[4]#comparison_hash=}"
  STATUS_TRANSPORT="${lines[5]#requested_transport=}"
  STATUS_EVENT_COUNT="${lines[6]#event_count=}"
  STATUS_NETWORK="${lines[7]#network=}"
  STATUS_VALIDATED="${lines[8]#validated=}"
  [[ "$STATUS_RUN_STATE" =~ ^(idle|active|finalized)$ ]] || { fail "automation status run_state value mismatch"; return 1; }
  [[ "$STATUS_EVENT_COUNT" =~ ^[0-9]+$ ]] || { fail "automation status event_count value mismatch"; return 1; }
  [[ "$STATUS_NETWORK" =~ ^(wifi|cellular|none)$ ]] || { fail "automation status network value mismatch"; return 1; }
  [[ "$STATUS_VALIDATED" =~ ^(true|false)$ ]] || { fail "automation status validated value mismatch"; return 1; }
}

read_android_network() {
  local connectivity
  local active_id
  local active_header
  local active_network_capabilities
  connectivity="$(adb_command shell dumpsys connectivity)" || {
    fail "Android connectivity readback failed"
    return 1
  }
  active_id="$(printf '%s\n' "$connectivity" | awk '/Active default network:/ { print $4; exit }')"
  [[ "$active_id" =~ ^[0-9]+$ ]] || {
    fail "Android has no numeric active default network"
    return 1
  }
  active_header="$(printf '%s\n' "$connectivity" | awk -v id="$active_id" '
    {
      line = $0
      sub(/^[[:space:]]*/, "", line)
      prefix = "NetworkAgentInfo{network{" id "}"
      if (index(line, prefix) == 1) { print; exit }
    }
  ')"
  [[ -n "$active_header" ]] || {
    fail "active default network details were not found"
    return 1
  }
  active_network_capabilities="$(printf '%s\n' "$active_header" | awk '
    {
      marker = " nc{"
      start = index($0, marker)
      if (start == 0) exit
      start += length(marker)
      depth = 1
      for (position = start; position <= length($0); position++) {
        character = substr($0, position, 1)
        if (character == "{") depth++
        if (character == "}") depth--
        if (depth == 0) {
          print substr($0, start, position - start)
          exit
        }
      }
    }
  ')"
  [[ -n "$active_network_capabilities" ]] || {
    fail "active default network capabilities were not found"
    return 1
  }
  [[ "$active_network_capabilities" =~ (^|[^A-Z0-9_])VALIDATED([^A-Z0-9_]|$) ]] || {
    fail "active default network is not validated"
    return 1
  }
  local has_wifi=0
  local has_cellular=0
  [[ "$active_network_capabilities" =~ (^|[^A-Z0-9_])WIFI([^A-Z0-9_]|$) ]] && has_wifi=1
  [[ "$active_network_capabilities" =~ (^|[^A-Z0-9_])CELLULAR([^A-Z0-9_]|$) ]] && has_cellular=1
  if (( has_wifi == 1 && has_cellular == 0 )); then
    printf 'wifi'
  elif (( has_cellular == 1 && has_wifi == 0 )); then
    printf 'cellular'
  else
    fail "active default network transport is ambiguous"
    return 1
  fi
}

WAIT_LABEL=""
WAIT_STARTED=0
WAIT_LAST=0
WAIT_ATTEMPTS=0

begin_wait() {
  WAIT_LABEL="$1"
  WAIT_STARTED="$(clock_now)"
  WAIT_LAST="$WAIT_STARTED"
  WAIT_ATTEMPTS=0
}

continue_wait() {
  local timeout_seconds="$1"
  local timeout_message="$2"
  local now
  WAIT_ATTEMPTS=$((WAIT_ATTEMPTS + 1))
  if (( WAIT_ATTEMPTS >= MAX_WAIT_ATTEMPTS )); then
    fail "wait attempt limit reached for $WAIT_LABEL"
    return 1
  fi
  now="$(clock_now)"
  if (( now < WAIT_LAST )); then
    fail "clock moved backward while waiting for $WAIT_LABEL"
    return 1
  fi
  WAIT_LAST="$now"
  if (( now - WAIT_STARTED >= timeout_seconds )); then
    fail "$timeout_message"
    return 1
  fi
  sleep_poll
}

wait_android_network() {
  local expected="$1"
  local observed
  begin_wait "Android $expected network"
  while true; do
    if observed="$(read_android_network 2>/dev/null)" && [[ "$observed" == "$expected" ]]; then
      return 0
    fi
    continue_wait "$WAIT_TIMEOUT_SECONDS" "timed out waiting for Android $expected network" || return 1
  done
}

read_idle_status() {
  local expected="$1"
  read_status || return 1
  if [[ "$STATUS_RUN_STATE" == "active" ]]; then
    fail "automation receiver already has an active run"
    return 1
  fi
  if [[ "$STATUS_NETWORK" == "$expected" && "$STATUS_VALIDATED" == "true" ]]; then
    return 0
  fi
  if [[ "$STATUS_NETWORK" == "none" && "$STATUS_VALIDATED" == "false" ]]; then
    return 0
  fi
  fail "network observation mismatch: app=$STATUS_NETWORK expected=$expected"
}

cross_check_network() {
  local expected="$1"
  local android_network
  android_network="$(read_android_network)"
  read_status
  [[ "$android_network" == "$expected" && "$STATUS_NETWORK" == "$expected" && "$STATUS_VALIDATED" == "true" ]] ||
    fail "network observation mismatch: Android=$android_network app=$STATUS_NETWORK expected=$expected"
  if [[ "$STATUS_RUN_STATE" == "active" ]]; then
    [[ "$STATUS_RUN_HASH" == "$VOICE_STAGE1_RUN_HASH" ]] || fail "automation status run hash mismatch"
    [[ "$STATUS_COMPARISON_HASH" == "$VOICE_STAGE1_COMPARISON_HASH" ]] ||
      fail "automation status comparison hash mismatch"
    [[ "$STATUS_TRANSPORT" == "$VOICE_STAGE1_TRANSPORT" ]] || fail "automation status transport mismatch"
  fi
}

stage_stream() {
  local destination="$1"
  adb_command shell run-as "$VOICE_STAGE1_PACKAGE" mkdir -p "$PRIVATE_FIXTURE_DIR" </dev/null >/dev/null
  timeout "${ADB_TIMEOUT_SECONDS}s" adb -s "$VOICE_STAGE1_SERIAL" \
    exec-in run-as "$VOICE_STAGE1_PACKAGE" sh -c 'umask 077; cat > "$1"' sh "$destination"
}

wait_staged_size() {
  local destination="$1"
  local expected_size="$2"
  local actual_size
  begin_wait "private fixture $destination"
  while true; do
    actual_size="$(
      adb_command shell run-as "$VOICE_STAGE1_PACKAGE" stat -c %s "$destination" 2>/dev/null |
        tr -d '\r[:space:]'
    )" || actual_size=""
    if [[ "$actual_size" =~ ^[0-9]+$ && "$actual_size" == "$expected_size" ]]; then
      return 0
    fi
    continue_wait "$WAIT_TIMEOUT_SECONDS" "private fixture staging size mismatch" || return 1
  done
}

stage_file() {
  local source_path="$1"
  local destination="$2"
  local expected_size
  expected_size="$(wc -c < "$source_path" | tr -d '[:space:]')"
  stage_stream "$destination" < "$source_path" >/dev/null
  wait_staged_size "$destination" "$expected_size"
}

remove_private_fixtures() {
  adb_command shell run-as "$VOICE_STAGE1_PACKAGE" rm -f \
    "$PRIVATE_PROMPT_PATH" "$PRIVATE_INTERRUPT_PATH" "$PRIVATE_STARTUP_PATH" >/dev/null
}

raw_cleanup_finalize() {
  adb_command shell am broadcast --user 0 \
    -n "$VOICE_STAGE1_PACKAGE/$CONTROL_RECEIVER_CLASS" \
    -a "$CONTROL_ACTION_PREFIX.FINALIZE" >/dev/null
}

raw_cleanup_end() {
  adb_command shell am start-foreground-service \
    -n "$VOICE_STAGE1_PACKAGE/$SERVICE_CLASS" \
    -a "$CALL_END_ACTION" >/dev/null
}

cleanup_resources() {
  local cleanup_status=0
  (( CLEANUP_RUNNING == 0 )) || return 0
  CLEANUP_RUNNING=1
  set +e
  if (( START_ATTEMPTED == 1 && END_ATTEMPTED == 0 )); then
    END_ATTEMPTED=1
    raw_cleanup_end || cleanup_status=1
  fi
  if (( AUTOMATION_ACTIVE == 1 && FINALIZE_ATTEMPTED == 0 )); then
    FINALIZE_ATTEMPTED=1
    if read_status &&
      [[ "$STATUS_RUN_STATE" == "active" ]] &&
      [[ "$STATUS_RUN_HASH" == "$VOICE_STAGE1_RUN_HASH" ]] &&
      [[ "$STATUS_COMPARISON_HASH" == "$VOICE_STAGE1_COMPARISON_HASH" ]] &&
      [[ "$STATUS_TRANSPORT" == "$VOICE_STAGE1_TRANSPORT" ]]; then
      raw_cleanup_finalize || cleanup_status=1
    fi
    AUTOMATION_ACTIVE=0
  fi
  if (( FIXTURES_STAGED == 1 )); then
    remove_private_fixtures || cleanup_status=1
    FIXTURES_STAGED=0
  fi
  if [[ "$WIFI_RESTORE_STATE" == "not_attempted" ]]; then
    local restored_network
    WIFI_RESTORE_STATE="pending"
    if adb_command shell svc wifi enable >/dev/null &&
      restored_network="$(read_android_network 2>/dev/null)" &&
      [[ "$restored_network" == "wifi" ]]; then
      WIFI_RESTORE_STATE="proven"
    else
      cleanup_status=1
    fi
  fi
  set -e
  CLEANUP_RUNNING=0
  return "$cleanup_status"
}

on_exit() {
  local original_status=$?
  local cleanup_status=0
  trap - EXIT
  cleanup_resources || cleanup_status=$?
  if (( original_status == 0 && cleanup_status != 0 )); then
    original_status=$cleanup_status
  fi
  exit "$original_status"
}

remove_preflight_fixture_once() {
  [[ "$PREFLIGHT_STAGE_STATE" == "pending" ]] || return 0
  PREFLIGHT_STAGE_STATE="remove_attempted"
  if adb_command shell run-as "$VOICE_STAGE1_PACKAGE" rm -f "$PRIVATE_FIXTURE_DIR/.preflight" >/dev/null; then
    PREFLIGHT_STAGE_STATE="removed"
    return 0
  fi
  return 1
}

preflight_on_exit() {
  local original_status=$?
  local cleanup_status=0
  trap - EXIT
  remove_preflight_fixture_once || cleanup_status=$?
  if (( original_status == 0 && cleanup_status != 0 )); then
    original_status=$cleanup_status
  fi
  exit "$original_status"
}

validate_event_lines() {
  local event_name="$1"
  local require_observed_transport="$2"
  local lines="$3"
  local line
  while IFS= read -r line; do
    [[ "$line" == *"\"name\":\"$event_name\""* ]] || continue
    [[ "$line" == *"\"runHash\":\"$VOICE_STAGE1_RUN_HASH\""* ]] ||
      { fail "event run hash mismatch"; return 1; }
    [[ "$line" == *"\"comparisonHash\":\"$VOICE_STAGE1_COMPARISON_HASH\""* ]] ||
      { fail "event comparison hash mismatch"; return 1; }
    [[ "$line" == *"\"requestedTransport\":\"$VOICE_STAGE1_TRANSPORT\""* ]] ||
      { fail "event requested transport mismatch"; return 1; }
    if [[ "$line" != *'"observedTransport":null'* &&
          "$line" != *"\"observedTransport\":\"$VOICE_STAGE1_TRANSPORT\""* ]]; then
      fail "observed transport mismatch"
      return 1
    fi
    if [[ "$require_observed_transport" == "1" &&
          "$line" != *"\"observedTransport\":\"$VOICE_STAGE1_TRANSPORT\""* ]]; then
      fail "observed transport mismatch"
      return 1
    fi
  done <<< "$lines"
}

wait_event() {
  local event_name="${1,,}"
  local require_observed_transport="${2:-0}"
  local lines
  local event_pattern="\"name\":\"$event_name\""
  begin_wait "$event_name"
  while true; do
    if lines="$(adb_command exec-out run-as "$VOICE_STAGE1_PACKAGE" \
      grep -F "$event_pattern" "$AUTOMATION_EVENT_PATH" 2>/dev/null)" &&
      [[ -n "$lines" ]]; then
      validate_event_lines "$event_name" "$require_observed_transport" "$lines" ||
        return 1
      return 0
    fi
    continue_wait "$WAIT_TIMEOUT_SECONDS" "timed out waiting for $event_name" || return 1
  done
}

latest_event_monotonic_ms() {
  local event_name="${1,,}"
  local lines
  local boundary_ms
  local event_pattern="\"name\":\"$event_name\""
  begin_wait "$event_name ordering boundary"
  while true; do
    if lines="$(adb_command exec-out run-as "$VOICE_STAGE1_PACKAGE" \
      grep -F "$event_pattern" "$AUTOMATION_EVENT_PATH" 2>/dev/null)" &&
      [[ -n "$lines" ]]; then
      validate_event_lines "$event_name" 0 "$lines" || return 1
      if boundary_ms="$(python3 -c '
import json, sys
events = [json.loads(line) for line in sys.stdin if line.strip()]
if not events:
    raise SystemExit(1)
print(max(event["monotonicMs"] for event in events))
' <<< "$lines")"; then
        printf '%s\n' "$boundary_ms"
        return 0
      fi
    fi
    continue_wait "$WAIT_TIMEOUT_SECONDS" \
      "timed out waiting for $event_name ordering boundary" || return 1
  done
}

latest_run_event_monotonic_ms() {
  local lines
  lines="$(adb_command exec-out run-as "$VOICE_STAGE1_PACKAGE" \
    grep -F '"schemaVersion":1' "$AUTOMATION_EVENT_PATH")" ||
    fail "automation run has no event boundary"
  python3 -c '
import json, sys
run_hash, comparison_hash, transport = sys.argv[1:]
events = [json.loads(line) for line in sys.stdin if line.strip()]
if not events or any(event.get("runHash") != run_hash or
                     event.get("comparisonHash") != comparison_hash or
                     event.get("requestedTransport") != transport for event in events):
    raise SystemExit(1)
print(max(event["monotonicMs"] for event in events))
' "$VOICE_STAGE1_RUN_HASH" "$VOICE_STAGE1_COMPARISON_HASH" "$VOICE_STAGE1_TRANSPORT" <<< "$lines" ||
    fail "automation event boundary identity mismatch"
}

read_android_app_state() {
  local activities
  local resumed
  activities="$(adb_command shell dumpsys activity activities)" || {
    fail "Android activity state readback failed"
    return 1
  }
  resumed="$(printf '%s\n' "$activities" |
    awk '/mResumedActivity:|topResumedActivity=|ResumedActivity:/ { print; exit }')"
  if [[ -z "$resumed" ]]; then
    fail "Android resumed activity was not found"
    return 1
  fi
  if [[ "$resumed" == *"$VOICE_STAGE1_PACKAGE/"* ]]; then
    printf 'foreground'
  else
    printf 'background'
  fi
}

event_exists_after() {
  local event_name="$1"
  local boundary_ms="$2"
  local lines="$3"
  validate_event_lines "$event_name" 0 "$lines"
  python3 -c '
import json, sys
boundary = int(sys.argv[1])
raise SystemExit(0 if any(json.loads(line)["monotonicMs"] > boundary for line in sys.stdin if line.strip()) else 1)
' "$boundary_ms" <<< "$lines"
}

wait_event_after() {
  local event_name="${1,,}"
  local boundary_ms="$2"
  local lines
  local event_pattern="\"name\":\"$event_name\""
  begin_wait "post-handover $event_name"
  while true; do
    if lines="$(adb_command exec-out run-as "$VOICE_STAGE1_PACKAGE" \
      grep -F "$event_pattern" "$AUTOMATION_EVENT_PATH" 2>/dev/null)" &&
      event_exists_after "$event_name" "$boundary_ms" "$lines"; then
      return 0
    fi
    continue_wait "$WAIT_TIMEOUT_SECONDS" "timed out waiting for post-handover $event_name" || return 1
  done
}

wait_matching_fixture_attestation() {
  local expected_bytes="$1"
  local lines
  local attestation_ms
  local evidence_status
  begin_wait "matching fixture attestation"
  while true; do
    if lines="$(adb_command exec-out run-as "$VOICE_STAGE1_PACKAGE" \
      grep -F '"schemaVersion":1' "$AUTOMATION_EVENT_PATH" 2>/dev/null)" &&
      [[ -n "$lines" ]]; then
      if attestation_ms="$(python3 -c '
import json, sys
run_hash, comparison_hash, transport, expected_bytes = sys.argv[1:]
expected_bytes = int(expected_bytes)
try:
    events = [json.loads(line) for line in sys.stdin if line.strip()]
except ValueError:
    raise SystemExit(2)
if any(event.get("runHash") != run_hash or
       event.get("comparisonHash") != comparison_hash or
       event.get("requestedTransport") != transport for event in events):
    raise SystemExit(2)
prompt_ends = [event for event in events
               if event.get("name") == "prompt_ended" and
               event.get("byteCount") == expected_bytes]
if not prompt_ends:
    raise SystemExit(1)
if len(prompt_ends) != 1:
    raise SystemExit(2)
prompt_end_ms = prompt_ends[0].get("monotonicMs")
attestations = [event for event in events
                if event.get("name") == "capture_attested" and
                event.get("captureSource") == "fixture" and
                event.get("micBytes") == 0 and
                event.get("fixtureBytes") == expected_bytes and
                isinstance(event.get("monotonicMs"), int) and
                event["monotonicMs"] > prompt_end_ms]
if not attestations:
    raise SystemExit(1)
if len(attestations) != 1:
    raise SystemExit(2)
print(attestations[0]["monotonicMs"])
' "$VOICE_STAGE1_RUN_HASH" "$VOICE_STAGE1_COMPARISON_HASH" \
        "$VOICE_STAGE1_TRANSPORT" "$expected_bytes" <<< "$lines")"; then
        printf '%s\n' "$attestation_ms"
        return 0
      else
        evidence_status=$?
        if (( evidence_status == 2 )); then
          fail "fixture attestation evidence mismatch"
          return 1
        fi
      fi
    fi
    continue_wait "$WAIT_TIMEOUT_SECONDS" \
      "timed out waiting for matching fixture attestation" || return 1
  done
}

wait_startup_pre_roll() {
  local started_at
  local now
  started_at="$(clock_now)"
  begin_wait "startup truth probe pre-roll"
  while true; do
    now="$(clock_now)"
    (( now >= started_at )) || fail "clock moved backward during startup truth probe pre-roll"
    if (( now - started_at >= STARTUP_PRE_ROLL_SECONDS + 1 )); then
      return 0
    fi
    continue_wait "$WAIT_TIMEOUT_SECONDS" \
      "timed out waiting for startup truth probe pre-roll" || return 1
  done
}

mark_boundary() {
  local boundary="${1,,}"
  control_broadcast MARK \
    --es boundary "$boundary" \
    --es run_hash "$VOICE_STAGE1_RUN_HASH"
  expect_control_data $'status=ok\naction=mark\nboundary='"$boundary"
}

request_route() {
  local boundary_ms
  local lines
  local evidence_status
  local expected_route="${VOICE_STAGE1_ROUTE^}"
  boundary_ms="$(latest_event_monotonic_ms CALL_ACTIVE)"
  control_broadcast ROUTE --es route "$VOICE_STAGE1_ROUTE"
  expect_control_data $'status=ok\naction=route\nroute='"$VOICE_STAGE1_ROUTE"$'\naccepted=true'
  begin_wait "fresh route_observed"
  while true; do
    if lines="$(adb_command exec-out run-as "$VOICE_STAGE1_PACKAGE" \
      grep -F '"route":' "$AUTOMATION_EVENT_PATH" 2>/dev/null)"; then
      validate_event_lines route_requested 0 "$lines"
      validate_event_lines route_observed 0 "$lines"
      if python3 -c '
import json, sys
boundary, expected = int(sys.argv[1]), sys.argv[2]
events = [json.loads(line) for line in sys.stdin if line.strip()]
matching_requests = [event for event in events if event["name"] == "route_requested" and
                     event["monotonicMs"] > boundary and event["route"] == expected]
requested = max(matching_requests, key=lambda event: event["monotonicMs"], default=None)
if requested is None:
    raise SystemExit(1)
observed = [event for event in events if event["name"] == "route_observed" and
            event["monotonicMs"] > requested["monotonicMs"]]
if any(event["route"] != expected for event in observed):
    raise SystemExit(2)
raise SystemExit(0 if any(event["route"] == expected for event in observed) else 1)
' "$boundary_ms" "$expected_route" <<< "$lines"; then
        return 0
      else
        evidence_status=$?
        if (( evidence_status == 2 )); then
          fail "conflicting route observation"
          return 1
        fi
      fi
    fi
    continue_wait "$WAIT_TIMEOUT_SECONDS" "timed out waiting for fresh route_observed" || return 1
  done
}

wait_lifecycle() {
  local expected="$1"
  local boundary_ms="$2"
  local lines
  local evidence_status
  local android_state
  begin_wait "fresh lifecycle_observed"
  while true; do
    if lines="$(adb_command exec-out run-as "$VOICE_STAGE1_PACKAGE" \
      grep -F '"name":"lifecycle_observed"' "$AUTOMATION_EVENT_PATH" 2>/dev/null)"; then
      validate_event_lines lifecycle_observed 0 "$lines"
      if python3 -c '
import json, sys
boundary, expected = int(sys.argv[1]), sys.argv[2]
events = [json.loads(line) for line in sys.stdin if line.strip() and
          json.loads(line)["monotonicMs"] > boundary]
if any(event["lifecycle"] != expected for event in events):
    raise SystemExit(2)
raise SystemExit(0 if any(event["lifecycle"] == expected for event in events) else 1)
' "$boundary_ms" "$expected" <<< "$lines"; then
        android_state="$(read_android_app_state)" || return 1
        if [[ "$android_state" != "$expected" ]]; then
          fail "lifecycle activity readback mismatch: Android=$android_state expected=$expected"
          return 1
        fi
        return 0
      else
        evidence_status=$?
        if (( evidence_status == 2 )); then
          fail "conflicting lifecycle observation"
          return 1
        fi
      fi
    fi
    continue_wait "$WAIT_TIMEOUT_SECONDS" "timed out waiting for fresh lifecycle_observed" || return 1
  done
}

fixture_broadcast() {
  local action="$1"
  shift
  local output
  local completed_output
  local result_code
  local raw_data
  output="$(adb_command shell am broadcast --user 0 \
    -n "$VOICE_STAGE1_PACKAGE/$FIXTURE_RECEIVER_CLASS" \
    -a "$action" "$@")" || fail "fixture broadcast failed"
  completed_output="$(printf '%s\n' "$output" | awk '
    capture { print; next }
    /^Broadcast completed:/ { capture=1; print }
  ')"
  if [[ ! "$completed_output" =~ ^Broadcast\ completed:\ result=([-0-9]+),\ data=\"(.*)\"$ ]]; then
    fail "fixture broadcast returned malformed output"
    return 1
  fi
  result_code="${BASH_REMATCH[1]}"
  raw_data="${BASH_REMATCH[2]}"
  FIXTURE_DATA="$(decode_broadcast_data "$raw_data")"
  [[ "$result_code" == "0" ]] || fail "fixture broadcast was rejected"
}

arm_capture_fixture() {
  if [[ "$STARTUP_TRUTH_PROBE" == "1" ]]; then
    fixture_broadcast "$FIXTURE_ARM_ACTION" \
      --es initial_path "$INJECTION_STARTUP_PATH" \
      --es staged_path "$INJECTION_PROMPT_PATH" \
      --ei chunk_bytes 3200 \
      --el chunk_delay_ms 100
  else
    fixture_broadcast "$FIXTURE_ARM_ACTION" \
      --es initial_path "$INJECTION_PROMPT_PATH" \
      --es staged_path "$INJECTION_INTERRUPT_PATH" \
      --ei chunk_bytes 3200 \
      --el chunk_delay_ms 100
  fi
  [[ "$FIXTURE_DATA" =~ ^status=ok$'\n'action=arm$'\n'token=(fixture-[1-9][0-9]*)$ ]] ||
    fail "fixture arm returned malformed data"
  FIXTURE_TOKEN="${BASH_REMATCH[1]}"
}

trigger_capture_fixture() {
  local app_relative_path="$1"
  fixture_broadcast "$FIXTURE_TRIGGER_ACTION" \
    --es token "$FIXTURE_TOKEN" \
    --es path "$app_relative_path"
  [[ "$FIXTURE_DATA" == $'status=ok\naction=trigger\naccepted=true' ]] ||
    fail "fixture trigger returned malformed data"
}

start_call() {
  START_ATTEMPTED=1
  adb_command shell am start-foreground-service \
    -n "$VOICE_STAGE1_PACKAGE/$SERVICE_CLASS" \
    -a "$CALL_START_ACTION" \
    --es conversationId "$VOICE_STAGE1_CONVERSATION_ID" \
    --es transport "$VOICE_STAGE1_TRANSPORT" \
    --es captureFixtureToken "$FIXTURE_TOKEN" >/dev/null
}

end_call() {
  END_ATTEMPTED=1
  adb_command shell am start-foreground-service \
    -n "$VOICE_STAGE1_PACKAGE/$SERVICE_CLASS" \
    -a "$CALL_END_ACTION" >/dev/null
}

wait_call_stopped() {
  local services
  begin_wait "call service stop"
  while true; do
    services="$(adb_command shell dumpsys activity services "$VOICE_STAGE1_PACKAGE")"
    if [[ "$services" != *"$SERVICE_CLASS"* ]]; then
      return 0
    fi
    continue_wait "$WAIT_TIMEOUT_SECONDS" "timed out waiting for call service to stop" || return 1
  done
}

wait_target_duration() {
  local started="$1"
  local now
  WAIT_LABEL="target duration"
  WAIT_STARTED="$started"
  WAIT_LAST="$started"
  WAIT_ATTEMPTS=0
  while true; do
    now="$(clock_now)"
    if (( now < WAIT_LAST )); then
      fail "clock moved backward while waiting for target duration"
      return 1
    fi
    WAIT_LAST="$now"
    if (( now - started >= VOICE_STAGE1_TARGET_SECONDS )); then
      return 0
    fi
    WAIT_ATTEMPTS=$((WAIT_ATTEMPTS + 1))
    if (( WAIT_ATTEMPTS >= MAX_WAIT_ATTEMPTS )); then
      fail "wait attempt limit reached for target duration"
      return 1
    fi
    sleep_poll
  done
}

perform_handover() {
  local wifi_restored_ms
  mark_boundary HANDOVER_STARTED
  adb_command shell svc data enable >/dev/null
  WIFI_RESTORE_STATE="not_attempted"
  adb_command shell svc wifi disable >/dev/null
  wait_event RECONNECT_STARTED
  wait_android_network cellular
  cross_check_network cellular
  mark_boundary HANDOVER_CELLULAR_OBSERVED
  WIFI_RESTORE_STATE="pending"
  adb_command shell svc wifi enable >/dev/null
  wait_android_network wifi
  cross_check_network wifi
  mark_boundary HANDOVER_WIFI_RESTORED
  WIFI_RESTORE_STATE="proven"
  wait_event RECONNECT_TRANSPORT_RESTORED
  wifi_restored_ms="$(latest_event_monotonic_ms NETWORK_OBSERVED)"
  wait_event_after PLAYBACK_WRITTEN "$wifi_restored_ms"
  wait_event HANDOVER_MEDIA_RESTORED
  wait_event RECONNECT_MEDIA_RESTORED
}

finalize_and_fetch() {
  local output_parent
  local temp_output=""
  local expected_route
  local expected_fixture_bytes
  local expected_startup_bytes=0
  output_parent="$(private_dirname "$VOICE_STAGE1_EVENT_OUTPUT")" ||
    fail "unable to fetch finalized automation events"
  private_mkdir "$output_parent" || fail "unable to fetch finalized automation events"

  FINALIZE_ATTEMPTED=1
  control_broadcast FINALIZE
  expect_control_data $'status=ok\naction=finalize'
  AUTOMATION_ACTIVE=0
  read_status
  [[ "$STATUS_RUN_STATE" == "finalized" ]] || fail "automation run did not finalize"
  [[ "$STATUS_RUN_HASH" == "$VOICE_STAGE1_RUN_HASH" ]] || fail "finalized run hash mismatch"
  [[ "$STATUS_COMPARISON_HASH" == "$VOICE_STAGE1_COMPARISON_HASH" ]] ||
    fail "finalized comparison hash mismatch"
  [[ "$STATUS_TRANSPORT" == "$VOICE_STAGE1_TRANSPORT" ]] || fail "finalized transport mismatch"

  expected_route="${VOICE_STAGE1_ROUTE^}"
  expected_fixture_bytes="$(private_fixture_size "$VOICE_STAGE1_PCM_PATH")" ||
    fail "unable to fetch finalized automation events"
  [[ "$expected_fixture_bytes" =~ ^[1-9][0-9]*$ ]] ||
    fail "unable to fetch finalized automation events"
  if [[ "$STARTUP_TRUTH_PROBE" == "1" ]]; then
    expected_startup_bytes="$(private_fixture_size "$VOICE_STAGE1_STARTUP_PCM_PATH")" ||
      fail "unable to fetch finalized automation events"
    [[ "$expected_startup_bytes" =~ ^[1-9][0-9]*$ ]] ||
      fail "unable to fetch finalized automation events"
  fi

  temp_output="$(private_mktemp "$output_parent/.voice-stage1-events.XXXXXX")" ||
    fail "unable to fetch finalized automation events"
  private_chmod "$temp_output" || {
    private_remove_temp "$temp_output"
    fail "unable to fetch finalized automation events"
  }
  if ! adb_command exec-out run-as "$VOICE_STAGE1_PACKAGE" cat "$AUTOMATION_EVENT_PATH" \
    >"$temp_output" 2>/dev/null; then
    private_remove_temp "$temp_output"
    fail "unable to fetch finalized automation events"
  fi
  [[ -s "$temp_output" ]] || {
    private_remove_temp "$temp_output"
    fail "finalized automation events are empty"
  }
  if [[ "$TRANSCRIPT_PROBE" == "1" ]] && ! fetch_voice_transcript_events; then
    private_remove_temp "$temp_output"
    fail "unable to collect sanitized transcript evidence"
    return 1
  fi
  local validation_output
  local validation_status=0
  if validation_output="$(python3 - "$temp_output" "$VOICE_STAGE1_RUN_HASH" "$VOICE_STAGE1_COMPARISON_HASH" \
    "$VOICE_STAGE1_TRANSPORT" "$expected_route" "$VOICE_STAGE1_APP_STATE" \
    "$VOICE_STAGE1_NETWORK" "$VOICE_STAGE1_LIFECYCLE" "$expected_fixture_bytes" \
    "$STARTUP_TRUTH_PROBE" "$expected_startup_bytes" 2>/dev/null <<'PY'
import json
import sys

path, run_hash, comparison_hash, transport, route, app_state, network_mode, lifecycle, expected_fixture_bytes, startup_truth_probe, expected_startup_bytes = sys.argv[1:]
expected_fixture_bytes = int(expected_fixture_bytes)
expected_startup_bytes = int(expected_startup_bytes)
MAX_GAP_MS = 250


def audio_interval(event):
    duration_us = event.get("audioWindowMicros")
    active = event.get("rmsActive")
    end_ms = event.get("monotonicMs")
    if (
        type(duration_us) is not int
        or duration_us <= 0
        or type(active) is not bool
        or type(end_ms) is not int
    ):
        raise ValueError("invalid LiveKit playback_written RMS window")
    duration_ms = (duration_us + 999) // 1000
    return end_ms - duration_ms, end_ms, active


def merge_active_offsets(intervals, attached_ms):
    merged = []
    for start_ms, end_ms in sorted(intervals):
        relative = [max(0, start_ms - attached_ms), end_ms - attached_ms]
        if merged and relative[0] <= merged[-1][1]:
            merged[-1][1] = max(merged[-1][1], relative[1])
        else:
            merged.append(relative)
    return merged


try:
    with open(path, encoding="utf-8") as handle:
        events = [json.loads(line) for line in handle if line.strip()]
except (OSError, ValueError) as error:
    raise SystemExit(f"invalid automation event JSONL: {error}")
if not events:
    raise SystemExit("automation event JSONL is empty")
for event in events:
    if event.get("runHash") != run_hash or event.get("comparisonHash") != comparison_hash:
        raise SystemExit("automation event hash mismatch")
    if event.get("requestedTransport") != transport:
        raise SystemExit("automation event requested transport mismatch")
    observed = event.get("observedTransport")
    if observed is not None and observed != transport:
        raise SystemExit("observed transport mismatch")

names = [event.get("name") for event in events]
probe_prompt_end_ms = None
probe_response_valid = False
if startup_truth_probe == "1":
    classification = "startup-indeterminate"
    prompt_overlap = False
    active_startup = []
    attached_ms = 0
    valid_intervals = []
    rms_schema_valid = True
    for event in events:
        if event.get("name") != "playback_written":
            continue
        try:
            valid_intervals.append(audio_interval(event))
        except ValueError:
            rms_schema_valid = False

    prompt_starts = [
        event for event in events
        if event.get("name") == "injection_started"
        and event.get("byteCount") == expected_fixture_bytes
    ]
    prompt_ends = [
        event for event in events
        if event.get("name") == "prompt_ended"
        and event.get("byteCount") == expected_fixture_bytes
    ]
    prompt_boundaries_valid = (
        len(prompt_starts) == 1
        and len(prompt_ends) == 1
        and type(prompt_starts[0].get("monotonicMs")) is int
        and type(prompt_ends[0].get("monotonicMs")) is int
        and prompt_starts[0]["monotonicMs"] < prompt_ends[0]["monotonicMs"]
    )
    if prompt_boundaries_valid:
        prompt_start_ms = prompt_starts[0]["monotonicMs"]
        probe_prompt_end_ms = prompt_ends[0]["monotonicMs"]
        prompt_overlap = any(
            active and start_ms <= probe_prompt_end_ms and end_ms >= prompt_start_ms
            for start_ms, end_ms, active in valid_intervals
        )
        probe_response_valid = any(
            active and start_ms > probe_prompt_end_ms
            for start_ms, _, active in valid_intervals
        )

        call_starts = [
            event for event in events
            if event.get("name") == "call_start_requested"
        ]
        attachment_events = [
            event for event in events
            if event.get("name") == "remote_track_attached"
        ]
        call_active_events = [
            event for event in events
            if event.get("name") == "call_active"
        ]
        detach_events = [
            event for event in events
            if event.get("name") == "remote_track_detached"
        ]
        lifecycle_schema_valid = all(
            type(event.get("monotonicMs")) is int
            for event in call_starts + attachment_events + call_active_events + detach_events
        )
        initial_starts = [
            event for event in events
            if event.get("name") == "injection_started"
            and event.get("byteCount") == expected_startup_bytes
        ]
        if call_starts and lifecycle_schema_valid:
            call_start_ms = min(event["monotonicMs"] for event in call_starts)
        else:
            call_start_ms = None
        detached_before_prompt = any(
            event["monotonicMs"] < prompt_start_ms
            for event in detach_events
            if lifecycle_schema_valid
        )
        ordering_valid = (
            rms_schema_valid
            and lifecycle_schema_valid
            and call_start_ms is not None
            and len(attachment_events) == 1
            and len(call_active_events) == 1
            and len(initial_starts) == 1
            and type(initial_starts[0].get("monotonicMs")) is int
            and not detached_before_prompt
        )
        if ordering_valid:
            attached_ms = attachment_events[0]["monotonicMs"]
            call_active_ms = call_active_events[0]["monotonicMs"]
            initial_start_ms = initial_starts[0]["monotonicMs"]
            ordering_valid = (
                call_start_ms <= call_active_ms < initial_start_ms < prompt_start_ms
                and call_start_ms <= attached_ms < prompt_start_ms
            )

        if ordering_valid:
            observation_intervals = sorted(
                (start_ms, end_ms, active)
                for start_ms, end_ms, active in valid_intervals
                if end_ms >= attached_ms and start_ms < prompt_start_ms
            )
            coverage_valid = bool(observation_intervals)
            coverage_end_ms = attached_ms
            for start_ms, end_ms, _ in observation_intervals:
                clipped_start_ms = max(start_ms, attached_ms)
                clipped_end_ms = min(end_ms, prompt_start_ms)
                if clipped_start_ms - coverage_end_ms > MAX_GAP_MS:
                    coverage_valid = False
                coverage_end_ms = max(coverage_end_ms, clipped_end_ms)
            if prompt_start_ms - coverage_end_ms > MAX_GAP_MS:
                coverage_valid = False

            active_observation = [
                (start_ms, end_ms)
                for start_ms, end_ms, active in observation_intervals
                if active
            ]
            active_straddles_boundary = any(
                start_ms < prompt_start_ms <= end_ms
                for start_ms, end_ms in active_observation
            )
            active_outside_startup = any(
                start_ms < attached_ms or end_ms >= prompt_start_ms
                for start_ms, end_ms in active_observation
            )
            if coverage_valid and not active_straddles_boundary and not active_outside_startup:
                active_startup = active_observation
                classification = (
                    "startup-audio-active" if active_startup else "startup-clean"
                )

    report = {
        "version": 1,
        "classification": classification,
        "promptOverlap": prompt_overlap,
        "activeIntervalsMs": (
            merge_active_offsets(active_startup, attached_ms)
            if classification == "startup-audio-active"
            else []
        ),
    }
    print("stage1.startup_probe=" + json.dumps(report, separators=(",", ":")))
    if classification != "startup-clean" or prompt_overlap:
        raise SystemExit(2)

required_names = [
    "run_prepared", "call_start_requested", "call_active", "call_stopped",
    "route_requested", "route_observed", "lifecycle_requested", "lifecycle_observed",
    "prompt_ended", "capture_attested", "run_finalized",
]
if startup_truth_probe != "1":
    required_names.extend(["playback_active", "remote_audio_first_non_silent"])
for required in required_names:
    if required not in names:
        raise SystemExit(f"missing required automation event: {required}")
attestations = [event for event in events if event.get("name") == "capture_attested"]
if any(event.get("captureSource") != "fixture" or event.get("micBytes") != 0
       for event in attestations):
    raise SystemExit("microphone contamination attested")
if startup_truth_probe == "1":
    startup_prompt_ends = [event for event in events
                           if event.get("name") == "prompt_ended" and
                           event.get("byteCount") == expected_startup_bytes]
    if len(startup_prompt_ends) != 1:
        raise SystemExit("startup fixture completion is missing or duplicated")
    startup_prompt_end = startup_prompt_ends[0]
    if not any(event.get("fixtureBytes") == expected_startup_bytes and
               event.get("monotonicMs", 0) > startup_prompt_end["monotonicMs"]
               for event in attestations):
        raise SystemExit("startup fixture capture attestation mismatch")
    staged_prompt_ends = [event for event in events
                          if event.get("name") == "prompt_ended" and
                          event.get("byteCount") == expected_fixture_bytes and
                          event.get("monotonicMs", 0) > startup_prompt_end["monotonicMs"]]
    if len(staged_prompt_ends) != 1:
        raise SystemExit("staged prompt completion is missing or duplicated")
    staged_prompt_end = staged_prompt_ends[0]
    if not any(event.get("fixtureBytes") == expected_fixture_bytes and
               event.get("monotonicMs", 0) > staged_prompt_end["monotonicMs"]
               for event in attestations):
        raise SystemExit("staged prompt capture attestation mismatch")
else:
    if (not attestations or attestations[0].get("fixtureBytes") != expected_fixture_bytes):
        raise SystemExit("capture source attestation mismatch")
    prompt_end_ms = min(event["monotonicMs"] for event in events
                        if event.get("name") == "prompt_ended")
    first_output_ms = min(event["monotonicMs"] for event in events
                          if event.get("name") == "remote_audio_first_non_silent")
    if first_output_ms - prompt_end_ms < 0:
        raise SystemExit("output audio began before prompt end")
if not any(event.get("name") == "call_active" and event.get("observedTransport") == transport
           for event in events):
    raise SystemExit("observed transport mismatch")
call_starts = [index for index, event in enumerate(events)
               if event.get("name") == "call_start_requested"]
call_active = [index for index, event in enumerate(events)
               if event.get("name") == "call_active"]
call_stops = [index for index, event in enumerate(events)
              if event.get("name") == "call_stopped" and event.get("succeeded") is True]
if (not call_starts or not call_active or len(call_stops) != 1 or
        max(call_starts) >= min(call_active) or max(call_active) >= call_stops[0] or
        call_stops[0] >= names.index("run_finalized")):
    raise SystemExit("call lifecycle evidence is incomplete or misordered")
route_requested_index = next((index for index in range(len(events) - 1, -1, -1)
                              if events[index].get("name") == "route_requested" and
                              events[index].get("route") == route), -1)
if route_requested_index < 0:
    raise SystemExit("route request mismatch")
route_observations = [(index, event) for index, event in enumerate(events)
                      if index > route_requested_index and event.get("name") == "route_observed"]
if any(event.get("route") != route for _, event in route_observations):
    raise SystemExit("conflicting route observation")
route_observed_index = next((index for index, event in route_observations if event.get("route") == route), -1)
if route_observed_index < 0:
    raise SystemExit("route observation mismatch")
if not any(event.get("name") == "lifecycle_requested" and event.get("lifecycle") == app_state
           for event in events):
    raise SystemExit("lifecycle request mismatch")
lifecycle_observations = [event for index, event in enumerate(events)
                          if index > route_observed_index and event.get("name") == "lifecycle_observed"]
if any(event.get("lifecycle") != app_state for event in lifecycle_observations):
    raise SystemExit("conflicting lifecycle observation")
if not any(event.get("lifecycle") == app_state for event in lifecycle_observations):
    raise SystemExit("lifecycle observation mismatch")

observed_networks = [event.get("network") for event in events if event.get("name") == "network_observed"]
if network_mode == "stable_wifi":
    if not observed_networks or any(value != "wifi" for value in observed_networks):
        raise SystemExit("stable Wi-Fi observation mismatch")
elif network_mode == "cellular":
    if not observed_networks or any(value != "cellular" for value in observed_networks):
        raise SystemExit("cellular observation mismatch")
else:
    cursor = 0
    for value in observed_networks:
        if cursor < 3 and value == ("wifi", "cellular", "wifi")[cursor]:
            cursor += 1
    if cursor != 3:
        raise SystemExit("handover network sequence mismatch")
    for marker in ("reconnect_started", "reconnect_transport_restored", "handover_started",
                   "handover_cellular_observed", "handover_wifi_restored",
                   "handover_media_restored", "reconnect_media_restored"):
        if names.count(marker) != 1:
            raise SystemExit(f"missing or duplicate handover marker: {marker}")
    reconnect_index = names.index("reconnect_started")
    transport_restored_index = names.index("reconnect_transport_restored")
    handover_index = names.index("handover_started")
    cellular_index = next((index for index, event in enumerate(events)
                           if event.get("name") == "network_observed" and
                           event.get("network") == "cellular"), -1)
    if cellular_index <= handover_index:
        raise SystemExit("cellular observation preceded handover marker")
    restored_wifi_index = next((index for index, event in enumerate(events[cellular_index + 1:], cellular_index + 1)
                                if event.get("name") == "network_observed" and
                                event.get("network") == "wifi"), -1)
    media_index = next((index for index, event in enumerate(events)
                        if event.get("name") == "playback_written" and index > restored_wifi_index), -1)
    cellular_marker_index = names.index("handover_cellular_observed")
    wifi_marker_index = names.index("handover_wifi_restored")
    handover_media_index = names.index("handover_media_restored")
    reconnect_media_index = names.index("reconnect_media_restored")
    if restored_wifi_index < 0 or media_index < 0:
        raise SystemExit("missing post-handover media restoration")
    if not (
        handover_index < reconnect_index < cellular_index <= cellular_marker_index < restored_wifi_index
        and restored_wifi_index <= wifi_marker_index < media_index
        and reconnect_index < transport_restored_index < media_index
        and media_index <= handover_media_index < reconnect_media_index < call_stops[0]
    ):
        raise SystemExit("handover restoration evidence is misordered")
    restored_epoch = events[media_index].get("playbackEpoch")
    if (events[handover_media_index].get("playbackEpoch") != restored_epoch or
            events[reconnect_media_index].get("playbackEpoch") != restored_epoch):
        raise SystemExit("handover restoration playback epoch mismatch")
if lifecycle == "interruption":
    if "interrupt_started" not in names or "playback_stopped" not in names:
        raise SystemExit("interruption evidence is incomplete")
if startup_truth_probe == "1" and not probe_response_valid:
    raise SystemExit("missing RMS-backed response playback after prompt completion")
PY
  )"; then
    validation_status=0
  else
    validation_status=$?
  fi

  if [[ "$STARTUP_TRUTH_PROBE" == "1" ]]; then
    private_move "$temp_output" "$VOICE_STAGE1_EVENT_OUTPUT" || {
      private_remove_temp "$temp_output"
      fail "unable to fetch finalized automation events"
    }
    temp_output=""
    if [[ -n "$validation_output" ]]; then
      printf '%s\n' "$validation_output"
    fi
    if [[ "$validation_status" -eq 2 ]]; then
      fail "startup probe did not produce a valid vertical slice"
    elif [[ "$validation_status" -ne 0 ]]; then
      fail "finalized automation event validation failed"
    fi
  else
    if [[ "$validation_status" -ne 0 ]]; then
      private_remove_temp "$temp_output"
      fail "finalized automation event validation failed"
    fi
    private_move "$temp_output" "$VOICE_STAGE1_EVENT_OUTPUT" || {
      private_remove_temp "$temp_output"
      fail "unable to fetch finalized automation events"
    }
    temp_output=""
  fi
}

run_preflight() {
  local preflight_payload=$'stage1-preflight\n'
  require_env VOICE_STAGE1_SERIAL
  require_env VOICE_STAGE1_PACKAGE
  require_expected_serial
  [[ "$VOICE_STAGE1_PACKAGE" =~ ^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+$ ]] || fail "invalid package name"
  validate_positive_integer VOICE_STAGE1_ADB_TIMEOUT_SECONDS "$ADB_TIMEOUT_SECONDS"
  validate_positive_integer VOICE_STAGE1_WAIT_TIMEOUT_SECONDS "$WAIT_TIMEOUT_SECONDS"
  validate_positive_integer VOICE_STAGE1_MAX_WAIT_ATTEMPTS "$MAX_WAIT_ATTEMPTS"
  validate_nonnegative_number VOICE_STAGE1_POLL_SECONDS "$POLL_SECONDS"
  require_command adb
  require_command timeout
  require_command awk
  require_command tr
  select_device
  require_package

  adb_command shell run-as "$VOICE_STAGE1_PACKAGE" id >/dev/null || fail "run-as is unavailable"
  local wifi_usage
  local wifi_usage_status=0
  local data_usage
  local data_usage_status=0
  local android_network
  wifi_usage="$(adb_command shell svc wifi)" || wifi_usage_status=$?
  [[ "$wifi_usage_status" -eq 0 || "$wifi_usage_status" -eq 1 ]] || fail "Wi-Fi control readback failed"
  grep -Fxq 'usage: svc wifi [enable|disable]' <<<"$wifi_usage" || fail "Wi-Fi control is unavailable"
  data_usage="$(adb_command shell svc data)" || data_usage_status=$?
  [[ "$data_usage_status" -eq 0 || "$data_usage_status" -eq 1 ]] || fail "cellular control readback failed"
  grep -Fxq 'usage: svc data [enable|disable]' <<<"$data_usage" || fail "cellular control is unavailable"
  android_network="$(read_android_network)"
  read_idle_status "$android_network"

  trap preflight_on_exit EXIT
  PREFLIGHT_STAGE_STATE="pending"
  printf '%s' "$preflight_payload" | stage_stream "$PRIVATE_FIXTURE_DIR/.preflight" >/dev/null
  wait_staged_size "$PRIVATE_FIXTURE_DIR/.preflight" "${#preflight_payload}" ||
    fail "private fixture staging verification failed"
  remove_preflight_fixture_once
  trap - EXIT

  printf 'stage1.device=%s\n' "$VOICE_STAGE1_SERIAL"
  printf 'stage1.run_as=ready\n'
  printf 'stage1.wifi_control=ready\n'
  printf 'stage1.cellular_control=ready\n'
  printf 'stage1.connectivity_readback=ready\n'
  printf 'stage1.automation_receiver=ready\n'
  printf 'stage1.fixture_staging=ready\n'
}

validate_normal_inputs() {
  local required
  local startup_fixture_bytes
  local prompt_fixture_bytes
  for required in \
    VOICE_STAGE1_SERIAL \
    VOICE_STAGE1_PACKAGE \
    VOICE_STAGE1_CONVERSATION_ID \
    VOICE_STAGE1_TRANSPORT \
    VOICE_STAGE1_PCM_PATH \
    VOICE_STAGE1_INTERRUPT_PCM_PATH \
    VOICE_STAGE1_ROUTE \
    VOICE_STAGE1_APP_STATE \
    VOICE_STAGE1_NETWORK \
    VOICE_STAGE1_LIFECYCLE \
    VOICE_STAGE1_TARGET_SECONDS \
    VOICE_STAGE1_RUN_HASH \
    VOICE_STAGE1_COMPARISON_HASH \
    VOICE_STAGE1_EVENT_OUTPUT; do
    require_env "$required"
  done
  [[ "$VOICE_STAGE1_PACKAGE" =~ ^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+$ ]] || fail "invalid package name"
  [[ "$VOICE_STAGE1_TRANSPORT" =~ ^(direct_gemini|livekit_experimental)$ ]] || fail "invalid transport"
  [[ "$VOICE_STAGE1_ROUTE" =~ ^(speaker|earpiece)$ ]] || fail "invalid route"
  [[ "$VOICE_STAGE1_APP_STATE" =~ ^(foreground|background)$ ]] || fail "invalid app state"
  [[ "$VOICE_STAGE1_NETWORK" =~ ^(stable_wifi|cellular|wifi_cellular_wifi)$ ]] || fail "invalid network"
  [[ "$VOICE_STAGE1_LIFECYCLE" =~ ^(steady|interruption|reconnect)$ ]] || fail "invalid lifecycle"
  [[ "$PROMPT_TRIGGER" == "initial_fixture" ]] || fail "invalid prompt trigger"
  [[ -f "$VOICE_STAGE1_PCM_PATH" && ! -L "$VOICE_STAGE1_PCM_PATH" && -s "$VOICE_STAGE1_PCM_PATH" ]] ||
    fail "VOICE_STAGE1_PCM_PATH must be a nonempty regular file"
  [[ -f "$VOICE_STAGE1_INTERRUPT_PCM_PATH" && ! -L "$VOICE_STAGE1_INTERRUPT_PCM_PATH" &&
     -s "$VOICE_STAGE1_INTERRUPT_PCM_PATH" ]] ||
    fail "VOICE_STAGE1_INTERRUPT_PCM_PATH must be a nonempty regular file"
  [[ "$STARTUP_TRUTH_PROBE" =~ ^(0|1)$ ]] ||
    fail "VOICE_STAGE1_STARTUP_TRUTH_PROBE must be 0 or 1"
  [[ "$TRANSCRIPT_PROBE" =~ ^(0|1)$ ]] ||
    fail "VOICE_STAGE1_TRANSCRIPT_PROBE must be 0 or 1"
  if [[ "$TRANSCRIPT_PROBE" == "1" ]]; then
    [[ "$STARTUP_TRUTH_PROBE" == "1" ]] ||
      fail "transcript probe requires startup truth probe"
    [[ "$VOICE_STAGE1_TRANSPORT" == "livekit_experimental" ]] ||
      fail "transcript probe requires livekit_experimental transport"
    [[ "$VOICE_STAGE1_LIFECYCLE" == "steady" ]] ||
      fail "transcript probe requires steady lifecycle"
  fi
  if [[ "$STARTUP_TRUTH_PROBE" == "1" ]]; then
    [[ "$VOICE_STAGE1_TRANSPORT" == "livekit_experimental" ]] ||
      fail "startup truth probe requires livekit_experimental transport"
    [[ "$VOICE_STAGE1_LIFECYCLE" == "steady" ]] ||
      fail "startup truth probe requires steady lifecycle"
    require_env VOICE_STAGE1_STARTUP_PCM_PATH
    [[ -f "$VOICE_STAGE1_STARTUP_PCM_PATH" && ! -L "$VOICE_STAGE1_STARTUP_PCM_PATH" &&
       -s "$VOICE_STAGE1_STARTUP_PCM_PATH" ]] ||
      fail "VOICE_STAGE1_STARTUP_PCM_PATH must be a nonempty regular file"
    require_command wc
    startup_fixture_bytes="$(wc -c < "$VOICE_STAGE1_STARTUP_PCM_PATH" | tr -d '[:space:]')"
    prompt_fixture_bytes="$(wc -c < "$VOICE_STAGE1_PCM_PATH" | tr -d '[:space:]')"
    [[ "$startup_fixture_bytes" != "$prompt_fixture_bytes" ]] ||
      fail "startup and prompt fixtures must have different byte counts"
  fi
  if [[ "$TRANSCRIPT_PROBE" == "1" ]]; then
    require_env VOICE_STAGE1_TRANSCRIPT_EVENT_OUTPUT
  fi
  require_command python3
  validate_evidence_output_paths
  validate_positive_integer VOICE_STAGE1_TARGET_SECONDS "$VOICE_STAGE1_TARGET_SECONDS"
  validate_positive_integer VOICE_STAGE1_ADB_TIMEOUT_SECONDS "$ADB_TIMEOUT_SECONDS"
  validate_positive_integer VOICE_STAGE1_WAIT_TIMEOUT_SECONDS "$WAIT_TIMEOUT_SECONDS"
  validate_positive_integer VOICE_STAGE1_MAX_WAIT_ATTEMPTS "$MAX_WAIT_ATTEMPTS"
  validate_nonnegative_number VOICE_STAGE1_POLL_SECONDS "$POLL_SECONDS"
  [[ "$VOICE_STAGE1_RUN_HASH" =~ ^sha256:[0-9a-f]{64}$ ]] || fail "invalid run hash"
  [[ "$VOICE_STAGE1_COMPARISON_HASH" =~ ^sha256:[0-9a-f]{64}$ ]] || fail "invalid comparison hash"
  require_expected_serial

  require_command adb
  require_command timeout
  require_command awk
  require_command tr
  require_command wc
  require_command python3
  require_command mktemp
  require_command sleep
  require_command flock
  if [[ -n "$CLOCK_COMMAND" ]]; then
    [[ -x "$CLOCK_COMMAND" ]] || fail "VOICE_STAGE1_CLOCK_COMMAND must be executable"
  fi
}

run_scenario() {
  local initial_network
  local run_started_at
  local lifecycle_boundary_ms
  local expected_prompt_bytes
  local expected_startup_bytes
  local initial_attestation_ms
  AUTOMATION_EVENT_PATH="$(app_artifact_path \
    "$APP_ARTIFACT_BASE_DIR/${VOICE_STAGE1_RUN_HASH#sha256:}" automation-events.jsonl)"

  acquire_run_lock
  select_device
  require_package
  trap on_exit EXIT

  if [[ "$VOICE_STAGE1_APP_STATE" == "foreground" ]]; then
    adb_command shell am start -W -a android.intent.action.MAIN \
      -c android.intent.category.HOME >/dev/null
  else
    adb_command shell am start -W \
      -n "$VOICE_STAGE1_PACKAGE/$ROUTE_ACTIVITY_CLASS" >/dev/null
  fi

  case "$VOICE_STAGE1_NETWORK" in
    stable_wifi|wifi_cellular_wifi)
      adb_command shell svc wifi enable >/dev/null
      initial_network=wifi
      ;;
    cellular)
      adb_command shell svc data enable >/dev/null
      WIFI_RESTORE_STATE="not_attempted"
      adb_command shell svc wifi disable >/dev/null
      initial_network=cellular
      ;;
  esac
  wait_android_network "$initial_network"
  read_idle_status "$initial_network"

  AUTOMATION_ACTIVE=1
  control_broadcast PREPARE \
    --es run_hash "$VOICE_STAGE1_RUN_HASH" \
    --es comparison_hash "$VOICE_STAGE1_COMPARISON_HASH" \
    --es transport "$VOICE_STAGE1_TRANSPORT" \
    --es lifecycle "$VOICE_STAGE1_APP_STATE"
  expect_control_data $'status=ok\naction=prepare'

  FIXTURES_STAGED=1
  if [[ "$STARTUP_TRUTH_PROBE" == "1" ]]; then
    stage_file "$VOICE_STAGE1_STARTUP_PCM_PATH" "$PRIVATE_STARTUP_PATH"
    stage_file "$VOICE_STAGE1_PCM_PATH" "$PRIVATE_PROMPT_PATH"
  else
    stage_file "$VOICE_STAGE1_PCM_PATH" "$PRIVATE_PROMPT_PATH"
    stage_file "$VOICE_STAGE1_INTERRUPT_PCM_PATH" "$PRIVATE_INTERRUPT_PATH"
  fi
  arm_capture_fixture

  if [[ "$TRANSCRIPT_PROBE" == "1" ]]; then
    snapshot_initial_voice_trace || fail "unable to collect sanitized transcript evidence"
  fi
  run_started_at="$(clock_now)"
  start_call
  wait_event CALL_ACTIVE 1
  cross_check_network "$initial_network"
  request_route

  lifecycle_boundary_ms="$(latest_run_event_monotonic_ms)"
  if [[ "$VOICE_STAGE1_APP_STATE" == "background" ]]; then
    adb_command shell input keyevent HOME >/dev/null
  else
    adb_command shell am start -W \
      -n "$VOICE_STAGE1_PACKAGE/$ROUTE_ACTIVITY_CLASS" >/dev/null
  fi
  wait_lifecycle "$VOICE_STAGE1_APP_STATE" "$lifecycle_boundary_ms"

  if [[ "$STARTUP_TRUTH_PROBE" == "1" ]]; then
    expected_startup_bytes="$(wc -c < "$VOICE_STAGE1_STARTUP_PCM_PATH" | tr -d '[:space:]')"
    expected_prompt_bytes="$(wc -c < "$VOICE_STAGE1_PCM_PATH" | tr -d '[:space:]')"
    initial_attestation_ms="$(wait_matching_fixture_attestation "$expected_startup_bytes")"
    [[ "$initial_attestation_ms" =~ ^[0-9]+$ ]] || fail "invalid initial fixture attestation timestamp"
    wait_startup_pre_roll
    trigger_capture_fixture "$INJECTION_PROMPT_PATH"
    wait_matching_fixture_attestation "$expected_prompt_bytes" >/dev/null
  else
    wait_event PROMPT_ENDED
    wait_event PLAYBACK_ACTIVE
  fi

  case "$VOICE_STAGE1_LIFECYCLE" in
    steady)
      ;;
    interruption)
      wait_event PLAYBACK_ACTIVE
      mark_boundary INTERRUPT_STARTED
      trigger_capture_fixture "$INJECTION_INTERRUPT_PATH"
      wait_event PLAYBACK_STOPPED
      ;;
    reconnect)
      wait_event PLAYBACK_ACTIVE
      perform_handover
      ;;
    *)
      fail "invalid lifecycle"
      ;;
  esac

  wait_target_duration "$run_started_at"
  if [[ "$VOICE_STAGE1_NETWORK" == "cellular" ]]; then
    cross_check_network cellular
  else
    cross_check_network wifi
  fi
  end_call
  wait_call_stopped
  finalize_and_fetch
  cleanup_resources
  trap - EXIT
  printf 'stage1.run=complete\n'
}

case "$#" in
  0)
    validate_normal_inputs
    run_scenario
    ;;
  1)
    case "$1" in
      --preflight)
        run_preflight
        ;;
      --classify-transcript)
        classify_transcript_evidence
        ;;
      *)
        fail "usage: voice-agent-stage1-e2e.sh [--preflight|--classify-transcript]"
        ;;
    esac
    ;;
  *)
    fail "usage: voice-agent-stage1-e2e.sh [--preflight|--classify-transcript]"
    ;;
esac
