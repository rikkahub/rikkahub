#!/usr/bin/env bash
set -euo pipefail
umask 077
set +x

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
READY="$ROOT_DIR/scripts/adb-device-ready.sh"
ARTIFACT_HELPERS="$ROOT_DIR/scripts/voice-agent-e2e-artifacts.sh"
PACKAGE_EXPECTED='me.rerere.rikkahub.debug'
CONTROL_RECEIVER='me.rerere.rikkahub.voiceagent.debug.VoiceAutomationControlReceiver'
FIXTURE_RECEIVER='me.rerere.rikkahub.voiceagent.debug.VoiceCaptureFixtureDebugReceiver'
SERVICE_CLASS='me.rerere.rikkahub.voiceagent.VoiceAgentCallService'
CONTROL_ACTION_PREFIX='me.rerere.rikkahub.voiceagent.automation'
FIXTURE_ARM_ACTION='me.rerere.rikkahub.debug.voiceagent.ARM_CAPTURE_FIXTURE'
FIXTURE_STAGE_ACTION='me.rerere.rikkahub.debug.voiceagent.STAGE_CAPTURE_FIXTURE'
FIXTURE_TRIGGER_ACTION='me.rerere.rikkahub.debug.voiceagent.TRIGGER_CAPTURE_FIXTURE'
CALL_START_ACTION='me.rerere.rikkahub.voiceagent.action.START'
CALL_END_ACTION='me.rerere.rikkahub.voiceagent.action.END'
APP_ARTIFACT_ROOT='no_backup/voice-e2e'
LATEST_TRACE_PATH="$APP_ARTIFACT_ROOT/latest-trace-id.txt"
TRANSPORT_EXPECTED='livekit_experimental'
FIXTURE_CHUNK_BYTES='3200'
FIXTURE_CHUNK_DELAY_MS='100'

# Only the path joiner is consumed. This preserves the existing artifact helper
# contract without changing its behavior.
source "$ARTIFACT_HELPERS"

SERIAL=''
PACKAGE=''
RUN_HASH=''
COMPARISON_HASH=''
CONVERSATION_ID=''
FIXTURE_TOKEN=''
TRACE_ID=''
FIXTURE_SNAPSHOT=''
FIXTURE_SIZE=''
FIXTURE_HASH=''
REMOTE_FIXTURE_DIR=''
REMOTE_FIXTURE_PATH=''
LOCAL_TEMP_DIR=''
STATE_PUBLICATION_TEMP=''
ERROR_REPORTED=0
START_CLEANUP_NEEDED=0
START_PREPARE_ATTEMPTED=0
START_CALL_ATTEMPTED=0
START_FIXTURE_DIR_CREATED=0
declare -a OWNED_TEMP_FILES=()
declare -A PARSED=()

die() {
  ERROR_REPORTED=1
  printf 'voice-step.error=%s\n' "$1" >&2
  exit 1
}

adb_read() {
  timeout --signal=TERM --kill-after=2s "${VOICE_STEP_ADB_TIMEOUT_SECONDS:-10}s" adb -s "$SERIAL" "$@"
}

adb_global_read() {
  timeout --signal=TERM --kill-after=2s "${VOICE_STEP_ADB_TIMEOUT_SECONDS:-10}s" adb "$@"
}

register_temp_file() {
  OWNED_TEMP_FILES+=("$1")
}

forget_temp_file() {
  local target="$1"
  local -a retained=()
  local path
  for path in "${OWNED_TEMP_FILES[@]}"; do
    [[ "$path" == "$target" ]] || retained+=("$path")
  done
  OWNED_TEMP_FILES=("${retained[@]}")
}

ensure_local_temp_dir() {
  if [[ -z "$LOCAL_TEMP_DIR" ]]; then
    LOCAL_TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/voice-real-room-step.XXXXXX" 2>/dev/null)" ||
      die 'local temporary storage failed'
    chmod 700 -- "$LOCAL_TEMP_DIR" 2>/dev/null || die 'local temporary storage failed'
  fi
}

cleanup_local_temps() {
  local cleanup_status=0
  local path
  for path in "${OWNED_TEMP_FILES[@]}"; do
    if [[ -e "$path" || -L "$path" ]]; then
      rm -f -- "$path" 2>/dev/null || cleanup_status=1
    fi
  done
  OWNED_TEMP_FILES=()
  if [[ -n "$LOCAL_TEMP_DIR" && ( -e "$LOCAL_TEMP_DIR" || -L "$LOCAL_TEMP_DIR" ) ]]; then
    rmdir -- "$LOCAL_TEMP_DIR" 2>/dev/null || cleanup_status=1
  fi
  LOCAL_TEMP_DIR=''
  return "$cleanup_status"
}

raw_start_cleanup() {
  local cleanup_status=0
  if (( START_CALL_ATTEMPTED == 1 )); then
    adb_read shell am start-foreground-service \
      -n "$PACKAGE/$SERVICE_CLASS" \
      -a "$CALL_END_ACTION" </dev/null >/dev/null 2>&1 || cleanup_status=1
    START_CALL_ATTEMPTED=0
  fi
  if (( START_PREPARE_ATTEMPTED == 1 )); then
    adb_read shell am broadcast --user 0 \
      -n "$PACKAGE/$CONTROL_RECEIVER" \
      -a "$CONTROL_ACTION_PREFIX.FINALIZE" </dev/null >/dev/null 2>&1 || cleanup_status=1
    START_PREPARE_ATTEMPTED=0
  fi
  if (( START_FIXTURE_DIR_CREATED == 1 )); then
    adb_read shell run-as "$PACKAGE" rm -rf -- "$REMOTE_FIXTURE_DIR" \
      </dev/null >/dev/null 2>&1 || cleanup_status=1
    START_FIXTURE_DIR_CREATED=0
  fi
  return "$cleanup_status"
}

