#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REAL_GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
if [[ "$REAL_GRADLE_USER_HOME" != /* ]]; then
  REAL_GRADLE_USER_HOME="$PWD/$REAL_GRADLE_USER_HOME"
fi
TEST_GRADLE_USER_HOME="$(mktemp -d)"
GENERATED_BUILD_CONFIG="$ROOT_DIR/app/build/generated/source/buildConfig/debug/me/rerere/rikkahub/BuildConfig.java"
FLAG_NAME='VOICE_AGENT_LIVEKIT_EXPERIMENT_ENABLED'

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

run_gradle() {
  local environment_value="$1"
  shift
  local -a environment=(
    -u VOICE_AGENT_LIVEKIT_EXPERIMENT_ENABLED
    -u ORG_GRADLE_PROJECT_voiceAgentLiveKitExperimentEnabled
    "GRADLE_USER_HOME=$TEST_GRADLE_USER_HOME"
  )
  if [[ "$environment_value" != 'unset' ]]; then
    environment+=("VOICE_AGENT_LIVEKIT_EXPERIMENT_ENABLED=$environment_value")
  fi
  env "${environment[@]}" \
    "$ROOT_DIR/gradlew" --project-dir "$ROOT_DIR" \
    --no-daemon --no-configuration-cache --rerun-tasks --console=plain \
    "$@" :app:generateDebugBuildConfig >/dev/null
}

assert_generated_flag() {
  local case_name="$1" expected="$2"
  [[ -f "$GENERATED_BUILD_CONFIG" ]] || fail "$case_name did not generate BuildConfig.java"
  grep -Fq "public static final boolean $FLAG_NAME = $expected;" "$GENERATED_BUILD_CONFIG" ||
    fail "$case_name expected $FLAG_NAME=$expected"
  printf '%s: %s=%s\n' "$case_name" "$FLAG_NAME" "$expected"
}

restore_default_and_cleanup() {
  local status=$?
  trap - EXIT INT TERM
  run_gradle unset || status=1
  for attempt in {1..20}; do
    if rm -rf -- "$TEST_GRADLE_USER_HOME" 2>/dev/null; then
      exit "$status"
    fi
    sleep 0.25
  done
  printf 'Failed to remove temporary Gradle user home.\n' >&2
  exit 1
}
trap restore_default_and_cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

mkdir -p "$TEST_GRADLE_USER_HOME/wrapper"
if [[ -d "$REAL_GRADLE_USER_HOME/caches" ]]; then
  ln -s "$REAL_GRADLE_USER_HOME/caches" "$TEST_GRADLE_USER_HOME/caches"
fi
if [[ -d "$REAL_GRADLE_USER_HOME/wrapper/dists" ]]; then
  ln -s "$REAL_GRADLE_USER_HOME/wrapper/dists" "$TEST_GRADLE_USER_HOME/wrapper/dists"
fi

run_gradle unset
assert_generated_flag 'default' false

run_gradle true
assert_generated_flag 'environment true' true

run_gradle false -PvoiceAgentLiveKitExperimentEnabled=true
assert_generated_flag 'property true overrides environment false' true

run_gradle true -PvoiceAgentLiveKitExperimentEnabled=false
assert_generated_flag 'property false overrides environment true' false

printf 'Voice Agent LiveKit BuildConfig flag tests passed.\n'
