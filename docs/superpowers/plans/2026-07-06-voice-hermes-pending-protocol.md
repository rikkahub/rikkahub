# Voice Hermes Pending Protocol Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Gemini understand queued Hermes answers as pending, so it only acknowledges that it is checking Hermes until the Hermes completion follow-up arrives.

**Architecture:** Keep the current asynchronous Hermes queue. Strengthen the voice system prompt and replace the immediate `ask_hermes` tool response text with a directive pending-state response. Do not add app-side semantic filtering, output blocking, or a new tool response JSON shape.

**Tech Stack:** Kotlin, Android, Gemini Live tool responses, Gradle unit tests, Bash E2E harnesses.

---

## Source Spec

Implement the approved spec:

- `docs/superpowers/specs/2026-07-06-voice-hermes-pending-protocol-design.md`

The implementation starts from the current branch state. The branch already contains uncommitted prompt-hardening and E2E prompt changes in `external/rikkahub`; do not revert them.

## File Structure

- Modify `app/src/main/java/me/rerere/rikkahub/voiceagent/persistence/VoiceContextBuilder.kt`
  - Owns the built-in voice system instruction.
  - Add the queued-Hermes protocol rule beside the existing Hermes tool policy.
- Modify `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceHermesSessionBridgeFactory.kt`
  - Owns `HERMES_QUEUED_ACKNOWLEDGEMENT`.
  - Change the immediate queued tool response to the directive pending response.
- Modify `app/src/test/java/me/rerere/rikkahub/voiceagent/persistence/VoiceContextBuilderTest.kt`
  - Keeps expected system prompt text aligned with `VoiceContextBuilder`.
- Modify `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentRuntimeTest.kt`
  - Verifies the exact queued tool response sent to Gemini.
  - Existing tests using `queuedAck(...)` should continue to work through the production constant after the implementation changes.
- Modify `docs/voice-agent-hermes-gbrain-live-e2e.md`
  - Update the runbook pass criteria to describe the directive pending response and live manual-review expectation.
- Modify `docs/voice-agent-hermes-queue-e2e.md`
  - Add the same queued-response expectation for the multi-request runbook.

No new production files are required.

## Task 1: Add Failing Tests For Pending Protocol

**Files:**
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/persistence/VoiceContextBuilderTest.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentRuntimeTest.kt`

- [ ] **Step 1: Update the expected voice system prompt**

In `app/src/test/java/me/rerere/rikkahub/voiceagent/persistence/VoiceContextBuilderTest.kt`, update `EXPECTED_VOICE_SYSTEM_PREFIX` so it expects the queued-Hermes rule after the "Do not answer..." paragraph and before the "Answer directly..." paragraph:

```kotlin
    private companion object {
        const val EXPECTED_VOICE_SYSTEM_PREFIX =
            "You are Hermes in RikkaHub voice mode.\n" +
                "Hermes is your primary knowledge and reasoning backend in RikkaHub voice mode.\n" +
                "You are the voice interface to Hermes, not a replacement for Hermes.\n" +
                "\n" +
                "When the user asks to ask Hermes, call Hermes, use Hermes, use the Hermes tool, " +
                "or asks about facts, memory, project state, current context, plans, decisions, " +
                "debugging, G-Brain, or anything Hermes may know, you MUST call ask_hermes before answering.\n" +
                "\n" +
                "Do not answer Hermes-directed or G-Brain-directed requests from your own knowledge, " +
                "even if you think you know the answer. If speech transcription is imperfect but the " +
                "intent appears to involve Hermes, call ask_hermes with the best-effort question.\n" +
                "\n" +
                "If ask_hermes returns that Hermes has not answered yet or that the request is pending, " +
                "do not answer the user's substantive question yet. Say only a brief pending " +
                "acknowledgement, such as \"I'm checking Hermes,\" then wait for the Hermes completion " +
                "follow-up. When the completion follow-up arrives, summarize the Hermes answer.\n" +
                "\n" +
                "Answer directly only for greetings, brief acknowledgements, voice controls, or short " +
                "clarification questions.\n" +
                "\n" +
                "After Hermes responds, summarize the answer naturally and briefly."
    }
