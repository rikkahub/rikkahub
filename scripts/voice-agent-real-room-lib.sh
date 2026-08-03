#!/usr/bin/env bash
# Shared security and lifecycle primitives for voice-agent-real-room-step.sh.
# This file is sourced; it is not a public command surface.
[[ "${BASH_SOURCE[0]}" != "$0" ]] || {
  printf 'voice-step.error=library is not executable\n' >&2
  exit 1
}

die() {
  ERROR_REPORTED=1
  printf 'voice-step.error=%s\n' "$1" >&2
  exit 1
}

adb_read() {
  timeout --signal=TERM --kill-after=2s "${VOICE_STEP_ADB_TIMEOUT_SECONDS:-10}s" adb -s "$SERIAL" "$@" 2>/dev/null
}

adb_global_read() {
  timeout --signal=TERM --kill-after=2s "${VOICE_STEP_ADB_TIMEOUT_SECONDS:-10}s" adb "$@" 2>/dev/null
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
  local -n snapshot_out="$2"
  local -n size_out="$3"
  local -n hash_out="$4"
  local snapshot_path
  local metadata
  local -a fixture_metadata=()
  ensure_local_temp_dir
  snapshot_path="$(mktemp "$LOCAL_TEMP_DIR/fixture.XXXXXX.pcm" 2>/dev/null)" ||
    die 'invalid fixture'
  chmod 600 -- "$snapshot_path" 2>/dev/null || die 'invalid fixture'
  register_temp_file "$snapshot_path"
  if ! metadata="$(python3 - "$source_path" "$snapshot_path" 2>/dev/null <<'PY'
import hashlib
import os
import stat
import sys

source_path, snapshot_path = sys.argv[1:]
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
    snapshot_before = os.lstat(snapshot_path)
    if stat.S_ISLNK(snapshot_before.st_mode) or not stat.S_ISREG(snapshot_before.st_mode):
        raise SystemExit(1)
    if stat.S_IMODE(snapshot_before.st_mode) != 0o600 or snapshot_before.st_size != 0:
        raise SystemExit(1)
    output_descriptor = os.open(
        snapshot_path,
        os.O_WRONLY | os.O_TRUNC | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0),
    )
    snapshot_opened = os.fstat(output_descriptor)
    if (snapshot_opened.st_dev, snapshot_opened.st_ino) != (snapshot_before.st_dev, snapshot_before.st_ino):
        raise SystemExit(1)
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
snapshot_after = os.lstat(snapshot_path)
if (snapshot_after.st_dev, snapshot_after.st_ino) != (snapshot_before.st_dev, snapshot_before.st_ino):
    raise SystemExit(1)
print(str(before.st_size))
print("sha256:" + digest.hexdigest())
PY
)"; then
    die 'invalid fixture'
  fi
  mapfile -t fixture_metadata <<< "$metadata"
  [[ "${#fixture_metadata[@]}" == 2 ]] || die 'invalid fixture'
  [[ "${fixture_metadata[0]}" =~ ^[1-9][0-9]*$ ]] || die 'invalid fixture'
  validate_hash "${fixture_metadata[1]}" 'fixture hash'
  snapshot_out="$snapshot_path"
  size_out="${fixture_metadata[0]}"
  hash_out="${fixture_metadata[1]}"
}

decode_state() {
  local state_path="$1"
  local snapshot
  local -a values=()
  if ! snapshot="$(python3 - "$state_path" 2>/dev/null <<'PY'
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
    sys.stdout.buffer.write(value + b"\n")
PY
)"; then
    die 'invalid state'
  fi
  mapfile -t values <<< "$snapshot"
  [[ "${#values[@]}" == 9 && "${values[0]}" == 1 ]] || die 'invalid state'
  [[ "${values[8]}" == "$TRANSPORT_EXPECTED" ]] || die 'invalid state'
  validate_identifier "${values[1]}" 'serial'
  [[ "${values[2]}" == "$PACKAGE_EXPECTED" ]] || die 'invalid package'
  validate_identifier "${values[3]}" 'conversation id'
  validate_hash "${values[4]}" 'run hash'
  validate_hash "${values[5]}" 'comparison hash'
  validate_identifier "${values[6]}" 'fixture token'
  validate_identifier "${values[7]}" 'trace id'
  printf '%s\n' "${values[@]:1:7}"
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
  decode_broadcast_data "$raw_data"
}

