# Voice Sentry Fail-Closed Debug Build Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every locally packaged or installed RikkaHub debug APK load the existing secure Voice Agent Sentry configuration and fail before packaging when that configuration is missing or invalid.

**Architecture:** Keep the runtime `BuildConfig` contract unchanged. Add a non-executing parser for `~/.config/voice-lab/local.env`, resolve raw settings with explicit precedence, validate them in one Gradle task, and wire that task only into debug APK packaging/deployment. Test the real Gradle task graph with synthetic credentials in a hermetic shell harness.

**Tech Stack:** Gradle Kotlin DSL, Android Gradle Plugin, Bash, existing Sentry runtime integration.

## Global Constraints

- Never commit, print, or copy a real Sentry DSN.
- A valid Sentry DSN must include a nonblank public key in URI user-info
  (`https://public@example.invalid/42`); a URI-shaped value without `public@`
  must fail build validation.
- Resolution precedence is Gradle project property, `local.properties`, process environment, then `~/.config/voice-lab/local.env`.
- Parse the shared environment file as data; never source or execute it.
- Debug APK assemble, package, and install tasks fail closed. Unit tests, Gradle help, and release-only tasks do not require personal debug credentials.
- The trace sample rate must be finite and within `0.0..1.0`.
- Preserve the three existing `VOICE_AGENT_SENTRY_*` `BuildConfig` field names.

---

## File Structure

- Modify `app/build.gradle.kts`: parse, resolve, validate, escape, and wire Sentry settings.
- Create `scripts/test-voice-agent-sentry-build.sh`: black-box Gradle contract tests.
- Modify `docs/voice-agent-hermes-gbrain-live-e2e.md`: document the fail-closed build path.

### Task 1: Add the failing Gradle contract harness

**Files:**
- Create: `scripts/test-voice-agent-sentry-build.sh`
- Test: `scripts/test-voice-agent-sentry-build.sh`

**Interfaces:**
- Consumes: future task `:app:validateVoiceAgentSentryDebug`.
- Produces: hermetic tests for missing configuration, local-file loading, precedence, invalid rates, secret redaction, and task wiring.

- [ ] **Step 1: Create the test script**

Use this complete structure; keep all credentials synthetic:

```bash
#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REAL_HOME="$HOME"
GRADLE_USER_HOME_VALUE="${GRADLE_USER_HOME:-$REAL_HOME/.gradle}"
TMP_DIR="$(mktemp -d)"
SYNTHETIC_DSN='https://public@example.invalid/42'
MISSING_PUBLIC_KEY_DSN='https://example.invalid/42'
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

missing_public_key_home="$(new_home missing-public-key)"
write_env_file "$missing_public_key_home" "$MISSING_PUBLIC_KEY_DSN" 'voice-debug' '0.5'
set +e
missing_public_key_output="$(run_clean_gradle "$missing_public_key_home" :app:validateVoiceAgentSentryDebug)"
missing_public_key_status=$?
set -e
[[ "$missing_public_key_status" -ne 0 ]] || fail 'Missing-public-key Sentry DSN unexpectedly passed.'
assert_contains "$missing_public_key_output" 'VOICE_AGENT_SENTRY_DSN'
assert_not_contains "$missing_public_key_output" "$MISSING_PUBLIC_KEY_DSN"

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
```

- [ ] **Step 2: Make the script executable and prove the task is absent**

Run:

```bash
chmod +x scripts/test-voice-agent-sentry-build.sh
scripts/test-voice-agent-sentry-build.sh
```

Expected: FAIL because `:app:validateVoiceAgentSentryDebug` does not exist.

### Task 2: Resolve and validate secure settings

**Files:**
- Modify: `app/build.gradle.kts`
- Test: `scripts/test-voice-agent-sentry-build.sh`

**Interfaces:**
- Consumes: project/local properties, process environment, and `${user.home}/.config/voice-lab/local.env`.
- Produces: raw resolved Sentry values, task `validateVoiceAgentSentryDebug`, and unchanged runtime `BuildConfig` names.

- [ ] **Step 1: Add raw parsing and resolution**

