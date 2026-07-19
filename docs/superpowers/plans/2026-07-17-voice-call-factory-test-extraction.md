# Voice Call Factory Test Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move all concrete `DefaultVoiceAgentCallFactory` coverage out of the 5,776-line runtime test into a focused factory test with locally owned fixtures.

**Architecture:** Create `VoiceAgentCallFactoryTest` as the sole test owner for concrete factory assembly, propagated metadata, route-owner transfer, and creation-failure retirement. Reuse package-visible shared voice fakes, but move factory-exclusive helpers out of `VoiceAgentRuntimeTest` so the new test does not depend on private monolith fixtures.

**Tech Stack:** Kotlin, Android JVM test stubs, kotlinx.coroutines-test/runBlocking, kotlinx.serialization JSON, JUnit 4, Gradle.

## Global Constraints

- This is a test-ownership refactor; production behavior and production files must not change.
- Preserve every assertion, cleanup `finally` block, exact failure identity, and suppressed-failure order from the moved tests.
- Do not introduce a shared generic fixture file for two tests; factory-exclusive helpers belong in `VoiceAgentCallFactoryTest.kt`.
- Shared package-visible fakes in `VoiceAgentFakes.kt` remain unchanged.
- Add no dependencies and leave no duplicate factory coverage in `VoiceAgentRuntimeTest`.
- Modify only `/home/muly/code/rikkahub`.
- This plan implements review finding 6 and is independently executable before or after the other plans in this design set.

## Binding Plan Contract

### Exact file ownership

Create:

- `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallFactoryTest.kt` — owns all `DefaultVoiceAgentCallFactory` construction and behavior tests.

Modify:

- `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentRuntimeTest.kt` — remove the factory tests, factory-exclusive helpers, and now-unused imports only.

Do not modify `VoiceAgentCallFactory.kt`, `VoiceAgentFakes.kt`, or any other source file.

### Exact tests to move

Move these two tests without weakening or renaming their behavior:

1. `default call factory started session writes propagated metadata through real artifact writer`
2. `default call factory keeps creation failure primary and suppresses lease retirement failure`

The first test remains responsible for proving:

- one `HermesVoiceApi` instance is shared with session and tool adapters;
- the lease's `VoiceAudioRouteOwner.Telecom` reaches the audio factory;
- starting the session does not retire/disconnect the route;
- trace/session/conversation/package/build/model/owner/time/Sentry metadata reaches the real artifact writer;
- `closeNow` retires the exact Telecom attempt once.

The second remains responsible for proving:

- a factory construction error is primary;
- lease retirement is attempted;
- retirement failure is suppressed on the creation error in exact identity/order.

### Exact helper ownership

Move from the runtime file into the new factory test file:

```kotlin
private class PropagatingVoiceObservability : VoiceObservability

private fun factoryLaunchConfig(
    voiceModelId: String = "gemini-flash",
): VoiceAgentLaunchConfig
```

Rename the moved `fakeLaunchConfig` to `factoryLaunchConfig` because its scope is now explicit. Add private `JsonObject.string` and `JsonObject.boolean` member extensions inside `VoiceAgentCallFactoryTest`; retain the runtime test's existing extensions because unrelated runtime tests still use them.

### Import contract

After extraction, `VoiceAgentRuntimeTest.kt` must no longer import or reference factory-exclusive symbols:

- `android.content.ContextWrapper`
- `BuildConfig`
- `VoiceAudioRouteOwner`
- `NoOpVoiceObservability`
- `VoiceObservability`
- `VoiceSpan`
- `HermesVoiceCredentials`
- `DefaultVoiceAgentCallFactory`
- `PropagatingVoiceObservability`
- `fakeLaunchConfig`

`Files`, JSON helpers, `VoiceTraceContext`, and other runtime imports remain when unrelated runtime tests still consume them. Let Kotlin/IDE import organization remove only imports proven unused after the move.

## Illustrative Implementation Guidance

Perform a semantic move: copy the two tests and exclusive helpers first, make the focused class pass, then remove the originals. Do not reformat unrelated regions of the monolith; a narrow diff makes accidental assertion loss visible.

---

### Task 1: Establish Focused Factory Test Ownership

**Files:**
- Create: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallFactoryTest.kt`
- Read/reuse: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentFakes.kt`
- Read/verify: `app/src/main/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallFactory.kt`

**Interfaces:**
- Consumes: existing internal `DefaultVoiceAgentCallFactory` constructor seams.
- Reuses: `FakeGeminiLiveVoiceClient`, `FakeVoiceSessionApi`, `FakeVoiceToolApi`, `FakeVoiceAudioEngine`, `FakeVoiceAgentContextProvider`, and `BlockedConnect` from existing package-visible test support.

**Invariants:**
- The new test owns its temporary directories and coroutine scopes with the existing `try/finally` cleanup.
- No production visibility is widened to enable the move.
- No test-only duplicate of a production type is introduced.

**Acceptance:**
- The new class contains both exact test names and both pass in isolation.
- The two tests retain their complete assertion sets.
- Factory-exclusive helpers are private to the new file.