parse_status_data() {
  local data="$1"
  local -a lines=()
  local run_state
  local run_hash
  local comparison_hash
  local transport
  local event_count
  local network
  local validated
  mapfile -t lines <<< "$data"
  [[ "${#lines[@]}" == 9 ]] || return 1
  [[ "${lines[0]}" == 'status=ok' && "${lines[1]}" == 'action=status' ]] ||
    return 1
  [[ "${lines[2]}" == run_state=* && "${lines[3]}" == run_hash=* &&
     "${lines[4]}" == comparison_hash=* && "${lines[5]}" == requested_transport=* &&
     "${lines[6]}" == event_count=* && "${lines[7]}" == network=* &&
     "${lines[8]}" == validated=* ]] || return 1
  run_state="${lines[2]#run_state=}"
  run_hash="${lines[3]#run_hash=}"
  comparison_hash="${lines[4]#comparison_hash=}"
  transport="${lines[5]#requested_transport=}"
  event_count="${lines[6]#event_count=}"
  network="${lines[7]#network=}"
  validated="${lines[8]#validated=}"
  [[ "$run_state" =~ ^(idle|active|finalized)$ ]] || return 1
  [[ "$event_count" =~ ^[0-9]+$ ]] || return 1
  [[ "$network" =~ ^(wifi|cellular|none)$ ]] || return 1
  [[ "$validated" == true ]] || return 1
  case "$run_state" in
    idle)
      [[ "$run_hash" == none && "$comparison_hash" == none && "$transport" == none ]] || return 1
      ;;
    active|finalized)
      [[ "$run_hash" =~ ^sha256:[0-9a-f]{64}$ &&
         "$comparison_hash" =~ ^sha256:[0-9a-f]{64}$ &&
         "$transport" == "$TRANSPORT_EXPECTED" ]] || return 1
      ;;
  esac
  printf '%s\n' "$run_state" "$run_hash" "$comparison_hash" "$transport" \
    "$event_count" "$network" "$validated"
}

read_status() {
  local data
  data="$(broadcast_read "$CONTROL_RECEIVER" "$CONTROL_ACTION_PREFIX.STATUS")" ||
    die 'unexpected status response'
  parse_status_data "$data" || die 'unexpected status response'
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

compute_remote_owner_hash() {
  local owner_hash
  owner_hash="$(python3 - "$SERIAL" "$PACKAGE" "$CONVERSATION_ID" \
    "$RUN_HASH" "$COMPARISON_HASH" 2>/dev/null <<'PY'
import hashlib
import sys

print("sha256:" + hashlib.sha256("\0".join(sys.argv[1:]).encode()).hexdigest())
PY
)" || die 'fixture ownership failed'
  validate_hash "$owner_hash" 'fixture ownership'
  printf '%s' "$owner_hash"
}