```

- [ ] **Step 2: Make the runtime queued response expectation explicit**

In `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentRuntimeTest.kt`, replace this assertion in the test named `Hermes tool call queues job and immediately acknowledges Gemini`:

```kotlin
        assertEquals(listOf(queuedAck("call-queued")), gemini.toolResponses)
```

with:

```kotlin
        assertEquals(
            listOf(
                "call-queued" to
                    "Hermes has not answered yet. Tell the user only that you are checking Hermes. " +
                    "Do not answer the user's question from your own knowledge. " +
                    "Wait for a later Hermes completion message before giving the answer."
            ),
            gemini.toolResponses,
        )
```

This test must not use `queuedAck("call-queued")`, because `queuedAck` reads the production constant and would not fail before implementation.

- [ ] **Step 3: Run the focused tests and confirm RED**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'me.rerere.rikkahub.voiceagent.persistence.VoiceContextBuilderTest' \
  --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentRuntimeTest.Hermes tool call queues job and immediately acknowledges Gemini'
```

Expected result:

- `VoiceContextBuilderTest` fails because the production prompt does not yet include the queued-Hermes protocol.
- `VoiceAgentRuntimeTest.Hermes tool call queues job and immediately acknowledges Gemini` fails because the production queued response is still `Hermes request queued. I will notify the user when the answer is ready.`

Do not commit while tests are red.

## Task 2: Implement The Pending Protocol

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/persistence/VoiceContextBuilder.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceHermesSessionBridgeFactory.kt`

- [ ] **Step 1: Add the queued-Hermes rule to the built-in voice prompt**

In `app/src/main/java/me/rerere/rikkahub/voiceagent/persistence/VoiceContextBuilder.kt`, replace the `VOICE_HERMES_TOOL_POLICY` constant with:

```kotlin
        private const val VOICE_HERMES_TOOL_POLICY =
            "Hermes is your primary knowledge and reasoning backend in RikkaHub voice mode.\n" +
                "You are the voice interface to Hermes, not a replacement for Hermes.\n\n" +
                "When the user asks to ask Hermes, call Hermes, use Hermes, use the Hermes tool, " +
                "or asks about facts, memory, project state, current context, plans, decisions, " +
                "debugging, G-Brain, or anything Hermes may know, you MUST call ask_hermes before answering.\n\n" +
                "Do not answer Hermes-directed or G-Brain-directed requests from your own knowledge, " +
                "even if you think you know the answer. If speech transcription is imperfect but the intent " +
                "appears to involve Hermes, call ask_hermes with the best-effort question.\n\n" +
                "If ask_hermes returns that Hermes has not answered yet or that the request is pending, " +
                "do not answer the user's substantive question yet. Say only a brief pending " +
                "acknowledgement, such as \"I'm checking Hermes,\" then wait for the Hermes completion " +
                "follow-up. When the completion follow-up arrives, summarize the Hermes answer.\n\n" +
                "Answer directly only for greetings, brief acknowledgements, voice controls, " +
                "or short clarification questions.\n\n" +
                "After Hermes responds, summarize the answer naturally and briefly."
```

- [ ] **Step 2: Replace the queued acknowledgement text**

In `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceHermesSessionBridgeFactory.kt`, replace `HERMES_QUEUED_ACKNOWLEDGEMENT` with:

```kotlin
const val HERMES_QUEUED_ACKNOWLEDGEMENT =
    "Hermes has not answered yet. Tell the user only that you are checking Hermes. " +
        "Do not answer the user's question from your own knowledge. " +
        "Wait for a later Hermes completion message before giving the answer."
```

Keep the constant name unchanged so existing runtime tests and call sites continue to use the same API.

- [ ] **Step 3: Run focused tests and confirm GREEN**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'me.rerere.rikkahub.voiceagent.persistence.VoiceContextBuilderTest' \
  --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentRuntimeTest.Hermes tool call queues job and immediately acknowledges Gemini'
```

Expected result:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 4: Run the broader affected runtime tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentRuntimeTest'
```

Expected result:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 5: Commit the prompt and runtime behavior change**

Run:

```bash
git add \
  app/src/main/java/me/rerere/rikkahub/voiceagent/persistence/VoiceContextBuilder.kt \
  app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceHermesSessionBridgeFactory.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/persistence/VoiceContextBuilderTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentRuntimeTest.kt
