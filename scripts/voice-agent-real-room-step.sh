#!/usr/bin/env bash
set -euo pipefail
umask 077
set +x

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACT_HELPERS="$ROOT_DIR/scripts/voice-agent-e2e-artifacts.sh"
REAL_ROOM_LIBRARY="$ROOT_DIR/scripts/voice-agent-real-room-lib.sh"
REAL_ROOM_DIAGNOSTICS="$ROOT_DIR/scripts/voice-agent-real-room-diagnostics.sh"
REAL_ROOM_CONTRACT="$ROOT_DIR/scripts/voice-agent-real-room-contract.py"
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
source "$REAL_ROOM_DIAGNOSTICS"
source "$REAL_ROOM_LIBRARY"

MDEV="${MDEV:-mdev}"
MDEV_OWNER=''
MDEV_OWNER_HASH=''
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
ORDERED_BROADCAST_OUTPUT=''
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
DIAGNOSTIC_ENABLED=0
DIAGNOSTIC_OPERATION=''
DIAGNOSTIC_DESTINATION=''
DIAGNOSTIC_STAGE=''
DIAGNOSTIC_ERROR_CATEGORY='none'
DIAGNOSTIC_CHILD_EXIT_STATUS='none'
DIAGNOSTIC_ERROR_FILE=''
DIAGNOSTIC_MANAGED_STATUS_FILE=''
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
    elif (( stopped_status == 1 )); then
      PACKAGE_FORCE_STOP_OWNED=0
      cleanup_status=1
    else
      cleanup_status=1
    fi
    if (( PACKAGE_FORCE_STOP_OWNED == 1 )); then
      restore_force_stopped_package || cleanup_status=1
    fi
  fi
  return "$cleanup_status"
}

on_exit() {
  local status=$?
  local cleanup_status=0
  trap defer_exit_cleanup_signal HUP INT TERM
  trap - EXIT
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
  if (( status == 0 && DIAGNOSTIC_ENABLED == 1 )); then
    diagnostic_publish success complete || status=1
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
  local expectation="$1"
  local status_snapshot
  local automation_snapshot
  local voice_snapshot
  local boundary
  local evaluation_status
  local -a status=()
  validate_runtime
  status_snapshot="$(read_status)"
  mapfile -t status <<< "$status_snapshot"
  [[ "${status[0]}" == active && "${status[1]}" == "$RUN_HASH" &&
     "${status[2]}" == "$COMPARISON_HASH" &&
     "${status[3]}" == "$TRANSPORT_EXPECTED" ]] || die 'status binding mismatch'
  read_call_service_active
  read_checkpoint_artifact_snapshots automation_snapshot voice_snapshot
  set +e
  boundary="$(python3 "$REAL_ROOM_CONTRACT" --evaluate "$expectation" \
    "$automation_snapshot" "$voice_snapshot" "$RUN_HASH" "$COMPARISON_HASH" \
    2000000000 2>/dev/null)"
  evaluation_status=$?
  set -e
  if (( evaluation_status != 0 )); then
    [[ "$boundary" =~ ^[a-z][a-z0-9_]{0,63}$ ]] || boundary=evidence
    die "checkpoint $boundary not proven"
  fi
  [[ -z "$boundary" ]] || die 'checkpoint evidence not proven'
  cleanup_local_temps || die 'cleanup failed'
  printf '%s\n' \
    'voice-step.status=ok' \
    'voice-step.operation=status' \
    "voice-step.expectation=$expectation" \
    'voice-step.expectation_met=true'
}

complete_finalize_outcome() {
  local destination="$1"
  local outcome="$2"
  local reason="$3"
  local call_stopped="$4"
  local automation_finalized="$5"
  local forced_fallback_used="$6"
  publish_finalization_record "$destination" "$outcome" "$reason" \
    "$call_stopped" "$automation_finalized" "$forced_fallback_used"
  cleanup_local_temps || die 'cleanup failed'
  printf '%s\n' \
    'voice-step.status=ok' \
    'voice-step.operation=finalize' \
    "voice-step.outcome=$outcome"
}