create_owned_remote_directory() {
  local remote_directory="$1"
  local owner_hash="$2"
  local result
  result="$(adb_read shell run-as "$PACKAGE" sh -c '
set -eu
: voice-step-create-owned-directory
directory=$1
owner=$2
parent=${directory%/*}
name=${directory##*/}
[ "$parent" = files/voice-real-room ] || exit 1
[ ! -e "$directory" ] && [ ! -L "$directory" ] || exit 1
[ ! -L "$parent" ] || exit 1
if [ ! -e "$parent" ]; then
  mkdir -m 700 -- "$parent" || exit 1
fi
[ -d "$parent" ] && [ ! -L "$parent" ] || exit 1
files_root=$(readlink -f files) || exit 1
parent_root=$(readlink -f "$parent") || exit 1
[ "$parent_root" = "$files_root/voice-real-room" ] || exit 1
cd -- "$parent" || exit 1
parent_inode=$(stat -c %d:%i .) || exit 1
exec 5< . || exit 1
[ "$(stat -Lc %d:%i /proc/self/fd/5)" = "$parent_inode" ] || exit 1
created_bound=0
directory_inode=
cleanup() {
  [ "$created_bound" = 1 ] || return 0
  exec 3>&-
  [ "$(stat -c %d:%i . 2>/dev/null || :)" = "$directory_inode" ] || return 1
  [ "$(stat -Lc %d:%i /proc/self/fd/4 2>/dev/null || :)" = "$directory_inode" ] || return 1
  for entry in .[!.]* ..?* *; do
    [ -e "$entry" ] || [ -L "$entry" ] || continue
    rm -rf -- "$entry" || return 1
  done
  cd /proc/self/fd/5 || return 1
  [ "$(stat -c %d:%i . 2>/dev/null || :)" = "$parent_inode" ] || return 1
  [ "$(stat -Lc %d:%i /proc/self/fd/5 2>/dev/null || :)" = "$parent_inode" ] || return 1
  [ "$(stat -c %d:%i "$name" 2>/dev/null || :)" = "$directory_inode" ] || return 1
  rmdir -- "$name" || return 1
  [ ! -e "$name" ] && [ ! -L "$name" ] || return 1
  [ "$(stat -Lc %d:%i /proc/self/fd/4 2>/dev/null || :)" = "$directory_inode" ] && \
    [ "$(stat -Lc %h /proc/self/fd/4 2>/dev/null || :)" = 0 ] || return 1
  exec 4<&-
  exec 5<&-
}
trap cleanup EXIT
trap "exit 1" HUP INT TERM
mkdir -m 700 -- "$name" || exit 1
directory_inode=$(stat -c %d:%i "$name") || exit 1
cd -- "$name" || exit 1
exec 4< . || exit 1
[ "$(stat -c %d:%i .)" = "$directory_inode" ] && \
  [ "$(stat -Lc %d:%i /proc/self/fd/4)" = "$directory_inode" ] && \
  [ "$(stat -c %a .)" = 700 ] || exit 1
for entry in .[!.]* ..?* *; do
  [ -e "$entry" ] || [ -L "$entry" ] || continue
  exit 1
done
created_bound=1
set -C
umask 077
exec 3> .voice-step-owner || exit 1
set +C
marker_inode=$(stat -Lc %d:%i /proc/self/fd/3) || exit 1
printf "%s\n" "$owner" >&3 || exit 1
[ "$(stat -Lc %a /proc/self/fd/3)" = 600 ] && \
  [ "$(stat -Lc %h /proc/self/fd/3)" = 1 ] && \
  [ "$(cat /proc/self/fd/3)" = "$owner" ] && \
  [ "$(stat -c %d:%i .voice-step-owner)" = "$marker_inode" ] || exit 1
exec 3>&-
trap - EXIT HUP INT TERM
exec 4<&-
exec 5<&-
printf created
' sh "$remote_directory" "$owner_hash" </dev/null)" ||
    die 'fixture staging failed'
  [[ "$result" == created ]] || die 'fixture staging failed'
  START_FIXTURE_DIR_CREATED=1
}