on_exit() {
  local status=$?
  local cleanup_status=0
  trap - EXIT HUP INT TERM
  set +e
  if (( status != 0 && START_CLEANUP_NEEDED == 1 )); then
    raw_start_cleanup || cleanup_status=1
  fi
  cleanup_local_temps || cleanup_status=1
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

on_signal() {
  die 'interrupted'
}

trap on_exit EXIT
trap on_signal HUP INT TERM

require_command() {
  command -v "$1" >/dev/null 2>&1 || die 'required command unavailable'
}

validate_positive_integer() {
  [[ "$1" =~ ^[1-9][0-9]*$ ]] || die 'invalid timeout configuration'
}

validate_runtime() {
  validate_positive_integer "${VOICE_STEP_ADB_TIMEOUT_SECONDS:-10}"
  validate_positive_integer "${VOICE_STEP_WAIT_TIMEOUT_SECONDS:-120}"
  validate_positive_integer "${VOICE_STEP_MAX_WAIT_ATTEMPTS:-120}"
  [[ "${VOICE_STEP_POLL_SECONDS:-1}" =~ ^([0-9]+)(\.[0-9]+)?$ ]] ||
    die 'invalid timeout configuration'
  require_command timeout
  require_command adb
  require_command python3
  require_command mktemp
  require_command chmod
  require_command ln
  require_command rm
  require_command dirname
  require_command awk
  require_command tr
  require_command sleep
  require_command sha256sum
  require_command stat
  require_command cmp
  [[ -x "$READY" ]] || die 'device-ready helper unavailable'
}

validate_identifier() {
  [[ "$1" =~ ^[A-Za-z0-9_-]{1,128}$ ]] || die "invalid $2"
}

validate_hash() {
  [[ "$1" =~ ^sha256:[0-9a-f]{64}$ ]] || die "invalid $2"
}

validate_package() {
  [[ "$1" == "$PACKAGE_EXPECTED" ]] || die 'invalid package'
}

validate_absent_destination() {
  python3 - "$1" 2>/dev/null <<'PY'
import os
import stat
import sys

path = sys.argv[1]
if not path or not os.path.isabs(path) or os.path.normpath(path) != path:
    raise SystemExit(1)
parent = os.path.dirname(path)
name = os.path.basename(path)
if not name or name in {".", ".."} or os.path.realpath(parent) != parent:
    raise SystemExit(1)
metadata = os.lstat(parent)
if not stat.S_ISDIR(metadata.st_mode) or stat.S_ISLNK(metadata.st_mode):
    raise SystemExit(1)
try:
    os.lstat(path)
except FileNotFoundError:
    pass
else:
    raise SystemExit(1)
PY
}

validate_distinct_destinations() {
  python3 - "$@" 2>/dev/null <<'PY'
import os
import sys

paths = [os.path.realpath(os.path.dirname(path)) + os.sep + os.path.basename(path) for path in sys.argv[1:]]
if len(paths) != len(set(paths)):
    raise SystemExit(1)
PY
}

snapshot_fixture() {
  local source_path="$1"
  local metadata_file
  ensure_local_temp_dir
  FIXTURE_SNAPSHOT="$LOCAL_TEMP_DIR/fixture.pcm"
  metadata_file="$LOCAL_TEMP_DIR/fixture.metadata"
  register_temp_file "$FIXTURE_SNAPSHOT"
  register_temp_file "$metadata_file"
  if ! python3 - "$source_path" "$FIXTURE_SNAPSHOT" "$metadata_file" 2>/dev/null <<'PY'
import hashlib
import os
import stat
import sys

source_path, snapshot_path, metadata_path = sys.argv[1:]
if not os.path.isabs(source_path) or os.path.normpath(source_path) != source_path:
    raise SystemExit(1)
if os.path.realpath(source_path) != source_path:
    raise SystemExit(1)
before = os.lstat(source_path)
if stat.S_ISLNK(before.st_mode) or not stat.S_ISREG(before.st_mode):
    raise SystemExit(1)
if stat.S_IMODE(before.st_mode) != 0o600 or before.st_size <= 0:
    raise SystemExit(1)
flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
descriptor = os.open(source_path, flags)
try:
    opened = os.fstat(descriptor)
    if (opened.st_dev, opened.st_ino, opened.st_mode, opened.st_size, opened.st_mtime_ns, opened.st_ctime_ns) != (
        before.st_dev,
        before.st_ino,
        before.st_mode,
        before.st_size,
        before.st_mtime_ns,
        before.st_ctime_ns,
    ):
        raise SystemExit(1)
    digest = hashlib.sha256()
    output_descriptor = os.open(snapshot_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(output_descriptor, "wb") as output:
        while True:
            block = os.read(descriptor, 65536)
            if not block:
                break
            output.write(block)
            digest.update(block)
        output.flush()
        os.fsync(output.fileno())
finally:
    os.close(descriptor)
after = os.lstat(source_path)
if (after.st_dev, after.st_ino, after.st_mode, after.st_size, after.st_mtime_ns, after.st_ctime_ns) != (
    before.st_dev,
    before.st_ino,
    before.st_mode,
    before.st_size,
    before.st_mtime_ns,
    before.st_ctime_ns,
):
    raise SystemExit(1)
with open(metadata_path, "x", encoding="ascii") as metadata:
    metadata.write(str(before.st_size) + "\n")
    metadata.write("sha256:" + digest.hexdigest() + "\n")
PY
  then
    die 'invalid fixture'
  fi
  mapfile -t fixture_metadata < "$metadata_file"
  [[ "${#fixture_metadata[@]}" == 2 ]] || die 'invalid fixture'
  FIXTURE_SIZE="${fixture_metadata[0]}"
  FIXTURE_HASH="${fixture_metadata[1]}"
  [[ "$FIXTURE_SIZE" =~ ^[1-9][0-9]*$ ]] || die 'invalid fixture'
  validate_hash "$FIXTURE_HASH" 'fixture hash'
}

decode_state() {
  local state_path="$1"
  local values_file
  local -a values=()
  ensure_local_temp_dir
  values_file="$LOCAL_TEMP_DIR/state.values"
  register_temp_file "$values_file"
  if ! python3 - "$state_path" >"$values_file" 2>/dev/null <<'PY'
import json
import os
import stat
import sys

path = sys.argv[1]
keys = [
    "schemaVersion",
    "serial",
    "package",
    "conversationId",
    "runHash",
    "comparisonHash",
    "fixtureToken",
    "traceId",
    "transport",
]
if not os.path.isabs(path) or os.path.normpath(path) != path or os.path.realpath(path) != path:
    raise SystemExit(1)
before = os.lstat(path)
if stat.S_ISLNK(before.st_mode) or not stat.S_ISREG(before.st_mode) or stat.S_IMODE(before.st_mode) != 0o600:
    raise SystemExit(1)
flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
descriptor = os.open(path, flags)
try:
    opened = os.fstat(descriptor)
    if (opened.st_dev, opened.st_ino, opened.st_mode, opened.st_size, opened.st_mtime_ns, opened.st_ctime_ns) != (
        before.st_dev,
        before.st_ino,
        before.st_mode,
        before.st_size,
        before.st_mtime_ns,
        before.st_ctime_ns,
    ):
        raise SystemExit(1)
    if opened.st_size <= 0 or opened.st_size > 65536:
        raise SystemExit(1)
    with os.fdopen(descriptor, "r", encoding="utf-8") as handle:
        descriptor = -1
        payload = json.load(handle, object_pairs_hook=lambda pairs: pairs)
        after_open = os.fstat(handle.fileno())
finally:
    if descriptor >= 0:
        os.close(descriptor)
after = os.lstat(path)
if (after.st_dev, after.st_ino, after.st_mode, after.st_size, after.st_mtime_ns, after.st_ctime_ns) != (
    before.st_dev,
    before.st_ino,
    before.st_mode,
    before.st_size,
    before.st_mtime_ns,
    before.st_ctime_ns,
) or (after_open.st_dev, after_open.st_ino, after_open.st_mode, after_open.st_size, after_open.st_mtime_ns, after_open.st_ctime_ns) != (
    before.st_dev,
    before.st_ino,
    before.st_mode,
    before.st_size,
    before.st_mtime_ns,
    before.st_ctime_ns,
):
    raise SystemExit(1)
if not isinstance(payload, list) or [key for key, _ in payload] != keys:
    raise SystemExit(1)
state = dict(payload)
if type(state["schemaVersion"]) is not int or state["schemaVersion"] != 1:
    raise SystemExit(1)
if any(type(state[key]) is not str for key in keys[1:]):
    raise SystemExit(1)
for key in keys:
    value = str(state[key]).encode("utf-8")
    sys.stdout.buffer.write(value + b"\0")
PY
  then
    die 'invalid state'
  fi
  mapfile -d '' -t values < "$values_file"
  [[ "${#values[@]}" == 9 && "${values[0]}" == 1 ]] || die 'invalid state'
  SERIAL="${values[1]}"
  PACKAGE="${values[2]}"
  CONVERSATION_ID="${values[3]}"
  RUN_HASH="${values[4]}"
  COMPARISON_HASH="${values[5]}"
  FIXTURE_TOKEN="${values[6]}"
  TRACE_ID="${values[7]}"
  [[ "${values[8]}" == "$TRANSPORT_EXPECTED" ]] || die 'invalid state'
  validate_identifier "$SERIAL" 'serial'
  validate_package "$PACKAGE"
  validate_identifier "$CONVERSATION_ID" 'conversation id'
  validate_hash "$RUN_HASH" 'run hash'
  validate_hash "$COMPARISON_HASH" 'comparison hash'
  validate_identifier "$FIXTURE_TOKEN" 'fixture token'
  validate_identifier "$TRACE_ID" 'trace id'
}

parse_options() {
  local allowed="$1"
  shift
  PARSED=()
  local option
  while (( $# > 0 )); do
    option="$1"
    case " $allowed " in
      *" $option "*) ;;
      *) die 'unknown option' ;;
    esac
    [[ -z "${PARSED[$option]+present}" ]] || die 'repeated option'
    (( $# >= 2 )) || die 'missing option value'
    PARSED["$option"]="$2"
    shift 2
  done
}

require_options() {
  local option
  for option in "$@"; do
    [[ -n "${PARSED[$option]+present}" && -n "${PARSED[$option]}" ]] || die 'missing required option'
  done
}

decode_broadcast_data() {
  local raw="$1"
  raw="${raw//\\n/$'\n'}"
  raw="${raw//\\r/$'\r'}"
  raw="${raw//\\\\/\\}"
  printf '%s' "$raw"
}

BROADCAST_DATA=''
broadcast_read() {
  local receiver="$1"
  local action="$2"
  shift 2
  local output
  local completed
  local result_code
  local raw_data
  if ! output="$(adb_read shell am broadcast --user 0 \
      -n "$PACKAGE/$receiver" -a "$action" "$@" 2>/dev/null)"; then
    die 'ADB command failed'
  fi
  completed="$(printf '%s\n' "$output" | awk '/^Broadcast completed:/ { count++; line=$0 } END { if (count == 1) print line }')"
  if [[ ! "$completed" =~ ^Broadcast\ completed:\ result=([-0-9]+),\ data=\"(.*)\"$ ]]; then
    die 'unexpected receiver response'
  fi
  result_code="${BASH_REMATCH[1]}"
  raw_data="${BASH_REMATCH[2]}"
  [[ "$result_code" == 0 ]] || die 'receiver rejected request'
  BROADCAST_DATA="$(decode_broadcast_data "$raw_data")"
}

STATUS_RUN_STATE=''
STATUS_RUN_HASH=''
STATUS_COMPARISON_HASH=''
STATUS_TRANSPORT=''
STATUS_EVENT_COUNT=''
STATUS_NETWORK=''
STATUS_VALIDATED=''
parse_status_data() {
  local -a lines=()
  mapfile -t lines <<< "$BROADCAST_DATA"
  [[ "${#lines[@]}" == 9 ]] || return 1
  [[ "${lines[0]}" == 'status=ok' && "${lines[1]}" == 'action=status' ]] ||
    return 1
  [[ "${lines[2]}" == run_state=* && "${lines[3]}" == run_hash=* &&
     "${lines[4]}" == comparison_hash=* && "${lines[5]}" == requested_transport=* &&
     "${lines[6]}" == event_count=* && "${lines[7]}" == network=* &&
     "${lines[8]}" == validated=* ]] || return 1
  STATUS_RUN_STATE="${lines[2]#run_state=}"
  STATUS_RUN_HASH="${lines[3]#run_hash=}"
  STATUS_COMPARISON_HASH="${lines[4]#comparison_hash=}"
  STATUS_TRANSPORT="${lines[5]#requested_transport=}"
  STATUS_EVENT_COUNT="${lines[6]#event_count=}"
  STATUS_NETWORK="${lines[7]#network=}"
  STATUS_VALIDATED="${lines[8]#validated=}"
  [[ "$STATUS_RUN_STATE" =~ ^(idle|active|finalized)$ ]] || return 1
  [[ "$STATUS_EVENT_COUNT" =~ ^[0-9]+$ ]] || return 1
  [[ "$STATUS_NETWORK" =~ ^(wifi|cellular|none)$ ]] || return 1
  [[ "$STATUS_VALIDATED" =~ ^(true|false)$ ]] || return 1
}

read_status() {
  broadcast_read "$CONTROL_RECEIVER" "$CONTROL_ACTION_PREFIX.STATUS"
  parse_status_data || die 'unexpected status response'
}

ensure_device_and_package() {
  local devices_output
  local authorized_count
  local selected_count
  local qemu
  local hardware
  local package_path
  if ! devices_output="$(adb_global_read devices -l 2>/dev/null)"; then
    die 'ADB command failed'
  fi
  authorized_count="$(printf '%s\n' "$devices_output" | awk '$2 == "device" { count++ } END { print count + 0 }')"
  selected_count="$(printf '%s\n' "$devices_output" | awk -v serial="$SERIAL" '$1 == serial && $2 == "device" { count++ } END { print count + 0 }')"
  [[ "$authorized_count" == 1 && "$selected_count" == 1 ]] || die 'device is not uniquely authorized'
  if ! VOICE_AGENT_E2E_SERIAL="$SERIAL" \
      ADB_DEVICE_READY_TIMEOUT_SECONDS="${VOICE_STEP_ADB_TIMEOUT_SECONDS:-10}" \
      "$READY" "$SERIAL" >/dev/null 2>&1; then
    die 'device is not ready'
  fi
  qemu="$(adb_read shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r[:space:]')" ||
    die 'ADB command failed'
  hardware="$(adb_read shell getprop ro.hardware 2>/dev/null | tr -d '\r[:space:]')" ||
    die 'ADB command failed'
  hardware="${hardware,,}"
  [[ "$qemu" == '' || "$qemu" == 0 || "$qemu" == false ]] || die 'physical device required'
  case "$hardware" in
    *ranchu*|*goldfish*|*cuttlefish*) die 'physical device required' ;;
  esac
  package_path="$(adb_read shell pm path "$PACKAGE" 2>/dev/null)" || die 'debug package unavailable'
  [[ "$package_path" == package:* ]] || die 'debug package unavailable'
  adb_read shell run-as "$PACKAGE" id </dev/null >/dev/null 2>&1 || die 'run-as unavailable'
}

verify_package_contract() {
  local package_dump
  local protected_probe
  package_dump="$(adb_read shell dumpsys package "$PACKAGE" 2>/dev/null)" || die 'package readback failed'
  [[ "$package_dump" == *'DEBUGGABLE'* &&
     "$package_dump" == *'VOICE_AGENT_LIVEKIT_EXPERIMENT_ENABLED=true'* &&
     "$package_dump" == *"$PACKAGE/$SERVICE_CLASS"* &&
     "$package_dump" == *"$PACKAGE/$CONTROL_RECEIVER"* &&
     "$package_dump" == *"$PACKAGE/$FIXTURE_RECEIVER"* ]] || die 'package contract mismatch'
  protected_probe="$(adb_read shell run-as "$PACKAGE" sh -c '
: voice-step-protected-root
root=$(readlink -f files) || exit 1
[ -d "$root" ] || exit 1
case "$root" in
  /data/user/[0-9]*/me.rerere.rikkahub.debug/files|/data/data/me.rerere.rikkahub.debug/files) ;;
  *) exit 1 ;;
esac
printf ready
' 2>/dev/null)" || die 'protected path unavailable'
  [[ "$protected_probe" == ready ]] || die 'protected path unavailable'
}

read_trace_pointer() {
  local probe
  local value
  probe="$(adb_read shell run-as "$PACKAGE" sh -c '
: voice-step-trace-probe
if [ -L "$1" ]; then
  printf invalid
elif [ -e "$1" ]; then
  [ -f "$1" ] || { printf invalid; exit; }
  printf present
else
  printf absent
fi
' sh "$LATEST_TRACE_PATH" 2>/dev/null)" || die 'trace readback failed'
  case "$probe" in
    absent)
      TRACE_POINTER_PRESENT=0
      TRACE_POINTER_VALUE=''
      ;;
    present)
      value="$(adb_read exec-out run-as "$PACKAGE" cat "$LATEST_TRACE_PATH" 2>/dev/null)" ||
        die 'trace readback failed'
      value="${value//$'\r'/}"
      value="${value//$'\n'/}"
      validate_identifier "$value" 'trace id'
      TRACE_POINTER_PRESENT=1
      TRACE_POINTER_VALUE="$value"
      ;;
    *) die 'trace readback failed' ;;
  esac
}

