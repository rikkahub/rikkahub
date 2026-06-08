# Installed App Voice Agent E2E Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `scripts/voice-agent-hermes-gbrain-e2e.sh` validate the Voice Agent configuration already installed on the phone, without rebuilding a credentialed APK or seeding Hermes provider settings.

**Architecture:** The app will always emit a safe Hermes response hash diagnostic containing `actualHash`, even when no expected hash is embedded in `BuildConfig`. The live E2E script will compare that logged `actualHash` to `VOICE_AGENT_E2E_EXPECTED_HASH` in shell, while using the installed app's existing provider API key and Cloudflare Access headers. The script remains the only E2E entry point.

**Tech Stack:** Bash, Android ADB/logcat, Kotlin/JUnit, Gradle Android debug unit tests.

---

## File Structure

- Modify `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCoordinator.kt`
  - Responsibility: emit safe Hermes request/response hash diagnostics from the running app.
  - Change: default `hermesResponseExpectedHash` to `null` and always log response `actualHash`.
- Modify `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentRuntimeTest.kt`
  - Responsibility: unit-test Voice Agent coordinator runtime behavior.
  - Change: replace the "skipped without expected hash" assertion with an "actual hash is emitted without expected hash" assertion.
- Modify `app/build.gradle.kts`
  - Responsibility: Android build configuration.
  - Change: remove `VOICE_AGENT_HERMES_E2E_EXPECTED_HASH` build fields because the live E2E expected hash belongs to the script, not the APK.
- Modify `scripts/voice-agent-hermes-gbrain-e2e.sh`
  - Responsibility: run the live device-backed E2E test.
  - Change: remove credential/build/install/seed behavior and run against the installed app configuration.
- Modify `docs/voice-agent-hermes-gbrain-live-e2e.md`
  - Responsibility: runbook for the live E2E.
  - Change: document that app/provider/Cloudflare configuration must already exist on the installed app and only test inputs are loaded from env.

---