Add `java.io.File` and `java.net.URI` imports, then add:

```kotlin
private fun loadPlainEnvironmentFile(file: File): Map<String, String> {
    if (!file.isFile) return emptyMap()
    return file.readLines().mapNotNull { rawLine ->
        val line = rawLine.trim()
        if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
        val separator = line.indexOf('=')
        if (separator <= 0) return@mapNotNull null
        val key = line.substring(0, separator).trim()
        val rawValue = line.substring(separator + 1).trim()
        val value = if (
            rawValue.length >= 2 &&
            ((rawValue.first() == '"' && rawValue.last() == '"') ||
                (rawValue.first() == '\'' && rawValue.last() == '\''))
        ) rawValue.substring(1, rawValue.lastIndex) else rawValue
        key to value
    }.toMap()
}

private fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

val voiceAgentLocalEnvironment = loadPlainEnvironmentFile(
    File(System.getProperty("user.home"), ".config/voice-lab/local.env"),
)

fun resolvedVoiceAgentSetting(propertyName: String, environmentName: String): String =
    providers.gradleProperty(propertyName).orNull
        ?: localProperties.getProperty(propertyName)
        ?: System.getenv(environmentName)
        ?: voiceAgentLocalEnvironment[environmentName]
        ?: ""

val voiceAgentSentryDsn = resolvedVoiceAgentSetting("voiceAgentSentryDsn", "VOICE_AGENT_SENTRY_DSN")
val voiceAgentSentryEnvironment = resolvedVoiceAgentSetting(
    "voiceAgentSentryEnvironment",
    "VOICE_AGENT_SENTRY_ENVIRONMENT",
)
val voiceAgentSentryTracesSampleRate = resolvedVoiceAgentSetting(
    "voiceAgentSentryTracesSampleRate",
    "VOICE_AGENT_SENTRY_TRACES_SAMPLE_RATE",
)
```

- [ ] **Step 2: Add the fail-closed validation task**

```kotlin
fun validateVoiceAgentSentryDebugConfiguration() {
    require(voiceAgentSentryDsn.isNotBlank()) {
        "VOICE_AGENT_SENTRY_DSN is required for debug APK builds; use a Gradle/local property, process environment, or ~/.config/voice-lab/local.env"
    }
    val dsn = runCatching { URI(voiceAgentSentryDsn) }.getOrNull()
    require(
        dsn != null && dsn.scheme in setOf("http", "https") &&
            dsn.rawUserInfo?.substringBefore(':')?.isNotBlank() == true &&
            !dsn.host.isNullOrBlank() && dsn.path?.trim('/')?.isNotBlank() == true
    ) { "VOICE_AGENT_SENTRY_DSN is invalid for a debug APK build" }
    require(voiceAgentSentryEnvironment.isNotBlank()) {
        "VOICE_AGENT_SENTRY_ENVIRONMENT is required for debug APK builds"
    }
    val rate = voiceAgentSentryTracesSampleRate.toDoubleOrNull()
    require(rate != null && rate.isFinite() && rate in 0.0..1.0) {
        "VOICE_AGENT_SENTRY_TRACES_SAMPLE_RATE must be a finite number in 0.0..1.0 for debug APK builds"
    }
}

val validateVoiceAgentSentryDebug by tasks.registering {
    group = "verification"
    description = "Validates Voice Agent Sentry settings before packaging a debug APK."
    doLast {
        validateVoiceAgentSentryDebugConfiguration()
        logger.lifecycle("Voice Agent Sentry debug configuration verified")
    }
}
```

- [ ] **Step 3: Preserve BuildConfig names with resolved values**

Replace the three Sentry declarations in `defaultConfig`:

```kotlin
buildConfigField("String", "VOICE_AGENT_SENTRY_DSN", voiceAgentSentryDsn.asBuildConfigString())
buildConfigField("String", "VOICE_AGENT_SENTRY_ENVIRONMENT", voiceAgentSentryEnvironment.asBuildConfigString())
buildConfigField(
    "String",
    "VOICE_AGENT_SENTRY_TRACES_SAMPLE_RATE",
    voiceAgentSentryTracesSampleRate.asBuildConfigString(),
)
```