stage_owned_snapshot() {
  local remote_directory="$1"
  local remote_path="$2"
  local owner_hash="$3"
  local fixture_snapshot="$4"
  local fixture_size="$5"
  local fixture_hash="$6"
  local metadata
  metadata="$(adb_read shell run-as "$PACKAGE" sh -c '
set -eu
: voice-step-stage-owned-fixture
: voice-step-descriptor-owned-stage
directory=$1
destination=$2
owner=$3
marker=$directory/.voice-step-owner
[ -d "${directory%/*}" ] && [ ! -L "${directory%/*}" ] || exit 1
[ "$(readlink -f "${directory%/*}")" = "$(readlink -f files)/voice-real-room" ] || exit 1
[ -d "$directory" ] && [ ! -L "$directory" ] && \
  [ "$(stat -c %a "$directory")" = 700 ] || exit 1
[ -f "$marker" ] && [ ! -L "$marker" ] && \
  [ "$(stat -c %a "$marker")" = 600 ] && [ "$(stat -c %h "$marker")" = 1 ] && \
  [ "$(cat "$marker")" = "$owner" ] || exit 1
case "$destination" in "$directory"/*.pcm) ;; *) exit 1 ;; esac
[ ! -e "$destination" ] && [ ! -L "$destination" ] || exit 1
descriptor_inode=
published=0
cleanup() {
  if [ "$published" = 0 ] && [ -n "$descriptor_inode" ] && \
      [ ! -L "$destination" ] && [ -f "$destination" ] && \
      [ "$(stat -c %d:%i "$destination" 2>/dev/null || :)" = "$descriptor_inode" ]; then
    rm -f -- "$destination"
  fi
  exec 3>&-
}
trap cleanup EXIT HUP INT TERM
set -C
umask 077
exec 3> "$destination" || exit 1
set +C
descriptor=/proc/self/fd/3
descriptor_inode=$(stat -Lc %d:%i "$descriptor") || exit 1
cat >&3 || exit 1
[ -f "$descriptor" ] && [ "$(stat -Lc %a "$descriptor")" = 600 ] && \
  [ "$(stat -Lc %h "$descriptor")" = 1 ] || exit 1
[ -f "$destination" ] && [ ! -L "$destination" ] && \
  [ "$(stat -c %d:%i "$destination")" = "$descriptor_inode" ] || exit 1
metadata=$(printf "%s\nsha256:%s\n" "$(stat -Lc %s "$descriptor")" \
  "$(sha256sum "$descriptor" | cut -d " " -f 1)") || exit 1
[ "$(stat -c %d:%i "$destination")" = "$descriptor_inode" ] || exit 1
published=1
exec 3>&-
trap - EXIT HUP INT TERM
printf "%s\n" "$metadata"
' sh "$remote_directory" "$remote_path" "$owner_hash" \
    < "$fixture_snapshot")" || die 'fixture staging failed'
  [[ "$metadata" == "$fixture_size"$'\n'"$fixture_hash" ]] ||
    die 'fixture staging verification failed'
}

remove_owned_remote_directory() {
  local remote_directory="$1"
  local owner_hash="$2"
  local result
  result="$(adb_read shell run-as "$PACKAGE" sh -c '
set -eu
: voice-step-remove-owned-directory
directory=$1
owner=$2
parent=${directory%/*}
name=${directory##*/}
[ "$parent" = files/voice-real-room ] || exit 1
[ -d "$parent" ] && [ ! -L "$parent" ] || exit 1
[ "$(readlink -f "$parent")" = "$(readlink -f files)/voice-real-room" ] || exit 1
cd -- "$parent" || exit 1
[ -d "$name" ] && [ ! -L "$name" ] && [ "$(stat -c %a "$name")" = 700 ] || exit 1
directory_inode=$(stat -c %d:%i "$name") || exit 1
cd -- "$name" || exit 1
[ "$(stat -c %d:%i .)" = "$directory_inode" ] || exit 1
exec 4< . || exit 1
[ "$(stat -Lc %d:%i /proc/self/fd/4)" = "$directory_inode" ] || exit 1
[ -f .voice-step-owner ] && [ ! -L .voice-step-owner ] || exit 1
exec 3< .voice-step-owner || exit 1
marker_inode=$(stat -Lc %d:%i /proc/self/fd/3) || exit 1
[ "$(stat -Lc %a /proc/self/fd/3)" = 600 ] && \
  [ "$(stat -Lc %h /proc/self/fd/3)" = 1 ] && [ "$(cat <&3)" = "$owner" ] && \
  [ "$(stat -c %d:%i .voice-step-owner)" = "$marker_inode" ] || exit 1
exec 3<&-
for entry in .[!.]* ..?* *; do
  [ -e "$entry" ] || [ -L "$entry" ] || continue
  rm -rf -- "$entry" || exit 1
done
cd .. || exit 1
[ "$(stat -c %d:%i "$name" 2>/dev/null || :)" = "$directory_inode" ] || exit 1
rmdir -- "$name" || exit 1
[ ! -e "$name" ] && [ ! -L "$name" ] || exit 1
[ "$(stat -Lc %d:%i /proc/self/fd/4)" = "$directory_inode" ] && \
  [ "$(stat -Lc %h /proc/self/fd/4)" = 0 ] || exit 1
exec 4<&-
printf removed
' sh "$remote_directory" "$owner_hash" </dev/null)" || return 1
  [[ "$result" == removed ]] || return 2
}

stage_snapshot() {
  local remote_directory="$1"
  local remote_path="$2"
  local fixture_snapshot="$3"
  local fixture_size="$4"
  local fixture_hash="$5"
  REMOTE_OWNER_HASH="$(compute_remote_owner_hash)"
  create_owned_remote_directory "$remote_directory" "$REMOTE_OWNER_HASH"
  stage_owned_snapshot "$remote_directory" "$remote_path" "$REMOTE_OWNER_HASH" \
    "$fixture_snapshot" "$fixture_size" "$fixture_hash"
}

inject_fixture_once() {
  local fixture_snapshot="$1"
  local fixture_size="$2"
  local fixture_hash="$3"
  local role="$4"
  local remote_directory
  local remote_path
  local owner_hash
  local reply
  remote_directory="files/voice-real-room/${RUN_HASH#sha256:}"
  remote_path="$remote_directory/${role}-${fixture_hash#sha256:}.pcm"
  owner_hash="$(compute_remote_owner_hash)"
  stage_owned_snapshot "$remote_directory" "$remote_path" "$owner_hash" \
    "$fixture_snapshot" "$fixture_size" "$fixture_hash"
  reply="$(broadcast_read "$FIXTURE_RECEIVER" "$FIXTURE_STAGE_ACTION" \
    --es token "$FIXTURE_TOKEN" \
    --es path "$remote_path" \
    --el expected_size "$fixture_size" \
    --es expected_sha256 "$fixture_hash" \
    --ei chunk_bytes "$FIXTURE_CHUNK_BYTES" \
    --el chunk_delay_ms "$FIXTURE_CHUNK_DELAY_MS")"
  [[ "$reply" == $'status=ok\naction=stage\naccepted=true' ]] ||
    die 'unexpected receiver response'
  reply="$(broadcast_read "$FIXTURE_RECEIVER" "$FIXTURE_TRIGGER_ACTION" \
    --es token "$FIXTURE_TOKEN" \
    --es path "$remote_path")"
  [[ "$reply" == $'status=ok\naction=trigger\naccepted=true' ]] ||
    die 'unexpected receiver response'
}

read_call_service_active() {
  local services
  services="$(adb_read shell dumpsys activity services "$PACKAGE" 2>/dev/null)" ||
    die 'call service readback failed'
  [[ "$services" == *"$PACKAGE/$SERVICE_CLASS"* ]] || die 'call service is not active'
}

read_source_metadata() {
  local source_path="$1"
  local metadata
  metadata="$(adb_read shell run-as "$PACKAGE" sh -c '
: voice-step-source-metadata
[ -f "$1" ] && [ ! -L "$1" ] && [ "$(stat -c %a "$1")" = 600 ] || exit 1
LC_ALL=C stat -c "%F|%h|%u|%a|%d|%i|%s|%y|%z" "$1"
' sh "$source_path" 2>/dev/null)" || die 'artifact source unavailable'
  [[ "$metadata" =~ ^regular\ file\|1\|[0-9]+\|600\|[0-9]+\|[0-9]+\|[1-9][0-9]*\|[-0-9:.+\ ]+\|[-0-9:.+\ ]+$ ]] ||
    die 'artifact source invalid'
  printf '%s' "$metadata"
}

read_stable_artifact() {
  local source_path="$1"
  local destination="$2"
  local -n stable_temp_out="$3"
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
  source_size="$(printf '%s' "$metadata_before" | awk -F'|' '{print $7}')"
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
  stable_temp_out="$first"
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

publish_state() {
  local destination="$1"
  local parent
  local expected_hash
  local link_status
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
  STATE_PUBLICATION_CRITICAL=1
  if ln -T -- "$STATE_PUBLICATION_TEMP" "$destination" 2>/dev/null; then
    link_status=0
  else
    link_status=$?
  fi
  if (( link_status == 0 )); then
    START_COMMITTED=1
    START_CLEANUP_NEEDED=0
  fi
  STATE_PUBLICATION_CRITICAL=0
  (( link_status == 0 )) || die 'state publication failed'
  if (( PUBLICATION_SIGNAL == 1 )); then
    die 'interrupted'
  fi
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