### Task 1: Always Emit Hermes Response Actual Hash

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCoordinator.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentRuntimeTest.kt`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Write the failing coordinator test**

In `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentRuntimeTest.kt`, replace the existing test named:

```kotlin
fun `Hermes response hash diagnostic is skipped without expected hash`() = runTest {
```

with this test:

```kotlin
@Test
fun `Hermes response hash diagnostic emits actual hash without expected hash`() = runTest {
    val gemini = FakeGeminiLiveVoiceClient()
    val toolApi = FakeVoiceToolApi()
    val diagnostics = VoiceDiagnostics()
    var loggedDetail: String? = null
    val expectedHash = HermesToolResponseHash.sha256HexNormalized("alpha beta")
    val coordinator = VoiceAgentCoordinator(
        gemini = gemini,
        toolApi = toolApi,
        audio = FakeVoiceAudioEngine(),
        diagnostics = diagnostics,
        hermesResponseExpectedHash = "",
        logHermesResponseHash = { loggedDetail = it },
        scope = this,
    )

    coordinator.onGeminiEvent(
        GeminiLiveEvent.ToolCall(callId = "call-no-hash", name = "ask_hermes", prompt = "private prompt")
    )
    assertEquals("call-no-hash" to "private prompt", toolApi.awaitRequest("call-no-hash"))
    toolApi.complete(response(callId = "call-no-hash", answer = " \nalpha\t  beta\r\n"))
    coordinator.awaitToolJobsWithTimeout()

    assertEquals(listOf("call-no-hash" to " \nalpha\t  beta\r\n"), gemini.toolResponses)
    val hashEvent = diagnostics.events.value.single { it.name == "hermes_tool_response_hash" }
    assertTrue(hashEvent.detail.contains("callId=call-no-hash"))
    assertTrue(hashEvent.detail.contains("actualHash=$expectedHash"))
    assertFalse(hashEvent.detail.contains("expectedHashMatch="))
    assertFalse(hashEvent.detail.contains("alpha"))
    assertFalse(hashEvent.detail.contains("beta"))
    assertEquals(hashEvent.detail, loggedDetail)

    val successEvent = diagnostics.events.value.single { it.name == "hermes_tool_succeeded" }
    assertTrue(successEvent.detail.contains("callId=call-no-hash"))
    assertTrue(successEvent.detail.contains("answerChars=16"))
    assertFalse(successEvent.detail.contains("alpha beta"))
}
```

- [ ] **Step 2: Run the focused test to verify it fails**

Run:

```bash
ANDROID_HOME=/home/muly/Android/Sdk ./gradlew :app:testDebugUnitTest --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentRuntimeTest.Hermes response hash diagnostic emits actual hash without expected hash'
```

Expected: FAIL because no `hermes_tool_response_hash` diagnostic is emitted when `hermesResponseExpectedHash` is blank.

- [ ] **Step 3: Implement actual-hash logging without a build-time expected hash**

In `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCoordinator.kt`, remove this import:

```kotlin
import me.rerere.rikkahub.BuildConfig
```

Change the constructor parameter from:

```kotlin
private val hermesResponseExpectedHash: String? =
    BuildConfig.VOICE_AGENT_HERMES_E2E_EXPECTED_HASH.trim().takeIf { it.isNotBlank() },
```

to:

```kotlin
private val hermesResponseExpectedHash: String? = null,
```

Change `recordHermesToolResponseHash()` from:

```kotlin
private fun recordHermesToolResponseHash(
    callId: String,
    answer: String,
    expectedHash: String?,
    elapsedMs: Long,
    serverElapsedMs: Long?,
) {
    val configuredExpectedHash = expectedHash?.takeIf { it.isNotBlank() } ?: return
    val detail = HermesToolResponseHash.diagnosticDetail(
        callId = callId,
        answer = answer,
        expectedSha256 = configuredExpectedHash,
        elapsedMs = elapsedMs,
        serverElapsedMs = serverElapsedMs,
    )
    diagnostics.record("hermes_tool_response_hash", detail)
    runCatching {
        logHermesResponseHash(detail)
    }.onFailure { error ->
        val message = error.message ?: error.javaClass.simpleName
        diagnostics.record("hermes_tool_response_hash_log_failed", "callId=$callId, message=$message")
    }
}
```

to:

```kotlin
private fun recordHermesToolResponseHash(
    callId: String,
    answer: String,
    expectedHash: String?,
    elapsedMs: Long,
    serverElapsedMs: Long?,
) {
    val detail = HermesToolResponseHash.diagnosticDetail(
        callId = callId,
        answer = answer,
        expectedSha256 = expectedHash?.takeIf { it.isNotBlank() },
        elapsedMs = elapsedMs,
        serverElapsedMs = serverElapsedMs,
    )
    diagnostics.record("hermes_tool_response_hash", detail)
    runCatching {
        logHermesResponseHash(detail)
    }.onFailure { error ->
        val message = error.message ?: error.javaClass.simpleName
        diagnostics.record("hermes_tool_response_hash_log_failed", "callId=$callId, message=$message")
    }
}
```

In `app/build.gradle.kts`, delete these two lines:

```kotlin
buildConfigField("String", "VOICE_AGENT_HERMES_E2E_EXPECTED_HASH", "\"\"")
buildConfigField("String", "VOICE_AGENT_HERMES_E2E_EXPECTED_HASH", localStringProperty("voiceAgentHermesE2eExpectedHash", "VOICE_AGENT_HERMES_E2E_EXPECTED_HASH"))
```

- [ ] **Step 4: Run the focused test to verify it passes**

Run:

```bash
ANDROID_HOME=/home/muly/Android/Sdk ./gradlew :app:testDebugUnitTest --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentRuntimeTest.Hermes response hash diagnostic emits actual hash without expected hash'
```

Expected: PASS.

- [ ] **Step 5: Run the response-hash unit slice**

Run:

```bash
ANDROID_HOME=/home/muly/Android/Sdk ./gradlew :app:testDebugUnitTest --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentRuntimeTest.*Hermes response hash*' --tests 'me.rerere.rikkahub.voiceagent.telemetry.HermesToolResponseHashTest'
```

Expected: PASS.

- [ ] **Step 6: Commit Task 1**

Run:

```bash
git add app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCoordinator.kt app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentRuntimeTest.kt app/build.gradle.kts
git commit -m "test: log voice agent response hash from installed app"
```

Expected: commit succeeds.

---

### Task 2: Convert Live E2E Script To Installed-App Validation

**Files:**
- Modify: `scripts/voice-agent-hermes-gbrain-e2e.sh`

- [ ] **Step 1: Run the current script to capture the failing behavior**

Run with only installed-app E2E inputs and no app credential env vars:

```bash
env \
  -u CF_ACCESS_CLIENT_ID \
  -u CF_ACCESS_CLIENT_SECRET \
  -u HERMES_PROFILE_API_KEY \
  VOICE_AGENT_E2E_EXPECTED_HASH=1a989ea86150171c687b0727f218eedbb94c4665a7da9b0add1bf5de607f2bf1 \
  VOICE_AGENT_E2E_PCM_PATH=/tmp/rikkahub-missing-prompt.pcm \
  VOICE_AGENT_E2E_CONVERSATION_ID=7efaeca0-f861-42a2-8cc9-01852c8c9c5a \
  scripts/voice-agent-hermes-gbrain-e2e.sh
```

Expected: FAIL with `Missing required environment variable: CF_ACCESS_CLIENT_ID`. This is the behavior being removed.

- [ ] **Step 2: Remove build/install/seed constants**

In `scripts/voice-agent-hermes-gbrain-e2e.sh`, replace the top constant block:

```bash
PACKAGE="${VOICE_AGENT_E2E_PACKAGE:-me.rerere.rikkahub.debug}"
SERVICE_COMPONENT="$PACKAGE/me.rerere.rikkahub.voiceagent.VoiceAgentCallService"
SEED_COMPONENT="$PACKAGE/me.rerere.rikkahub.voiceagent.debug.VoiceAgentDebugSeedReceiver"
INJECT_COMPONENT="$PACKAGE/me.rerere.rikkahub.voiceagent.debug.VoiceAudioDebugInjectionReceiver"
SEED_ACTION="me.rerere.rikkahub.debug.voiceagent.SEED_HERMES_PROVIDER"
INJECT_ACTION="me.rerere.rikkahub.debug.voiceagent.INJECT_PCM"
CALL_START_ACTION="me.rerere.rikkahub.voiceagent.action.START"
CALL_END_ACTION="me.rerere.rikkahub.voiceagent.action.END"
```

with:

```bash
PACKAGE="${VOICE_AGENT_E2E_PACKAGE:-me.rerere.rikkahub.debug}"
SERVICE_COMPONENT="$PACKAGE/me.rerere.rikkahub.voiceagent.VoiceAgentCallService"
INJECT_COMPONENT="$PACKAGE/me.rerere.rikkahub.voiceagent.debug.VoiceAudioDebugInjectionReceiver"
INJECT_ACTION="me.rerere.rikkahub.debug.voiceagent.INJECT_PCM"
CALL_START_ACTION="me.rerere.rikkahub.voiceagent.action.START"
CALL_END_ACTION="me.rerere.rikkahub.voiceagent.action.END"
```

Replace:

```bash
COMMON_FORBIDDEN_PATTERN='Voice Lab request failed 403|Cloudflare|cf-error|Access denied|FATAL EXCEPTION|VoiceAgentE2E.*hermes_tool_response_hash .*expectedHashMatch=false|Voice playback write failed|AudioTrack write failed|AudioTrack write error'
```

with:

```bash
COMMON_FORBIDDEN_PATTERN='Voice Lab request failed 403|Cloudflare|cf-error|Access denied|FATAL EXCEPTION|Voice playback write failed|AudioTrack write failed|AudioTrack write error'
```

- [ ] **Step 3: Require only installed-app E2E inputs**

Replace:

```bash
require_env CF_ACCESS_CLIENT_ID
require_env CF_ACCESS_CLIENT_SECRET
require_env HERMES_PROFILE_API_KEY
require_env VOICE_AGENT_E2E_EXPECTED_HASH
require_env VOICE_AGENT_E2E_PCM_PATH
require_env VOICE_AGENT_E2E_CONVERSATION_ID
```

with:

```bash
require_env VOICE_AGENT_E2E_EXPECTED_HASH
require_env VOICE_AGENT_E2E_PCM_PATH
require_env VOICE_AGENT_E2E_CONVERSATION_ID
```

- [ ] **Step 4: Remove the credentialed rebuild and install block**

Delete this block from `scripts/voice-agent-hermes-gbrain-e2e.sh`:

```bash
printf 'Building credentialed debug APK...\n'
CF_ACCESS_CLIENT_ID="$CF_ACCESS_CLIENT_ID" \
CF_ACCESS_CLIENT_SECRET="$CF_ACCESS_CLIENT_SECRET" \
VOICE_AGENT_HERMES_E2E_EXPECTED_HASH="$VOICE_AGENT_E2E_EXPECTED_HASH" \
./gradlew :app:assembleDebug

APK="${VOICE_AGENT_E2E_APK_PATH:-app/build/outputs/apk/debug/app-arm64-v8a-debug.apk}"
if [[ ! -f "$APK" ]]; then
  APK="app/build/outputs/apk/debug/app-universal-debug.apk"
fi
if [[ ! -f "$APK" ]]; then
  printf 'Debug APK was not found. Checked arm64-v8a and universal debug APK paths.\n' >&2
  exit 2
fi

printf 'Installing debug APK...\n'
adb_long_cmd install -r "$APK" >/dev/null

printf 'Granting debug permissions...\n'
adb_cmd shell pm grant "$PACKAGE" android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
adb_cmd shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
```

Add this block in the same location:

```bash
printf 'Checking installed app package...\n'
if ! adb_cmd shell pm path "$PACKAGE" >/dev/null; then
  printf 'Installed app package was not found: %s\n' "$PACKAGE" >&2
  printf 'Install and configure the app on the phone before running this E2E.\n' >&2
  exit 2
fi
```

- [ ] **Step 5: Remove Hermes provider seeding**

Delete this block from `scripts/voice-agent-hermes-gbrain-e2e.sh`:

```bash
printf 'Seeding Hermes provider in debug settings...\n'
adb_cmd shell am broadcast \
  -n "$SEED_COMPONENT" \
  -a "$SEED_ACTION" \
  --es conversation_id "$VOICE_AGENT_E2E_CONVERSATION_ID" \
  --es api_key "$HERMES_PROFILE_API_KEY" \
  --es base_url "${VOICE_AGENT_E2E_HERMES_BASE_URL:-https://muly-hermes-api.core8.co/v1}" >/dev/null

wait_for_log_or_fail \
  "Hermes debug seed succeeded" \
  'VoiceAgentDebugSeed.*debug_seed_hermes_provider result=success' \
  "Hermes debug seed failed" \
  'VoiceAgentDebugSeed.*debug_seed_hermes_provider failed' \
  30
```

Do not replace it with another seed path. The next block in the script must be:

```bash
printf 'Copying private PCM prompt into app-private files...\n'
adb_cmd shell "run-as $PACKAGE mkdir -p files/voice-e2e"
adb_long_cmd push "$VOICE_AGENT_E2E_PCM_PATH" "$DEVICE_TMP_PCM" >/dev/null
adb_cmd shell "run-as $PACKAGE cp $DEVICE_TMP_PCM files/$APP_PCM_PATH"
adb_cmd shell rm -f "$DEVICE_TMP_PCM" >/dev/null 2>&1 || true
```

- [ ] **Step 6: Compare the installed app's logged actual hash in shell**

Replace:

```bash
wait_for_log "Hermes response hash matched" "VoiceAgentE2E.*hermes_tool_response_hash .*actualHash=$EXPECTED_HASH_LOWER.*expectedHashMatch=true" "$HERMES_RESPONSE_TIMEOUT_SECONDS"
```

with:

```bash
wait_for_log "Hermes response hash matched" "VoiceAgentE2E.*hermes_tool_response_hash .*actualHash=$EXPECTED_HASH_LOWER(,|$)" "$HERMES_RESPONSE_TIMEOUT_SECONDS"
```

Delete:

```bash
fail_if_log "Hermes hash mismatch" 'VoiceAgentE2E.*hermes_tool_response_hash .*expectedHashMatch=false'
```

Add this final mismatch check immediately after the existing forbidden marker checks:

```bash
if grep -E 'VoiceAgentE2E.*hermes_tool_response_hash .*actualHash=' "$LOG_FILE" |
  grep -Ev "actualHash=$EXPECTED_HASH_LOWER(,|$)" >/dev/null 2>&1; then
  printf 'Forbidden marker found: Hermes hash mismatch\n' >&2
  printf 'Expected actualHash=%s\n' "$EXPECTED_HASH_LOWER" >&2
  exit 1
fi
```

- [ ] **Step 7: Remove `VoiceAgentDebugSeed` from scoped logcat**

In the logcat command, remove this line:

```bash
VoiceAgentDebugSeed:I \
```

The scoped logcat block must still include:

```bash
VoiceAgentCallService:D \
VoiceAgentCallSession:D \
VoiceAgentGemini:D \
VoiceAgentE2E:D \
VoiceAudioDebugInjection:I \
AndroidVoiceAudioEngine:D \
AndroidRuntime:E \
'*:S' > "$LOG_FILE" &
```

- [ ] **Step 8: Run the missing-env behavior check**

Run:

```bash
env \
  -u CF_ACCESS_CLIENT_ID \
  -u CF_ACCESS_CLIENT_SECRET \
  -u HERMES_PROFILE_API_KEY \
  -u VOICE_AGENT_E2E_EXPECTED_HASH \
  -u VOICE_AGENT_E2E_PCM_PATH \
  -u VOICE_AGENT_E2E_CONVERSATION_ID \
  scripts/voice-agent-hermes-gbrain-e2e.sh
```

Expected: FAIL with `Missing required environment variable: VOICE_AGENT_E2E_EXPECTED_HASH`.

- [ ] **Step 9: Run the removed-credential guard check**

Run:

```bash
env \
  -u CF_ACCESS_CLIENT_ID \
  -u CF_ACCESS_CLIENT_SECRET \
  -u HERMES_PROFILE_API_KEY \
  VOICE_AGENT_E2E_EXPECTED_HASH=1a989ea86150171c687b0727f218eedbb94c4665a7da9b0add1bf5de607f2bf1 \
  VOICE_AGENT_E2E_PCM_PATH=/tmp/rikkahub-missing-prompt.pcm \
  VOICE_AGENT_E2E_CONVERSATION_ID=7efaeca0-f861-42a2-8cc9-01852c8c9c5a \
  scripts/voice-agent-hermes-gbrain-e2e.sh
```

Expected: FAIL with `VOICE_AGENT_E2E_PCM_PATH does not exist: /tmp/rikkahub-missing-prompt.pcm`. It must not mention `CF_ACCESS_CLIENT_ID`, `CF_ACCESS_CLIENT_SECRET`, or `HERMES_PROFILE_API_KEY`.

- [ ] **Step 10: Run static script checks**

Run:

```bash
bash -n scripts/voice-agent-hermes-gbrain-e2e.sh
rg -n 'CF_ACCESS_CLIENT_ID|CF_ACCESS_CLIENT_SECRET|HERMES_PROFILE_API_KEY|Building credentialed debug APK|Installing debug APK|Seeding Hermes provider|VOICE_AGENT_HERMES_E2E_EXPECTED_HASH|VoiceAgentDebugSeed' scripts/voice-agent-hermes-gbrain-e2e.sh
```

Expected: `bash -n` exits 0. `rg` exits 1 with no matches.

- [ ] **Step 11: Commit Task 2**

Run:

```bash
git add scripts/voice-agent-hermes-gbrain-e2e.sh
git commit -m "fix: run voice agent e2e against installed app"
```

Expected: commit succeeds.

---

### Task 3: Update The Live E2E Runbook

**Files:**
- Modify: `docs/voice-agent-hermes-gbrain-live-e2e.md`

- [ ] **Step 1: Update the secret inputs section**

In `docs/voice-agent-hermes-gbrain-live-e2e.md`, replace the `## Secret Inputs` section table with:

```markdown
## Test Inputs

Set these values in your shell or source them from a local file outside the repository:

| Variable | Notes |
| --- | --- |
| `VOICE_AGENT_E2E_EXPECTED_HASH` | SHA-256 hex digest of the normalized expected Hermes answer. |
| `VOICE_AGENT_E2E_PCM_PATH` | Absolute path to the private PCM prompt file. |
| `VOICE_AGENT_E2E_CONVERSATION_ID` | Existing app conversation id used to start the Voice Agent service. |

The app/provider configuration is not supplied by this script. Before running, the installed app must already have a
Hermes Mobile API provider, the provider API key, and any required Cloudflare Access headers configured in app settings.
For Cloudflare-protected Hermes endpoints, configure both `CF-Access-Client-Id` and `CF-Access-Client-Secret` as
provider/model/assistant custom headers in the app.
```

- [ ] **Step 2: Replace the credentialed-build warning**

Replace this paragraph:

```markdown
Important: this script builds a credentialed debug APK and local build output. The Cloudflare credentials and
`VOICE_AGENT_E2E_EXPECTED_HASH` are embedded into `BuildConfig` for the debug APK produced by this run. Do not share the
APK, build outputs, or installed debug app/device state. The run also seeds the Hermes API key into app settings and
copies the private PCM prompt into app-private files. Treat the connected device as credential/private-data-bearing
until app data is cleared, the debug app is uninstalled, or the seeded Hermes provider/settings and copied PCM prompt
are explicitly removed. Simply replacing or reinstalling the APK is not enough when app data is preserved. If the
machine is shared, or if artifacts may leave the trusted environment, clean local build artifacts when the run is
complete.
```

with:

```markdown
Important: this script uses the installed app and does not rebuild, reinstall, grant permissions, or seed provider
settings. It copies the private PCM prompt into app-private files and writes a local scoped log. Treat the connected
device as private-data-bearing until the copied PCM prompt is removed, app data is cleared, or the debug app is
uninstalled. Do not share the PCM prompt or `build/voice-agent-e2e/logcat.txt`.
```

- [ ] **Step 3: Update the running section**

Replace:

```markdown
The script requires all secret inputs except `VOICE_AGENT_E2E_HERMES_BASE_URL`, verifies the PCM file exists, lowercases
the expected hash for marker matching, and writes a local scoped log to `build/voice-agent-e2e/logcat.txt`.
```

with:

```markdown
The script requires `VOICE_AGENT_E2E_EXPECTED_HASH`, `VOICE_AGENT_E2E_PCM_PATH`, and
`VOICE_AGENT_E2E_CONVERSATION_ID`, verifies the PCM file exists, lowercases the expected hash for marker matching, and
writes a local scoped log to `build/voice-agent-e2e/logcat.txt`.
```

Replace the current behavior list with:

```markdown
The current script behavior is:

1. Verifies ADB device readiness.
2. Verifies the target package is installed.
3. Clears logcat and starts scoped log capture.
4. Copies the private PCM prompt into app-private files with `run-as`.
5. Starts the Voice Agent foreground service for `VOICE_AGENT_E2E_CONVERSATION_ID`.
6. Waits for Gemini setup, injects the PCM prompt in chunks, then waits for the E2E markers.
7. Compares the app-logged Hermes response `actualHash` with `VOICE_AGENT_E2E_EXPECTED_HASH`.
8. Checks forbidden markers during each wait and again at the end, so auth, crash, hash mismatch, and playback write
   failures fail fast.
9. Ends the foreground service and stops log capture during cleanup.
```

- [ ] **Step 4: Update pass criteria**

Replace:

```markdown
- App emits `hermes_tool_response_hash` with expected hash and `expectedHashMatch=true`.
```

with:

```markdown
- App emits `hermes_tool_response_hash` with `actualHash` equal to `VOICE_AGENT_E2E_EXPECTED_HASH`.
```

- [ ] **Step 5: Remove obsolete references**

Run:

```bash
rg -n 'CF_ACCESS_CLIENT_ID|CF_ACCESS_CLIENT_SECRET|HERMES_PROFILE_API_KEY|VOICE_AGENT_E2E_HERMES_BASE_URL|credentialed debug APK|BuildConfig|seed' docs/voice-agent-hermes-gbrain-live-e2e.md
```

Expected: no matches except the intentional phrase `app/provider configuration is not supplied by this script` if the wording from Step 1 is still present. If `rg` returns that phrase only, no edit is needed.

- [ ] **Step 6: Commit Task 3**

Run:

```bash
git add docs/voice-agent-hermes-gbrain-live-e2e.md
git commit -m "docs: clarify installed app voice agent e2e"
```

Expected: commit succeeds.

---

### Task 4: Final Verification And Device Run

**Files:**
- Verify: `scripts/voice-agent-hermes-gbrain-e2e.sh`
- Verify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCoordinator.kt`
- Verify: `docs/voice-agent-hermes-gbrain-live-e2e.md`

- [ ] **Step 1: Run formatting and unit verification**

Run:

```bash
git diff --check
ANDROID_HOME=/home/muly/Android/Sdk ./gradlew test
```

Expected: both commands exit 0.

- [ ] **Step 2: Verify the installed-app script no longer consumes app credentials**

Run:

```bash
rg -n 'CF_ACCESS_CLIENT_ID|CF_ACCESS_CLIENT_SECRET|HERMES_PROFILE_API_KEY|VOICE_AGENT_HERMES_E2E_EXPECTED_HASH|voiceAgentHermesE2eExpectedHash|Building credentialed debug APK|Installing debug APK|Seeding Hermes provider' scripts app/build.gradle.kts app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCoordinator.kt
```

Expected: no matches.

- [ ] **Step 3: Verify ADB readiness**

Run:

```bash
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 scripts/adb-device-ready.sh
```

Expected: exits 0 and prints `adb-device-ready tests passed.` or the equivalent readiness success output from that helper.

- [ ] **Step 4: Run the live installed-app E2E with the private test inputs**

Source only test inputs, not app credentials:

```bash
set -a
source /home/muly/.config/rikkahub/voice-agent-e2e.env
set +a
```

The file must contain these keys:

```bash
VOICE_AGENT_E2E_EXPECTED_HASH=<sha-256-hex-digest>
VOICE_AGENT_E2E_PCM_PATH=<absolute-path-to-private-pcm-file>
VOICE_AGENT_E2E_CONVERSATION_ID=<existing-conversation-id>
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037
VOICE_AGENT_E2E_SERIAL=RZCX71NXRPB
```

Run:

```bash
scripts/voice-agent-hermes-gbrain-e2e.sh
```

Expected pass output includes:

```text
PASS marker: Gemini setup complete
PASS marker: debug PCM delivered
PASS marker: Gemini ask_hermes tool call received
PASS marker: Hermes response hash matched
PASS marker: Gemini tool response sent
PASS marker: Gemini output audio received
PASS marker: Voice playback queued
PASS marker: Voice playback wrote
Voice Agent Hermes/Gbrain live E2E passed. Safe log: build/voice-agent-e2e/logcat.txt
```

If this fails with `Voice Lab request failed 403` or a Cloudflare marker, report that the installed app is not configured correctly and do not rebuild or seed from the script.

- [ ] **Step 5: Commit verification-only doc note if needed**

If Task 4 reveals an additional operator note that belongs in the runbook, add a concise sentence to `docs/voice-agent-hermes-gbrain-live-e2e.md`, then run:

```bash
git add docs/voice-agent-hermes-gbrain-live-e2e.md
git commit -m "docs: add voice agent e2e operator note"
```

If no note is needed, do not create a commit for this step.

- [ ] **Step 6: Push the branch**

Run:

```bash
git status --short --branch
git push
git status --short --branch
```

Expected: final status is clean and synced with `origin/voice-agent-call-runtime`.

---

## Self-Review

**Spec coverage:** The plan removes app credential env vars from the live E2E, stops the script from rebuilding/installing/seeding, keeps only private test inputs in env, compares the installed app's logged `actualHash` to the expected hash, updates docs, and verifies with the real live script.

**Completeness scan:** Code and commands are included for every code-changing step, and each task has concrete expected output.

**Type consistency:** Existing names are used consistently: `VoiceAgentCoordinator`, `hermesResponseExpectedHash`, `HermesToolResponseHash.diagnosticDetail`, `VOICE_AGENT_E2E_EXPECTED_HASH`, `VOICE_AGENT_E2E_PCM_PATH`, and `VOICE_AGENT_E2E_CONVERSATION_ID`.
