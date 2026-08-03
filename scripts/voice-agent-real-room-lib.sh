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
  require_command flock
  require_command mkdir
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

validate_android_user_id() {
  [[ "$1" =~ ^(0|[1-9][0-9]*)$ ]] || die 'invalid Android user'
}

validate_package_uid() {
  [[ "$1" =~ ^[1-9][0-9]*$ ]] || die 'invalid package UID'
}

validate_fixture_identity() {
  [[ "$1" =~ ^[1-9][0-9]*:[1-9][0-9]*:40700:[1-9][0-9]*:[1-9][0-9]*$ ]] ||
    die 'invalid fixture ownership receipt'
}

validate_fixture_nonce() {
  [[ "$1" =~ ^[0-9a-f]{32}$ ]] || die 'invalid fixture ownership receipt'
}

acquire_host_operation_lock() {
  local lock_root="/tmp/rikkahub-voice-real-room-locks-${EUID}"
  local lock_key
  local lock_name
  local lock_path
  python3 - /tmp 2>/dev/null <<'PY' || die 'host operation lock unavailable'
import os
import stat
import sys

metadata = os.lstat(sys.argv[1])
if (
    stat.S_ISLNK(metadata.st_mode)
    or not stat.S_ISDIR(metadata.st_mode)
    or metadata.st_uid != 0
    or not metadata.st_mode & stat.S_ISVTX
):
    raise SystemExit(1)
PY
  if [[ ! -e "$lock_root" && ! -L "$lock_root" ]]; then
    mkdir -m 700 -- "$lock_root" 2>/dev/null || true
  fi
  python3 - "$lock_root" 2>/dev/null <<'PY' || die 'host operation lock unavailable'
import os
import stat
import sys

metadata = os.lstat(sys.argv[1])
if (
    stat.S_ISLNK(metadata.st_mode)
    or not stat.S_ISDIR(metadata.st_mode)
    or stat.S_IMODE(metadata.st_mode) != 0o700
    or metadata.st_uid != os.geteuid()
):
    raise SystemExit(1)
PY
  exec {HOST_LOCK_ROOT_FD}<"$lock_root" || die 'host operation lock unavailable'
  python3 - "$lock_root" "$HOST_LOCK_ROOT_FD" 2>/dev/null <<'PY' ||
    die 'host operation lock unavailable'
import os
import stat
import sys

path_metadata = os.lstat(sys.argv[1])
descriptor_metadata = os.fstat(int(sys.argv[2]))
if (
    stat.S_ISLNK(path_metadata.st_mode)
    or not stat.S_ISDIR(descriptor_metadata.st_mode)
    or (path_metadata.st_dev, path_metadata.st_ino) !=
       (descriptor_metadata.st_dev, descriptor_metadata.st_ino)
):
    raise SystemExit(1)
PY
  lock_key="$(python3 - "$SERIAL" "$PACKAGE" 2>/dev/null <<'PY'
import hashlib
import sys

print(hashlib.sha256("\0".join(sys.argv[1:]).encode()).hexdigest())
PY
)" || die 'host operation lock unavailable'
  [[ "$lock_key" =~ ^[0-9a-f]{64}$ ]] || die 'host operation lock unavailable'
  lock_name="$lock_key.lock"
  lock_path="/proc/self/fd/$HOST_LOCK_ROOT_FD/$lock_name"
  if [[ ! -e "$lock_path" && ! -L "$lock_path" ]]; then
    mkdir -m 700 -- "$lock_path" 2>/dev/null || true
  fi
  python3 - "$HOST_LOCK_ROOT_FD" "$lock_name" 2>/dev/null <<'PY' ||
    die 'host operation lock unavailable'
import os
import stat
import sys

root_descriptor, name = int(sys.argv[1]), sys.argv[2]
metadata = os.stat(name, dir_fd=root_descriptor, follow_symlinks=False)
if (
    not stat.S_ISDIR(metadata.st_mode)
    or stat.S_IMODE(metadata.st_mode) != 0o700
    or metadata.st_uid != os.geteuid()
):
    raise SystemExit(1)
