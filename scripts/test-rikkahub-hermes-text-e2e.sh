#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HARNESS="$ROOT_DIR/scripts/rikkahub-hermes-text-e2e.sh"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

cat > "$TMP_DIR/adb" <<'FAKE_ADB'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "${FAKE_ADB_ARGS_LOG:?}"

args=("$@")
if [[ "${args[0]:-}" == "-s" ]]; then
  args=("${args[@]:2}")
fi

case "${args[*]}" in
  "get-state")
    if [[ "${FAKE_ADB_SCENARIO:-success}" == "missing_device" ]]; then
      printf 'offline\n'
    else
      printf 'device\n'
    fi
    ;;
  "shell pm path me.rerere.rikkahub.debug")
    printf 'package:/data/app/debug/base.apk\n'
    ;;
  "shell am broadcast"*)
    printf 'Broadcast completed: result=0\n'
    ;;
  "logcat -d -s HermesTextDebugE2E:I *:S")
    case "${FAKE_ADB_SCENARIO:-success}" in
      success)
        printf 'I/HermesTextDebugE2E: debug_hermes_text result=success exact=true http_status=200 request_origin=https://dev-remote-machine-1.tail83108.ts.net:8642\n'
        ;;
      wrong_host)
        printf 'I/HermesTextDebugE2E: debug_hermes_text result=success exact=true http_status=200 request_origin=https://wrong.example.test:8642\n'
        ;;
      http_failure)
        printf 'E/HermesTextDebugE2E: debug_hermes_text result=failure category=http_status\n'
        ;;
      timeout)
        ;;
    esac
    ;;
  "logcat -c")
    ;;
  *)
    printf 'Unexpected fake adb call: %s\n' "${args[*]}" >&2
    exit 91
    ;;
esac
FAKE_ADB
chmod +x "$TMP_DIR/adb"

export PATH="$TMP_DIR:$PATH"
export FAKE_ADB_ARGS_LOG="$TMP_DIR/adb-args.log"
export VOICE_AGENT_E2E_SERIAL="fake-serial"
export VOICE_AGENT_E2E_CONVERSATION_ID="11111111-1111-4111-8111-111111111111"
export VOICE_AGENT_E2E_TIMEOUT_SECONDS=1
export VOICE_AGENT_E2E_POLL_INTERVAL_SECONDS=0.05
export VOICE_AGENT_E2E_REPORT_DIR="$TMP_DIR/report"

run_harness() {
  FAKE_ADB_SCENARIO="$1" "$HARNESS" >"$TMP_DIR/$1.out" 2>"$TMP_DIR/$1.err"
}

assert_failure() {
  local scenario="$1"
  if run_harness "$scenario"; then
    printf 'Expected scenario %s to fail\n' "$scenario" >&2
    exit 1
  fi
}

: > "$FAKE_ADB_ARGS_LOG"
run_harness success
grep -Fx 'result=success' "$VOICE_AGENT_E2E_REPORT_DIR/result.txt" >/dev/null
grep -Fx 'exact=true' "$VOICE_AGENT_E2E_REPORT_DIR/result.txt" >/dev/null
grep -Fx 'http_status=200' "$VOICE_AGENT_E2E_REPORT_DIR/result.txt" >/dev/null
grep -Fx 'request_origin=https://dev-remote-machine-1.tail83108.ts.net:8642' \
  "$VOICE_AGENT_E2E_REPORT_DIR/result.txt" >/dev/null
if grep -Eq '11111111-1111-4111-8111-111111111111|Reply exactly|hermes-device-ok' \
  "$VOICE_AGENT_E2E_REPORT_DIR/result.txt"; then
  printf 'Sanitized report contains request content\n' >&2
  exit 1
fi
grep -F 'SEND_HERMES_TEXT' "$FAKE_ADB_ARGS_LOG" >/dev/null
cleanup_count="$(grep -Fc 'logcat -c' "$FAKE_ADB_ARGS_LOG")"
if (( cleanup_count < 2 )); then
  printf 'Expected start and trap logcat cleanup, got %s\n' "$cleanup_count" >&2
  exit 1
fi

assert_failure missing_device
assert_failure wrong_host
assert_failure http_failure
assert_failure timeout

printf 'rikkahub-hermes-text-e2e fake ADB tests passed\n'
