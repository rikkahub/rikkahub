# Direct Audio Retirement Thread Test Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the direct-audio retirement concurrency test wait for its worker thread to terminate before asserting that the thread is no longer alive.

**Architecture:** This is a test-only synchronization repair. The existing completion latch continues to prove that `lease.retire()` returned; a bounded thread join then establishes actual JVM thread termination before `isAlive` is asserted.

**Tech Stack:** Kotlin, JUnit 4, Java `Thread`, `CountDownLatch`, Gradle Android JVM unit tests

## Global Constraints

- Change only `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectAudioRouteControllerTest.kt`.
- Do not modify production behavior, neighboring tests, dependencies, or test fixtures.
- Keep the existing `finally` block unchanged so failed assertions still release recorder configuration and join both workers.
- Use a bounded `retirement.join(1_000)`; do not use an unbounded join or remove the termination assertion.

## Binding Plan Contract

- File ownership: only the affected test method at lines 374-415 may change.
- Interfaces and types: no interface, signature, type, dependency, or production-code changes are permitted.
- Ordering invariant: the test must successfully await `retirementCompleted`, join `retirement` for at most 1,000 milliseconds, assert `retirement.isAlive` is false, and then assert `fixture.device.retireCalls` is zero before releasing `releaseDevice`.
- Cleanup invariant: `releaseDevice.countDown()`, `configuration.join(5_000)`, and `retirement.join(5_000)` remain in the existing `finally` block and in their current order.
- Failure behavior: if the retirement worker does not terminate within the bounded join, `assertFalse(retirement.isAlive)` fails and the existing `finally` block still unblocks and joins both workers.
- Security behavior: this test-only change performs no network access, secret handling, permission changes, or external-state mutation.
- Focused acceptance command: `./gradlew :app:testDebugUnitTest --tests '*AndroidDirectAudioRouteControllerTest'` exits 0 with no failed tests.
- Full-suite acceptance command: `./gradlew :app:testDebugUnitTest` exits 0 with no failed tests.

## Illustrative Implementation Guidance

No production implementation changes are allowed. The complete intended test assertion sequence is specified in Task 1.

## File Structure

- `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectAudioRouteControllerTest.kt`: owns the direct-audio controller unit tests; add the bounded join to the existing retirement/configuration race test.

---

### Task 1: Establish Retirement Worker Termination Before Assertion

**Files:**
- Modify: `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectAudioRouteControllerTest.kt:396-403`
- Test: `app/src/test/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectAudioRouteControllerTest.kt`

**Interfaces:**
- Consumes: Kotlin `Thread.join(millis: Long)` on the existing `retirement: Thread` worker.
- Produces: no reusable interface; the test now establishes worker termination before reading `retirement.isAlive`.

**Invariants:**
- The completion latch remains the first wait and retains its one-second timeout.
- `retirement.join(1_000)` occurs immediately after the successful latch assertion and immediately before `assertFalse(retirement.isAlive)`.
- The pre-release `fixture.device.retireCalls == 0` assertion and all post-release capability retirement assertions remain unchanged.
- The `finally` cleanup remains unchanged and always runs after the assertion sequence begins.

**Acceptance:**
- The affected test no longer assumes latch completion implies JVM thread termination.
- The focused test class passes with zero failures.
- The full app JVM unit suite passes with zero failures.
- The source diff contains exactly one added executable line in the affected test method.

- [ ] **Step 1: Reproduce the existing invalid assertion**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests '*AndroidDirectAudioRouteControllerTest' --rerun-tasks
```

Expected before the fix: FAIL in `retire does not wait for recorder configuration and late device lease retires locally` at the immediate `assertFalse(retirement.isAlive)` check. This existing failing test is the RED evidence; do not add another test.

- [ ] **Step 2: Add the bounded retirement-thread join**

Make the assertion sequence exactly:

```kotlin
assertTrue(retirementCompleted.await(1, TimeUnit.SECONDS))
retirement.join(1_000)
assertFalse(retirement.isAlive)
assertEquals(0, fixture.device.retireCalls)
```

Do not change the worker body, latch timing, capability assertions, or `finally` block.

- [ ] **Step 3: Run the focused test class**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests '*AndroidDirectAudioRouteControllerTest'
```

Expected: BUILD SUCCESSFUL; `AndroidDirectAudioRouteControllerTest` has zero failures.

- [ ] **Step 4: Run the full app JVM unit suite**

Run:

```bash
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL with zero failed tests.

- [ ] **Step 5: Verify the final diff**

Run:

```bash
git diff --check
git diff -- app/src/test/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectAudioRouteControllerTest.kt
```

Expected: `git diff --check` emits no output, and the file diff adds only `retirement.join(1_000)` between the latch assertion and `assertFalse(retirement.isAlive)`.

- [ ] **Step 6: Commit the repair**

```bash
git add app/src/test/java/me/rerere/rikkahub/voiceagent/audio/AndroidDirectAudioRouteControllerTest.kt
git commit -m "test(voice): join direct audio retirement worker"
```