complete_finalization_failure() {
  local destination="$1"
  local original_reason="$2"
  local force_status=0
  local stopped_status
  local reason="$original_reason"
  local forced_fallback_used=false
  PACKAGE_FORCE_STOP_OWNED=1
  adb_read shell cmd activity force-stop --user "$ANDROID_USER_ID" "$PACKAGE" \
    </dev/null >/dev/null 2>&1 || force_status=$?
  if read_package_stopped_state true; then
    stopped_status=0
  else
    stopped_status=$?
  fi
  if (( force_status == 0 || stopped_status == 0 )); then
    reason=forced_fallback_used
    forced_fallback_used=true
  fi
  if (( stopped_status == 1 )); then
    PACKAGE_FORCE_STOP_OWNED=0
  fi

  # Product classification is monotonic. Publish it before best-effort
  # quiescence and restoration so later access failures cannot erase or
  # upgrade the already-classifiable outcome.
  publish_finalization_record "$destination" product_failure "$reason" \
    false false "$forced_fallback_used"
  if (( stopped_status == 0 )); then
    prove_package_quiescence >/dev/null 2>&1 || true
  fi
  if (( PACKAGE_FORCE_STOP_OWNED == 1 )); then
    restore_force_stopped_package >/dev/null 2>&1 || true
  fi
  cleanup_local_temps || die 'cleanup failed'
  printf '%s\n' \
    'voice-step.status=ok' \
    'voice-step.operation=finalize' \
    'voice-step.outcome=product_failure'
}

