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
  DIAGNOSTIC_PARENT_IDENTITY="$parent_identity"
}

validate_distinct_diagnostic_state_destination() {
  local state_destination="$1"
  local diagnostic_destination="$2"
  [[ "${STATE_PARENT_IDENTITY:-}" =~ ^[0-9]+:[0-9]+$ ]] || return 1
  [[ "${DIAGNOSTIC_PARENT_IDENTITY:-}" =~ ^[0-9]+:[0-9]+$ ]] || return 1
  [[ "$STATE_PARENT_IDENTITY:${state_destination##*/}" != \
     "$DIAGNOSTIC_PARENT_IDENTITY:${diagnostic_destination##*/}" ]]
}

diagnostic_initialize() {
  local operation="$1"
  local destination="$2"
  diagnostic_token_is_valid "$operation" || return 1
  [[ "$operation" == start ]] || return 1
  [[ "${DIAGNOSTIC_PARENT_IDENTITY:-}" =~ ^[0-9]+:[0-9]+$ ]] || return 1
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
  DIAGNOSTIC_CAPTURE_MANAGED_EXIT=1
}

diagnostic_set_stage() {
  local stage="$1"
  diagnostic_token_is_valid "$stage" || return 1
  [[ -z "${DIAGNOSTIC_MANAGED_STATUS_FILE:-}" ]] ||
    : > "$DIAGNOSTIC_MANAGED_STATUS_FILE"
  DIAGNOSTIC_CHILD_EXIT_STATUS='none'
  DIAGNOSTIC_STAGE="$stage"
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

diagnostic_snapshot_private_state() {
  local category child
  [[ "${DIAGNOSTIC_ENABLED:-0}" == 1 ]] || return 0
  [[ -e "$DIAGNOSTIC_ERROR_FILE" && -e "$DIAGNOSTIC_MANAGED_STATUS_FILE" ]] || return 1
  category="$(<"$DIAGNOSTIC_ERROR_FILE")" || return 1
  child="$(<"$DIAGNOSTIC_MANAGED_STATUS_FILE")" || return 1
  diagnostic_token_is_valid "$category" || return 1
  [[ -z "$child" ]] && child=none
  [[ "$child" == none || "$child" =~ ^([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$ ]] || return 1
  DIAGNOSTIC_ERROR_CATEGORY="$category"
  DIAGNOSTIC_CHILD_EXIT_STATUS="$child"
}

diagnostic_publish_failure() {
  local cleanup="$1"
  [[ "${DIAGNOSTIC_ENABLED:-0}" == 1 ]] || return 0
  diagnostic_token_is_valid "$DIAGNOSTIC_OPERATION" || return 1
  diagnostic_token_is_valid "$DIAGNOSTIC_STAGE" || return 1
  diagnostic_token_is_valid "$DIAGNOSTIC_ERROR_CATEGORY" || return 1
  [[ "$DIAGNOSTIC_ERROR_CATEGORY" != none ]] || return 1
  [[ "$cleanup" == complete || "$cleanup" == failed ]] || return 1
  [[ "$DIAGNOSTIC_CHILD_EXIT_STATUS" == none ||
     "$DIAGNOSTIC_CHILD_EXIT_STATUS" =~ ^([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$ ]] || return 1
  [[ "${DIAGNOSTIC_PARENT_IDENTITY:-}" =~ ^[0-9]+:[0-9]+$ ]] || return 1
  python3 "$REAL_ROOM_DIAGNOSTIC_PUBLISHER" \
    "$DIAGNOSTIC_DESTINATION" "$DIAGNOSTIC_PARENT_IDENTITY" \
    "$DIAGNOSTIC_OPERATION" "$DIAGNOSTIC_STAGE" \
    "$DIAGNOSTIC_ERROR_CATEGORY" "$DIAGNOSTIC_CHILD_EXIT_STATUS" "$cleanup" \
    </dev/null >/dev/null 2>&1
}