stage_snapshot() {
  adb_read shell run-as "$PACKAGE" mkdir -p "$REMOTE_FIXTURE_DIR" \
    </dev/null >/dev/null 2>&1 || die 'fixture staging failed'
  START_FIXTURE_DIR_CREATED=1
  adb_read shell run-as "$PACKAGE" sh -c 'umask 077; cat > "$1"' sh "$REMOTE_FIXTURE_PATH" \
    < "$FIXTURE_SNAPSHOT" >/dev/null 2>&1 || die 'fixture staging failed'
  local metadata
  metadata="$(adb_read shell run-as "$PACKAGE" sh -c '
: voice-step-fixture-metadata
[ -f "$1" ] && [ ! -L "$1" ] && [ "$(stat -c %a "$1")" = 600 ] || exit 1
printf "%s\nsha256:%s\n" "$(stat -c %s "$1")" "$(sha256sum "$1" | cut -d " " -f 1)"
' sh "$REMOTE_FIXTURE_PATH" 2>/dev/null)" || die 'fixture staging verification failed'
  [[ "$metadata" == "$FIXTURE_SIZE"$'\n'"$FIXTURE_HASH" ]] || die 'fixture staging verification failed'
}

stream_and_verify_snapshot() {
  adb_read shell run-as "$PACKAGE" sh -c 'umask 077; cat > "$1"' sh "$REMOTE_FIXTURE_PATH" \
    < "$FIXTURE_SNAPSHOT" >/dev/null 2>&1 || die 'fixture staging failed'
  local metadata
  metadata="$(adb_read shell run-as "$PACKAGE" sh -c '
: voice-step-fixture-metadata
[ -f "$1" ] && [ ! -L "$1" ] && [ "$(stat -c %a "$1")" = 600 ] || exit 1
printf "%s\nsha256:%s\n" "$(stat -c %s "$1")" "$(sha256sum "$1" | cut -d " " -f 1)"
' sh "$REMOTE_FIXTURE_PATH" 2>/dev/null)" || die 'fixture staging verification failed'
  [[ "$metadata" == "$FIXTURE_SIZE"$'\n'"$FIXTURE_HASH" ]] || die 'fixture staging verification failed'
}