run_finalize() {
  local finalization_output="$1"
  local end_status=0
  local stop_status
  local reply
  local reply_status
  local status_snapshot
  local status_status
  local terminal_status
  local device_access
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
    </dev/null >/dev/null 2>&1 || end_status=$?
  if (( end_status != 0 )); then
    if classify_device_access; then
      device_access=0
    else
      device_access=$?
    fi
    case "$device_access" in
      0) complete_finalization_failure "$finalization_output" bound_call_rejected ;;
      1) complete_finalize_outcome "$finalization_output" infrastructure_interruption \
           device_unavailable false false false ;;
      3) complete_finalize_outcome "$finalization_output" infrastructure_interruption \
           adb_route_unavailable false false false ;;
      *) die 'ambiguous finalization readback' ;;
    esac
    return
  fi
  if wait_for_durable_call_stopped; then
    stop_status=0
  else
    stop_status=$?
  fi
  case "$stop_status" in
    0) ;;
    1) complete_finalization_failure "$finalization_output" call_stop_timeout; return ;;
    4) complete_finalization_failure "$finalization_output" call_stop_failed; return ;;
    3)
      if classify_device_access; then
        complete_finalization_failure "$finalization_output" persistence_drain_failed
      else
        case "$?" in
          1) complete_finalize_outcome "$finalization_output" infrastructure_interruption \
               device_unavailable false false false ;;
          3) complete_finalize_outcome "$finalization_output" infrastructure_interruption \
               adb_route_unavailable false false false ;;
          *) die 'ambiguous finalization readback' ;;
        esac
      fi
      return
      ;;
    *) die 'invalid durable call-stop evidence' ;;
  esac

  if reply="$(ordered_broadcast_read --user "$ANDROID_USER_ID" \
      -n "$PACKAGE/$CONTROL_RECEIVER" -a "$CONTROL_ACTION_PREFIX.FINALIZE_BOUND" \
      --es run_hash "$RUN_HASH" \
      --es comparison_hash "$COMPARISON_HASH" \
      --es transport "$TRANSPORT_EXPECTED")"; then
    reply_status=0
  else
    reply_status=$?
  fi
  case "$reply_status" in
    0)
      [[ "$reply" == $'status=ok\naction=finalize' ]] ||
        die 'unexpected receiver response'
      ;;
    3)
      case "$reply" in
        $'status=rejected\nreason=call_not_stopped'|$'status=rejected\nreason=binding_mismatch')
          complete_finalize_outcome "$finalization_output" product_failure \
            automation_finalize_rejected true false false
          return
          ;;
        $'status=error\nerror=invalid_request'|$'status=error\nerror=invalid_state'|$'status=error\nerror=runtime_failure')
          complete_finalize_outcome "$finalization_output" product_failure \
            automation_finalize_failed true false false
          return
          ;;
        *) die 'unexpected receiver response' ;;
      esac
      ;;
    4)
      if classify_device_access; then
        complete_finalize_outcome "$finalization_output" product_failure \
          automation_finalize_failed true false false
      else
        case "$?" in
          1) complete_finalize_outcome "$finalization_output" infrastructure_interruption \
               device_unavailable false false false ;;
          3) complete_finalize_outcome "$finalization_output" infrastructure_interruption \
               adb_route_unavailable false false false ;;
          *) die 'ambiguous finalization readback' ;;
        esac
      fi
      return
      ;;
    *) die 'unexpected receiver response' ;;
  esac
  if status_snapshot="$(read_status_snapshot)"; then
    status_status=0
  else
    status_status=$?
  fi
  if (( status_status != 0 )); then
    (( status_status == 3 )) || die 'unexpected status response'
    if classify_device_access; then
      complete_finalize_outcome "$finalization_output" product_failure \
        automation_finalize_failed true false false
    else
      case "$?" in
        1) complete_finalize_outcome "$finalization_output" infrastructure_interruption \
             device_unavailable false false false ;;
        3) complete_finalize_outcome "$finalization_output" infrastructure_interruption \
             adb_route_unavailable false false false ;;
        *) die 'ambiguous finalization readback' ;;
      esac
    fi
    return
  fi
  mapfile -t status <<< "$status_snapshot"
  [[ "${status[1]}" == "$RUN_HASH" && "${status[2]}" == "$COMPARISON_HASH" &&
     "${status[3]}" == "$TRANSPORT_EXPECTED" ]] || die 'finalized status mismatch'
  if [[ "${status[0]}" == active ]]; then
    complete_finalize_outcome "$finalization_output" product_failure \
      automation_finalize_failed true false false
    return
  fi
  [[ "${status[0]}" == finalized ]] || die 'finalized status mismatch'
  if read_automation_terminal_snapshot finalized; then
    terminal_status=0
  else
    terminal_status=$?
  fi
  case "$terminal_status" in
    0) ;;
    1)
      complete_finalize_outcome "$finalization_output" product_failure \
        automation_finalize_failed true false false
      return
      ;;
    3)
      if classify_device_access; then
        complete_finalize_outcome "$finalization_output" product_failure \
          automation_finalize_failed true false false
      else
        case "$?" in
          1) complete_finalize_outcome "$finalization_output" infrastructure_interruption \
               device_unavailable false false false ;;
          3) complete_finalize_outcome "$finalization_output" infrastructure_interruption \
               adb_route_unavailable false false false ;;
          *) die 'ambiguous finalization readback' ;;
        esac
      fi
      return
      ;;
    *) die 'invalid durable final evidence' ;;
  esac
  complete_finalize_outcome "$finalization_output" complete complete true true false
}