git commit -m "fix: make queued Hermes response pending-aware"
```

Expected result:

```text
[master <sha>] fix: make queued Hermes response pending-aware
```

## Task 3: Update Runbook Documentation

**Files:**
- Modify: `docs/voice-agent-hermes-gbrain-live-e2e.md`
- Modify: `docs/voice-agent-hermes-queue-e2e.md`

- [ ] **Step 1: Update G-Brain live E2E pass criteria**

In `docs/voice-agent-hermes-gbrain-live-e2e.md`, replace the pass-criteria bullet:

```markdown
- App sends the queued acknowledgement tool response back to Gemini.
```

with:

```markdown
- App sends the directive pending `ask_hermes` tool response back to Gemini.
- Gemini's immediate spoken output, if present before Hermes completion, is only a brief pending acknowledgement such
  as "I'm checking Hermes."
```

- [ ] **Step 2: Update G-Brain manual review criteria**

In `docs/voice-agent-hermes-gbrain-live-e2e.md`, replace this paragraph:

```markdown
In manual review mode, the script reaches a manual review gate instead of declaring an automatic pass. The operator must
read the private answer artifact and decide pass/fail.
```

with:

```markdown
In manual review mode, the script reaches a manual review gate instead of declaring an automatic pass. The operator must
read the private answer artifact and report, then decide pass/fail. The report passes semantic review only when Gemini
does not provide a substantive answer before Hermes completion and the final answer is grounded in the Hermes answer.
```

- [ ] **Step 3: Add queue runbook expectation**

In `docs/voice-agent-hermes-queue-e2e.md`, find the section that describes the run's expected markers or manual review output. Add this paragraph after the list of generated prompts:

```markdown
The immediate `ask_hermes` tool response is a pending-state instruction, not the final answer. During manual review,
Gemini should only acknowledge that it is checking Hermes until the completion follow-up arrives. Substantive answers
before Hermes completion are protocol failures, even when Hermes eventually completes.
```

- [ ] **Step 4: Verify the docs mention the pending protocol**

Run:

```bash
rg -n "directive pending|pending-state instruction|checking Hermes|substantive answer before Hermes completion" \
  docs/voice-agent-hermes-gbrain-live-e2e.md \
  docs/voice-agent-hermes-queue-e2e.md
```

Expected result: at least one match in each runbook.

- [ ] **Step 5: Commit the docs update**

Run:

```bash
git add \
  docs/voice-agent-hermes-gbrain-live-e2e.md \
  docs/voice-agent-hermes-queue-e2e.md
git commit -m "docs: clarify queued Hermes pending review"
```

Expected result:

```text
[master <sha>] docs: clarify queued Hermes pending review
```

## Task 4: Run Local Verification

**Files:**
- Test only.

- [ ] **Step 1: Run focused unit tests**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'me.rerere.rikkahub.voiceagent.persistence.VoiceContextBuilderTest' \
  --tests 'me.rerere.rikkahub.voiceagent.VoiceAgentRuntimeTest'
```

Expected result:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 2: Run shell harness tests**

Run:

```bash
scripts/test-voice-agent-hermes-gbrain-e2e.sh
scripts/test-voice-agent-hermes-queue-e2e.sh
```

Expected result:

```text
voice-agent-hermes-gbrain-e2e tests passed.
Queue E2E shell harness passed.
```

- [ ] **Step 3: Run broader app unit tests**

Run:

```bash
./gradlew :app:testDebugUnitTest
```

Expected result:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 4: Build the debug APK**

Run:

```bash
./gradlew :app:assembleDebug
```

Expected result:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 5: Commit any verification-only test/doc adjustments**

If Task 4 required small test harness updates, commit only those files:

```bash
git status --short
git add scripts/test-voice-agent-hermes-gbrain-e2e.sh scripts/test-voice-agent-hermes-queue-e2e.sh
git commit -m "test: cover queued Hermes pending protocol"
```

Expected result if there are harness updates:

```text
[master <sha>] test: cover queued Hermes pending protocol
```

If there are no harness updates, expected result from `git status --short` is no script changes to commit.

## Task 5: Install And Run Live E2E

**Files:**
- Test only.

- [ ] **Step 1: Verify Tailscale ADB device**

Run:

```bash
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 adb devices -l
```

Expected result includes:

```text
RZCX71NXRPB            device
```

- [ ] **Step 2: Install the debug APK**

Run:

```bash
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 \
adb -s RZCX71NXRPB install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

Expected result:

```text
Success
```

- [ ] **Step 3: Run live manual-review E2E**

Use a configured conversation id from the current phone. If the latest known E2E conversation is still valid, use:

```bash
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 \
VOICE_AGENT_E2E_SERIAL=RZCX71NXRPB \
VOICE_AGENT_E2E_CONVERSATION_ID=f13f0c59-24cd-4ec9-974f-9797e44aa5af \
VOICE_AGENT_E2E_MANUAL_REVIEW=1 \
VOICE_AGENT_E2E_LOG_DIR=build/voice-agent-e2e/pending-protocol-$(date -u +%Y%m%dT%H%M%SZ) \
scripts/voice-agent-hermes-gbrain-e2e.sh
```

Expected result:

```text
PIPELINE: passed
CLEANUP: passed
Voice Agent Hermes/Gbrain live E2E reached manual review gate.
```

- [ ] **Step 4: Review live E2E report**

Run:

```bash
latest_dir="$(ls -td build/voice-agent-e2e/pending-protocol-* | head -n 1)"
sed -n '1,220p' "$latest_dir/report.txt"
```

Expected report evidence:

- `Text used to generate voice:` is present.
- `Gemini understood from voice:` is present.
- `Hermes call:` is present.
- `Hermes elapsed time:` is present.
- `Hermes answer:` is present.
- `Gemini response to user:` is present.
- The Gemini response does not contain a substantive answer before Hermes completion. In manual review this is a human semantic check, not an automated code filter.

- [ ] **Step 5: Capture the latest trace id for debugging**

Run:

```bash
ADB_SERVER_SOCKET=tcp:100.69.79.32:5037 \
adb -s RZCX71NXRPB shell run-as me.rerere.rikkahub.debug \
cat /data/data/me.rerere.rikkahub.debug/no_backup/voice-e2e/latest-trace-id.txt
```

Expected result is a trace id shaped like:

```text
VA123456-abcdef1234567890
```

## Task 6: Final Status And Parent Repo Handling

**Files:**
- Repository status only.

- [ ] **Step 1: Check submodule status**

Run:

```bash
git status --short
git log --oneline -5
```

Expected result:

- No unstaged implementation changes remain in `external/rikkahub`.
- Recent commits include:
  - `docs: design voice Hermes pending protocol`
  - `fix: make queued Hermes response pending-aware`
  - `docs: clarify queued Hermes pending review`
  - any optional harness-test commit from Task 4.

- [ ] **Step 2: Check parent repo status**

Run from `/home/muly/agora2`:

```bash
git status --short
```

Expected result includes:

```text
 M external/rikkahub
```

because the submodule pointer changed.

- [ ] **Step 3: Do not commit the parent pointer unless explicitly requested**

Report the submodule commit hash and parent status to the user. The project `AGENTS.md` requires submodule changes to be
committed and pushed before a parent commit that updates the submodule pointer. Do not make the parent commit as part of
this implementation plan unless the user explicitly asks to finish or merge the development branch.

## Self-Review

Spec coverage:

- System prompt queued-Hermes protocol: Task 1 and Task 2.
- Directive pending queued response: Task 1 and Task 2.
- No app-side semantic filtering or blocking: enforced by file structure and non-goals; no task adds filtering.
- Existing async queue and completion follow-up remain: Task 2 modifies only prompt text and queued response text.
- Docs and live E2E manual review: Task 3 and Task 5.
- Trace artifacts remain available: Task 5 reads latest trace id and does not change artifact storage.

Red-flag scan:

- No unfinished marker words or unspecified implementation steps are present.
- Every code-changing step includes exact replacement text.
- Every verification step includes exact commands and expected output.

Type consistency:

- `HERMES_QUEUED_ACKNOWLEDGEMENT`, `VOICE_HERMES_TOOL_POLICY`, `queuedAck`, and `EXPECTED_VOICE_SYSTEM_PREFIX` match existing code names.
- The plan keeps the Gemini Live tool response JSON shape unchanged by only changing the `answer` string.
