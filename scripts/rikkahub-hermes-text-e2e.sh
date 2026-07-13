#!/usr/bin/env bash
set -euo pipefail

SERIAL="${VOICE_AGENT_E2E_SERIAL:-}"
CONVERSATION_ID="${VOICE_AGENT_E2E_CONVERSATION_ID:-}"
PACKAGE="${VOICE_AGENT_E2E_PACKAGE:-me.rerere.rikkahub.debug}"
EXPECTED_ORIGIN="${VOICE_AGENT_E2E_EXPECTED_TEXT_ORIGIN:-https://dev-remote-machine-1.tail83108.ts.net:8642}"
TIMEOUT_SECONDS="${VOICE_AGENT_E2E_TIMEOUT_SECONDS:-180}"
POLL_INTERVAL_SECONDS="${VOICE_AGENT_E2E_POLL_INTERVAL_SECONDS:-1}"
REPORT_DIR="${VOICE_AGENT_E2E_REPORT_DIR:-build/reports/hermes-text-e2e}"
ACTION="me.rerere.rikkahub.debug.voiceagent.SEND_HERMES_TEXT"
RECEIVER="$PACKAGE/.voiceagent.debug.HermesTextDebugReceiver"
PROMPT="Reply exactly: hermes-device-ok"
EXPECTED_ANSWER="hermes-device-ok"
TAG="HermesTextDebugE2E"

if [[ -z "$SERIAL" ]]; then
  printf 'VOICE_AGENT_E2E_SERIAL is required.\n' >&2
  exit 2
fi
if [[ -z "$CONVERSATION_ID" ]]; then
  printf 'VOICE_AGENT_E2E_CONVERSATION_ID is required.\n' >&2
  exit 2
fi
if [[ "$PACKAGE" != "me.rerere.rikkahub.debug" ]]; then
  printf 'VOICE_AGENT_E2E_PACKAGE must be the RikkaHub debug package.\n' >&2
  exit 2
fi
if [[ ! "$TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]]; then
  printf 'VOICE_AGENT_E2E_TIMEOUT_SECONDS must be a positive integer.\n' >&2
  exit 2
fi
if [[ ! "$EXPECTED_ORIGIN" =~ ^https://[A-Za-z0-9.-]+\.ts\.net:8642$ ]]; then
  printf 'VOICE_AGENT_E2E_EXPECTED_TEXT_ORIGIN must be Tailscale HTTPS on port 8642.\n' >&2
  exit 2
fi
if ! command -v adb >/dev/null 2>&1; then
  printf 'adb is required.\n' >&2
  exit 2
fi

adb_device() {
  adb -s "$SERIAL" "$@"
}

cleanup() {
  adb_device logcat -c >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [[ "$(adb_device get-state 2>/dev/null || true)" != "device" ]]; then
  printf 'The configured ADB device is unavailable.\n' >&2
  exit 3
fi
if ! adb_device shell pm path "$PACKAGE" 2>/dev/null | grep -Fq 'package:'; then
  printf 'The configured RikkaHub debug package is not installed.\n' >&2
  exit 3
fi

adb_device logcat -c >/dev/null
adb_device shell am broadcast \
  -a "$ACTION" \
  -n "$RECEIVER" \
  --es conversation_id "$CONVERSATION_ID" \
  --es prompt "$PROMPT" \
  --es expected_answer "$EXPECTED_ANSWER" \
  >/dev/null

deadline=$((SECONDS + TIMEOUT_SECONDS))
success_line=""
while (( SECONDS < deadline )); do
  tagged_logs="$(adb_device logcat -d -s "$TAG:I" '*:S' 2>/dev/null || true)"
  if grep -Fq 'debug_hermes_text result=failure category=' <<<"$tagged_logs"; then
    printf 'Hermes text E2E failed in the app.\n' >&2
    exit 4
  fi
  success_line="$(grep -F 'debug_hermes_text result=success exact=true' <<<"$tagged_logs" | tail -n 1 || true)"
  if [[ -n "$success_line" ]]; then
    break
  fi
  sleep "$POLL_INTERVAL_SECONDS"
done

if [[ -z "$success_line" ]]; then
  printf 'Hermes text E2E timed out.\n' >&2
  exit 4
fi

http_status="$(sed -n 's/.* http_status=\([^ ]*\).*/\1/p' <<<"$success_line")"
request_origin="$(sed -n 's/.* request_origin=\([^ ]*\).*/\1/p' <<<"$success_line")"
if [[ "$http_status" != "200" ]]; then
  printf 'Hermes text E2E did not observe HTTP 200.\n' >&2
  exit 5
fi
if [[ "$request_origin" != "$EXPECTED_ORIGIN" ]]; then
  printf 'Hermes text E2E used an unexpected request origin.\n' >&2
  exit 5
fi

umask 077
mkdir -p "$REPORT_DIR"
report_tmp="$(mktemp "$REPORT_DIR/.result.XXXXXX")"
printf '%s\n' \
  'result=success' \
  'exact=true' \
  'http_status=200' \
  "request_origin=$request_origin" \
  > "$report_tmp"
mv "$report_tmp" "$REPORT_DIR/result.txt"

printf 'Hermes text E2E passed with an exact answer over HTTP 200.\n'