- [ ] **Step 4: Wire only APK-producing debug tasks**

Add after the `android` block:

```kotlin
val voiceAgentSentryProtectedDebugTasks = setOf(
    "assembleDebug",
    "packageDebug",
    "packageDebugUniversalApk",
    "installDebug",
)
tasks.configureEach {
    if (name in voiceAgentSentryProtectedDebugTasks) {
        dependsOn(validateVoiceAgentSentryDebug)
    }
}
```

These are the current application APK tasks reported by `:app:tasks --all`.
Do not match by substring: `assembleDebugUnitTest` must remain independent of
personal Sentry credentials.

- [ ] **Step 5: Run contract and runtime tests**

```bash
scripts/test-voice-agent-sentry-build.sh
./gradlew :app:testDebugUnitTest \
  --tests '*VoiceSentryRuntimeConfigDiagnosticsTest' \
  --tests '*VoiceE2ESessionMetadataTest'
```

Expected: harness prints `voice-agent-sentry-build tests passed.` and Gradle reports BUILD SUCCESSFUL. Unit tests do not trigger packaging validation.

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts scripts/test-voice-agent-sentry-build.sh
git commit -m "fix(voice): require Sentry for debug APK builds"
```

### Task 3: Update the build runbook

**Files:**
- Modify: `docs/voice-agent-hermes-gbrain-live-e2e.md`

**Interfaces:**
- Consumes: the validation task and universal APK output.
- Produces: one documented build path without manual environment export.

- [ ] **Step 1: Document the secure auto-load and build commands**

Add this content before the debug build/install section:

````markdown
Debug APK packaging automatically resolves Voice Agent Sentry settings from
explicit Gradle/local properties, process environment, then
`~/.config/voice-lab/local.env`. Never source or print that file. Packaging
fails if the DSN, environment, or trace sample rate is missing or invalid.

```bash
scripts/test-voice-agent-sentry-build.sh
./gradlew :app:validateVoiceAgentSentryDebug :app:assembleDebug
```

The build must report `Voice Agent Sentry debug configuration verified` without
printing any value. Install
`app/build/outputs/apk/debug/app-universal-debug.apk`.
````

- [ ] **Step 2: Verify and commit documentation**

```bash
scripts/test-voice-agent-sentry-build.sh
git diff --check -- docs/voice-agent-hermes-gbrain-live-e2e.md
git add docs/voice-agent-hermes-gbrain-live-e2e.md
git commit -m "docs(voice): document fail-closed Sentry builds"
```

Expected: harness passes and `git diff --check` prints nothing.

### Task 4: Package and verify without exposing secrets

**Files:**
- Verify: `app/build/outputs/apk/debug/app-universal-debug.apk`
- Verify: generated debug `BuildConfig`.

**Interfaces:**
- Consumes: the secure local file already present on this machine.
- Produces: a universal debug APK with nonblank Sentry fields.

- [ ] **Step 1: Build**

```bash
scripts/test-voice-agent-sentry-build.sh
./gradlew :app:assembleDebug
test -f app/build/outputs/apk/debug/app-universal-debug.apk
```

Expected: BUILD SUCCESSFUL and the universal APK exists.

- [ ] **Step 2: Verify only presence, never values**

```bash
BUILD_CONFIG="$(rg -l 'VOICE_AGENT_SENTRY_DSN' app/build/generated/source/buildConfig/debug | head -n 1)"
test -n "$BUILD_CONFIG"
for field in VOICE_AGENT_SENTRY_DSN VOICE_AGENT_SENTRY_ENVIRONMENT VOICE_AGENT_SENTRY_TRACES_SAMPLE_RATE; do
  rg -q "${field} = \".+\";" "$BUILD_CONFIG" || exit 1
done
printf 'Generated Voice Agent Sentry fields are nonblank.\n'
```

Expected: the presence message only. Do not print matching source lines.

- [ ] **Step 3: Confirm repository state**

```bash
git status --short
git log -2 --oneline
```

Expected: only known user-owned untracked files remain.