inject_fixture_once() {
  local role="$1"
  REMOTE_FIXTURE_DIR="files/voice-real-room/${RUN_HASH#sha256:}"
  REMOTE_FIXTURE_PATH="$REMOTE_FIXTURE_DIR/${role}-${FIXTURE_HASH#sha256:}.pcm"
  stream_and_verify_snapshot
  broadcast_read "$FIXTURE_RECEIVER" "$FIXTURE_STAGE_ACTION" \
    --es token "$FIXTURE_TOKEN" \
    --es path "$REMOTE_FIXTURE_PATH" \
    --ei chunk_bytes "$FIXTURE_CHUNK_BYTES" \
    --el chunk_delay_ms "$FIXTURE_CHUNK_DELAY_MS"
  [[ "$BROADCAST_DATA" == $'status=ok\naction=stage\naccepted=true' ]] ||
    die 'unexpected receiver response'
  broadcast_read "$FIXTURE_RECEIVER" "$FIXTURE_TRIGGER_ACTION" \
    --es token "$FIXTURE_TOKEN" \
    --es path "$REMOTE_FIXTURE_PATH"
  [[ "$BROADCAST_DATA" == $'status=ok\naction=trigger\naccepted=true' ]] ||
    die 'unexpected receiver response'
}

read_call_service_active() {
  local services
  services="$(adb_read shell dumpsys activity services "$PACKAGE" 2>/dev/null)" ||
    die 'call service readback failed'
  [[ "$services" == *"$PACKAGE/$SERVICE_CLASS"* ]] || die 'call service is not active'
}

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
  presence="$(adb_read shell run-as "$PACKAGE" sh -c '
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
  adb_read exec-out run-as "$PACKAGE" cat "$sanitized_path" \
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
  validate_runtime
  snapshot_fixture "$fixture_path"
  inject_fixture_once "$role"
  cleanup_local_temps || die 'cleanup failed'
  printf '%s\n' \
    'voice-step.status=ok' \
    'voice-step.operation=inject' \
    'voice-step.fixture=accepted'
}

