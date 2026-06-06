# Voice Agent Call Architecture Gap Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move RikkaHub Voice Agent to a self-managed call-style runtime that can continue while the app is backgrounded, while fixing transcript identity and debug receiver exposure.

**Architecture:** Extract the live voice session out of the Compose/ViewModel lifecycle into a service-backed `VoiceAgentCallManager` and `VoiceAgentCallService`. The screen becomes a controller over a service-owned call; Android foreground-service notification and self-managed Telecom integration provide call-style background behavior. Small correctness fixes land first so persistence and debug safety are improved before the larger lifecycle migration.

**Tech Stack:** Kotlin, Android Service/foreground service, Android Telecom self-managed `ConnectionService`, Jetpack Compose, Koin, kotlinx.coroutines `StateFlow`, OkHttp, existing Voice Agent Gemini/Voice Lab/audio/persistence components, JVM unit tests.

---

## File Structure

### Existing Files To Modify

- `app/src/main/AndroidManifest.xml`
  - Add `FOREGROUND_SERVICE_MICROPHONE` and `MANAGE_OWN_CALLS` permissions.
  - Register `VoiceAgentCallService` with `android:foregroundServiceType="microphone"`.
  - Register `VoiceAgentConnectionService` with `android.permission.BIND_TELECOM_CONNECTION_SERVICE`.

- `app/src/debug/AndroidManifest.xml`
  - Declare a debug-only signature permission.
  - Apply it to exported debug receivers.

- `app/src/main/java/me/rerere/rikkahub/RikkaHubApp.kt`
  - Create a `VOICE_AGENT_NOTIFICATION_CHANNEL_ID` notification channel.

- `app/src/main/java/me/rerere/rikkahub/RouteActivity.kt`
  - Reuse the existing `voiceAgentConversationId` route extra for notification return-to-call.

- `app/src/main/java/me/rerere/rikkahub/di/AppModule.kt`
  - Register `VoiceAgentCallFactory`, `VoiceAgentCallManager`, `VoiceAgentNotificationFactory`, and `VoiceAgentTelecomAdapter`.

- `app/src/main/java/me/rerere/rikkahub/di/ViewModelModule.kt`
  - Remove the old `VoiceAgentViewModel` binding after the screen no longer uses it.

- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentRoute.kt`
  - Replace ViewModel ownership with service start/attach and controller commands.
  - Request/check notification permission on Android 13+ before starting the call service, because the ongoing notification is the return-to-call path.
  - Remove the `ON_STOP -> endBecauseBackgrounded()` behavior.

- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentStartGate.kt`
  - Extend the start gate from microphone-only to microphone-plus-notification readiness.

- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentState.kt`
  - Add a lightweight call-runtime status to the existing `VoiceAgentUiState` so the screen and notification can distinguish "session connected" from "background-capable call runtime is ready/degraded".

- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentViewModel.kt`
  - Extract non-ViewModel session lifecycle into `VoiceAgentCallSession`.
  - Move the file-private `withTurnsFoldedIntoSystemInstruction` / `voiceContextLabel` helpers into `VoiceAgentCallSession.kt`.
  - Delete `endBecauseBackgrounded()` in Task 11 after the route no longer uses the ViewModel.

- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentViewModelFactory.kt`
  - Keep until Task 11, then delete after the route uses `VoiceAgentCallManager`.

- `app/src/main/java/me/rerere/rikkahub/voiceagent/persistence/VoiceConversationPersister.kt`
  - Include `voice_session_id` in transcript upsert identity when present.

- `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentViewModelTest.kt`
  - Move session lifecycle tests to `VoiceAgentCallSessionTest`.
  - Remove or rewrite background-ending expectation.

- `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentStartGateTest.kt`
  - Add notification-permission gating coverage.

- `app/src/test/java/me/rerere/rikkahub/voiceagent/persistence/VoiceConversationPersisterTest.kt`
  - Add cross-session transcript identity coverage.

### New Files To Create

- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallContract.kt`
  - Intent actions, extras, notification id/channel constants, and route helpers.

- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallSession.kt`
  - Non-ViewModel owner for one Gemini/audio/Hermes/persistence voice session.

- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallFactory.kt`
  - Creates `VoiceAgentCallSession` from `conversationId` and `VoiceAgentLaunchConfig`.

- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManager.kt`
  - App-scoped runtime that owns at most one active Voice Agent call and exposes state/commands to service and UI.

- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallService.kt`
  - Foreground service that owns Android process/lifecycle visibility for the active call.

- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentNotificationFactory.kt`
  - Builds ongoing notification and action `PendingIntent`s.

- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentTelecomAdapter.kt`
  - Registers self-managed phone account and starts/ends platform call integration.

- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentConnectionService.kt`
  - Minimal self-managed `ConnectionService`.

- `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentTelecomConnection.kt`
  - Minimal `Connection` object that maps disconnect to Voice Agent end.

- `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallSessionTest.kt`
  - Session lifecycle tests moved from ViewModel tests.

- `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManagerTest.kt`
  - Manager command/state ownership tests.

- `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallContractTest.kt`
  - Intent action/extra helper tests.

- `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentDebugManifestTest.kt`
  - Parses debug manifest and verifies receiver permission hardening.

---

## Task 1: Fix Transcript Identity Across Voice Sessions

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/persistence/VoiceConversationPersister.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/voiceagent/persistence/VoiceConversationPersisterTest.kt`

- [ ] **Step 1: Write the failing cross-session transcript test**

Add this test to `VoiceConversationPersisterTest`:

```kotlin
@Test
fun `upsert transcript keeps same turn id from different voice sessions`() {
    val persister = VoiceConversationPersister()
    val conversation = Conversation.ofId(Uuid.random())

    val afterFirst = persister.upsertUserTranscriptTurn(
        conversation = conversation,
        text = "first session text",
        turnId = "user-1",
        sessionId = "session-a",
        status = VoiceTranscriptStatus.Complete,
    )
    val afterSecond = persister.upsertUserTranscriptTurn(
        conversation = afterFirst,
        text = "second session text",
        turnId = "user-1",
        sessionId = "session-b",
        status = VoiceTranscriptStatus.Complete,
    )

    val texts = afterSecond.currentMessages
        .flatMap { it.parts }
        .filterIsInstance<UIMessagePart.Text>()
        .map { it.text }

    assertEquals(listOf("first session text", "second session text"), texts)
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests 'me.rerere.rikkahub.voiceagent.persistence.VoiceConversationPersisterTest.upsert transcript keeps same turn id from different voice sessions'
```

Expected: FAIL because the second `session-b/user-1` write replaces `session-a/user-1`.

- [ ] **Step 3: Pass session id through transcript upsert identity**

Change `upsertTranscriptTurn` signature and calls in `VoiceConversationPersister.kt`:

```kotlin
private fun upsertTranscriptTurn(
    conversation: Conversation,
    message: UIMessage,
    transcriptRole: String,
    turnId: String,
    sessionId: String?,
): Conversation {
    if (message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }.isBlank()) {
        return conversation
    }

    val currentMessages = conversation.currentMessages
    val existingIndex = currentMessages.indexOfLast {
        it.isVoiceTranscript(transcriptRole = transcriptRole, turnId = turnId, sessionId = sessionId)
    }
    if (existingIndex >= 0) {
        val updatedMessages = currentMessages.toMutableList()
        val existingMessage = currentMessages[existingIndex]
        updatedMessages[existingIndex] = message.copy(id = existingMessage.id)
        return conversation.updateCurrentMessages(updatedMessages)
    }

    return conversation.appendMessage(message)
}
```

Update both callers:

```kotlin
transcriptRole = VOICE_TRANSCRIPT_USER_ROLE,
turnId = turnId,
sessionId = sessionId,
```

```kotlin
transcriptRole = VOICE_TRANSCRIPT_ASSISTANT_ROLE,
turnId = turnId,
sessionId = sessionId,
```

Replace `isVoiceTranscript` with:

```kotlin
private fun UIMessage.isVoiceTranscript(
    transcriptRole: String,
    turnId: String,
    sessionId: String?,
): Boolean {
    return parts.any { part ->
        if (part !is UIMessagePart.Text) return@any false
        val metadata = part.metadata ?: return@any false
        val roleMatches = metadata[VOICE_TRANSCRIPT_ROLE_KEY]?.jsonPrimitive?.content == transcriptRole
        val turnMatches = metadata[VOICE_TRANSCRIPT_TURN_ID_KEY]?.jsonPrimitive?.content == turnId
        val existingSessionId = metadata[VOICE_SESSION_ID_KEY]?.jsonPrimitive?.content
        val sessionMatches = if (sessionId == null || existingSessionId == null) {
            true
        } else {
            existingSessionId == sessionId
        }
        roleMatches && turnMatches && sessionMatches
    }
}
```

