#!/usr/bin/env bash
# Private, fixed-token diagnostics for voice-agent-real-room-step.sh.
# This file is sourced; it is not a public command surface.
[[ "${BASH_SOURCE[0]}" != "$0" ]] || {
  printf 'voice-step.error=library is not executable\n' >&2
  exit 1
}

diagnostic_token_is_valid() { [[ "$1" =~ ^[a-z][a-z0-9-]{0,63}$ ]]; }

validate_private_diagnostic_destination() {
  python3 - "$1" 2>/dev/null <<'PY'
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
PY
}

diagnostic_initialize() {
  local operation="$1"
  local destination="$2"
  diagnostic_token_is_valid "$operation" || return 1
  [[ "$operation" == start ]] || return 1
  ensure_local_temp_dir || return 1
  DIAGNOSTIC_ERROR_FILE="$(mktemp "$LOCAL_TEMP_DIR/diagnostic-error.XXXXXX" 2>/dev/null)" || return 1
  chmod 600 -- "$DIAGNOSTIC_ERROR_FILE" 2>/dev/null || return 1
  register_temp_file "$DIAGNOSTIC_ERROR_FILE"
  DIAGNOSTIC_MANAGED_STATUS_FILE="$(mktemp "$LOCAL_TEMP_DIR/diagnostic-managed-status.XXXXXX" 2>/dev/null)" || return 1
  chmod 600 -- "$DIAGNOSTIC_MANAGED_STATUS_FILE" 2>/dev/null || return 1
  register_temp_file "$DIAGNOSTIC_MANAGED_STATUS_FILE"
  printf 'none' > "$DIAGNOSTIC_ERROR_FILE" || return 1
  printf 'none' > "$DIAGNOSTIC_MANAGED_STATUS_FILE" || return 1
  DIAGNOSTIC_ENABLED=1
  DIAGNOSTIC_OPERATION="$operation"
  DIAGNOSTIC_DESTINATION="$destination"
  DIAGNOSTIC_STAGE='option-validation'
  DIAGNOSTIC_ERROR_CATEGORY='none'
  DIAGNOSTIC_CHILD_EXIT_STATUS='none'
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
  [[ -z "${DIAGNOSTIC_ERROR_FILE:-}" ]] ||
    printf '%s' "$category" > "$DIAGNOSTIC_ERROR_FILE"
}

diagnostic_note_managed_exit() {
  local status="$1"
  [[ "$status" =~ ^([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$ ]] || return 1
  DIAGNOSTIC_CHILD_EXIT_STATUS="$status"
  [[ -z "${DIAGNOSTIC_MANAGED_STATUS_FILE:-}" ]] ||
    printf '%s' "$status" > "$DIAGNOSTIC_MANAGED_STATUS_FILE"
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
  python3 - "$DIAGNOSTIC_DESTINATION" "$DIAGNOSTIC_OPERATION" "$DIAGNOSTIC_STAGE" \
    "$outcome" "$DIAGNOSTIC_ERROR_CATEGORY" "$DIAGNOSTIC_CHILD_EXIT_STATUS" "$cleanup" 2>/dev/null <<'PY'
import os
import stat
import sys
import tempfile

destination, operation, stage, outcome, category, child, cleanup = sys.argv[1:]
temporary = None
temporary_identity = None

def remove_owned_temporary():
    if temporary is None or temporary_identity is None:
        return
    try:
        metadata = os.lstat(temporary)
        if (metadata.st_dev, metadata.st_ino) == temporary_identity:
            os.unlink(temporary)
    except OSError:
        pass

try:
    parent = os.path.dirname(destination)
    parent_metadata = os.lstat(parent)
    if (
        stat.S_ISLNK(parent_metadata.st_mode)
        or not stat.S_ISDIR(parent_metadata.st_mode)
        or stat.S_IMODE(parent_metadata.st_mode) != 0o700
        or parent_metadata.st_uid != os.geteuid()
    ):
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
    descriptor, temporary = tempfile.mkstemp(prefix=".voice-step-diagnostic.", dir=parent)
    temporary_metadata = os.fstat(descriptor)
    temporary_identity = (temporary_metadata.st_dev, temporary_metadata.st_ino)
    os.fchmod(descriptor, 0o600)
    with os.fdopen(descriptor, "wb") as handle:
        handle.write(payload)
        handle.flush()
        os.fsync(handle.fileno())
    os.link(temporary, destination, follow_symlinks=False)
    temporary_metadata = os.lstat(temporary)
    destination_metadata = os.lstat(destination)
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
    with open(destination, "rb") as handle:
        if handle.read() != payload:
            raise OSError
    remove_owned_temporary()
    temporary = None
    destination_metadata = os.lstat(destination)
    if (
        not stat.S_ISREG(destination_metadata.st_mode)
        or stat.S_IMODE(destination_metadata.st_mode) != 0o600
        or destination_metadata.st_nlink != 1
    ):
        raise OSError
    with open(destination, "rb") as handle:
        if handle.read() != payload:
            raise OSError
except Exception:
    remove_owned_temporary()
    raise SystemExit(1)
PY
}