run_capture() {
  local finalization_path="$1"
  local automation_output="$2"
  local private_output="$3"
  local sanitized_output="$4"
  local automation_source
  local private_source
  local sanitized_source
  local automation_temp
  local private_temp
  local sanitized_temp
  local finalization_snapshot
  local finalization_check
  local finalization_values
  local outcome
  local call_stopped
  local automation_finalized
  local forced_fallback_used
  local force_stop_status=0
  local stopped_status
  local quiescence_status
  local status_before
  local status_after
  local -a status=()
  local -a finalization=()

  snapshot_finalization_record "$finalization_path" finalization_snapshot
  finalization_values="$(read_finalization_values "$finalization_snapshot")" ||
    die 'invalid finalization record'
  mapfile -t finalization <<< "$finalization_values"
  [[ "${#finalization[@]}" == 5 ]] || die 'invalid finalization record'
  outcome="${finalization[0]}"
  call_stopped="${finalization[2]}"
  automation_finalized="${finalization[3]}"
  forced_fallback_used="${finalization[4]}"
  validate_runtime

  case "$outcome" in
    complete)
      [[ "$call_stopped" == true && "$automation_finalized" == true &&
         "$forced_fallback_used" == false ]] || die 'invalid finalization record'
      status_before="$(read_status)"
      mapfile -t status <<< "$status_before"
      [[ "${status[0]}" == finalized && "${status[1]}" == "$RUN_HASH" &&
         "${status[2]}" == "$COMPARISON_HASH" &&
         "${status[3]}" == "$TRANSPORT_EXPECTED" ]] || die 'finalized status mismatch'
      ;;
    product_failure)
      PACKAGE_FORCE_STOP_OWNED=1
      adb_read shell cmd activity force-stop --user "$ANDROID_USER_ID" "$PACKAGE" \
        </dev/null >/dev/null 2>&1 || force_stop_status=$?
      (( force_stop_status == 0 )) || die 'capture quiescence not proven'
      if read_package_stopped_state true; then
        stopped_status=0
      else
        stopped_status=$?
      fi
      (( stopped_status == 0 )) || die 'capture quiescence not proven'
      if prove_package_quiescence; then
        quiescence_status=0
      else
        quiescence_status=$?
      fi
      (( quiescence_status == 0 )) || die 'capture quiescence not proven'
      ;;
    *) die 'capture unavailable for interrupted infrastructure' ;;
  esac

  automation_source="$(app_artifact_path "$APP_ARTIFACT_ROOT/${RUN_HASH#sha256:}" automation-events.jsonl)"
  private_source="$(app_artifact_path "$APP_ARTIFACT_ROOT/$TRACE_ID" voice-experience-private.ndjson)"
  sanitized_source="$(app_artifact_path "$APP_ARTIFACT_ROOT/$TRACE_ID" voice-experience-events.ndjson)"
  read_capture_bundle_snapshots \
    "$automation_source" "$private_source" "$sanitized_source" \
    "$automation_output" "$private_output" "$sanitized_output" \
    automation_temp private_temp sanitized_temp
  python3 "$REAL_ROOM_CONTRACT" --validate-capture \
    "$automation_temp" "$private_temp" "$sanitized_temp" \
    "$RUN_HASH" "$COMPARISON_HASH" "$finalization_snapshot" \
    >/dev/null 2>&1 || die 'captured evidence violates contract'

  if [[ "$outcome" == complete ]]; then
    status_after="$(read_status)"
    [[ "$status_after" == "$status_before" ]] || die 'status changed during capture'
  else
    restore_force_stopped_package || die 'package restoration failed'
  fi
  snapshot_finalization_record "$finalization_path" finalization_check
  cmp -s -- "$finalization_snapshot" "$finalization_check" ||
    die 'finalization record changed'
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
  local device_state
  local reachability
  device_state="$(adb_read get-state 2>/dev/null)" || return 1
  device_state="${device_state//$'\r'/}"
  device_state="${device_state//$'\n'/}"
  [[ "$device_state" == device ]] || return 2
  reachability="$(adb_read shell echo voice-step-reachable 2>/dev/null)" || return 3
  [[ "$reachability" == voice-step-reachable ]] || return 2
  return 0
}