- [ ] **Step 4: Run persistence tests**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests 'me.rerere.rikkahub.voiceagent.persistence.VoiceConversationPersisterTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/voiceagent/persistence/VoiceConversationPersister.kt app/src/test/java/me/rerere/rikkahub/voiceagent/persistence/VoiceConversationPersisterTest.kt
git commit -m "fix: preserve voice transcripts across sessions"
```

---

## Task 2: Harden Debug Receivers While Keeping ADB Workflows

**Files:**
- Modify: `app/src/debug/AndroidManifest.xml`
- Test: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentDebugManifestTest.kt`

- [ ] **Step 1: Write manifest protection test**

Create `VoiceAgentDebugManifestTest.kt`:

```kotlin
package me.rerere.rikkahub.voiceagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class VoiceAgentDebugManifestTest {
    @Test
    fun `debug receivers require signature permission`() {
        val manifest = File("src/debug/AndroidManifest.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest)
        val receivers = document.getElementsByTagName("receiver")
        val protectedReceivers = (0 until receivers.length)
            .map { receivers.item(it).attributes }
            .associate { attributes ->
                attributes.getNamedItem("android:name").nodeValue to
                    attributes.getNamedItem("android:permission")?.nodeValue
            }

        assertEquals(
            "me.rerere.rikkahub.debug.permission.VOICE_AGENT_DEBUG",
            protectedReceivers[".voiceagent.debug.VoiceAudioDebugInjectionReceiver"],
        )
        assertEquals(
            "me.rerere.rikkahub.debug.permission.VOICE_AGENT_DEBUG",
            protectedReceivers[".voiceagent.debug.VoiceAgentDebugSeedReceiver"],
        )
    }

    @Test
    fun `debug permission is signature scoped`() {
        val manifest = File("src/debug/AndroidManifest.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest)
        val permissions = document.getElementsByTagName("permission")
        val protectionLevels = (0 until permissions.length)
            .map { permissions.item(it).attributes }
            .associate { attributes ->
                attributes.getNamedItem("android:name").nodeValue to
                    attributes.getNamedItem("android:protectionLevel").nodeValue
            }

        assertTrue("debug permission missing", protectionLevels.containsKey("me.rerere.rikkahub.debug.permission.VOICE_AGENT_DEBUG"))
        assertEquals("signature", protectionLevels["me.rerere.rikkahub.debug.permission.VOICE_AGENT_DEBUG"])
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentDebugManifestTest'
```

Expected: FAIL because receivers have no `android:permission` and no signature permission is declared.

- [ ] **Step 3: Protect debug receivers**

Change `app/src/debug/AndroidManifest.xml` to:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <permission
    android:name="me.rerere.rikkahub.debug.permission.VOICE_AGENT_DEBUG"
    android:protectionLevel="signature" />

  <application>
    <receiver
      android:name=".voiceagent.debug.VoiceAudioDebugInjectionReceiver"
      android:exported="true"
      android:permission="me.rerere.rikkahub.debug.permission.VOICE_AGENT_DEBUG">
      <intent-filter>
        <action android:name="me.rerere.rikkahub.debug.voiceagent.INJECT_PCM" />
      </intent-filter>
    </receiver>
    <receiver
      android:name=".voiceagent.debug.VoiceAgentDebugSeedReceiver"
      android:exported="true"
      android:permission="me.rerere.rikkahub.debug.permission.VOICE_AGENT_DEBUG">
      <intent-filter>
        <action android:name="me.rerere.rikkahub.debug.voiceagent.SEED_HERMES_PROVIDER" />
      </intent-filter>
    </receiver>
  </application>
</manifest>
```

- [ ] **Step 4: Run debug manifest test**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentDebugManifestTest'
```

Expected: PASS.

- [ ] **Step 5: Verify ADB debug workflows still work on a debug install**

After the manifest protection test passes, install a debug build on the connected device and verify both debug broadcast entry points still work from ADB. This is required because the spec explicitly preserves ADB convenience; a manifest-only unit test is not enough.

Run:

```bash
./gradlew --no-daemon :app:assembleDebug
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 adb install -r app/build/outputs/apk/debug/app-debug.apk
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 adb shell am broadcast \
  -a me.rerere.rikkahub.debug.voiceagent.SEED_HERMES_PROVIDER
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 adb shell am broadcast \
  -a me.rerere.rikkahub.debug.voiceagent.INJECT_PCM \
  --es path voice-agent-debug/test-prompt.pcm
```

Expected:

- ADB broadcasts are delivered, or the receiver records a clear diagnostic for missing test input.
- A normal third-party app cannot invoke the receivers because they require the debug-only permission.
- If ADB is blocked by the `signature` permission on the target Android version, revise the receiver protection before continuing. Do not defer this failure to Task 12.

- [ ] **Step 6: Commit**

```bash
git add app/src/debug/AndroidManifest.xml app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentDebugManifestTest.kt
git commit -m "fix: protect Voice Agent debug receivers"
```

---

## Task 3: Add Service Contract And Call Runtime State

> **Design note:** Keep `VoiceSessionStatus` as the Gemini/session status, but add a small `VoiceCallStatus` field to `VoiceAgentUiState`. This avoids a broad state rewrite while still satisfying the spec requirement that the UI can show whether Android foreground-service/call support is starting, background-capable, degraded, ending, or ended.

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallContract.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentState.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallContractTest.kt`

- [ ] **Step 1: Write contract tests**

Create `VoiceAgentCallContractTest.kt`:

```kotlin
package me.rerere.rikkahub.voiceagent

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class VoiceAgentCallContractTest {
    @Test
    fun `call actions are stable`() {
        val conversationId = Uuid.parse("11111111-1111-4111-8111-111111111111")

        assertEquals("11111111-1111-4111-8111-111111111111", conversationId.toString())
        assertEquals("me.rerere.rikkahub.voiceagent.action.START", VoiceAgentCallContract.ACTION_START)
        assertEquals("me.rerere.rikkahub.voiceagent.action.END", VoiceAgentCallContract.ACTION_END)
        assertEquals("conversationId", VoiceAgentCallContract.EXTRA_CONVERSATION_ID)
    }

    @Test
    fun `notification route extra matches RouteActivity contract`() {
        assertEquals("voiceAgentConversationId", VoiceAgentCallContract.EXTRA_ROUTE_VOICE_AGENT_CONVERSATION_ID)
    }
}
```

- [ ] **Step 2: Run tests and verify they fail**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentCallContractTest'
```

Expected: FAIL because `VoiceAgentCallContract` does not exist.

- [ ] **Step 3: Add call contract helpers**

Create `VoiceAgentCallContract.kt`:

```kotlin
package me.rerere.rikkahub.voiceagent

object VoiceAgentCallContract {
    const val ACTION_START = "me.rerere.rikkahub.voiceagent.action.START"
    const val ACTION_END = "me.rerere.rikkahub.voiceagent.action.END"
    const val ACTION_MUTE = "me.rerere.rikkahub.voiceagent.action.MUTE"
    const val ACTION_UNMUTE = "me.rerere.rikkahub.voiceagent.action.UNMUTE"
    const val EXTRA_CONVERSATION_ID = "conversationId"
    const val EXTRA_ROUTE_VOICE_AGENT_CONVERSATION_ID = "voiceAgentConversationId"
    const val NOTIFICATION_ID = 2401
}
```

Also modify `VoiceAgentState.kt`:

```kotlin
sealed interface VoiceCallStatus {
    data object Idle : VoiceCallStatus
    data object ForegroundStarting : VoiceCallStatus
    data object BackgroundCapable : VoiceCallStatus
    data class Degraded(val message: String) : VoiceCallStatus
    data object Ending : VoiceCallStatus
    data object Ended : VoiceCallStatus
}

data class VoiceAgentUiState(
    val session: VoiceSessionStatus = VoiceSessionStatus.Idle,
    val audio: VoiceAudioStatus = VoiceAudioStatus.Listening,
    val tool: VoiceToolStatus = VoiceToolStatus.Idle,
    val call: VoiceCallStatus = VoiceCallStatus.Idle,
    val toolCalls: Map<String, VoiceToolStatus> = emptyMap(),
    val persistence: VoicePersistenceStatus = VoicePersistenceStatus.Idle,
    val inputTranscript: String = "",
    val outputTranscript: String = "",
    val error: String? = null,
    val diagnostics: List<VoiceDiagnosticLine> = emptyList(),
)
```

Keep existing `session` status labels intact. Task 7 uses `call` for notification text, Task 8 updates it from the foreground service, and Task 9 renders it on the focused Voice Agent screen.

- [ ] **Step 4: Run tests**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentCallContractTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallContract.kt app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentState.kt app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallContractTest.kt
git commit -m "feat: add Voice Agent call contract"
```

---

## Task 4: Extract Service-Owned Voice Session

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallSession.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallSessionTest.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentViewModel.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentViewModelTest.kt`

- [ ] **Step 1: Create session test by copying the current ViewModel start/end test**

