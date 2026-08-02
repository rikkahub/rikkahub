# Agent Detail Identity Content Key Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Preserve coherent Agent detail identity frames without animating live-duration clock ticks.

**Architecture:** Represent the rendered detail identity as one immutable presentation value. Pass that value to
`AnimatedContent`, and derive `contentKey` from run ID and status so only semantic phase changes start transitions.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Compose UI instrumentation tests, Gradle.

---

### Task 1: Capture both identity update paths

**Files:**

- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt`

**Step 1: Strengthen the terminal-transition test**

Render a live detail header with a deterministic clock. Change status and final duration together, advance one frame,
and assert that the complete old and new localized identity lines coexist. After settling, assert only the new line.

**Step 2: Add the duration-only test**

Keep status unchanged, advance the supplied clock, and assert that the old identity line disappears immediately while
the new identity line is displayed. This prevents duration from becoming an animation key.

**Step 3: Compile the tests**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`

Expected: compilation succeeds. On a device, the strengthened terminal test fails before the implementation because
the outgoing frame reads the new duration.

### Task 2: Implement complete frames with a stable content key

**Files:**

- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`

**Step 1: Add the immutable identity frame**

Create a small private value containing short run ID, status, and duration, plus a run ID/status animation key.

**Step 2: Target the complete frame**

Build the identity after computing live duration. Pass it as `AnimatedContent.targetState` and use the animation key as
`contentKey`.

**Step 3: Render frame-owned values**

Build the localized identity line only from the content lambda argument. Preserve the current native vertical motion.

### Task 3: Verify the Agent surface

**Files:**

- Test: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/AgentRunPresentationTest.kt`

**Step 1: Compile production and instrumentation sources**

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin`

Expected: both tasks succeed.

**Step 2: Run focused JVM regression tests**

Run the existing nine focused Agent and tool JVM test classes. Expected: all pass.

**Step 3: Run static checks**

Run `git diff --check` and verify the changed Kotlin and Markdown lines are at most 120 characters.

**Step 4: Check instrumentation availability**

Run `adb devices`. Execute the focused instrumentation test when a device is connected; otherwise report that the
instrumentation source compiled but did not execute.