PY
  exec {HOST_LOCK_FD}<"$lock_path" || die 'host operation lock unavailable'
  python3 - "$HOST_LOCK_ROOT_FD" "$lock_name" "$HOST_LOCK_FD" 2>/dev/null <<'PY' ||
    die 'host operation lock unavailable'
import os
import stat
import sys

root_descriptor, name, descriptor = int(sys.argv[1]), sys.argv[2], int(sys.argv[3])
path_metadata = os.stat(name, dir_fd=root_descriptor, follow_symlinks=False)
descriptor_metadata = os.fstat(descriptor)
if (
    not stat.S_ISDIR(path_metadata.st_mode)
    or stat.S_IMODE(path_metadata.st_mode) != 0o700
    or path_metadata.st_uid != os.geteuid()
    or (path_metadata.st_dev, path_metadata.st_ino) !=
       (descriptor_metadata.st_dev, descriptor_metadata.st_ino)
):
    raise SystemExit(1)
PY
  flock -n "$HOST_LOCK_FD" 2>/dev/null || die 'host operation already active'
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
    "androidUserId",
    "packageUid",
    "conversationId",
    "runHash",
    "comparisonHash",
    "fixtureToken",
    "fixtureParentIdentity",
    "fixtureDirectoryIdentity",
    "fixtureOwnershipNonce",
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
if type(state["schemaVersion"]) is not int or state["schemaVersion"] != 2:
    raise SystemExit(1)
if type(state["androidUserId"]) is not int or type(state["packageUid"]) is not int:
    raise SystemExit(1)
string_keys = [key for key in keys[1:] if key not in {"androidUserId", "packageUid"}]
if any(type(state[key]) is not str for key in string_keys):
    raise SystemExit(1)
for key in keys:
    value = str(state[key]).encode("utf-8")
    sys.stdout.buffer.write(value + b"\n")
PY
)"; then
    die 'invalid state'
  fi
  mapfile -t values <<< "$snapshot"
  [[ "${#values[@]}" == 14 && "${values[0]}" == 2 ]] || die 'invalid state'
  [[ "${values[13]}" == "$TRANSPORT_EXPECTED" ]] || die 'invalid state'
  validate_identifier "${values[1]}" 'serial'
  [[ "${values[2]}" == "$PACKAGE_EXPECTED" ]] || die 'invalid package'
  validate_android_user_id "${values[3]}"
  validate_package_uid "${values[4]}"
  validate_identifier "${values[5]}" 'conversation id'
  validate_hash "${values[6]}" 'run hash'
  validate_hash "${values[7]}" 'comparison hash'
  validate_identifier "${values[8]}" 'fixture token'
  validate_fixture_identity "${values[9]}"
  validate_fixture_identity "${values[10]}"
  validate_fixture_nonce "${values[11]}"
  validate_identifier "${values[12]}" 'trace id'
  printf '%s\n' "${values[@]:1:12}"
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

parse_ordered_broadcast_output() {
  local output="$1"
  local parsed
  local result_code
  local data
  if ! parsed="$(printf '%s' "$output" | python3 -c '
import re
import sys

text = sys.stdin.read()
marker = "Broadcast completed: result="
if text.count(marker) != 1:
    raise SystemExit(1)
prefix, record = text.split(marker, 1)
if prefix and not prefix.endswith("\n"):
    raise SystemExit(1)
match = re.fullmatch(r"(-?[0-9]+), data=\"([^\x00\r]*)\"", record, re.DOTALL)
if match is None:
    raise SystemExit(1)
sys.stdout.write(match.group(1) + "\n" + match.group(2))
' 2>/dev/null)"; then
    return 2
  fi
  [[ "$parsed" == *$'\n'* ]] || return 2
  result_code="${parsed%%$'\n'*}"
  data="${parsed#*$'\n'}"
  [[ "$result_code" == 0 ]] || return 3
  printf '%s' "$data"
}