Create `VoiceAgentCallSessionTest.kt` with this initial test:

```kotlin
package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.voiceagent.gemini.GeminiContentTurn
import me.rerere.rikkahub.voiceagent.persistence.VoiceContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class VoiceAgentCallSessionTest {
    @Test
    fun `session starts forwards capture audio and closes resources`() = runTest {
        val sessionApi = FakeVoiceSessionApi()
        val gemini = FakeGeminiLiveVoiceClient()
        val audio = FakeVoiceAudioEngine()
        val session = VoiceAgentCallSession(
            modelId = "gemini-flash",
            sessionApi = sessionApi,
            toolApi = FakeVoiceToolApi(),
            gemini = gemini,
            audio = audio,
            conversationStore = FakeVoiceConversationStore(),
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(
                    systemInstruction = "system voice prompt",
                    turns = listOf(GeminiContentTurn(role = "user", text = "prior turn")),
                )
            ),
            scope = this,
        )

        session.start()
        gemini.awaitConnect()

        assertEquals(listOf("gemini-flash"), sessionApi.createdSessions)
        assertEquals("token-1", gemini.connectedToken)
        assertEquals("wss://voice.test/live", gemini.connectedWebsocketUrl)
        assertEquals("gemini-live-test", gemini.connectedProviderModel)
        assertEquals(
            "system voice prompt\n\nPrevious RikkaHub conversation context:\nUser: prior turn",
            gemini.connectedSystemInstruction,
        )
        assertEquals(1, audio.startCaptureCalls)

        audio.emitCapture(byteArrayOf(1, 2, 3))
        assertEquals(listOf(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))), gemini.audioMessages)

        session.end()
        withTimeout(500) {
            while (gemini.closeCalls < 1 || audio.releaseCalls < 1) {
                delay(10)
            }
        }

        assertTrue(gemini.closeCalls >= 1)
        assertEquals(1, audio.releaseCalls)
        assertEquals(VoiceSessionStatus.Ended, session.state.value.session)
    }
}
```

Move every private fake class used by this test from `VoiceAgentViewModelTest` into `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentFakes.kt` before running the test. The moved classes keep the same names and constructor signatures.