run_interrupt() {
  local fixture_path="$1"
  validate_runtime
  snapshot_fixture "$fixture_path"
  broadcast_read "$CONTROL_RECEIVER" "$CONTROL_ACTION_PREFIX.MARK" \
    --es boundary interrupt_started \
    --es run_hash "$RUN_HASH"
  [[ "$BROADCAST_DATA" == $'status=ok\naction=mark\nboundary=interrupt_started' ]] ||
    die 'unexpected receiver response'
  inject_fixture_once interruption
  cleanup_local_temps || die 'cleanup failed'
  printf '%s\n' \
    'voice-step.status=ok' \
    'voice-step.operation=interrupt' \
    'voice-step.fixture=accepted'
}

run_status_operation() {
  validate_runtime
  read_status
  [[ "$STATUS_RUN_STATE" == active && "$STATUS_RUN_HASH" == "$RUN_HASH" &&
     "$STATUS_COMPARISON_HASH" == "$COMPARISON_HASH" &&
     "$STATUS_TRANSPORT" == "$TRANSPORT_EXPECTED" ]] || die 'status binding mismatch'
  read_call_service_active
  read_status_artifacts
  cleanup_local_temps || die 'cleanup failed'
  printf '%s\n' \
    'voice-step.status=ok' \
    'voice-step.operation=status' \
    "voice-step.run_state=$STATUS_RUN_STATE" \
    'voice-step.call_state=active' \
    "voice-step.event_count=$STATUS_EVENT_COUNT" \
    "voice-step.network=$STATUS_NETWORK" \
    "voice-step.validated=$STATUS_VALIDATED" \
    'voice-step.voice_events=present' \
    "voice-step.job_accepted_count=$STATUS_JOB_ACCEPTED_COUNT" \
    "voice-step.job_terminal_count=$STATUS_JOB_TERMINAL_COUNT" \
    "voice-step.delivery_blocked_count=$STATUS_DELIVERY_BLOCKED_COUNT" \
    "voice-step.delivery_announced_count=$STATUS_DELIVERY_ANNOUNCED_COUNT"
}

run_finalize() {
  validate_runtime
  broadcast_read "$CONTROL_RECEIVER" "$CONTROL_ACTION_PREFIX.FINALIZE"
  [[ "$BROADCAST_DATA" == $'status=ok\naction=finalize' ]] || die 'unexpected receiver response'
  read_status
  [[ "$STATUS_RUN_STATE" == finalized && "$STATUS_RUN_HASH" == "$RUN_HASH" &&
     "$STATUS_COMPARISON_HASH" == "$COMPARISON_HASH" &&
     "$STATUS_TRANSPORT" == "$TRANSPORT_EXPECTED" ]] || die 'finalized status mismatch'
  printf '%s\n' \
    'voice-step.status=ok' \
    'voice-step.operation=finalize' \
    'voice-step.automation=finalized'
}