complete_end_outcome() {
  local destination="$1"
  local finalization_snapshot="$2"
  local outcome="$3"
  local call_stopped="$4"
  local automation_finalized="$5"
  local fixtures_removed="$6"
  local finalization_hash
  finalization_hash="sha256:$(sha256sum -- "$finalization_snapshot" | awk '{print $1}')" ||
    die 'finalization hash failed'
  publish_cleanup_record "$destination" "$outcome" "$call_stopped" \
    "$automation_finalized" "$fixtures_removed" "$finalization_hash"
  cleanup_local_temps || die 'cleanup failed'
  printf '%s\n' \
    'voice-step.status=ok' \
    'voice-step.operation=end' \
    "voice-step.outcome=$outcome"
}

run_end() {
  local finalization_path="$1"
  local cleanup_output="$2"
  local remote_fixture_dir
  local automation_source
  local baseline_temp
  local post_cleanup_temp
  local finalization_snapshot
  local finalization_check
  local finalization_values
  local outcome
  local call_stopped
  local automation_finalized
  local forced_fallback_used
  local stopped_status
  local force_stop_status=0
  local quiescence_status
  local cleanup_status
  local fixtures_removed=false
  local -a finalization=()

  snapshot_finalization_record "$finalization_path" finalization_snapshot
  finalization_values="$(read_finalization_values "$finalization_snapshot")" ||
    die 'invalid finalization record'
  mapfile -t finalization <<< "$finalization_values"
  [[ "${#finalization[@]}" == 5 ]] || die 'invalid finalization record'
  outcome="${finalization[0]}"
  call_stopped="${finalization[2]}"
  automation_finalized="${finalization[3]}"
  forced_fallback_used="${finalization[4]}"
  validate_runtime
  remote_fixture_dir="files/voice-real-room/${RUN_HASH#sha256:}"
  automation_source="$(app_artifact_path "$APP_ARTIFACT_ROOT/${RUN_HASH#sha256:}" automation-events.jsonl)"

  read_stable_artifact "$automation_source" "$cleanup_output" baseline_temp
  python3 "$REAL_ROOM_CONTRACT" --validate-automation-finalization \
    "$baseline_temp" "$RUN_HASH" "$COMPARISON_HASH" "$finalization_snapshot" \
    >/dev/null 2>&1 || die 'invalid durable final evidence'

  PACKAGE_FORCE_STOP_OWNED=1
  adb_read shell cmd activity force-stop --user "$ANDROID_USER_ID" "$PACKAGE" \
    </dev/null >/dev/null 2>&1 || force_stop_status=$?
  if read_package_stopped_state true; then
    stopped_status=0
  else
    stopped_status=$?
  fi
  case "$stopped_status" in
    0) ;;
    1)
      PACKAGE_FORCE_STOP_OWNED=0
      [[ "$outcome" != complete ]] || die 'complete cleanup failed'
      snapshot_finalization_record "$finalization_path" finalization_check
      cmp -s -- "$finalization_snapshot" "$finalization_check" ||
        die 'finalization record changed'
      complete_end_outcome "$cleanup_output" "$finalization_snapshot" \
        "$outcome" "$call_stopped" "$automation_finalized" false
      return
      ;;
    *) die 'ambiguous package stopped-state readback' ;;
  esac
  (( force_stop_status == 0 )) || {
    restore_force_stopped_package || die 'package restoration failed'
    [[ "$outcome" != complete ]] || die 'complete cleanup failed'
    snapshot_finalization_record "$finalization_path" finalization_check
    cmp -s -- "$finalization_snapshot" "$finalization_check" ||
      die 'finalization record changed'
    complete_end_outcome "$cleanup_output" "$finalization_snapshot" \
      "$outcome" "$call_stopped" "$automation_finalized" false
    return
  }
  if prove_package_quiescence; then
    quiescence_status=0
  else
    quiescence_status=$?
  fi
  case "$quiescence_status" in
    0) ;;
    1|2)
      restore_force_stopped_package || die 'package restoration failed'
      [[ "$outcome" != complete ]] || die 'complete cleanup failed'
      snapshot_finalization_record "$finalization_path" finalization_check
      cmp -s -- "$finalization_snapshot" "$finalization_check" ||
        die 'finalization record changed'
      complete_end_outcome "$cleanup_output" "$finalization_snapshot" \
        "$outcome" "$call_stopped" "$automation_finalized" false
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
    0) fixtures_removed=true ;;
    1) fixtures_removed=false ;;
    2) die 'ambiguous fixture cleanup readback' ;;
    *) die 'ambiguous fixture cleanup readback' ;;
  esac

  read_stable_artifact "$automation_source" "$cleanup_output" post_cleanup_temp
  cmp -s -- "$baseline_temp" "$post_cleanup_temp" || die 'artifact source changed'
  python3 "$REAL_ROOM_CONTRACT" --validate-automation-finalization \
    "$post_cleanup_temp" "$RUN_HASH" "$COMPARISON_HASH" "$finalization_snapshot" \
    >/dev/null 2>&1 || die 'invalid durable final evidence'
  read_package_stopped_state true || die 'ambiguous package stopped-state readback'

  restore_force_stopped_package || die 'package restoration failed'
  snapshot_finalization_record "$finalization_path" finalization_check
  cmp -s -- "$finalization_snapshot" "$finalization_check" ||
    die 'finalization record changed'
  [[ "$outcome" != complete || "$fixtures_removed" == true ]] ||
    die 'complete cleanup failed'
  complete_end_outcome "$cleanup_output" "$finalization_snapshot" \
    "$outcome" "$call_stopped" "$automation_finalized" "$fixtures_removed"
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
  diagnostic_set_stage complete || die 'diagnostic state failed'
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
  local stored_mdev_owner_hash="${state[0]}"
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
  [[ "$stored_mdev_owner_hash" == "$MDEV_OWNER_HASH" ]] || die 'managed owner mismatch'
  case "$requested_operation" in
    inject|interrupt|status|finalize|capture|end)
      validate_runtime
      acquire_host_operation_lock
      ;;
  esac
  case "$requested_operation" in
    inject) run_inject "$@" ;;
    interrupt) run_interrupt "$@" ;;
    status) run_status_operation "$@" ;;
    finalize) run_finalize "$@" ;;
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
    parse_options '--mdev-owner --package' "$@"
    require_options --mdev-owner --package
    MDEV_OWNER="${PARSED[--mdev-owner]}"
    PACKAGE="${PARSED[--package]}"
    prepare_mdev_owner
    validate_package "$PACKAGE"
    run_preflight
    ;;
  start)
    parse_options '--state --diagnostic-record --mdev-owner --package --conversation-id --run-hash --comparison-hash --fixture' "$@"
    require_options --mdev-owner --state --package --conversation-id --run-hash --comparison-hash --fixture
    if [[ -n "${PARSED[--diagnostic-record]+present}" ]]; then
      [[ -n "${PARSED[--diagnostic-record]}" ]] || die 'invalid diagnostic destination'
      validate_private_diagnostic_destination "${PARSED[--diagnostic-record]}" ||
        die 'invalid diagnostic destination'
      [[ "${PARSED[--diagnostic-record]}" != "${PARSED[--state]}" ]] ||
        die 'diagnostic destination must differ from state'
      diagnostic_initialize start "${PARSED[--diagnostic-record]}" ||
        die 'diagnostic initialization failed'
    fi
    MDEV_OWNER="${PARSED[--mdev-owner]}"
    PACKAGE="${PARSED[--package]}"
    CONVERSATION_ID="${PARSED[--conversation-id]}"
    RUN_HASH="${PARSED[--run-hash]}"
    COMPARISON_HASH="${PARSED[--comparison-hash]}"
    prepare_mdev_owner
    validate_package "$PACKAGE"
    validate_identifier "$CONVERSATION_ID" 'conversation id'
    validate_hash "$RUN_HASH" 'run hash'
    validate_hash "$COMPARISON_HASH" 'comparison hash'
    validate_absent_destination "${PARSED[--state]}" || die 'invalid state destination'
    run_start "${PARSED[--state]}" "${PARSED[--fixture]}"
    ;;
  inject)
    parse_options '--mdev-owner --state --fixture --role' "$@"
    if [[ -n "${PARSED[--role]+present}" ]]; then
      case "${PARSED[--role]}" in
        request|follow_up|interruption) ;;
        *) die 'invalid fixture role' ;;
      esac
    fi
    require_options --mdev-owner --state --fixture --role
    MDEV_OWNER="${PARSED[--mdev-owner]}"
    prepare_mdev_owner
    run_with_decoded_state inject "${PARSED[--state]}" \
      "${PARSED[--fixture]}" "${PARSED[--role]}"
    ;;
  interrupt)
    parse_options '--mdev-owner --state --fixture' "$@"
    require_options --mdev-owner --state --fixture
    MDEV_OWNER="${PARSED[--mdev-owner]}"
    prepare_mdev_owner
    run_with_decoded_state interrupt "${PARSED[--state]}" "${PARSED[--fixture]}"
    ;;
  status)
    parse_options '--mdev-owner --state --expect' "$@"
    require_options --mdev-owner --state --expect
    MDEV_OWNER="${PARSED[--mdev-owner]}"
    prepare_mdev_owner
    python3 "$REAL_ROOM_CONTRACT" --validate-expectation "${PARSED[--expect]}" \
      >/dev/null 2>&1 || die 'invalid expectation'
    CHECKPOINT_ERROR_MODE=1
    # A failure inside command substitution reports from a child shell, whose
    # ERROR_REPORTED update cannot reach the parent EXIT trap. Pre-arm the
    # parent so that one routed diagnostic is not followed by a generic one.
    ERROR_REPORTED=1
    run_with_decoded_state status "${PARSED[--state]}" "${PARSED[--expect]}"
    ERROR_REPORTED=0
    ;;
  finalize)
    parse_options '--mdev-owner --state --finalization-output' "$@"
    require_options --mdev-owner --state --finalization-output
    MDEV_OWNER="${PARSED[--mdev-owner]}"
    prepare_mdev_owner
    validate_absent_destination "${PARSED[--finalization-output]}" ||
      die 'invalid finalization destination'
    run_with_decoded_state finalize "${PARSED[--state]}" \
      "${PARSED[--finalization-output]}"
    ;;
  capture)
    parse_options '--mdev-owner --state --finalization --automation-output --private-voice-output --sanitized-voice-output' "$@"
    require_options --mdev-owner --state --finalization --automation-output --private-voice-output --sanitized-voice-output
    MDEV_OWNER="${PARSED[--mdev-owner]}"
    prepare_mdev_owner
    validate_absent_destination "${PARSED[--automation-output]}" || die 'invalid output destination'
    validate_absent_destination "${PARSED[--private-voice-output]}" || die 'invalid output destination'
    validate_absent_destination "${PARSED[--sanitized-voice-output]}" || die 'invalid output destination'
    validate_distinct_destinations \
      "${PARSED[--automation-output]}" \
      "${PARSED[--private-voice-output]}" \
      "${PARSED[--sanitized-voice-output]}" || die 'output destinations must be distinct'
    run_with_decoded_state capture "${PARSED[--state]}" \
      "${PARSED[--finalization]}" \
      "${PARSED[--automation-output]}" \
      "${PARSED[--private-voice-output]}" \
      "${PARSED[--sanitized-voice-output]}"
    ;;
  end)
    parse_options '--mdev-owner --state --finalization --cleanup-output' "$@"
    require_options --mdev-owner --state --finalization --cleanup-output
    MDEV_OWNER="${PARSED[--mdev-owner]}"
    prepare_mdev_owner
    validate_absent_destination "${PARSED[--cleanup-output]}" || die 'invalid cleanup destination'
    run_with_decoded_state end "${PARSED[--state]}" \
      "${PARSED[--finalization]}" "${PARSED[--cleanup-output]}"
    ;;
  *)
    die 'invalid operation'
    ;;
esac