broadcast_read() {
  local receiver="$1"
  local action="$2"
  shift 2
  local output
  local data
  local parse_status
  if ! output="$(adb_read shell am broadcast --user "$ANDROID_USER_ID" \
      -n "$PACKAGE/$receiver" -a "$action" "$@" 2>/dev/null)"; then
    die 'ADB command failed'
  fi
  if data="$(parse_ordered_broadcast_output "$output")"; then
    parse_status=0
  else
    parse_status=$?
  fi
  case "$parse_status" in
    0) printf '%s' "$data" ;;
    3) die 'receiver rejected request' ;;
    *) die 'unexpected receiver response' ;;
  esac
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
}

validate_process_capability_readbacks() {
  local process_rows="$1"
  local isolated_rows="$2"
  printf '%s\n' "$process_rows" | python3 -c '
import re
import sys

lines = sys.stdin.read().splitlines()
if not lines or lines[0] != "UID PID PPID STAT NAME":
    raise SystemExit(1)
for line in lines[1:]:
    fields = line.split()
    if len(fields) != 5:
        raise SystemExit(1)
    uid, pid, ppid, state, name = fields
    if not uid.isdecimal() or not pid.isdecimal() or not ppid.isdecimal():
        raise SystemExit(1)
    if re.fullmatch(r"[A-Za-z+<NslL]+", state) is None or not name:
        raise SystemExit(1)
' 2>/dev/null || die 'package process readback unavailable'
  [[ "$isolated_rows" =~ ^\[\]$ ||
     "$isolated_rows" =~ ^\[[0-9]+(,\ [0-9]+)*\]$ ]] ||
    die 'isolated process readback unavailable'
}

resolve_package_identity() {
  local current_user
  local package_row
  local uid_rows
  local process_rows
  local isolated_rows
  current_user="$(adb_read shell cmd activity get-current-user 2>/dev/null)" ||
    die 'Android user readback failed'
  current_user="${current_user//$'\r'/}"
  validate_android_user_id "$current_user"
  ANDROID_USER_ID="$current_user"

  package_row="$(adb_read shell cmd package list packages --user "$ANDROID_USER_ID" \
    -U --show-stopped "$PACKAGE" 2>/dev/null)" || die 'package identity readback failed'
  if [[ "$package_row" =~ ^package:me\.rerere\.rikkahub\.debug\ stopped=(true|false)\ uid:([1-9][0-9]*)$ ]]; then
    PACKAGE_UID="${BASH_REMATCH[2]}"
  else
    die 'package identity readback failed'
  fi
  validate_package_uid "$PACKAGE_UID"

  uid_rows="$(adb_read shell cmd package list packages --user "$ANDROID_USER_ID" \
    --uid "$PACKAGE_UID" 2>/dev/null)" || die 'package UID readback failed'
  [[ "$uid_rows" == "package:$PACKAGE uid:$PACKAGE_UID" ]] || die 'package UID is shared'

  process_rows="$(adb_read exec-out ps -A -n -o UID,PID,PPID,STAT,NAME 2>/dev/null)" ||
    die 'package process readback unavailable'
  isolated_rows="$(adb_read shell cmd activity get-isolated-pids "$PACKAGE_UID" 2>/dev/null)" ||
    die 'isolated process readback unavailable'
  validate_process_capability_readbacks "$process_rows" "$isolated_rows"
  adb_read shell run-as "$PACKAGE" --user "$ANDROID_USER_ID" id \
    </dev/null >/dev/null 2>&1 || die 'run-as unavailable'
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
  protected_probe="$(adb_read shell run-as "$PACKAGE" --user "$ANDROID_USER_ID" sh -c '
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
  probe="$(adb_read shell run-as "$PACKAGE" --user "$ANDROID_USER_ID" sh -c '
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
      value="$(adb_read exec-out run-as "$PACKAGE" --user "$ANDROID_USER_ID" cat "$LATEST_TRACE_PATH" 2>/dev/null)" ||
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
  local -a receipt=()
  result="$(adb_read shell run-as "$PACKAGE" --user "$ANDROID_USER_ID" sh -c '
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
nonce=$(od -An -N16 -tx1 /dev/urandom | tr -d " \n") || exit 1
[ "${#nonce}" = 32 ] || exit 1
case "$nonce" in *[!0-9a-f]*) exit 1 ;; esac
printf "%s\n%s\n" "$owner" "$nonce" >&3 || exit 1
[ "$(stat -Lc %a /proc/self/fd/3)" = 600 ] && \
  [ "$(stat -Lc %h /proc/self/fd/3)" = 1 ] && \
  [ "$(cat /proc/self/fd/3)" = "$(printf "%s\n%s" "$owner" "$nonce")" ] && \
  [ "$(stat -c %d:%i .voice-step-owner)" = "$marker_inode" ] || exit 1
