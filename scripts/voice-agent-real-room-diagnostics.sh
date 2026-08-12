#!/usr/bin/env bash
# Private, fixed-token diagnostics for voice-agent-real-room-step.sh.
# This file is sourced; it is not a public command surface.
[[ "${BASH_SOURCE[0]}" != "$0" ]] || {
  printf 'voice-step.error=library is not executable\n' >&2
  exit 1
}

diagnostic_token_is_valid() { [[ "$1" =~ ^[a-z][a-z0-9-]{0,63}$ ]]; }

validate_private_diagnostic_destination() {
  local parent_identity
  parent_identity="$(python3 - "$1" 2>/dev/null <<'PY'
import os, stat, sys
path = sys.argv[1]
if not path or not os.path.isabs(path) or os.path.normpath(path) != path:
    raise SystemExit(1)
parent, name = os.path.dirname(path), os.path.basename(path)
if not name or name in {".", ".."} or os.path.realpath(parent) != parent:
    raise SystemExit(1)
metadata = os.lstat(parent)
if (stat.S_ISLNK(metadata.st_mode) or not stat.S_ISDIR(metadata.st_mode)
        or stat.S_IMODE(metadata.st_mode) != 0o700
        or metadata.st_uid != os.geteuid()):
    raise SystemExit(1)
try:
    os.lstat(path)
except FileNotFoundError:
    pass
else:
    raise SystemExit(1)
print(f"{metadata.st_dev}:{metadata.st_ino}")
PY
  )" || return 1
  [[ "$parent_identity" =~ ^[0-9]+:[0-9]+$ ]] || return 1
  local parent="${1%/*}"
  [[ -n "$parent" ]] || parent=/
  exec {DIAGNOSTIC_PARENT_FD}<"$parent" || return 1
  if ! python3 - "$parent" "$parent_identity" "$DIAGNOSTIC_PARENT_FD" 2>/dev/null <<'PY'
import os
import stat
import sys

parent, expected_identity, parent_fd = sys.argv[1:]
path_metadata = os.lstat(parent)
descriptor_metadata = os.fstat(int(parent_fd))
for metadata in (path_metadata, descriptor_metadata):
    if (
        not stat.S_ISDIR(metadata.st_mode)
        or stat.S_IMODE(metadata.st_mode) != 0o700
        or metadata.st_uid != os.geteuid()
        or f"{metadata.st_dev}:{metadata.st_ino}" != expected_identity
    ):
        raise SystemExit(1)
PY
  then
    exec {DIAGNOSTIC_PARENT_FD}<&-
    DIAGNOSTIC_PARENT_FD=''
    return 1
  fi
  DIAGNOSTIC_PARENT_IDENTITY="$parent_identity"
}

diagnostic_initialize() {
  local operation="$1"
  local destination="$2"
  diagnostic_token_is_valid "$operation" || return 1
  [[ "$operation" == start ]] || return 1
  [[ "${DIAGNOSTIC_PARENT_FD:-}" =~ ^[0-9]+$ ]] || return 1
  ensure_local_temp_dir || return 1
  DIAGNOSTIC_ERROR_FILE="$(mktemp "$LOCAL_TEMP_DIR/diagnostic-error.XXXXXX" 2>/dev/null)" || return 1
  chmod 600 -- "$DIAGNOSTIC_ERROR_FILE" 2>/dev/null || return 1
  register_temp_file "$DIAGNOSTIC_ERROR_FILE"
  DIAGNOSTIC_MANAGED_STATUS_FILE="$(mktemp "$LOCAL_TEMP_DIR/diagnostic-managed-status.XXXXXX" 2>/dev/null)" || return 1
  chmod 600 -- "$DIAGNOSTIC_MANAGED_STATUS_FILE" 2>/dev/null || return 1
  register_temp_file "$DIAGNOSTIC_MANAGED_STATUS_FILE"
  printf 'none' > "$DIAGNOSTIC_ERROR_FILE" || return 1
  printf 'none' > "$DIAGNOSTIC_MANAGED_STATUS_FILE" || return 1
  local identity_channel
  identity_channel="$(mktemp "$LOCAL_TEMP_DIR/diagnostic-published-identity.XXXXXX" 2>/dev/null)" || return 1
  chmod 600 -- "$identity_channel" 2>/dev/null || return 1
  exec {DIAGNOSTIC_IDENTITY_READ_FD}<"$identity_channel" || return 1
  exec {DIAGNOSTIC_IDENTITY_WRITE_FD}>"$identity_channel" || return 1
  rm -f -- "$identity_channel" 2>/dev/null || return 1
  DIAGNOSTIC_ENABLED=1
  DIAGNOSTIC_OPERATION="$operation"
  DIAGNOSTIC_DESTINATION="$destination"
  DIAGNOSTIC_STAGE='option-validation'
  DIAGNOSTIC_ERROR_CATEGORY='none'
  DIAGNOSTIC_CHILD_EXIT_STATUS='none'
  DIAGNOSTIC_CAPTURE_MANAGED_EXIT=1
  DIAGNOSTIC_PUBLISHED_IDENTITY=''
}