STABLE_CAPTURE_TEMP=''
read_source_metadata() {
  local source_path="$1"
  local metadata
  metadata="$(adb_read shell run-as "$PACKAGE" sh -c '
: voice-step-source-metadata
[ -f "$1" ] && [ ! -L "$1" ] && [ "$(stat -c %a "$1")" = 600 ] || exit 1
printf "regular:600:%s:%s:%s:%s\n" \
  "$(stat -c %s "$1")" "$(stat -c %i "$1")" "$(stat -c %Y "$1")" "$(stat -c %Z "$1")"
' sh "$source_path" 2>/dev/null)" || die 'artifact source unavailable'
  [[ "$metadata" =~ ^regular:600:[1-9][0-9]*:[0-9]+:-?[0-9]+:-?[0-9]+$ ]] ||
    die 'artifact source invalid'
  printf '%s' "$metadata"
}

read_stable_artifact() {
  local source_path="$1"
  local destination="$2"
  local parent
  local first
  local second
  local metadata_before
  local metadata_between
  local metadata_after
  local source_size
  parent="$(dirname -- "$destination")" || die 'capture temporary storage failed'
  first="$(mktemp "$parent/.voice-step-capture.XXXXXX" 2>/dev/null)" ||
    die 'capture temporary storage failed'
  second="$(mktemp "$parent/.voice-step-capture.XXXXXX" 2>/dev/null)" || {
    register_temp_file "$first"
    die 'capture temporary storage failed'
  }
  register_temp_file "$first"
  register_temp_file "$second"
  chmod 600 -- "$first" "$second" 2>/dev/null || die 'capture temporary storage failed'
  metadata_before="$(read_source_metadata "$source_path")"
  adb_read exec-out run-as "$PACKAGE" cat "$source_path" >"$first" 2>/dev/null ||
    die 'artifact read failed'
  metadata_between="$(read_source_metadata "$source_path")"
  adb_read exec-out run-as "$PACKAGE" cat "$source_path" >"$second" 2>/dev/null ||
    die 'artifact read failed'
  metadata_after="$(read_source_metadata "$source_path")"
  [[ "$metadata_before" == "$metadata_between" && "$metadata_before" == "$metadata_after" ]] ||
    die 'artifact source changed'
  source_size="$(printf '%s' "$metadata_before" | awk -F: '{print $3}')"
  [[ "$(stat -c %s "$first" 2>/dev/null)" == "$source_size" &&
     "$(stat -c %s "$second" 2>/dev/null)" == "$source_size" ]] ||
    die 'artifact source changed'
  cmp -s -- "$first" "$second" || die 'artifact source changed'
  python3 - "$first" 2>/dev/null <<'PY' || die 'artifact source invalid'
import os
import stat
import sys

path = sys.argv[1]
metadata = os.lstat(path)
if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISREG(metadata.st_mode):
    raise SystemExit(1)
if stat.S_IMODE(metadata.st_mode) != 0o600 or metadata.st_size <= 0:
    raise SystemExit(1)
with open(path, "rb") as handle:
    content = handle.read()
if len(content) != metadata.st_size or not content.endswith(b"\n"):
    raise SystemExit(1)
PY
  rm -- "$second" 2>/dev/null || die 'capture temporary cleanup failed'
  forget_temp_file "$second"
  STABLE_CAPTURE_TEMP="$first"
}

publish_owned_temp() {
  local temporary="$1"
  local destination="$2"
  local expected_size
  local expected_hash
  expected_size="$(stat -c %s "$temporary" 2>/dev/null)" || die 'publication failed'
  expected_hash="$(sha256sum "$temporary" 2>/dev/null | awk '{print $1}')" || die 'publication failed'
  [[ "$expected_size" =~ ^[1-9][0-9]*$ && "$expected_hash" =~ ^[0-9a-f]{64}$ ]] ||
    die 'publication failed'
  ln -T -- "$temporary" "$destination" 2>/dev/null || die 'publication failed'
  if ! python3 - "$temporary" "$destination" "$expected_size" "$expected_hash" 2>/dev/null <<'PY'
import hashlib
import os
import stat
import sys

temporary, destination, expected_size, expected_hash = sys.argv[1:]
expected_size = int(expected_size)
temporary_stat = os.lstat(temporary)
destination_stat = os.lstat(destination)
if (
    stat.S_ISLNK(destination_stat.st_mode)
    or not stat.S_ISREG(destination_stat.st_mode)
    or stat.S_IMODE(destination_stat.st_mode) != 0o600
    or destination_stat.st_size != expected_size
    or (temporary_stat.st_dev, temporary_stat.st_ino) != (destination_stat.st_dev, destination_stat.st_ino)
):
    raise SystemExit(1)
with open(destination, "rb") as handle:
    content = handle.read()
if len(content) != expected_size or hashlib.sha256(content).hexdigest() != expected_hash:
    raise SystemExit(1)
PY
  then
    die 'publication failed'
  fi
  rm -- "$temporary" 2>/dev/null || die 'publication temporary cleanup failed'
  forget_temp_file "$temporary"
  if ! python3 - "$destination" "$expected_size" "$expected_hash" 2>/dev/null <<'PY'
import hashlib
import os
import stat
import sys

destination, expected_size, expected_hash = sys.argv[1:]
metadata = os.lstat(destination)
if (
    stat.S_ISLNK(metadata.st_mode)
    or not stat.S_ISREG(metadata.st_mode)
    or stat.S_IMODE(metadata.st_mode) != 0o600
    or metadata.st_nlink != 1
    or metadata.st_size != int(expected_size)
):
    raise SystemExit(1)
with open(destination, "rb") as handle:
    content = handle.read()
if len(content) != metadata.st_size or hashlib.sha256(content).hexdigest() != expected_hash:
    raise SystemExit(1)
PY
  then
    die 'publication failed'
  fi
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
  validate_runtime
  read_status
  [[ "$STATUS_RUN_STATE" == finalized && "$STATUS_RUN_HASH" == "$RUN_HASH" &&
     "$STATUS_COMPARISON_HASH" == "$COMPARISON_HASH" &&
     "$STATUS_TRANSPORT" == "$TRANSPORT_EXPECTED" ]] || die 'finalized status mismatch'
  automation_source="$(app_artifact_path "$APP_ARTIFACT_ROOT/${RUN_HASH#sha256:}" automation-events.jsonl)"
  private_source="$(app_artifact_path "$APP_ARTIFACT_ROOT/$TRACE_ID" voice-experience-private.ndjson)"
  sanitized_source="$(app_artifact_path "$APP_ARTIFACT_ROOT/$TRACE_ID" voice-experience-events.ndjson)"
  read_stable_artifact "$automation_source" "$automation_output"
  automation_temp="$STABLE_CAPTURE_TEMP"
  read_stable_artifact "$private_source" "$private_output"
  private_temp="$STABLE_CAPTURE_TEMP"
  read_stable_artifact "$sanitized_source" "$sanitized_output"
  sanitized_temp="$STABLE_CAPTURE_TEMP"
  read_status
  [[ "$STATUS_RUN_STATE" == finalized && "$STATUS_RUN_HASH" == "$RUN_HASH" &&
     "$STATUS_COMPARISON_HASH" == "$COMPARISON_HASH" &&
     "$STATUS_TRANSPORT" == "$TRANSPORT_EXPECTED" ]] || die 'finalized status mismatch'
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

try_read_end_status() {
  local output
  local completed
  local result_code
  local raw_data
  if ! output="$(adb_read shell am broadcast --user 0 \
      -n "$PACKAGE/$CONTROL_RECEIVER" -a "$CONTROL_ACTION_PREFIX.STATUS" 2>/dev/null)"; then
    return 10
  fi
  completed="$(printf '%s\n' "$output" | awk '/^Broadcast completed:/ { count++; line=$0 } END { if (count == 1) print line }')"
  if [[ ! "$completed" =~ ^Broadcast\ completed:\ result=([-0-9]+),\ data=\"(.*)\"$ ]]; then
    return 20
  fi
  result_code="${BASH_REMATCH[1]}"
  raw_data="${BASH_REMATCH[2]}"
  [[ "$result_code" == 0 ]] || return 20
  BROADCAST_DATA="$(decode_broadcast_data "$raw_data")"
  parse_status_data || return 20
  [[ "$STATUS_RUN_STATE" == finalized && "$STATUS_RUN_HASH" == "$RUN_HASH" &&
     "$STATUS_COMPARISON_HASH" == "$COMPARISON_HASH" &&
     "$STATUS_TRANSPORT" == "$TRANSPORT_EXPECTED" ]] || return 20
}

probe_end_service() {
  local result
  if ! result="$(adb_read shell sh -c '
: voice-step-service-status
dump=$(dumpsys activity services "$2") || exit 1
count=$(printf "%s\n" "$dump" | grep -F -c "$1")
case "$count" in
  0) printf stopped ;;
  1) printf active ;;
  *) printf invalid ;;
esac
' sh "$PACKAGE/$SERVICE_CLASS" "$PACKAGE" 2>/dev/null)"; then
    return 10
  fi
  case "$result" in
    active|stopped)
      END_SERVICE_STATE="$result"
      return 0
      ;;
    *) return 20 ;;
  esac
}