exec 3>&-
parent_mode=$(printf "%o" "0x$(stat -Lc %f /proc/self/fd/5)") || exit 1
directory_mode=$(printf "%o" "0x$(stat -Lc %f /proc/self/fd/4)") || exit 1
parent_identity=$(printf "%s:%s:%s:%s:%s" \
  "$(stat -Lc %d /proc/self/fd/5)" "$(stat -Lc %i /proc/self/fd/5)" "$parent_mode" \
  "$(stat -Lc %u /proc/self/fd/5)" "$(stat -Lc %g /proc/self/fd/5)") || exit 1
directory_identity=$(printf "%s:%s:%s:%s:%s" \
  "$(stat -Lc %d /proc/self/fd/4)" "$(stat -Lc %i /proc/self/fd/4)" "$directory_mode" \
  "$(stat -Lc %u /proc/self/fd/4)" "$(stat -Lc %g /proc/self/fd/4)") || exit 1
trap - EXIT HUP INT TERM
exec 4<&-
exec 5<&-
printf "created\nparent_identity=%s\ndirectory_identity=%s\nownership_nonce=%s\n" \
  "$parent_identity" "$directory_identity" "$nonce"
' sh "$remote_directory" "$owner_hash" </dev/null)" ||
    die 'fixture staging failed'
  mapfile -t receipt <<< "$result"
  [[ "${#receipt[@]}" == 4 && "${receipt[0]}" == created &&
     "${receipt[1]}" == parent_identity=* && "${receipt[2]}" == directory_identity=* &&
     "${receipt[3]}" == ownership_nonce=* ]] || die 'fixture staging failed'
  FIXTURE_PARENT_IDENTITY="${receipt[1]#parent_identity=}"
  FIXTURE_DIRECTORY_IDENTITY="${receipt[2]#directory_identity=}"
  FIXTURE_OWNERSHIP_NONCE="${receipt[3]#ownership_nonce=}"
  validate_fixture_identity "$FIXTURE_PARENT_IDENTITY"
  validate_fixture_identity "$FIXTURE_DIRECTORY_IDENTITY"
  validate_fixture_nonce "$FIXTURE_OWNERSHIP_NONCE"
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
  metadata="$(adb_read shell run-as "$PACKAGE" --user "$ANDROID_USER_ID" sh -c '
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
  [ "$(stat -c %a "$marker")" = 600 ] && [ "$(stat -c %h "$marker")" = 1 ] || exit 1
marker_payload=$(cat "$marker") || exit 1
case "$marker_payload" in "$owner"$'\n'[0-9a-f][0-9a-f][0-9a-f][0-9a-f]*) ;; *) exit 1 ;; esac
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