diagnostic_set_stage() {
  local stage="$1"
  diagnostic_token_is_valid "$stage" || return 1
  DIAGNOSTIC_STAGE="$stage"
  [[ -z "${DIAGNOSTIC_MANAGED_STATUS_FILE:-}" ]] ||
    : > "$DIAGNOSTIC_MANAGED_STATUS_FILE"
}

diagnostic_error_category() {
  case "$1" in
    'invalid run hash') printf invalid-run-hash ;;
    'invalid timeout configuration') printf invalid-timeout-configuration ;;
    'host operation lock unavailable') printf host-operation-lock-unavailable ;;
    'host operation already active') printf host-operation-already-active ;;
    'invalid fixture') printf invalid-fixture ;;
    'device is not ready') printf device-not-ready ;;
    'Android user readback failed') printf android-user-readback-failed ;;
    'package readback failed') printf package-readback-failed ;;
    'package contract mismatch') printf package-contract-mismatch ;;
    'unexpected status response') printf unexpected-status-response ;;
    'automation is not ready') printf automation-not-ready ;;
    'trace readback failed') printf trace-readback-failed ;;
    'fixture ownership failed') printf fixture-ownership-failed ;;
    'fixture staging failed') printf fixture-staging-failed ;;
    'fixture staging verification failed') printf fixture-staging-verification-failed ;;
    'receiver rejected request') printf receiver-rejected-request ;;
    'ADB command failed') printf adb-command-failed ;;
    'unexpected receiver response') printf unexpected-receiver-response ;;
    'call start failed') printf call-start-failed ;;
    'ambiguous call readback') printf ambiguous-call-readback ;;
    'call activation timed out') printf call-activation-timed-out ;;
    'trace activation timed out') printf trace-activation-timed-out ;;
    'state publication failed') printf state-publication-failed ;;
    'cleanup failed') printf cleanup-failed ;;
    'interrupted') printf interrupted ;;
    'diagnostic publication failed') printf diagnostic-publication-failed ;;
    *) printf operation-failed ;;
  esac
}

diagnostic_note_error() {
  local category
  category="$(diagnostic_error_category "$1")"
  diagnostic_token_is_valid "$category" || category='operation-failed'
  DIAGNOSTIC_ERROR_CATEGORY="$category"
  if [[ -n "${DIAGNOSTIC_ERROR_FILE:-}" && -e "$DIAGNOSTIC_ERROR_FILE" ]]; then
    printf '%s' "$category" > "$DIAGNOSTIC_ERROR_FILE"
  fi
}

