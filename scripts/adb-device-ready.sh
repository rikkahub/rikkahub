#!/usr/bin/env bash
set -euo pipefail

SERIAL="${1:-${VOICE_AGENT_E2E_SERIAL:-}}"
ADB_TIMEOUT_SECONDS="${ADB_DEVICE_READY_TIMEOUT_SECONDS:-10}"

if ! command -v adb >/dev/null 2>&1; then
  printf 'adb was not found in PATH.\n' >&2
  exit 2
fi

run_adb() {
  local timeout_seconds="$1"
  shift
  set +e
  timeout "${timeout_seconds}s" adb "$@"
  local status=$?
  set -e
  if [[ "$status" -eq 124 ]]; then
    printf 'ADB command timed out after %ss.\n' "$timeout_seconds" >&2
  fi
  return "$status"
}

adb_for_serial() {
  run_adb "$ADB_TIMEOUT_SECONDS" -s "$SERIAL" "$@"
}

read_devices() {
  run_adb "$ADB_TIMEOUT_SECONDS" devices -l
}

devices_output="$(read_devices)"

if [[ -n "$SERIAL" ]]; then
  device_line="$(printf '%s\n' "$devices_output" | awk -v serial="$SERIAL" '$1 == serial { print; exit }')"
  if [[ -z "$device_line" ]]; then
    printf 'ADB device %s was not found.\n' "$SERIAL" >&2
    printf '%s\n' "$devices_output" >&2
    exit 5
  fi
else
  device_count="$(printf '%s\n' "$devices_output" | awk '$2 == "device" { count++ } END { print count + 0 }')"
  if [[ "$device_count" != "1" ]]; then
    printf 'Expected exactly one authorized ADB device, found %s. Set VOICE_AGENT_E2E_SERIAL or pass a serial.\n' \
      "$device_count" >&2
    printf '%s\n' "$devices_output" >&2
    exit 2
  fi
  SERIAL="$(printf '%s\n' "$devices_output" | awk '$2 == "device" { print $1; exit }')"
  device_line="$(printf '%s\n' "$devices_output" | awk -v serial="$SERIAL" '$1 == serial { print; exit }')"
fi

state="$(printf '%s\n' "$device_line" | awk '{ print $2 }')"
case "$state" in
  device)
    ;;
  unauthorized)
    printf 'ADB device %s is unauthorized. Unlock the phone and accept the USB debugging RSA prompt.\n' "$SERIAL" >&2
    exit 3
    ;;
  offline)
    printf 'ADB device %s is offline. Replug USB or restart ADB on the phone host.\n' "$SERIAL" >&2
    exit 4
    ;;
  *)
    printf 'ADB device %s is not ready. State: %s\n' "$SERIAL" "${state:-unknown}" >&2
    exit 5
    ;;
esac

shell_roundtrip="$(adb_for_serial shell echo ok | tr -d '\r')"
if [[ "$shell_roundtrip" != "ok" ]]; then
  printf 'ADB shell roundtrip failed for %s.\n' "$SERIAL" >&2
  exit 6
fi

boot_completed="$(adb_for_serial shell getprop sys.boot_completed | tr -d '\r')"
if [[ "$boot_completed" != "1" ]]; then
  printf 'ADB device %s is not fully booted. sys.boot_completed=%s\n' "$SERIAL" "${boot_completed:-empty}" >&2
  exit 7
fi

bootanim="$(adb_for_serial shell getprop init.svc.bootanim | tr -d '\r')"
if [[ "$bootanim" != "stopped" ]]; then
  printf 'ADB device %s boot animation is not stopped. init.svc.bootanim=%s\n' "$SERIAL" "${bootanim:-empty}" >&2
  exit 7
fi

model="$(adb_for_serial shell getprop ro.product.model | tr -d '\r')"
android="$(adb_for_serial shell getprop ro.build.version.release | tr -d '\r')"

printf 'ADB ready: serial=%s state=device boot_completed=1 bootanim=stopped model=%s android=%s\n' \
  "$SERIAL" "${model:-unknown}" "${android:-unknown}"
