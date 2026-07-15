#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REAL_GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
if [[ "$REAL_GRADLE_USER_HOME" != /* ]]; then
  REAL_GRADLE_USER_HOME="$PWD/$REAL_GRADLE_USER_HOME"
fi
TMP_DIR="$(mktemp -d)"
PROJECT_DIR="$TMP_DIR/project"
TEST_GRADLE_USER_HOME="$TMP_DIR/gradle-user-home"
SYNTHETIC_DSN='https://public@example.invalid/42'
MISSING_PUBLIC_KEY_DSN='https://example.invalid/42'
SECRET_MARKER='must-not-appear-in-gradle-output'
INVALID_DSN_MARKER='invalid-dsn-must-not-appear-in-gradle-output'

cleanup() {
  local status=$? attempt
  trap - EXIT INT TERM
  for attempt in {1..20}; do
    if rm -rf -- "$TMP_DIR" 2>/dev/null; then
      exit "$status"
    fi
    sleep 0.25
  done
  printf 'Failed to remove temporary test fixture.\n' >&2
  [[ "$status" -ne 0 ]] || status=1
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

fail() { printf '%s\n' "$1" >&2; exit 1; }
assert_contains() { [[ "$1" == *"$2"* ]] || fail "Expected output to contain: $2"; }
assert_not_contains() { [[ "$1" != *"$2"* ]] || fail 'Gradle output exposed a forbidden test fixture.'; }

assert_redacted() {
  local output="$1"
  assert_not_contains "$output" "$SYNTHETIC_DSN"
  assert_not_contains "$output" "$MISSING_PUBLIC_KEY_DSN"
  assert_not_contains "$output" "$SECRET_MARKER"
  assert_not_contains "$output" "$INVALID_DSN_MARKER"
}

sdk_dir="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$sdk_dir" && -f "$ROOT_DIR/local.properties" ]]; then
  sdk_dir="$(awk -F= '$1 == "sdk.dir" { sub(/^[^=]*=/, ""); print; exit }' "$ROOT_DIR/local.properties")"
fi
[[ -n "$sdk_dir" ]] || fail 'Android SDK path is required via ANDROID_SDK_ROOT, ANDROID_HOME, or local.properties.'

mkdir -p "$PROJECT_DIR" "$TEST_GRADLE_USER_HOME/wrapper"
(
  cd "$ROOT_DIR"
  git ls-files -z | tar --null --files-from=- --create --file=-
) | tar --extract --file=- --directory="$PROJECT_DIR"

# Reuse only dependency/distribution caches. The temporary Gradle user home has
# no gradle.properties and cannot inherit caller-specific project properties.
if [[ -d "$REAL_GRADLE_USER_HOME/caches" ]]; then
  ln -s "$REAL_GRADLE_USER_HOME/caches" "$TEST_GRADLE_USER_HOME/caches"
fi
if [[ -d "$REAL_GRADLE_USER_HOME/wrapper/dists" ]]; then
  ln -s "$REAL_GRADLE_USER_HOME/wrapper/dists" "$TEST_GRADLE_USER_HOME/wrapper/dists"
fi

write_local_properties() {
  local dsn="${1-}" environment="${2-}" rate="${3-}"
  {
    printf 'sdk.dir=%s\n' "$sdk_dir"
    if [[ -n "$dsn" || -n "$environment" || -n "$rate" ]]; then
      printf 'voiceAgentSentryDsn=%s\n' "$dsn"
      printf 'voiceAgentSentryEnvironment=%s\n' "$environment"
      printf 'voiceAgentSentryTracesSampleRate=%s\n' "$rate"
    fi
  } > "$PROJECT_DIR/local.properties"
}

new_home() {
  local home="$TMP_DIR/homes/$1"
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

run_gradle() {
  local home="$1"
  shift
  local -a extra_environment=()
  while [[ "${1-}" != '--' ]]; do
    extra_environment+=("$1")
    shift
  done
  shift

  local -a clean_environment=(
    "HOME=$home"
    "GRADLE_USER_HOME=$TEST_GRADLE_USER_HOME"
    "PATH=$PATH"
  )
  if [[ -n "${JAVA_HOME:-}" ]]; then
    clean_environment+=("JAVA_HOME=$JAVA_HOME")
  fi

  env -i "${clean_environment[@]}" "${extra_environment[@]}" \
    "$PROJECT_DIR/gradlew" --project-dir "$PROJECT_DIR" --no-daemon \
    -Duser.home="$home" "$@" 2>&1
}

run_clean_gradle() {
  local home="$1"
  shift
  run_gradle "$home" -- "$@"
}

run_environment_gradle() {
  local home="$1" dsn="$2" environment="$3" rate="$4"
  shift 4
  run_gradle "$home" \
    "VOICE_AGENT_SENTRY_DSN=$dsn" \
    "VOICE_AGENT_SENTRY_ENVIRONMENT=$environment" \
    "VOICE_AGENT_SENTRY_TRACES_SAMPLE_RATE=$rate" \
    -- "$@"
}

expect_validation_failure() {
  local name="$1" expected_message="$2" dsn="$3" environment="$4" rate="$5"
  local home output status
  home="$(new_home "$name")"
  write_env_file "$home" "$dsn" "$environment" "$rate"
  set +e
  output="$(run_clean_gradle "$home" :app:validateVoiceAgentSentryDebug)"
  status=$?
  set -e
  [[ "$status" -ne 0 ]] || fail "$name unexpectedly passed."
  assert_contains "$output" "$expected_message"
  assert_redacted "$output"
}

write_local_properties

missing_home="$(new_home missing)"
set +e
missing_output="$(run_clean_gradle "$missing_home" :app:validateVoiceAgentSentryDebug)"
missing_status=$?
set -e
[[ "$missing_status" -ne 0 ]] || fail 'Missing Sentry configuration unexpectedly passed.'
assert_contains "$missing_output" 'VOICE_AGENT_SENTRY_DSN'
assert_redacted "$missing_output"

file_home="$(new_home file)"
write_env_file "$file_home" "$SYNTHETIC_DSN" 'voice-file' '1.0'
file_output="$(run_clean_gradle "$file_home" :app:validateVoiceAgentSentryDebug)"
assert_contains "$file_output" 'Voice Agent Sentry debug configuration verified'
assert_redacted "$file_output"

environment_home="$(new_home environment)"
write_env_file "$environment_home" "$SECRET_MARKER" '' 'invalid-file-rate'
environment_output="$(run_environment_gradle "$environment_home" \
  "$SYNTHETIC_DSN" 'voice-environment' '0.5' \
  :app:validateVoiceAgentSentryDebug)"
assert_contains "$environment_output" 'Voice Agent Sentry debug configuration verified'
assert_redacted "$environment_output"

local_home="$(new_home local-property)"
write_env_file "$local_home" "$SECRET_MARKER" '' 'invalid-file-rate'
write_local_properties "$SYNTHETIC_DSN" 'voice-local' '0.75'
local_output="$(run_environment_gradle "$local_home" \
  "$SECRET_MARKER" '' 'invalid-environment-rate' \
  :app:validateVoiceAgentSentryDebug)"
assert_contains "$local_output" 'Voice Agent Sentry debug configuration verified'
assert_redacted "$local_output"

property_home="$(new_home project-property)"
write_env_file "$property_home" "$SECRET_MARKER" '' 'invalid-file-rate'
write_local_properties "$SECRET_MARKER" '' 'invalid-local-rate'
property_output="$(run_environment_gradle "$property_home" \
  "$SECRET_MARKER" '' 'invalid-environment-rate' \
  -PvoiceAgentSentryDsn="$SYNTHETIC_DSN" \
  -PvoiceAgentSentryEnvironment='voice-property' \
  -PvoiceAgentSentryTracesSampleRate='0.25' \
  :app:validateVoiceAgentSentryDebug)"
assert_contains "$property_output" 'Voice Agent Sentry debug configuration verified'
assert_redacted "$property_output"

write_local_properties
expect_validation_failure \
  invalid-dsn 'VOICE_AGENT_SENTRY_DSN' \
  "$INVALID_DSN_MARKER" 'voice-debug' '0.5'
expect_validation_failure \
  missing-public-key-dsn 'VOICE_AGENT_SENTRY_DSN' \
  "$MISSING_PUBLIC_KEY_DSN" 'voice-debug' '0.5'
expect_validation_failure \
  blank-environment 'VOICE_AGENT_SENTRY_ENVIRONMENT' \
  "$SYNTHETIC_DSN" '' '0.5'
expect_validation_failure \
  nan-rate 'VOICE_AGENT_SENTRY_TRACES_SAMPLE_RATE' \
  "$SYNTHETIC_DSN" 'voice-debug' 'NaN'
expect_validation_failure \
  infinite-rate 'VOICE_AGENT_SENTRY_TRACES_SAMPLE_RATE' \
  "$SYNTHETIC_DSN" 'voice-debug' 'Infinity'
expect_validation_failure \
  below-range-rate 'VOICE_AGENT_SENTRY_TRACES_SAMPLE_RATE' \
  "$SYNTHETIC_DSN" 'voice-debug' '-0.01'
expect_validation_failure \
  above-range-rate 'VOICE_AGENT_SENTRY_TRACES_SAMPLE_RATE' \
  "$SYNTHETIC_DSN" 'voice-debug' '1.01'

help_output="$(run_clean_gradle "$missing_home" :app:tasks)"
assert_contains "$help_output" 'Tasks runnable from project'
assert_redacted "$help_output"

for protected_task in \
  assembleDebug \
  packageDebug \
  packageDebugUniversalApk \
  installDebug
do
  dry_run_output="$(run_clean_gradle "$file_home" ":app:$protected_task" --dry-run)"
  assert_contains "$dry_run_output" ':app:validateVoiceAgentSentryDebug'
  assert_redacted "$dry_run_output"
done

independent_output="$(run_clean_gradle "$missing_home" \
  :app:assembleDebugUnitTest :app:assembleRelease --dry-run)"
assert_contains "$independent_output" ':app:assembleDebugUnitTest'
assert_contains "$independent_output" ':app:assembleRelease'
assert_not_contains "$independent_output" ':app:validateVoiceAgentSentryDebug'
assert_redacted "$independent_output"

printf 'voice-agent-sentry-build tests passed.\n'
