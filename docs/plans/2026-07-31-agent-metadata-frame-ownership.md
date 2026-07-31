# Agent Metadata Frame Ownership Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Keep active-card model, step, and duration text coherent without animating duration-only refreshes.

**Architecture:** Add duration to the immutable metadata frame and derive a stable content key from model and step
values. Keep the existing directional `AnimatedContent` transition for step changes.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Compose UI instrumentation tests, Gradle.

---

### Task 1: Capture metadata frame behavior

**Files:**

- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt`

**Step 1: Test a combined step and duration change**

Render a terminal active card with a deterministic 1/4 and one-second metadata line. Change it to 2/4 and nine seconds,
advance one frame, and assert that both complete lines coexist. After settling, assert only the new line remains.

**Step 2: Test a duration-only change**

Keep model and step values stable while changing terminal duration. Advance one frame and assert that the old line is
gone and the new line exists, proving that duration is not an animation key.

**Step 3: Compile instrumentation sources**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`

Expected: compilation succeeds. On a connected device, the combined-change test fails before implementation because
the outgoing step frame reads the new outer duration.

### Task 2: Implement complete metadata frames

**Files:**

- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`

**Step 1: Add duration and a stable key**

Add duration to the metadata frame and define a key containing model, completed steps, and maximum steps.

**Step 2: Apply `contentKey`**

Pass the complete frame to `AnimatedContent` and use the stable key for transition identity.

**Step 3: Render only frame-owned values**

Build the metadata line from the content lambda argument, including its duration. Preserve current direction logic.

### Task 3: Verify the Agent surface

**Files:**

- Test: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/AgentRunProgressTest.kt`

**Step 1: Compile production and instrumentation sources**

Run `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin` and expect success.

**Step 2: Run focused JVM regressions**

Run the existing nine focused Agent and tool JVM test classes and expect all to pass.

**Step 3: Run static checks**

Run `git diff --check` and verify changed Kotlin and Markdown lines are at most 120 characters.

**Step 4: Check instrumentation availability**

Run `adb devices`. Execute the focused instrumentation tests if a device is connected; otherwise report that they
compiled but did not execute.
