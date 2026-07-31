# Agent Linear Progress Phase Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Give the active Agent card's bottom progress indicator coherent phase and value ownership.

**Architecture:** Replace nested visibility and mode booleans with one immutable hidden/determinate/indeterminate frame.
Use phase as `AnimatedContent.contentKey`, and animate numeric progress only inside the determinate subtree.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Compose UI instrumentation tests, Gradle.

---

### Task 1: Capture exit snapshots

**Files:**

- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt`

**Step 1: Test terminal exit with a progress jump**

Render a settled 1/4 live card, then change it to 4/4 succeeded. During the exit, assert that 25% remains and 100% is
absent. After settling, assert that no determinate progress remains.

**Step 2: Test stopping exit**

Keep the run live but toggle `isStopping`. Assert that the 25% progress bar remains during the first exit frame and is
removed after the transition.

**Step 3: Compile instrumentation sources**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`

Expected: compilation succeeds. On a device, the terminal test fails before implementation because the outgoing bar
continues reading the new outer progress target.

### Task 2: Implement the three-phase progress frame

**Files:**

- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`

**Step 1: Define phase and frame values**

Add hidden, determinate, and indeterminate phases plus a frame containing phase and nullable progress.

**Step 2: Replace the nested transitions**

Build one frame from run state, stop state, and step progress. Use the phase as `contentKey` in one `AnimatedContent`.

**Step 3: Keep same-phase numeric motion**

Inside the determinate branch, retain and animate the frame's progress with the expressive spatial motion spec.

### Task 3: Verify the Agent motion surface

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

Run `adb devices`. Execute focused instrumentation tests if a device is connected; otherwise report that they compiled
but did not execute.