wait_end_service() {
  local attempt=0
  local started=$SECONDS
  local probe_status
  while (( attempt < ${VOICE_STEP_MAX_WAIT_ATTEMPTS:-120} )); do
    attempt=$((attempt + 1))
    if probe_end_service; then
      probe_status=0
    else
      probe_status=$?
    fi
    (( probe_status == 0 )) || return "$probe_status"
    [[ "$END_SERVICE_STATE" == stopped ]] && return 0
    if (( SECONDS - started >= ${VOICE_STEP_WAIT_TIMEOUT_SECONDS:-120} )); then
      break
    fi
    sleep "${VOICE_STEP_POLL_SECONDS:-1}"
  done
  return 30
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
  local wait_status
  local status_readback
  local run_fixture_dir="files/voice-real-room/${RUN_HASH#sha256:}"
  validate_runtime
  if ! adb_read shell am start-foreground-service \
    -n "$PACKAGE/$SERVICE_CLASS" \
    -a "$CALL_END_ACTION" </dev/null >/dev/null 2>&1; then
    complete_end_outcome "$cleanup_output" infrastructure_interruption false false false
    return
  fi
  if wait_end_service; then
    wait_status=0
  else
    wait_status=$?
  fi
  case "$wait_status" in
    10)
      complete_end_outcome "$cleanup_output" infrastructure_interruption false false false
      return
      ;;
    20)
      die 'ambiguous cleanup readback'
      ;;
    30)
      if try_read_end_status; then
        status_readback=0
      else
        status_readback=$?
      fi
      case "$status_readback" in
        0) complete_end_outcome "$cleanup_output" product_failure false false true ;;
        10) complete_end_outcome "$cleanup_output" infrastructure_interruption false false false ;;
        *) die 'ambiguous cleanup readback' ;;
      esac
      return
      ;;
    0) ;;
    *) die 'ambiguous cleanup readback' ;;
  esac
  if ! adb_read shell run-as "$PACKAGE" rm -rf -- "$run_fixture_dir" \
    </dev/null >/dev/null 2>&1; then
    complete_end_outcome "$cleanup_output" infrastructure_interruption true false false
    return
  fi
  if try_read_end_status; then
    status_readback=0
  else
    status_readback=$?
  fi
  case "$status_readback" in
    0) complete_end_outcome "$cleanup_output" complete true true true ;;
    10) complete_end_outcome "$cleanup_output" infrastructure_interruption true true false ;;
    *) die 'ambiguous cleanup readback' ;;
  esac
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
    if adb_read exec-out run-as "$PACKAGE" cat "$events_path" \
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