read_automation_terminal_snapshot() {
  local required_ending="$1"
  local artifact_path
  local snapshot_path
  local validation_status
  artifact_path="$(app_artifact_path "$APP_ARTIFACT_ROOT/${RUN_HASH#sha256:}" automation-events.jsonl)"
  ensure_local_temp_dir
  snapshot_path="$LOCAL_TEMP_DIR/automation-terminal.jsonl"
  : > "$snapshot_path"
  chmod 600 -- "$snapshot_path" 2>/dev/null || return 3
  if [[ -z "${AUTOMATION_TERMINAL_TEMP_REGISTERED:-}" ]]; then
    register_temp_file "$snapshot_path"
    AUTOMATION_TERMINAL_TEMP_REGISTERED=1
  fi
  adb_read exec-out run-as "$PACKAGE" --user "$ANDROID_USER_ID" cat "$artifact_path" \
    >"$snapshot_path" 2>/dev/null || return 3
  set +e
  python3 - "$snapshot_path" "$RUN_HASH" "$COMPARISON_HASH" \
    "$TRANSPORT_EXPECTED" "$required_ending" 2>/dev/null <<'PY'
import json
import sys

path, run_hash, comparison_hash, transport, ending = sys.argv[1:]
keys = [
    "schemaVersion", "monotonicMs", "wallClockMs", "runHash",
    "comparisonHash", "requestedTransport", "observedTransport", "name",
    "route", "network", "lifecycle", "playbackEpoch", "byteCount",
    "rmsActive", "audioWindowMicros", "succeeded", "correlationKind",
    "correlationHash", "requestedModelHash", "observedModelHash", "voiceHash",
    "instructionHash", "directAccountConfigurationHash", "conversationHash",
    "captureSource", "micBytes", "fixtureBytes",
]

try:
    content = open(path, "rb").read()
except OSError:
    raise SystemExit(2)
if not content or len(content) > 16 * 1024 * 1024 or not content.endswith(b"\n"):
    raise SystemExit(2)
try:
    text = content.decode("utf-8")
except UnicodeDecodeError:
    raise SystemExit(2)
rows = []
for line in text.splitlines():
    if not line:
        raise SystemExit(2)
    try:
        pairs = json.loads(line, object_pairs_hook=lambda value: value)
    except (TypeError, ValueError, json.JSONDecodeError):
        raise SystemExit(2)
    if not isinstance(pairs, list) or any(not isinstance(pair, tuple) for pair in pairs):
        raise SystemExit(2)
    if [key for key, _ in pairs] != keys:
        raise SystemExit(2)
    row = dict(pairs)
    if json.dumps(row, separators=(",", ":"), ensure_ascii=False) != line:
        raise SystemExit(2)
    if (
        type(row["schemaVersion"]) is not int
        or row["schemaVersion"] != 1
        or type(row["monotonicMs"]) is not int
        or type(row["wallClockMs"]) is not int
        or row["runHash"] != run_hash
        or row["comparisonHash"] != comparison_hash
        or row["requestedTransport"] != transport
        or type(row["name"]) is not str
    ):
        raise SystemExit(2)
    rows.append(row)

if ending == "call-stopped":
    if rows[-1]["name"] != "call_stopped":
        raise SystemExit(1)
    if rows[-1]["succeeded"] is not True:
        raise SystemExit(2)
elif ending == "finalized":
    if len(rows) < 2 or [row["name"] for row in rows[-2:]] != ["call_stopped", "run_finalized"]:
        raise SystemExit(2)
    if rows[-2]["succeeded"] is not True:
        raise SystemExit(2)
else:
    raise SystemExit(2)
PY
  validation_status=$?
  set -e
  return "$validation_status"
}

wait_for_durable_call_stopped() {
  local attempt=0
  local started=$SECONDS
  local proof_status
  while (( attempt < ${VOICE_STEP_MAX_WAIT_ATTEMPTS:-120} )); do
    attempt=$((attempt + 1))
    if read_automation_terminal_snapshot call-stopped; then
      return 0
    else
      proof_status=$?
    fi
    case "$proof_status" in
      1) ;;
      2) die 'invalid durable call-stop evidence' ;;
      3) die 'durable call-stop read failed' ;;
      *) die 'invalid durable call-stop evidence' ;;
    esac
    if (( SECONDS - started >= ${VOICE_STEP_WAIT_TIMEOUT_SECONDS:-120} )); then
      break
    fi
    sleep "${VOICE_STEP_POLL_SECONDS:-1}"
  done
  die 'call stop timed out'
}