- [ ] **Step 1: Record the baseline focused behavior**

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*VoiceAgentRuntimeTest.default call factory*'
```

Expected: both existing factory tests pass before the move.

- [ ] **Step 2: Create the focused class by semantic copy**

Create `VoiceAgentCallFactoryTest` in the same package. Copy the two tests, `PropagatingVoiceObservability`, and launch-config fixture. Rename calls to `factoryLaunchConfig`. Add only the imports and two JSON member extensions needed by this file.

- [ ] **Step 3: Run only the new class**

```bash
./gradlew :app:testDebugUnitTest --tests '*VoiceAgentCallFactoryTest'
```

Expected: `BUILD SUCCESSFUL` while the original tests still exist temporarily.

- [ ] **Step 4: Compare the copied assertions before deleting originals**

```bash
rg -n "assert|finally|closeNow|retire|disconnect|suppressed|sessionJson|audioRouteOwner|sessionMobileApi|toolMobileApi" \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallFactoryTest.kt
```

Expected: both lifecycle-cleanup blocks and every behavior family in the binding contract are visibly represented.

### Task 2: Remove Factory Ownership from the Runtime Monolith

**Files:**
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentRuntimeTest.kt`
- Verify: `app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallFactoryTest.kt`

**Interfaces:**
- Deletes: runtime test's two concrete-factory methods and exclusive helper definitions.
- Preserves: all unrelated runtime tests and shared runtime member helpers.

**Invariants:**
- Only the exact factory test blocks and exclusive top-level helpers are removed.
- Runtime JSON extensions remain because other runtime tests consume them.
- Runtime file line count decreases; no unrelated test is moved in this plan.

**Acceptance:**
- `VoiceAgentRuntimeTest.kt` contains no concrete factory reference or factory-exclusive import.
- `VoiceAgentCallFactoryTest.kt` is the only file containing the two exact test names.
- Runtime and factory test classes both pass.

- [ ] **Step 1: Delete the original test and helper blocks**

Remove the two exact test methods, `PropagatingVoiceObservability`, and `fakeLaunchConfig` from `VoiceAgentRuntimeTest.kt`. Remove only imports that become unused because of those deletions.

- [ ] **Step 2: Run both classes**

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*VoiceAgentCallFactoryTest' \
  --tests '*VoiceAgentRuntimeTest'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run exact ownership scans**

```bash
if rg -n "DefaultVoiceAgentCallFactory|PropagatingVoiceObservability|fakeLaunchConfig|ContextWrapper|VoiceAudioRouteOwner|NoOpVoiceObservability|HermesVoiceCredentials" \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentRuntimeTest.kt; then
  exit 1
fi

test "$(rg -l 'default call factory started session writes propagated metadata through real artifact writer' app/src/test | wc -l)" -eq 1
test "$(rg -l 'default call factory keeps creation failure primary and suppresses lease retirement failure' app/src/test | wc -l)" -eq 1
```

Expected: every command exits `0`; the runtime scan prints nothing and each exact test name has one owner.

- [ ] **Step 4: Confirm the extraction commit removes more runtime lines than it adds**

```bash
git diff --numstat -- \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentRuntimeTest.kt | \
  awk 'NF == 3 { found = 1; if ($2 <= $1) exit 1 } END { if (!found) exit 1 }'
```

Expected before the Task 2 commit: exit `0`; the runtime file has more deletions than additions.

- [ ] **Step 5: Commit the extraction**

```bash
git add app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallFactoryTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentRuntimeTest.kt
git commit -m "test(voice): extract call factory coverage"
```

### Task 3: Verify the Test-Only Refactor

**Files:**
- Verify only: the two files owned by Tasks 1-2

**Interfaces:**
- Verifies: focused ownership, behavior parity, and absence of production changes.

**Invariants:**
- Test extraction introduces no production diff.
- Both tests still exercise the real factory and artifact writer seams.

**Acceptance:**
- Focused tests, full app JVM tests, lint, ownership scans, and diff-scope checks pass.

- [ ] **Step 1: Run focused tests and ownership scans**

Run Task 2 Steps 2-4.

Expected: all commands succeed.

- [ ] **Step 2: Run the app JVM suite**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run Android lint**

```bash
./gradlew :app:lintDebug
```

Expected: `BUILD SUCCESSFUL`. If an environment-only prerequisite prevents lint, record the exact command and error; do not weaken JVM verification.

- [ ] **Step 4: Verify diff scope**

```bash
git diff --name-only HEAD^ -- \
  app/src/main \
  app/src/test/java/me/rerere/rikkahub/voiceagent
```

Expected after the Task 2 commit: only `VoiceAgentCallFactoryTest.kt` and `VoiceAgentRuntimeTest.kt`. If commits were combined or rebased, compare against the pre-plan commit instead of assuming `HEAD^`.

- [ ] **Step 5: Commit verification-only fixes if required**

If verification required scoped fixes, stage only the two owned test files and commit:

```bash
git add app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentCallFactoryTest.kt \
  app/src/test/java/me/rerere/rikkahub/voiceagent/VoiceAgentRuntimeTest.kt
git commit -m "test(voice): verify factory test ownership"
```

Expected: skip this commit when verification requires no changes.