publish_state() {
  local destination="$1"
  local parent
  local expected_hash
  parent="$(dirname -- "$destination")" || die 'state publication failed'
  STATE_PUBLICATION_TEMP="$(mktemp "$parent/.voice-step-state.XXXXXX" 2>/dev/null)" ||
    die 'state publication failed'
  register_temp_file "$STATE_PUBLICATION_TEMP"
  chmod 600 -- "$STATE_PUBLICATION_TEMP" 2>/dev/null || die 'state publication failed'
  if ! python3 - "$STATE_PUBLICATION_TEMP" "$SERIAL" "$PACKAGE" "$CONVERSATION_ID" \
    "$RUN_HASH" "$COMPARISON_HASH" "$FIXTURE_TOKEN" "$TRACE_ID" 2>/dev/null <<'PY'
import json
import os
import sys

path, serial, package, conversation, run_hash, comparison_hash, token, trace = sys.argv[1:]
payload = {
    "schemaVersion": 1,
    "serial": serial,
    "package": package,
    "conversationId": conversation,
    "runHash": run_hash,
    "comparisonHash": comparison_hash,
    "fixtureToken": token,
    "traceId": trace,
    "transport": "livekit_experimental",
}
flags = os.O_WRONLY | os.O_TRUNC | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
descriptor = os.open(path, flags)
with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
    json.dump(payload, handle, separators=(",", ":"))
    handle.write("\n")
    handle.flush()
    os.fsync(handle.fileno())
PY
  then
    die 'state publication failed'
  fi
  [[ -s "$STATE_PUBLICATION_TEMP" ]] || die 'state publication failed'
  expected_hash="$(sha256sum "$STATE_PUBLICATION_TEMP" 2>/dev/null | awk '{print $1}')" ||
    die 'state publication failed'
  ln -T -- "$STATE_PUBLICATION_TEMP" "$destination" 2>/dev/null || die 'state publication failed'
  if ! python3 - "$STATE_PUBLICATION_TEMP" "$destination" "$expected_hash" 2>/dev/null <<'PY'
import hashlib
import os
import stat
import sys

temporary, destination, expected_hash = sys.argv[1:]
temp_stat = os.lstat(temporary)
dest_stat = os.lstat(destination)
if (
    stat.S_ISLNK(dest_stat.st_mode)
    or not stat.S_ISREG(dest_stat.st_mode)
    or stat.S_IMODE(dest_stat.st_mode) != 0o600
    or (temp_stat.st_dev, temp_stat.st_ino) != (dest_stat.st_dev, dest_stat.st_ino)
):
    raise SystemExit(1)
with open(destination, "rb") as handle:
    content = handle.read()
if hashlib.sha256(content).hexdigest() != expected_hash or len(content) != dest_stat.st_size:
    raise SystemExit(1)
PY
  then
    die 'state publication failed'
  fi
  rm -- "$STATE_PUBLICATION_TEMP" 2>/dev/null || die 'state publication failed'
  forget_temp_file "$STATE_PUBLICATION_TEMP"
  STATE_PUBLICATION_TEMP=''
  if ! python3 - "$destination" 2>/dev/null <<'PY'
import os
import stat
import sys

metadata = os.lstat(sys.argv[1])
if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISREG(metadata.st_mode):
    raise SystemExit(1)
if stat.S_IMODE(metadata.st_mode) != 0o600 or metadata.st_nlink != 1:
    raise SystemExit(1)
PY
  then
    die 'state publication failed'
  fi
}

run_preflight() {
  validate_runtime
  ensure_device_and_package
  verify_package_contract
  read_status
  [[ "$STATUS_RUN_STATE" == idle || "$STATUS_RUN_STATE" == finalized ]] ||
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
  validate_runtime
  snapshot_fixture "$fixture_path"
  REMOTE_FIXTURE_DIR="files/voice-real-room/${RUN_HASH#sha256:}"
  REMOTE_FIXTURE_PATH="$REMOTE_FIXTURE_DIR/request-${FIXTURE_HASH#sha256:}.pcm"
  ensure_device_and_package
  verify_package_contract
  read_status
  [[ "$STATUS_RUN_STATE" == idle || "$STATUS_RUN_STATE" == finalized ]] ||
    die 'automation is not ready'
  read_trace_pointer
  old_trace_present="$TRACE_POINTER_PRESENT"
  old_trace_value="$TRACE_POINTER_VALUE"

  START_CLEANUP_NEEDED=1
  stage_snapshot
  START_PREPARE_ATTEMPTED=1
  broadcast_read "$CONTROL_RECEIVER" "$CONTROL_ACTION_PREFIX.PREPARE" \
    --es run_hash "$RUN_HASH" \
    --es comparison_hash "$COMPARISON_HASH" \
    --es transport "$TRANSPORT_EXPECTED" \
    --es lifecycle foreground
  [[ "$BROADCAST_DATA" == $'status=ok\naction=prepare' ]] || die 'unexpected receiver response'

  broadcast_read "$FIXTURE_RECEIVER" "$FIXTURE_ARM_ACTION" \
    --es initial_path "$REMOTE_FIXTURE_PATH" \
    --ei chunk_bytes "$FIXTURE_CHUNK_BYTES" \
    --el chunk_delay_ms "$FIXTURE_CHUNK_DELAY_MS"
  if [[ "$BROADCAST_DATA" =~ ^status=ok$'\n'action=arm$'\n'token=(fixture-[1-9][0-9]*)$ ]]; then
    FIXTURE_TOKEN="${BASH_REMATCH[1]}"
  else
    die 'unexpected receiver response'
  fi
  validate_identifier "$FIXTURE_TOKEN" 'fixture token'

  START_CALL_ATTEMPTED=1
  adb_read shell am start-foreground-service \
    -n "$PACKAGE/$SERVICE_CLASS" \
    -a "$CALL_START_ACTION" \
    --es conversationId "$CONVERSATION_ID" \
    --es transport "$TRANSPORT_EXPECTED" \
    --es captureFixtureToken "$FIXTURE_TOKEN" \
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
    decode_state "${PARSED[--state]}"
    run_inject "${PARSED[--fixture]}" "${PARSED[--role]}"
    ;;
  interrupt)
    parse_options '--state --fixture' "$@"
    require_options --state --fixture
    decode_state "${PARSED[--state]}"
    run_interrupt "${PARSED[--fixture]}"
    ;;
  status)
    parse_options '--state' "$@"
    require_options --state
    decode_state "${PARSED[--state]}"
    run_status_operation
    ;;
  finalize)
    parse_options '--state' "$@"
    require_options --state
    decode_state "${PARSED[--state]}"
    run_finalize
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
    decode_state "${PARSED[--state]}"
    run_capture \
      "${PARSED[--automation-output]}" \
      "${PARSED[--private-voice-output]}" \
      "${PARSED[--sanitized-voice-output]}"
    ;;
  end)
    parse_options '--state --cleanup-output' "$@"
    require_options --state --cleanup-output
    validate_absent_destination "${PARSED[--cleanup-output]}" || die 'invalid cleanup destination'
    decode_state "${PARSED[--state]}"
    run_end "${PARSED[--cleanup-output]}"
    ;;
  *)
    die 'invalid operation'
    ;;
esac