read_package_stopped_state() {
  local expected="$1"
  local row
  local opposite
  [[ "$expected" == true || "$expected" == false ]] || return 2
  if [[ "$expected" == true ]]; then
    opposite=false
  else
    opposite=true
  fi
  row="$(adb_read shell cmd package list packages --user "$ANDROID_USER_ID" \
    -U --show-stopped "$PACKAGE" 2>/dev/null)" || return 2
  if [[ "$row" == "package:$PACKAGE stopped=$expected uid:$PACKAGE_UID" ]]; then
    return 0
  fi
  [[ "$row" == "package:$PACKAGE stopped=$opposite uid:$PACKAGE_UID" ]] && return 1
  return 2
}

prove_package_quiescence() {
  local iteration
  local processes
  local isolated
  for iteration in 1 2; do
    processes="$(adb_read exec-out ps -A -n -o UID,PID,PPID,STAT,NAME 2>/dev/null)" || return 1
    isolated="$(adb_read shell cmd activity get-isolated-pids "$PACKAGE_UID" 2>/dev/null)" || return 1
    if ! (validate_process_capability_readbacks "$processes" "$isolated") \
        >/dev/null 2>&1; then
      return 3
    fi
    printf '%s\n' "$processes" | python3 -c '
import sys

uid = sys.argv[1]
for line in sys.stdin.read().splitlines()[1:]:
    if line.split()[0] == uid:
        raise SystemExit(1)
' "$PACKAGE_UID" 2>/dev/null || return 2
    [[ "$isolated" == '[]' ]] || return 2
  done
}

remove_owned_remote_directory_quiescent() {
  local remote_directory="$1"
  local result
  result="$(adb_read shell run-as "$PACKAGE" --user "$ANDROID_USER_ID" sh -c '
set -eu
: voice-step-cleanup-broker
directory=$1
expected_parent=$2
expected_directory=$3
expected_nonce=$4
expected_uid=$5
parent=${directory%/*}
name=${directory##*/}
[ "$parent" = files/voice-real-room ] || exit 1
[ -d "$parent" ] && [ ! -L "$parent" ] || exit 1
cd -- "$parent" || exit 1
exec 5< . || exit 1
identity() {
  descriptor=$1
  mode=$(printf "%o" "0x$(stat -Lc %f "$descriptor")") || exit 1
  printf "%s:%s:%s:%s:%s" \
    "$(stat -Lc %d "$descriptor")" "$(stat -Lc %i "$descriptor")" "$mode" \
    "$(stat -Lc %u "$descriptor")" "$(stat -Lc %g "$descriptor")"
}
[ "$(identity /proc/self/fd/5)" = "$expected_parent" ] || exit 1
[ -d "$name" ] && [ ! -L "$name" ] || exit 1
[ "$(identity "$name")" = "$expected_directory" ] || exit 1
cd -- "$name" || exit 1
exec 4< . || exit 1
[ "$(identity /proc/self/fd/4)" = "$expected_directory" ] || exit 1
[ "$(stat -Lc %u /proc/self/fd/4)" = "$expected_uid" ] || exit 1
[ -f .voice-step-owner ] && [ ! -L .voice-step-owner ] || exit 1
exec 3< .voice-step-owner || exit 1
[ "$(stat -Lc %a /proc/self/fd/3)" = 600 ] && \
  [ "$(stat -Lc %h /proc/self/fd/3)" = 1 ] && \
  [ "$(stat -Lc %u /proc/self/fd/3)" = "$expected_uid" ] || exit 1
[ "$(awk "END { print NR }" /proc/self/fd/3)" = 2 ] || exit 1
owner_hash=$(sed -n "1p" /proc/self/fd/3) || exit 1
marker_nonce=$(sed -n "2p" /proc/self/fd/3) || exit 1
[ "${#owner_hash}" = 71 ] || exit 1
case "$owner_hash" in sha256:*) ;; *) exit 1 ;; esac
[ "$marker_nonce" = "$expected_nonce" ] || exit 1
exec 3<&-
for entry in .[!.]* ..?* *; do
  [ -e "$entry" ] || [ -L "$entry" ] || continue
  case "$entry" in .voice-step-owner|*.pcm) ;; *) exit 1 ;; esac
  [ -f "$entry" ] && [ ! -L "$entry" ] && \
    [ "$(stat -Lc %a "$entry")" = 600 ] && \
    [ "$(stat -Lc %h "$entry")" = 1 ] && \
    [ "$(stat -Lc %u "$entry")" = "$expected_uid" ] || exit 1
  rm -f -- "$entry" || exit 1