- [ ] **Step 2: Run the new test and verify it fails**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentCallSessionTest.session starts forwards capture audio and closes resources'
```

Expected: FAIL because `VoiceAgentCallSession` does not exist.

- [ ] **Step 3: Extract `VoiceAgentCallSession` mechanically**

Create `VoiceAgentCallSession.kt` by moving the current `VoiceAgentViewModel` constructor dependencies and methods into a plain class. This is a mechanical extraction: the method bodies for `runSession`, `handleGeminiEvent`, `cleanupFailedStartup`, `ensureActiveSession`, `startCapture`, and `invalidateAudioSessions`, plus the two file-private helper extensions `withTurnsFoldedIntoSystemInstruction` and `voiceContextLabel`, must be copied byte-for-byte from the current `VoiceAgentViewModel`. Both helpers are `private` (file-scoped) in `VoiceAgentViewModel.kt`, so they are invisible to the new file unless moved — `voiceContextLabel` is shown at the bottom of the file below (`withTurnsFoldedIntoSystemInstruction` is the member function already shown above).

```kotlin
class VoiceAgentCallSession(
    private val modelId: String,
    private val sessionApi: VoiceSessionApi,
    private val toolApi: VoiceToolApi,
    private val gemini: GeminiLiveVoiceClient,
    private val audio: VoiceAudioEngine,
    conversationStore: VoiceConversationStore,
    contextProvider: VoiceAgentContextProvider,
    diagnostics: VoiceDiagnostics = VoiceDiagnostics(),
    scope: CoroutineScope,
) {
    private val lifecycleScope = scope
    private val coordinator = VoiceAgentCoordinator(
        gemini = gemini,
        toolApi = toolApi,
        audio = audio,
        diagnostics = diagnostics,
        conversationStore = conversationStore,
        scope = lifecycleScope,
    )
    private var startJob: Job? = null
    private var muted = false
    private var sessionId = 0L
    private var ended = false

    val state: StateFlow<VoiceAgentUiState> = coordinator.state
    private val conversation = conversationStore.conversation
    private val contextProvider = contextProvider

    private fun VoiceContext.withTurnsFoldedIntoSystemInstruction(): VoiceContext {
        if (turns.isEmpty()) return this

        val previousContext = turns.joinToString(separator = "\n\n") { turn ->
            "${turn.voiceContextLabel()}: ${turn.text}"
        }
        return copy(
            systemInstruction = listOf(
                systemInstruction,
                "Previous RikkaHub conversation context:\n$previousContext",
            )
                .filter { it.isNotBlank() }
                .joinToString(separator = "\n\n"),
            turns = emptyList(),
        )
    }

    fun start() {
        if (ended || startJob?.isActive == true) return
        val currentSessionId = coordinator.nextSessionId()
        sessionId = currentSessionId
        startJob = lifecycleScope.launch { runSession(currentSessionId) }
    }

    fun interrupt() {
        if (!ended) coordinator.suppressPlayback()
    }

    fun setMuted(value: Boolean) {
        if (ended || muted == value) return
        muted = value
        if (muted) {
            gemini.sendAudioStreamEnd(sessionId)
            audio.stopCapture()
            coordinator.updateAudioStatus(VoiceAudioStatus.Muted)
        } else if (state.value.session == VoiceSessionStatus.Connected) {
            startCapture(sessionId)
        }
    }

    fun reconnect() {
        if (ended) return
        val previousJob = startJob
        coordinator.prepareForReconnect()
        invalidateAudioSessions()
        audio.stopCapture()
        audio.suppressPlayback()
        gemini.close()
        startJob = lifecycleScope.launch {
            previousJob?.cancelAndJoin()
            if (ended) return@launch
            coordinator.updateSessionStatus(VoiceSessionStatus.Reconnecting)
            val currentSessionId = coordinator.nextSessionId()
            sessionId = currentSessionId
            runSession(currentSessionId)
        }
    }

    fun end() {
        if (ended) return
        ended = true
        val previousJob = startJob
        coordinator.prepareForSessionEnd()
        invalidateAudioSessions()
        audio.stopCapture()
        audio.suppressPlayback()
        gemini.close()
        lifecycleScope.launch {
            previousJob?.cancelAndJoin()
            coordinator.updateSessionStatus(VoiceSessionStatus.Ending)
            coordinator.close()
            coordinator.launchPersistenceDrain()
        }
    }

    fun closeNow() {
        if (!ended) ended = true
        startJob?.cancel()
        coordinator.prepareForSessionEnd()
        invalidateAudioSessions()
        audio.stopCapture()
        audio.suppressPlayback()
        coordinator.updateSessionStatus(VoiceSessionStatus.Ending)
        coordinator.close(waitForStartedSends = false)
        coordinator.launchPersistenceDrain()
    }

    private suspend fun runSession(currentSessionId: Long) {
        val sessionJob = currentCoroutineContext()[Job]
        startJob = sessionJob
        var geminiStarted = false
        try {
            coordinator.updateSessionStatus(VoiceSessionStatus.PreparingContext)
            val voiceContext = contextProvider.build(conversation.value).withTurnsFoldedIntoSystemInstruction()
            coordinator.recordDiagnostic(
                name = "voice_context_prepared",
                detail = "turns=${voiceContext.turns.size}, systemInstructionChars=${voiceContext.systemInstruction.length}",
            )
            ensureActiveSession(currentSessionId)
            coordinator.updateSessionStatus(VoiceSessionStatus.RequestingToken)
            val session = sessionApi.createSession(modelId = modelId)
            coordinator.recordDiagnostic(
                name = "voice_session_created",
                detail = "modelId=${session.modelId}, providerModel=${session.providerModel}, " +
                    "inputSampleRate=${session.inputSampleRate}, outputSampleRate=${session.outputSampleRate}",
            )
            ensureActiveSession(currentSessionId)
            coordinator.updateSessionStatus(VoiceSessionStatus.ConnectingGemini)
            geminiStarted = true
            gemini.connect(
                token = session.token,
                websocketUrl = session.websocketUrl,
                providerModel = session.providerModel,
                liveConnectConfig = session.liveConnectConfig,
                systemInstruction = voiceContext.systemInstruction,
                contextTurns = voiceContext.turns,
                onEvent = { event -> handleGeminiEvent(currentSessionId, event) },
            )
            ensureActiveSession(currentSessionId)
            if (coordinator.state.value.session is VoiceSessionStatus.Error) {
                cleanupFailedStartup(currentSessionId, closeGemini = true)
                return
            }
            coordinator.updateSessionStatus(VoiceSessionStatus.Connected)
            gemini.activateOutboundSession(currentSessionId)
            audio.activatePlaybackSession(currentSessionId)
            if (!muted) {
                startCapture(currentSessionId)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (coordinator.isActiveSession(currentSessionId)) {
                cleanupFailedStartup(currentSessionId, closeGemini = geminiStarted)
                coordinator.updateSessionStatus(
                    VoiceSessionStatus.Error(error.message ?: error.javaClass.simpleName)
                )
            }
        } finally {
            if (startJob === sessionJob) {
                startJob = null
            }
        }
    }

    private fun handleGeminiEvent(sessionId: Long, event: GeminiLiveEvent) {
        coordinator.onGeminiEvent(sessionId, event)
        when (event) {
            is GeminiLiveEvent.Error,
            is GeminiLiveEvent.WebSocketClosed,
            is GeminiLiveEvent.WebSocketFailure,
                -> cleanupFailedStartup(sessionId, closeGemini = true)
            else -> Unit
        }
    }

    private fun cleanupFailedStartup(sessionId: Long, closeGemini: Boolean) {
        if (!coordinator.isActiveSession(sessionId)) return
        coordinator.prepareForSessionEnd()
        invalidateAudioSessions()
        audio.stopCapture()
        audio.suppressPlayback()
        if (closeGemini) {
            gemini.close()
        }
    }

    private suspend fun ensureActiveSession(sessionId: Long) {
        currentCoroutineContext().ensureActive()
        check(coordinator.isActiveSession(sessionId)) { "Voice Agent session is stale" }
    }

    private fun startCapture(currentSessionId: Long) {
        audio.startCapture { pcm16 ->
            if (!coordinator.isActiveSession(currentSessionId)) {
                return@startCapture
            }
            val sent = gemini.sendAudio(
                base64Pcm16 = Base64.getEncoder().encodeToString(pcm16),
                sessionId = currentSessionId,
            )
            if (sent && coordinator.isActiveSession(currentSessionId)) {
                coordinator.updateAudioStatus(VoiceAudioStatus.UserSpeaking)
            }
        }
        coordinator.updateAudioStatus(VoiceAudioStatus.Listening)
    }

    private fun invalidateAudioSessions() {
        gemini.invalidateOutboundSession()
        audio.invalidatePlaybackSession()
    }
}

private fun me.rerere.rikkahub.voiceagent.gemini.GeminiContentTurn.voiceContextLabel(): String =
    when (role) {
        "model" -> "Assistant"
        else -> "User"
    }
```

- [ ] **Step 4: Make `VoiceAgentViewModel` delegate to `VoiceAgentCallSession` until Task 11**

Replace most of `VoiceAgentViewModel` body with:

```kotlin
class VoiceAgentViewModel(
    modelId: String,
    sessionApi: VoiceSessionApi,
    toolApi: VoiceToolApi,
    gemini: GeminiLiveVoiceClient,
    audio: VoiceAudioEngine,
    conversationStore: VoiceConversationStore,
    contextProvider: VoiceAgentContextProvider,
    diagnostics: VoiceDiagnostics = VoiceDiagnostics(),
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val callSession = VoiceAgentCallSession(
        modelId = modelId,
        sessionApi = sessionApi,
        toolApi = toolApi,
        gemini = gemini,
        audio = audio,
        conversationStore = conversationStore,
        contextProvider = contextProvider,
        diagnostics = diagnostics,
        scope = scope ?: viewModelScope,
    )

    val state: StateFlow<VoiceAgentUiState> = callSession.state

    fun start() = callSession.start()
    fun interrupt() = callSession.interrupt()
    fun setMuted(value: Boolean) = callSession.setMuted(value)
    fun reconnect() = callSession.reconnect()
    fun end() = callSession.end()

    override fun onCleared() {
        callSession.closeNow()
        super.onCleared()
    }
}
```

After this delegation compiles, delete the now-unused file-private `withTurnsFoldedIntoSystemInstruction` and `voiceContextLabel` extensions from `VoiceAgentViewModel.kt` — they live in `VoiceAgentCallSession.kt` now. Keep `VoiceAgentCoordinator` and the API interfaces in place.

- [ ] **Step 5: Run session and existing ViewModel tests**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentCallSessionTest' --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentViewModelTest'
```

Expected: PASS after moving any private fakes into shared test helpers and removing the old background-ending test assertion.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallSession.kt app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentViewModel.kt app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallSessionTest.kt app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentViewModelTest.kt app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentFakes.kt
git commit -m "refactor: extract Voice Agent call session"
```

---

## Task 5: Add Call Factory And Manager

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallFactory.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManager.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentViewModelFactory.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManagerTest.kt`

- [ ] **Step 1: Write manager ownership test**

Create `VoiceAgentCallManagerTest.kt`:

```kotlin
package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.uuid.Uuid

class VoiceAgentCallManagerTest {
    @Test
    fun `start creates one active session and exposes its state`() = runTest {
        val session = FakeManagedVoiceCallSession()
        val factory = FakeVoiceAgentCallFactory(session)
        val manager = VoiceAgentCallManager(factory = factory)
        val conversationId = Uuid.parse("33333333-3333-4333-8333-333333333333")
        val config = fakeLaunchConfig()

        manager.start(conversationId = conversationId, config = config, scope = this)

        val observedState = manager.state
        session.state.value = VoiceAgentUiState(session = VoiceSessionStatus.Connected)
        advanceUntilIdle()

        assertSame(observedState, manager.state)
        assertEquals(VoiceSessionStatus.Connected, manager.state.value.session)
        assertEquals(listOf(conversationId to config), factory.created)
        assertEquals(1, session.startCalls)
    }

    @Test
    fun `starting another conversation ends previous session before replacing it`() = runTest {
        val first = FakeManagedVoiceCallSession()
        val second = FakeManagedVoiceCallSession()
        val manager = VoiceAgentCallManager(factory = FakeVoiceAgentCallFactory(first, second))
        val firstConversationId = Uuid.parse("44444444-4444-4444-8444-444444444444")
        val secondConversationId = Uuid.parse("55555555-5555-4555-8555-555555555555")

        manager.start(firstConversationId, fakeLaunchConfig(), this)
        manager.start(secondConversationId, fakeLaunchConfig(), this)

        assertEquals(1, first.endCalls)
        assertEquals(0, first.closeNowCalls)
        assertEquals(1, second.startCalls)
        assertEquals(secondConversationId, manager.activeConversationId.value)
    }

    @Test
    fun `detach does not end active session`() = runTest {
        val session = FakeManagedVoiceCallSession()
        val manager = VoiceAgentCallManager(factory = FakeVoiceAgentCallFactory(session))

        manager.start(Uuid.random(), fakeLaunchConfig(), this)
        manager.detachUi()

        assertEquals(0, session.endCalls)
        assertEquals(0, session.closeNowCalls)
    }

    @Test
    fun `end forwards to active session and clears active call`() = runTest {
        val session = FakeManagedVoiceCallSession()
        val manager = VoiceAgentCallManager(factory = FakeVoiceAgentCallFactory(session))

        manager.start(Uuid.random(), fakeLaunchConfig(), this)
        manager.end()

        assertEquals(1, session.endCalls)
        assertEquals(null, manager.activeConversationId.value)
    }
}
```

Add fakes in the same file:

```kotlin
private class FakeManagedVoiceCallSession : ManagedVoiceCallSession {
    override val state = MutableStateFlow(VoiceAgentUiState())
    var startCalls = 0
    var endCalls = 0
    var closeNowCalls = 0
    override fun start() { startCalls += 1 }
    override fun interrupt() = Unit
    override fun setMuted(value: Boolean) = Unit
    override fun reconnect() = Unit
    override fun end() { endCalls += 1 }
    override fun closeNow() { closeNowCalls += 1 }
}

private class FakeVoiceAgentCallFactory(
    private vararg val sessions: ManagedVoiceCallSession,
) : VoiceAgentCallFactory {
    val created = mutableListOf<Pair<Uuid, VoiceAgentLaunchConfig>>()
    private var nextSession = 0
    override fun create(conversationId: Uuid, config: VoiceAgentLaunchConfig, scope: CoroutineScope): ManagedVoiceCallSession {
        created += conversationId to config
        return sessions[nextSession++]
    }
}

private fun fakeLaunchConfig() = VoiceAgentLaunchConfig(
    voiceLabBaseUrl = "https://voice.test",
    credentials = VoiceLabMobileCredentials(hermesProfileApiKey = "profile-key"),
    voiceModelId = "gemini-flash",
    assistantName = "Hermes",
    assistantPrompt = "system",
)
```

- [ ] **Step 2: Run manager tests and verify they fail**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentCallManagerTest'
```

Expected: FAIL because manager/factory interfaces do not exist.

- [ ] **Step 3: Add session interface and production factory**

Create `VoiceAgentCallFactory.kt`:

```kotlin
package me.rerere.rikkahub.voiceagent

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.voiceagent.audio.AndroidVoiceAudioEngine
import me.rerere.rikkahub.voiceagent.voicelab.VoiceLabMobileApi
import okhttp3.OkHttpClient
import kotlin.uuid.Uuid

interface ManagedVoiceCallSession {
    val state: StateFlow<VoiceAgentUiState>
    fun start()
    fun interrupt()
    fun setMuted(value: Boolean)
    fun reconnect()
    fun end()
    fun closeNow()
}

interface VoiceAgentCallFactory {
    fun create(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        scope: CoroutineScope,
    ): ManagedVoiceCallSession
}

class DefaultVoiceAgentCallFactory(
    private val context: Context,
    private val chatService: ChatService,
    private val settingsStore: SettingsStore,
    private val okHttpClient: OkHttpClient,
) : VoiceAgentCallFactory {
    override fun create(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        scope: CoroutineScope,
    ): ManagedVoiceCallSession {
        val voiceLabApi = VoiceLabMobileApi(
            baseUrl = config.voiceLabBaseUrl,
            credentials = config.credentials,
        )
        return VoiceAgentCallSession(
            modelId = config.voiceModelId,
            sessionApi = VoiceLabVoiceSessionApi(api = voiceLabApi),
            toolApi = VoiceLabHermesToolApi(api = voiceLabApi),
            gemini = me.rerere.rikkahub.voiceagent.gemini.OkHttpGeminiLiveVoiceClient(httpClient = okHttpClient),
            audio = AndroidVoiceAudioEngine(context = context),
            conversationStore = ChatServiceVoiceConversationStore(
                conversationId = conversationId,
                chatService = chatService,
            ),
            contextProvider = SettingsVoiceAgentContextProvider(
                settingsStore = settingsStore,
                voiceModelName = config.voiceModelId,
            ),
            scope = scope,
        )
    }
}
```

Make `VoiceAgentCallSession : ManagedVoiceCallSession`.

- [ ] **Step 4: Add manager**

Create `VoiceAgentCallManager.kt`:

```kotlin
package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

class VoiceAgentCallManager(
    private val factory: VoiceAgentCallFactory,
) {
    private val lock = Any()
    private val _state = MutableStateFlow(VoiceAgentUiState())
    private var activeSession: ManagedVoiceCallSession? = null
    private var stateCollectionJob: Job? = null
    private var callStatus: VoiceCallStatus = VoiceCallStatus.Idle

    val activeConversationId = MutableStateFlow<Uuid?>(null)
    val state: StateFlow<VoiceAgentUiState> = _state

    fun start(conversationId: Uuid, config: VoiceAgentLaunchConfig, scope: CoroutineScope) {
        var previousSession: ManagedVoiceCallSession? = null
        val session = synchronized(lock) {
            val current = activeSession
            if (current != null && activeConversationId.value == conversationId) {
                current
            } else {
                previousSession = current
                stateCollectionJob?.cancel()
                factory.create(conversationId = conversationId, config = config, scope = scope).also { created ->
                    activeSession = created
                    activeConversationId.value = conversationId
                    _state.value = created.state.value.copy(call = callStatus)
                    stateCollectionJob = scope.launch {
                        created.state.collect { sessionState ->
                            _state.value = sessionState.copy(call = callStatus)
                        }
                    }
                }
            }
        }
        previousSession?.end()
        session.start()
    }

    fun detachUi() = Unit

    fun interrupt() = synchronized(lock) { activeSession }?.interrupt()

    fun setMuted(value: Boolean) = synchronized(lock) { activeSession }?.setMuted(value)

    fun reconnect() = synchronized(lock) { activeSession }?.reconnect()

    fun updateCallStatus(status: VoiceCallStatus) {
        synchronized(lock) {
            callStatus = status
            _state.value = _state.value.copy(call = status)
        }
    }

    fun end() {
        val session = synchronized(lock) {
            stateCollectionJob?.cancel()
            stateCollectionJob = null
            callStatus = VoiceCallStatus.Ending
            _state.value = _state.value.copy(call = VoiceCallStatus.Ending)
            activeSession.also {
                activeSession = null
                activeConversationId.value = null
            }
        }
        session?.end()
    }

    fun closeNow() {
        val session = synchronized(lock) {
            activeSession?.also {
                stateCollectionJob?.cancel()
                stateCollectionJob = null
                callStatus = VoiceCallStatus.Ended
                _state.value = VoiceAgentUiState(call = VoiceCallStatus.Ended)
                activeSession = null
                activeConversationId.value = null
            }
        }
        session?.closeNow()
    }
}
```

- [ ] **Step 5: Run manager and session tests**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentCallManagerTest' --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentCallSessionTest'
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallFactory.kt app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManager.kt app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallSession.kt app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManagerTest.kt
git commit -m "feat: add Voice Agent call manager"
```

---

## Task 6: Register Call Dependencies

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/di/AppModule.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManagerTest.kt`

- [ ] **Step 1: Add a factory type assertion test**

Add to `VoiceAgentCallManagerTest`:

```kotlin
@Test
fun `manager exposes idle state before start`() {
    val manager = VoiceAgentCallManager(factory = FakeVoiceAgentCallFactory(FakeManagedVoiceCallSession()))

    assertEquals(VoiceSessionStatus.Idle, manager.state.value.session)
    assertEquals(null, manager.activeConversationId.value)
}
```

- [ ] **Step 2: Register production dependencies in Koin**

Modify `AppModule.kt` imports:

```kotlin
import me.rerere.rikkahub.voiceagent.DefaultVoiceAgentCallFactory
import me.rerere.rikkahub.voiceagent.VoiceAgentCallFactory
import me.rerere.rikkahub.voiceagent.VoiceAgentCallManager
```

Add to `appModule`:

```kotlin
single<VoiceAgentCallFactory> {
    DefaultVoiceAgentCallFactory(
        context = get(),
        chatService = get(),
        settingsStore = get(),
        okHttpClient = get(),
    )
}

single {
    VoiceAgentCallManager(factory = get())
}
```

Keep `VoiceAgentViewModelFactory` registered until Task 11 because `VoiceAgentRoute` still uses it before route migration.

- [ ] **Step 3: Run focused tests**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentCallManagerTest'
```

Expected: PASS.

- [ ] **Step 4: Compile app**

Run:

```bash
./gradlew --no-daemon :app:compileDebugKotlin
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/di/AppModule.kt app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManagerTest.kt
git commit -m "chore: register Voice Agent call manager"
```

---

## Task 7: Add Foreground Service Notification Channel And Factory

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/RikkaHubApp.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallService.kt` as a compile-only stub; Task 8 replaces the body with the foreground service implementation.
- Create: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentNotificationFactory.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallContract.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallContractTest.kt`

- [ ] **Step 1: Add notification route helper test**

Add to `VoiceAgentCallContractTest`:

```kotlin
@Test
fun `service action constants are stable`() {
    assertEquals("me.rerere.rikkahub.voiceagent.action.START", VoiceAgentCallContract.ACTION_START)
    assertEquals("me.rerere.rikkahub.voiceagent.action.END", VoiceAgentCallContract.ACTION_END)
    assertEquals(2401, VoiceAgentCallContract.NOTIFICATION_ID)
}
```

- [ ] **Step 2: Add channel constant**

Add near the existing notification channel constants in `RikkaHubApp.kt`:

```kotlin
const val VOICE_AGENT_NOTIFICATION_CHANNEL_ID = "voice_agent"
```

In `createNotificationChannel()`, add:

```kotlin
val voiceAgentChannel = NotificationChannelCompat
    .Builder(VOICE_AGENT_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
    .setName("Voice Agent")
    .setDescription("Ongoing Voice Agent call status")
    .build()
notificationManager.createNotificationChannel(voiceAgentChannel)
```

- [ ] **Step 3: Add a temporary service stub so notification intents compile**

Create `VoiceAgentCallService.kt`. Task 8 replaces this body with the real foreground service.

```kotlin
package me.rerere.rikkahub.voiceagent

import android.app.Service
import android.content.Intent
import android.os.IBinder

class VoiceAgentCallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf()
        return START_NOT_STICKY
    }
}
```

- [ ] **Step 4: Add notification factory**

Create `VoiceAgentNotificationFactory.kt`:

```kotlin
package me.rerere.rikkahub.voiceagent

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.VOICE_AGENT_NOTIFICATION_CHANNEL_ID

class VoiceAgentNotificationFactory(
    private val context: Context,
) {
    fun activeNotification(conversationId: String, state: VoiceAgentUiState): Notification {
        return NotificationCompat.Builder(context, VOICE_AGENT_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle("RikkaHub Voice Agent")
            .setContentText(state.notificationText())
            .setContentIntent(openVoiceAgentPendingIntent(conversationId))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "End", endPendingIntent())
            .build()
    }

    private fun openVoiceAgentPendingIntent(conversationId: String): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java)
            .putExtra(VoiceAgentCallContract.EXTRA_ROUTE_VOICE_AGENT_CONVERSATION_ID, conversationId)
        return PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun endPendingIntent(): PendingIntent {
        val intent = Intent(context, VoiceAgentCallService::class.java)
            .setAction(VoiceAgentCallContract.ACTION_END)
        return PendingIntent.getService(
            context,
            VoiceAgentCallContract.NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

private fun VoiceAgentUiState.notificationText(): String =
    when (val callStatus = call) {
        VoiceCallStatus.ForegroundStarting -> "Starting call runtime"
        VoiceCallStatus.BackgroundCapable -> when (session) {
            VoiceSessionStatus.Connected -> "Active - background ready"
            VoiceSessionStatus.Reconnecting -> "Reconnecting"
            else -> "Starting"
        }
        is VoiceCallStatus.Degraded -> "Degraded: ${callStatus.message}"
        VoiceCallStatus.Ending -> "Ending"
        VoiceCallStatus.Ended -> "Ended"
        VoiceCallStatus.Idle -> when (session) {
            is VoiceSessionStatus.Error -> "Error: ${session.message}"
            else -> "Starting"
        }
    }
```

- [ ] **Step 5: Register notification factory**

Add to `AppModule.kt`:

```kotlin
single {
    VoiceAgentNotificationFactory(context = get())
}
```

- [ ] **Step 6: Run tests and compile**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentCallContractTest'
./gradlew --no-daemon :app:compileDebugKotlin
```

Expected: both PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/RikkaHubApp.kt app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallService.kt app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentNotificationFactory.kt app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallContract.kt app/src/main/java/me/rerere/rikkahub/di/AppModule.kt app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallContractTest.kt
git commit -m "feat: add Voice Agent notification support"
```

---

## Task 8: Add Foreground Call Service

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallService.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallContractTest.kt`

- [ ] **Step 1: Add service intent construction helpers**

Replace `voiceAgentCallStartIntent` in `VoiceAgentCallContract.kt` with a context-aware helper:

```kotlin
fun voiceAgentCallStartIntent(context: android.content.Context, conversationId: String): android.content.Intent =
    android.content.Intent(context, VoiceAgentCallService::class.java)
        .setAction(VoiceAgentCallContract.ACTION_START)
        .putExtra(VoiceAgentCallContract.EXTRA_CONVERSATION_ID, conversationId)

fun voiceAgentCallEndIntent(context: android.content.Context): android.content.Intent =
    android.content.Intent(context, VoiceAgentCallService::class.java)
        .setAction(VoiceAgentCallContract.ACTION_END)
```

Keep JVM tests focused on action and extra constants. The context-dependent helper is verified by `:app:compileDebugKotlin`.

- [ ] **Step 2: Add service permissions and service declaration**

Add to `app/src/main/AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.MANAGE_OWN_CALLS" />
```

Add inside `<application>`:

```xml
<service
  android:name=".voiceagent.VoiceAgentCallService"
  android:exported="false"
  android:foregroundServiceType="microphone" />
```

- [ ] **Step 3: Replace the Task 7 stub with the real foreground service**

Replace `VoiceAgentCallService.kt` with:

```kotlin
package me.rerere.rikkahub.voiceagent

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.ChatService
import org.koin.android.ext.android.inject
import kotlin.uuid.Uuid

class VoiceAgentCallService : Service() {
    private val manager: VoiceAgentCallManager by inject()
    private val settingsStore: SettingsStore by inject()
    private val chatService: ChatService by inject()
    private val notificationFactory: VoiceAgentNotificationFactory by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var activeConversationId: Uuid? = null
    private var notificationJob: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            VoiceAgentCallContract.ACTION_START -> startCall(intent)
            VoiceAgentCallContract.ACTION_END -> endCall()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        notificationJob?.cancel()
        notificationJob = null
        manager.closeNow()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startCall(intent: Intent) {
        val id = intent.getStringExtra(VoiceAgentCallContract.EXTRA_CONVERSATION_ID)
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?: run {
                stopSelf()
                return
            }
        activeConversationId = id
        notificationJob?.cancel()
        manager.updateCallStatus(VoiceCallStatus.ForegroundStarting)
        startForegroundFor(id.toString(), VoiceAgentUiState(call = VoiceCallStatus.ForegroundStarting))
        serviceScope.launch {
            val settings = settingsStore.settingsFlow.first()
            val conversation = chatService.getConversationFlow(id).value
            when (val result = VoiceAgentConfigResolver().resolve(settings = settings, conversation = conversation)) {
                is VoiceAgentConfigResult.Available -> {
                    manager.start(conversationId = id, config = result.config, scope = serviceScope)
                    manager.updateCallStatus(VoiceCallStatus.BackgroundCapable)
                    notificationJob = serviceScope.launch {
                        manager.state.collect { state ->
                            startForegroundFor(id.toString(), state)
                        }
                    }
                }
                is VoiceAgentConfigResult.Unavailable -> {
                    manager.updateCallStatus(VoiceCallStatus.Degraded(result.message))
                    startForegroundFor(id.toString(), manager.state.value)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun endCall() {
        notificationJob?.cancel()
        notificationJob = null
        manager.end()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundFor(conversationId: String, state: VoiceAgentUiState) {
        val notification = notificationFactory.activeNotification(conversationId = conversationId, state = state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                VoiceAgentCallContract.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(VoiceAgentCallContract.NOTIFICATION_ID, notification)
        }
    }
}
```

- [ ] **Step 4: Run compile**

Run:

```bash
./gradlew --no-daemon :app:compileDebugKotlin
```

Expected: PASS. The service is declared and started as a `microphone` foreground service, and `startForeground` passes `FOREGROUND_SERVICE_TYPE_MICROPHONE`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallService.kt app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallContract.kt
git commit -m "feat: run Voice Agent as foreground call service"
```

---

## Task 9: Wire Voice Agent Screen To Service-Owned Manager

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentRoute.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentStartGate.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/di/ViewModelModule.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManagerTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentStartGateTest.kt`

- [ ] **Step 1: Add test that UI detach does not end session**

The `detach does not end active session` test from Task 5 already covers the key behavior. Add this assertion to it:

```kotlin
assertEquals(null, manager.activeConversationId.value?.takeIf { session.endCalls > 0 })
```

Update `VoiceAgentStartGateTest` to cover notification readiness:

```kotlin
@Test
fun `start gate requires notification permission after microphone permission`() {
    assertEquals(
        VoiceAgentStartGate.NeedsNotificationPermission,
        voiceAgentStartGate(
            hasMicrophonePermission = true,
            hasNotificationPermission = false,
        ),
    )
}

@Test
fun `start gate is ready when microphone and notification are granted`() {
    assertEquals(
        VoiceAgentStartGate.Ready,
        voiceAgentStartGate(
            hasMicrophonePermission = true,
            hasNotificationPermission = true,
        ),
    )
}
```

Then update `VoiceAgentStartGate.kt`:

```kotlin
internal enum class VoiceAgentStartGate {
    Ready,
    NeedsMicrophonePermission,
    NeedsNotificationPermission,
}

internal fun voiceAgentStartGate(
    hasMicrophonePermission: Boolean,
    hasNotificationPermission: Boolean,
): VoiceAgentStartGate =
    when {
        !hasMicrophonePermission -> VoiceAgentStartGate.NeedsMicrophonePermission
        !hasNotificationPermission -> VoiceAgentStartGate.NeedsNotificationPermission
        else -> VoiceAgentStartGate.Ready
    }
```

- [ ] **Step 2: Replace ViewModel injection in route**

In `VoiceAgentRoute.kt`, inside the `VoiceAgentConfigResult.Available` branch, delete the `koinViewModel` block and the `VoiceAgentScreen(vm = vm, ...)` call, and wire the screen to the service-owned manager instead:

```kotlin
val context = androidx.compose.ui.platform.LocalContext.current
val callManager = org.koin.compose.koinInject<VoiceAgentCallManager>()
VoiceAgentScreen(
    stateProvider = { callManager.state },
    title = result.config.assistantName,
    onStart = {
        androidx.core.content.ContextCompat.startForegroundService(
            context,
            voiceAgentCallStartIntent(context, conversationId.toString()),
        )
    },
    onBack = { navController.popBackStack() },
    onMuteToggle = { muted -> callManager.setMuted(!muted) },
    onInterrupt = callManager::interrupt,
    onReconnect = callManager::reconnect,
    onDetach = callManager::detachUi,
    onEnd = {
        context.startService(voiceAgentCallEndIntent(context))
        navController.popBackStack()
    },
)
```

`onEnd` and the Telecom disconnect (Task 10) both tear down through the service's `ACTION_END`, which is the single place that calls `manager.end()` and stops the foreground service. Do **not** also call `callManager.end()` here, or the call ends twice.

Change the `VoiceAgentScreen` signature to take commands instead of the ViewModel, and **keep the permission gate**. Two behaviors are load-bearing:
- `onStart()` must fire only at `VoiceAgentStartGate.Ready`. Starting a `microphone` foreground service without `RECORD_AUDIO` throws `SecurityException`; the `startGate` is what guarantees microphone permission is granted first. On Android 13+, it also gates on `POST_NOTIFICATIONS` because the ongoing notification is the call return path.
- Leaving the screen must **detach**, not end — the service keeps the call alive in the background.

Add imports for `PermissionNotification` and `android.os.Build` if they are not already present.

```kotlin
@Composable
private fun VoiceAgentScreen(
    stateProvider: () -> StateFlow<VoiceAgentUiState>,
    title: String,
    onStart: () -> Unit,
    onBack: () -> Unit,
    onMuteToggle: (Boolean) -> Unit,
    onInterrupt: () -> Unit,
    onReconnect: () -> Unit,
    onDetach: () -> Unit,
    onEnd: () -> Unit,
) {
    val state by stateProvider().collectAsStateWithLifecycle()
    val muted = state.audio == VoiceAudioStatus.Muted
    val microphonePermission = rememberPermissionState(PermissionRecordAudio)
    val notificationPermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        rememberPermissionState(PermissionNotification)
    } else {
        null
    }
    val startGate = voiceAgentStartGate(
        hasMicrophonePermission = microphonePermission.allRequiredPermissionsGranted,
        hasNotificationPermission = notificationPermission?.allRequiredPermissionsGranted ?: true,
    )
    var requestedMicrophonePermission by remember { mutableStateOf(false) }
    var requestedNotificationPermission by remember { mutableStateOf(false) }

    PermissionManager(permissionState = microphonePermission)
    if (notificationPermission != null) {
        PermissionManager(permissionState = notificationPermission)
    }
    KeepScreenOn()

    LaunchedEffect(startGate) {
        when {
            startGate == VoiceAgentStartGate.NeedsMicrophonePermission && !requestedMicrophonePermission -> {
                requestedMicrophonePermission = true
                microphonePermission.requestPermissions()
            }
            startGate == VoiceAgentStartGate.NeedsNotificationPermission && !requestedNotificationPermission -> {
                requestedNotificationPermission = true
                notificationPermission?.requestPermissions()
            }
        }
    }

    // Was `LaunchedEffect(vm, startGate) { if (Ready) vm.start() }`. The `vm` key is gone;
    // the gate stays so the foreground microphone service never starts without microphone
    // permission or, on Android 13+, without notification permission for the call return path.
    LaunchedEffect(startGate) {
        if (startGate == VoiceAgentStartGate.Ready) {
            onStart()
        }
    }

    // Was `DisposableEffect(vm) { onDispose { vm.end() } }`. The call now outlives the
    // screen, so detach the UI instead of ending it.
    DisposableEffect(Unit) {
        onDispose { onDetach() }
    }

    // DELETE the previous `DisposableEffect(lifecycleOwner, vm)` that ran
    // `vm.endBecauseBackgrounded()` on `ON_STOP`, and drop the now-unused `lifecycleOwner`.

    // Body otherwise unchanged from the current screen: VoiceAgentScaffold + VoiceAgentControls,
    // now reading `state` / `muted` / `startGate` and calling the command callbacks
    // (onBack, onMuteToggle, onInterrupt, onReconnect, onEnd) instead of `vm.*`.
    // Also render `state.call` near the main session status so "Background ready" and
    // Telecom/foreground-service degradation are visible while debugging.
    // If `startGate == VoiceAgentStartGate.NeedsNotificationPermission`, show a clear
    // visible status such as "Notification permission required for background call controls".
}
```

Add a small display helper next to the existing status text helpers:

```kotlin
private fun VoiceAgentUiState.callStatusText(): String = when (val callStatus = call) {
    VoiceCallStatus.Idle -> "Call idle"
    VoiceCallStatus.ForegroundStarting -> "Starting call runtime"
    VoiceCallStatus.BackgroundCapable -> "Background ready"
    is VoiceCallStatus.Degraded -> "Call degraded: ${callStatus.message}"
    VoiceCallStatus.Ending -> "Ending call"
    VoiceCallStatus.Ended -> "Call ended"
}
```

- [ ] **Step 3: Remove old ViewModel binding after route compile passes**

Remove from `ViewModelModule.kt`:

```kotlin
import me.rerere.rikkahub.voiceagent.VoiceAgentLaunchConfig
import me.rerere.rikkahub.voiceagent.VoiceAgentViewModel
import me.rerere.rikkahub.voiceagent.VoiceAgentViewModelFactory
```

Remove:

```kotlin
viewModel<VoiceAgentViewModel> { params ->
    get<VoiceAgentViewModelFactory>().create(
        conversationId = Uuid.parse(params.get<String>()),
        config = params.get<VoiceAgentLaunchConfig>(),
    )
}
```

Keep `VoiceAgentViewModel` source until all tests are migrated; remove it in the cleanup task.

- [ ] **Step 4: Run compile and manager tests**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentCallManagerTest' --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentStartGateTest'
./gradlew --no-daemon :app:compileDebugKotlin
```

Expected: PASS. The route no longer ends the call on `ON_STOP`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentRoute.kt app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentStartGate.kt app/src/main/java/me/rerere/rikkahub/di/ViewModelModule.kt app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManagerTest.kt app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentStartGateTest.kt
git commit -m "feat: attach Voice Agent screen to call service"
```

---

## Task 10: Add Self-Managed Telecom Adapter

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentTelecomAdapter.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentConnectionService.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentTelecomConnection.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallService.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/di/AppModule.kt`

- [ ] **Step 1: Add Telecom manifest declarations**

Add inside `<application>`:

```xml
<service
  android:name=".voiceagent.VoiceAgentConnectionService"
  android:exported="true"
  android:permission="android.permission.BIND_TELECOM_CONNECTION_SERVICE">
  <intent-filter>
    <action android:name="android.telecom.ConnectionService" />
  </intent-filter>
</service>
```

- [ ] **Step 2: Add adapter**

Create `VoiceAgentTelecomAdapter.kt`:

```kotlin
package me.rerere.rikkahub.voiceagent

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager

class VoiceAgentTelecomAdapter(
    private val context: Context,
) {
    private val handle: PhoneAccountHandle
        get() = PhoneAccountHandle(
            ComponentName(context, VoiceAgentConnectionService::class.java),
            "rikka-voice-agent",
        )

    fun register(): Result<Unit> = runCatching {
        val telecomManager = requireTelecomManager()
        val account = PhoneAccount.builder(handle, "RikkaHub Voice Agent")
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .build()
        telecomManager.registerPhoneAccount(account)
    }

    fun startCall(): Result<Unit> = runCatching {
        val telecomManager = requireTelecomManager()
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
        }
        telecomManager.placeCall(Uri.fromParts("rikkahub-voice", "voice-agent", null), extras)
    }

    private fun requireTelecomManager(): TelecomManager =
        context.getSystemService(TelecomManager::class.java)
            ?: error("TelecomManager unavailable")
}
```

- [ ] **Step 3: Add connection service and connection**

Create `VoiceAgentConnectionService.kt`:

```kotlin
package me.rerere.rikkahub.voiceagent

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import org.koin.android.ext.android.inject

class VoiceAgentConnectionService : ConnectionService() {
    private val callManager: VoiceAgentCallManager by inject()

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: android.telecom.PhoneAccountHandle?,
        request: ConnectionRequest?,
    ): Connection {
        return VoiceAgentTelecomConnection(context = applicationContext).apply {
            setInitializing()
            setActive()
        }
    }

    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: android.telecom.PhoneAccountHandle?,
        request: ConnectionRequest?,
    ) {
        val detail = "Android Telecom rejected Voice Agent call"
        callManager.recordDiagnostic("telecom_outgoing_failed", detail)
        callManager.updateCallStatus(VoiceCallStatus.Degraded(detail))
    }
}
```

Create `VoiceAgentTelecomConnection.kt`:

```kotlin
package me.rerere.rikkahub.voiceagent

import android.content.Context
import android.telecom.Connection
import android.telecom.DisconnectCause

class VoiceAgentTelecomConnection(
    private val context: Context,
) : Connection() {
    override fun onDisconnect() {
        // Tear down via the service so the foreground notification and the service
        // itself stop — not just the voice session.
        context.startService(voiceAgentCallEndIntent(context))
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
    }
}
```

Add to `VoiceAgentCallManager`:

```kotlin
fun recordDiagnostic(name: String, detail: String) {
    val current = synchronized(lock) { activeSession }
    if (current is VoiceAgentCallSession) {
        current.recordDiagnostic(name, detail)
    }
}
```

Add to `VoiceAgentCallSession`:

```kotlin
fun recordDiagnostic(name: String, detail: String) {
    coordinator.recordDiagnostic(name, detail)
}
```

- [ ] **Step 4: Register and invoke adapter from service**

Add Koin registration:

```kotlin
single {
    VoiceAgentTelecomAdapter(context = get())
}
```

Inject into `VoiceAgentCallService`:

```kotlin
private val telecomAdapter: VoiceAgentTelecomAdapter by inject()
```

Inside the `VoiceAgentConfigResult.Available` branch in `startCall`, call this immediately after `manager.start(...)` and `manager.updateCallStatus(VoiceCallStatus.BackgroundCapable)`. Do not run Telecom setup before `manager.start(...)`, because diagnostics need an active session to attach to.

```kotlin
telecomAdapter.register()
    .onFailure {
        val detail = it.message ?: it.javaClass.simpleName
        manager.recordDiagnostic("telecom_register_failed", detail)
        manager.updateCallStatus(VoiceCallStatus.Degraded("Telecom unavailable: $detail"))
    }
telecomAdapter.startCall()
    .onFailure {
        val detail = it.message ?: it.javaClass.simpleName
        manager.recordDiagnostic("telecom_start_failed", detail)
        manager.updateCallStatus(VoiceCallStatus.Degraded("Telecom call unavailable: $detail"))
    }
```

- [ ] **Step 5: Run compile**

Run:

```bash
./gradlew --no-daemon :app:compileDebugKotlin
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentTelecomAdapter.kt app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentConnectionService.kt app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentTelecomConnection.kt app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallService.kt app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallManager.kt app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallSession.kt app/src/main/java/me/rerere/rikkahub/di/AppModule.kt
git commit -m "feat: add self-managed Voice Agent call integration"
```

---

## Task 11: Clean Up Old ViewModel Path

**Files:**
- Delete: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentViewModelFactory.kt`
- Modify or delete: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentViewModel.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentViewModelTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallSessionTest.kt`

- [ ] **Step 1: Move remaining useful ViewModel tests to session or manager tests**

For each remaining `VoiceAgentViewModelTest` that verifies session behavior, move it to `VoiceAgentCallSessionTest` and replace:

```kotlin
val vm = VoiceAgentViewModel(...)
```

with:

```kotlin
val session = VoiceAgentCallSession(..., scope = this)
```

Replace `vm.state` with `session.state`, `vm.start()` with `session.start()`, and `vm.invokeOnClearedForTest()` with `session.closeNow()`.

- [ ] **Step 2: Delete background-ending test**

Remove the test named:

```kotlin
fun `ViewModel ends active session with diagnostic when screen backgrounds`()
```

This behavior is intentionally obsolete.

- [ ] **Step 3: Delete old factory**

Delete:

```bash
app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentViewModelFactory.kt
```

Keep `VoiceAgentViewModel.kt` in place if it still contains `VoiceAgentCoordinator` and API interfaces after deleting the ViewModel class. Do not rename files in this task.

- [ ] **Step 4: Run focused tests**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentCallSessionTest' --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentCallManagerTest'
./gradlew --no-daemon :app:compileDebugKotlin
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/voiceagent app/src/test/java/me/rerere/rikkahub/voiceagent
git commit -m "refactor: remove screen-owned Voice Agent session"
```

---

## Task 12: Full Verification And Device Smoke

**Files:**
- No source files expected.
- Update plan checkboxes as completed if this plan is being executed directly.

- [ ] **Step 1: Run all focused unit tests**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest \
  --tests 'me.rerere.rikkahub.voiceagent.*' \
  --tests 'me.rerere.rikkahub.RouteActivityIntentTest'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Assemble debug APK**

Run with existing credential environment:

```bash
set -a
. /tmp/voice_probe_env.sh
set +a
export CF_ACCESS_CLIENT_ID="$CF_ID"
export CF_ACCESS_CLIENT_SECRET="$CF_SECRET"
./gradlew --no-daemon :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. Do not print credential values.

- [ ] **Step 3: Install on connected device**

Run:

```bash
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 adb devices -l
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: target device appears as `device`, install prints `Success`.

- [ ] **Step 4: Start RikkaHub and open Voice Agent manually**

Run:

```bash
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 adb shell monkey -p me.rerere.rikkahub.debug 1
```

Expected: app launches. In the UI, open a chat and tap `Start talking`.

- [ ] **Step 5: Verify foreground notification and background behavior**

Manual steps:

1. Start Voice Agent.
2. Confirm notification shade shows `RikkaHub Voice Agent`.
3. Press Home or switch to another app.
4. Wait 20 seconds.
5. Return by tapping the notification.

Expected:

- Voice Agent screen returns to the same active call.
- Session is not `Ended`.
- No diagnostic says `voice_agent_backgrounded`.
- Notification End action is visible.

- [ ] **Step 6: Verify audio/debug injection while service is active**

Run the debug injection command using the current PCM test file path:

```bash
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 adb shell am broadcast \
  -a me.rerere.rikkahub.debug.voiceagent.INJECT_PCM \
  --es path voice-agent-debug/test-prompt.pcm
```

Expected:

- Broadcast succeeds from ADB.
- Voice Agent receives input or records a clear injection diagnostic.
- Ordinary apps cannot call the receiver because the debug receiver requires signature permission.

- [ ] **Step 7: Verify history persistence**

Manual steps:

1. End the call from notification or screen.
2. Return to normal chat history.

Expected:

- User transcript is visible in normal chat.
- Assistant transcript is visible in normal chat.
- Hermes/MS-agent tool records are visible if the turn triggered tools.
- Starting a second Voice Agent call in the same chat appends new transcript messages instead of overwriting the previous call.

- [ ] **Step 8: Capture logs without secrets**

Run:

```bash
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 adb logcat -d \
  | rg 'VoiceAgent|VoiceLab|Gemini|Hermes|Telecom|Foreground' \
  > /tmp/voice-agent-call-architecture-smoke.log
```

Expected:

- Log includes service start, foreground notification, Gemini connect, and call end.
- Log does not include raw Cloudflare credential values.
- Log does not include Cloudflare HTML/auth failure.

- [ ] **Step 9: Commit verification notes if source changed during verification**

If verification required source fixes, commit those fixes with the relevant files. If verification required no source fixes, do not create an empty commit.

```bash
git status --short
```

Expected: only planned uncommitted files remain, or clean state for files touched by this plan.

---

## Self-Review Checklist

- Spec coverage:
  - Transcript identity: Task 1.
  - Debug receiver hardening: Task 2.
  - Service-owned call session: Tasks 4 through 9.
  - Foreground notification: Tasks 7 and 8.
  - Self-managed call integration: Task 10.
  - Old `ON_STOP` hard-end removal: Task 9 and Task 11.
  - Visible background readiness/degradation state: Tasks 3, 7, 8, 9, and 10.
  - Device smoke: Task 12.
  - Cloudflare credential risk accepted and not redesigned: Task 12 uses existing credential env without printing secrets.

- Placeholder scan:
  - The plan contains no intentionally blank implementation steps.
  - Foreground service uses one explicit type, `microphone`, in both the manifest (Task 8) and the runtime `startForeground` call (`FOREGROUND_SERVICE_TYPE_MICROPHONE`).
  - The extracted `VoiceAgentCallSession` carries its own `voiceContextLabel` / `withTurnsFoldedIntoSystemInstruction` helpers (Task 4), so it has no unresolved references.

- Type consistency:
  - `VoiceAgentCallManager`, `VoiceAgentCallSession`, `ManagedVoiceCallSession`, and `VoiceAgentCallFactory` names are consistent across tasks.
  - `VoiceAgentCallContract` action and extra names are used consistently by service, notification, route, and tests.
  - Gemini/session progress stays in the existing `VoiceSessionStatus`.
  - Android call-runtime readiness/degradation is modeled separately by the lightweight `VoiceCallStatus`.

- Review fixes applied (2026-06-06):
  - Task 4 moves the file-private `voiceContextLabel` helper into `VoiceAgentCallSession.kt` (it was an unresolved reference — file-private symbols don't cross files).
  - Task 9 keeps the `startGate == Ready` microphone-permission gate around `onStart()` and detaches (does not end) the call when the screen leaves composition.
  - Task 10 routes Telecom `onDisconnect` through the service's `ACTION_END` so the foreground service and notification are torn down, not just the session.
  - `phoneCall` foreground-service type and `FOREGROUND_SERVICE_PHONE_CALL` dropped (declared-but-unused); background readiness is represented by `VoiceCallStatus` and diagnostics instead.
  - Manager state changed to one stable `StateFlow<VoiceAgentUiState>` so Compose and the service never collect a stale idle/session flow reference.
  - Task 7 now creates a temporary service stub before notification code references `VoiceAgentCallService`, so the compile step is ordered correctly.
  - Task 2 now requires a real ADB broadcast smoke before continuing, because preserving debug injection is part of the spec.
  - Task 9 now gates start on notification permission on Android 13+ so the foreground notification return path is not silently missing.
  - Task 10 now treats missing `TelecomManager` as a visible failure and surfaces asynchronous Telecom rejection through `VoiceCallStatus.Degraded`.
