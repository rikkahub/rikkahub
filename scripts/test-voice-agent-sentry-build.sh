#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REAL_HOME="$HOME"
GRADLE_USER_HOME_VALUE="${GRADLE_USER_HOME:-$REAL_HOME/.gradle}"
TMP_DIR="$(mktemp -d)"
SYNTHETIC_DSN='https://public@example.invalid/42'
SECRET_MARKER='must-not-appear-in-gradle-output'
trap 'rm -rf "$TMP_DIR"' EXIT

fail() { printf '%s\n' "$1" >&2; exit 1; }
assert_contains() { [[ "$1" == *"$2"* ]] || fail "Expected output to contain: $2"; }
assert_not_contains() { [[ "$1" != *"$2"* ]] || fail "Output exposed forbidden value: $2"; }

new_home() {
  local home="$TMP_DIR/$1"
  mkdir -p "$home/.config/voice-lab"
  printf '%s\n' "$home"
}

write_env_file() {
  local home="$1" dsn="$2" environment="$3" rate="$4"
  {
    printf '# synthetic configuration\n'
    printf 'VOICE_AGENT_SENTRY_DSN=%s\n' "$dsn"
    printf 'VOICE_AGENT_SENTRY_ENVIRONMENT=%s\n' "$environment"
    printf 'VOICE_AGENT_SENTRY_TRACES_SAMPLE_RATE=%s\n' "$rate"
  } > "$home/.config/voice-lab/local.env"
}

run_clean_gradle() {
  local home="$1"
  shift
  env -u VOICE_AGENT_SENTRY_DSN \
      -u VOICE_AGENT_SENTRY_ENVIRONMENT \
      -u VOICE_AGENT_SENTRY_TRACES_SAMPLE_RATE \
      HOME="$home" GRADLE_USER_HOME="$GRADLE_USER_HOME_VALUE" \
      "$ROOT_DIR/gradlew" --no-daemon -Duser.home="$home" "$@" 2>&1
}

missing_home="$(new_home missing)"
set +e
missing_output="$(run_clean_gradle "$missing_home" :app:validateVoiceAgentSentryDebug)"
missing_status=$?
set -e
[[ "$missing_status" -ne 0 ]] || fail 'Missing Sentry configuration unexpectedly passed.'
assert_contains "$missing_output" 'VOICE_AGENT_SENTRY_DSN'

file_home="$(new_home file)"
write_env_file "$file_home" "$SYNTHETIC_DSN" 'voice-debug' '1.0'
file_output="$(run_clean_gradle "$file_home" :app:validateVoiceAgentSentryDebug)"
assert_contains "$file_output" 'Voice Agent Sentry debug configuration verified'
assert_not_contains "$file_output" "$SYNTHETIC_DSN"

env_home="$(new_home env)"
write_env_file "$env_home" "$SYNTHETIC_DSN" 'voice-debug' 'invalid-file-rate'
env_output="$(HOME="$env_home" GRADLE_USER_HOME="$GRADLE_USER_HOME_VALUE" \
  VOICE_AGENT_SENTRY_DSN="$SYNTHETIC_DSN" \
  VOICE_AGENT_SENTRY_ENVIRONMENT='voice-env' \
  VOICE_AGENT_SENTRY_TRACES_SAMPLE_RATE='0.5' \
  "$ROOT_DIR/gradlew" --no-daemon -Duser.home="$env_home" \
  :app:validateVoiceAgentSentryDebug 2>&1)"
assert_contains "$env_output" 'Voice Agent Sentry debug configuration verified'

property_home="$(new_home property)"
write_env_file "$property_home" "$SYNTHETIC_DSN" 'voice-debug' 'invalid-file-rate'
property_output="$(HOME="$property_home" GRADLE_USER_HOME="$GRADLE_USER_HOME_VALUE" \
  VOICE_AGENT_SENTRY_DSN="$SECRET_MARKER" \
  VOICE_AGENT_SENTRY_ENVIRONMENT='invalid-env' \
  VOICE_AGENT_SENTRY_TRACES_SAMPLE_RATE='invalid-env-rate' \
  "$ROOT_DIR/gradlew" --no-daemon -Duser.home="$property_home" \
  -PvoiceAgentSentryDsn="$SYNTHETIC_DSN" \
  -PvoiceAgentSentryEnvironment='voice-property' \
  -PvoiceAgentSentryTracesSampleRate='0.25' \
  :app:validateVoiceAgentSentryDebug 2>&1)"
assert_contains "$property_output" 'Voice Agent Sentry debug configuration verified'
assert_not_contains "$property_output" "$SECRET_MARKER"

invalid_home="$(new_home invalid)"
write_env_file "$invalid_home" "$SYNTHETIC_DSN" 'voice-debug' 'NaN'
set +e
invalid_output="$(run_clean_gradle "$invalid_home" :app:validateVoiceAgentSentryDebug)"
invalid_status=$?
set -e
[[ "$invalid_status" -ne 0 ]] || fail 'NaN trace rate unexpectedly passed.'
assert_contains "$invalid_output" 'VOICE_AGENT_SENTRY_TRACES_SAMPLE_RATE'
assert_not_contains "$invalid_output" "$SYNTHETIC_DSN"

help_output="$(run_clean_gradle "$missing_home" :app:tasks)"
assert_contains "$help_output" 'Tasks runnable from project'

dry_run_output="$(run_clean_gradle "$file_home" :app:assembleDebug --dry-run)"
assert_contains "$dry_run_output" ':app:validateVoiceAgentSentryDebug'

printf 'voice-agent-sentry-build tests passed.\n'