done
cd /proc/self/fd/5 || exit 1
[ "$(identity /proc/self/fd/5)" = "$expected_parent" ] || exit 1
[ "$(identity "$name" 2>/dev/null || :)" = "$expected_directory" ] || exit 1
rmdir -- "$name" || exit 1
[ ! -e "$name" ] && [ ! -L "$name" ] || exit 1
[ "$(stat -Lc %h /proc/self/fd/4)" = 0 ] || exit 1
exec 4<&-
exec 5<&-
printf removed
' sh "$remote_directory" "$FIXTURE_PARENT_IDENTITY" "$FIXTURE_DIRECTORY_IDENTITY" \
    "$FIXTURE_OWNERSHIP_NONCE" "$PACKAGE_UID" </dev/null)" || return 1
  [[ "$result" == removed ]] || return 2
}

restore_force_stopped_package() {
  local output
  local data
  local status_snapshot
  local -a status=()
  output="$(adb_read shell am broadcast --user "$ANDROID_USER_ID" \
    --include-stopped-packages -n "$PACKAGE/$CONTROL_RECEIVER" \
    -a "$CONTROL_ACTION_PREFIX.STATUS" 2>/dev/null)" || return 1
  data="$(parse_ordered_broadcast_output "$output")" || return 2
  status_snapshot="$(parse_status_data "$data")" || return 2
  mapfile -t status <<< "$status_snapshot"
  [[ "${#status[@]}" == 7 && "${status[0]}" == idle && "${status[1]}" == none &&
     "${status[2]}" == none && "${status[3]}" == none && "${status[4]}" == 0 &&
     "${status[5]}" == none && "${status[6]}" == true ]] || return 2
  read_package_stopped_state false || return 2
  PACKAGE_FORCE_STOP_OWNED=0
}

read_source_metadata() {
  local source_path="$1"
  local metadata
  metadata="$(adb_read shell run-as "$PACKAGE" --user "$ANDROID_USER_ID" sh -c '
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
  adb_read exec-out run-as "$PACKAGE" --user "$ANDROID_USER_ID" cat "$source_path" >"$first" 2>/dev/null ||
    die 'artifact read failed'
  metadata_between="$(read_source_metadata "$source_path")"
  adb_read exec-out run-as "$PACKAGE" --user "$ANDROID_USER_ID" cat "$source_path" >"$second" 2>/dev/null ||
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
  if ! python3 - "$STATE_PUBLICATION_TEMP" "$SERIAL" "$PACKAGE" "$ANDROID_USER_ID" \
    "$PACKAGE_UID" "$CONVERSATION_ID" "$RUN_HASH" "$COMPARISON_HASH" "$FIXTURE_TOKEN" \
    "$FIXTURE_PARENT_IDENTITY" "$FIXTURE_DIRECTORY_IDENTITY" "$FIXTURE_OWNERSHIP_NONCE" \
    "$TRACE_ID" 2>/dev/null <<'PY'
import json
import os
import sys

(
    path,
    serial,
    package,
    android_user_id,
    package_uid,
    conversation,
    run_hash,
    comparison_hash,
    token,
    parent_identity,
    directory_identity,
    ownership_nonce,
    trace,
) = sys.argv[1:]
payload = {
    "schemaVersion": 2,
    "serial": serial,
    "package": package,
    "androidUserId": int(android_user_id),
    "packageUid": int(package_uid),
    "conversationId": conversation,
    "runHash": run_hash,
    "comparisonHash": comparison_hash,
    "fixtureToken": token,
    "fixtureParentIdentity": parent_identity,
    "fixtureDirectoryIdentity": directory_identity,
    "fixtureOwnershipNonce": ownership_nonce,
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