diagnostic_note_managed_exit() {
  local status="$1"
  [[ "${DIAGNOSTIC_CAPTURE_MANAGED_EXIT:-0}" == 1 ]] || return 0
  [[ "$status" =~ ^([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$ ]] || return 1
  DIAGNOSTIC_CHILD_EXIT_STATUS="$status"
  if [[ -n "${DIAGNOSTIC_MANAGED_STATUS_FILE:-}" &&
        -e "$DIAGNOSTIC_MANAGED_STATUS_FILE" ]]; then
    printf '%s' "$status" > "$DIAGNOSTIC_MANAGED_STATUS_FILE"
  fi
}

diagnostic_take_published_identity() {
  local identity
  DIAGNOSTIC_PUBLISHED_IDENTITY=''
  [[ "${DIAGNOSTIC_IDENTITY_READ_FD:-}" =~ ^[0-9]+$ ]] || return 1
  IFS= read -r -u "$DIAGNOSTIC_IDENTITY_READ_FD" identity || return 1
  [[ "$identity" =~ ^[0-9]+:[0-9]+$ ]] || return 1
  DIAGNOSTIC_PUBLISHED_IDENTITY="$identity"
}

diagnostic_remove_owned_destination() {
  local owned_identity="$1"
  [[ "${DIAGNOSTIC_ENABLED:-0}" == 1 ]] || return 0
  [[ "$owned_identity" =~ ^[0-9]+:[0-9]+$ ]] || return 1
  [[ "${DIAGNOSTIC_PARENT_IDENTITY:-}" =~ ^[0-9]+:[0-9]+$ ]] || return 1
  [[ "${DIAGNOSTIC_PARENT_FD:-}" =~ ^[0-9]+$ ]] || return 1
  python3 - "$DIAGNOSTIC_DESTINATION" "$DIAGNOSTIC_PARENT_IDENTITY" \
    "$owned_identity" "$DIAGNOSTIC_PARENT_FD" 2>/dev/null <<'PY'
import os
import stat
import sys

destination, expected_parent_identity, owned_identity, inherited_parent_fd = sys.argv[1:]
name = os.path.basename(destination)
parent_fd = None
try:
    parent_fd = os.dup(int(inherited_parent_fd))
    parent_metadata = os.fstat(parent_fd)
    if (
        f"{parent_metadata.st_dev}:{parent_metadata.st_ino}" != expected_parent_identity
        or not stat.S_ISDIR(parent_metadata.st_mode)
        or stat.S_IMODE(parent_metadata.st_mode) != 0o700
        or parent_metadata.st_uid != os.geteuid()
    ):
        raise OSError
    destination_metadata = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
    if f"{destination_metadata.st_dev}:{destination_metadata.st_ino}" != owned_identity:
        raise OSError
    os.unlink(name, dir_fd=parent_fd)
except OSError:
    raise SystemExit(1)
finally:
    if parent_fd is not None:
        os.close(parent_fd)
PY
}

diagnostic_snapshot_private_state() {
  local category child
  [[ "${DIAGNOSTIC_ENABLED:-0}" == 1 ]] || return 0
  category="$(<"$DIAGNOSTIC_ERROR_FILE")" || return 1
  child="$(<"$DIAGNOSTIC_MANAGED_STATUS_FILE")" || return 1
  diagnostic_token_is_valid "$category" || return 1
  [[ -z "$child" ]] && child=none
  [[ "$child" == none || "$child" =~ ^([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$ ]] || return 1
  DIAGNOSTIC_ERROR_CATEGORY="$category"
  DIAGNOSTIC_CHILD_EXIT_STATUS="$child"
}

diagnostic_publish() {
  local outcome="$1"
  local cleanup="$2"
  [[ "${DIAGNOSTIC_ENABLED:-0}" == 1 ]] || return 0
  diagnostic_token_is_valid "$DIAGNOSTIC_OPERATION" || return 1
  diagnostic_token_is_valid "$DIAGNOSTIC_STAGE" || return 1
  diagnostic_token_is_valid "$DIAGNOSTIC_ERROR_CATEGORY" || return 1
  [[ "$outcome" == success || "$outcome" == failure ]] || return 1
  [[ "$cleanup" == complete || "$cleanup" == failed ]] || return 1
  [[ "$DIAGNOSTIC_CHILD_EXIT_STATUS" == none ||
     "$DIAGNOSTIC_CHILD_EXIT_STATUS" =~ ^([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$ ]] || return 1
  [[ "${DIAGNOSTIC_PARENT_IDENTITY:-}" =~ ^[0-9]+:[0-9]+$ ]] || return 1
  [[ "${DIAGNOSTIC_PARENT_FD:-}" =~ ^[0-9]+$ ]] || return 1
  [[ "${DIAGNOSTIC_IDENTITY_WRITE_FD:-}" =~ ^[0-9]+$ ]] || return 1
  python3 - "$DIAGNOSTIC_DESTINATION" "$DIAGNOSTIC_OPERATION" "$DIAGNOSTIC_STAGE" \
    "$outcome" "$DIAGNOSTIC_ERROR_CATEGORY" "$DIAGNOSTIC_CHILD_EXIT_STATUS" "$cleanup" \
    "$DIAGNOSTIC_PARENT_IDENTITY" "$DIAGNOSTIC_PARENT_FD" \
    "$DIAGNOSTIC_IDENTITY_WRITE_FD" 2>/dev/null <<'PY'
import os
import secrets
import stat
import sys

(
    destination,
    operation,
    stage,
    outcome,
    category,
    child,
    cleanup,
    expected_parent_identity,
    inherited_parent_fd,
    identity_fd,
) = sys.argv[1:]
identity_fd = int(identity_fd)
parent = os.path.dirname(destination)
destination_name = os.path.basename(destination)
parent_fd = None
temporary = None
temporary_identity = None
published_identity = None

def remove_owned_temporary():
    if parent_fd is None or temporary is None or temporary_identity is None:
        return
    try:
        metadata = os.stat(temporary, dir_fd=parent_fd, follow_symlinks=False)
        if (metadata.st_dev, metadata.st_ino) == temporary_identity:
            os.unlink(temporary, dir_fd=parent_fd)
    except OSError:
        pass

def remove_owned_destination():
    if parent_fd is None or published_identity is None:
        return
    try:
        metadata = os.stat(destination_name, dir_fd=parent_fd, follow_symlinks=False)
        if (metadata.st_dev, metadata.st_ino) == published_identity:
            os.unlink(destination_name, dir_fd=parent_fd)
    except OSError:
        pass

def open_verified(name, identity, expected_links):
    descriptor = os.open(name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=parent_fd)
    try:
        metadata = os.fstat(descriptor)
        if (
            not stat.S_ISREG(metadata.st_mode)
            or stat.S_IMODE(metadata.st_mode) != 0o600
            or metadata.st_nlink != expected_links
            or (metadata.st_dev, metadata.st_ino) != identity
        ):
            raise OSError
        content = bytearray()
        while True:
            chunk = os.read(descriptor, 4096)
            if not chunk:
                break
            content.extend(chunk)
        return bytes(content)
    finally:
        os.close(descriptor)

def verify_current_parent():
    descriptor = os.open(parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        metadata = os.fstat(descriptor)
        if (
            f"{metadata.st_dev}:{metadata.st_ino}" != expected_parent_identity
            or not stat.S_ISDIR(metadata.st_mode)
            or stat.S_IMODE(metadata.st_mode) != 0o700
            or metadata.st_uid != os.geteuid()
        ):
            raise OSError
    finally:
        os.close(descriptor)

try:
    parent_fd = os.dup(int(inherited_parent_fd))
    parent_metadata = os.fstat(parent_fd)
    if (
        not stat.S_ISDIR(parent_metadata.st_mode)
        or stat.S_IMODE(parent_metadata.st_mode) != 0o700
        or parent_metadata.st_uid != os.geteuid()
        or f"{parent_metadata.st_dev}:{parent_metadata.st_ino}" != expected_parent_identity
    ):
        raise OSError
    verify_current_parent()
    try:
        os.stat(destination_name, dir_fd=parent_fd, follow_symlinks=False)
    except FileNotFoundError:
        pass
    else:
        raise OSError
    payload = (
        "version=1\n"
        f"operation={operation}\n"
        f"stage={stage}\n"
        f"outcome={outcome}\n"
        f"error_category={category}\n"
        f"child_exit_status={child}\n"
        f"cleanup={cleanup}\n"
    ).encode("ascii")
    for unused_attempt in range(32):
        temporary = ".voice-step-diagnostic." + secrets.token_hex(12)
        try:
            descriptor = os.open(
                temporary,
                os.O_RDWR | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                0o600,
                dir_fd=parent_fd,
            )
            break
        except FileExistsError:
            temporary = None
    else:
        raise OSError
    temporary_metadata = os.fstat(descriptor)
    temporary_identity = (temporary_metadata.st_dev, temporary_metadata.st_ino)
    os.fchmod(descriptor, 0o600)
    with os.fdopen(descriptor, "wb") as handle:
        handle.write(payload)
        handle.flush()
        os.fsync(handle.fileno())
    os.link(
        temporary,
        destination_name,
        src_dir_fd=parent_fd,
        dst_dir_fd=parent_fd,
        follow_symlinks=False,
    )
    published_identity = temporary_identity
    temporary_metadata = os.stat(temporary, dir_fd=parent_fd, follow_symlinks=False)
    destination_metadata = os.stat(
        destination_name, dir_fd=parent_fd, follow_symlinks=False
    )
    if (
        not stat.S_ISREG(temporary_metadata.st_mode)
        or not stat.S_ISREG(destination_metadata.st_mode)
        or stat.S_IMODE(temporary_metadata.st_mode) != 0o600
        or stat.S_IMODE(destination_metadata.st_mode) != 0o600
        or temporary_metadata.st_nlink != 2
        or destination_metadata.st_nlink != 2
        or (temporary_metadata.st_dev, temporary_metadata.st_ino) != temporary_identity
        or (temporary_metadata.st_dev, temporary_metadata.st_ino)
        != (destination_metadata.st_dev, destination_metadata.st_ino)
    ):
        raise OSError
    if open_verified(destination_name, temporary_identity, 2) != payload:
        raise OSError
    remove_owned_temporary()
    temporary = None
    if open_verified(destination_name, temporary_identity, 1) != payload:
        raise OSError
    verify_current_parent()
    identity = f"{temporary_identity[0]}:{temporary_identity[1]}\n".encode("ascii")
    if os.write(
        identity_fd,
        identity,
    ) != len(identity):
        raise OSError
except Exception:
    remove_owned_temporary()
    remove_owned_destination()
    raise SystemExit(1)
finally:
    if parent_fd is not None:
        os.close(parent_fd)
PY
}
