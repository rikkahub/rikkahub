# Agent Visual Progress Frame Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Keep Agent progress-ring snapshots coherent through terminal and stopping transitions.

**Architecture:** Add a shared immutable frame containing visual state, progress visibility, and step progress. Use the
complete frame as `AnimatedContent.targetState`, with visual state and visibility as `contentKey`, on both affected UI
surfaces.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Compose UI instrumentation tests, Gradle.

---

### Task 1: Capture stale outer-progress reads

**Files:**

- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt`

**Step 1: Strengthen the run-entry transition test**

Pause the Compose clock, settle the initial 25% progress ring, and change the run to 4/4 succeeded. On the first frame,
assert that 25% remains and 100% is absent. After settling, assert that the ring is removed.

**Step 2: Strengthen the detail-header transition test**

Change completed steps to 4 together with the terminal state in the existing identity transition test. Assert the same
25%-retained and 100%-absent behavior during the transition.

**Step 3: Compile the tests**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`

Expected: compilation succeeds. On a connected device, the old implementation exposes the new progress value through
the outgoing subtree.

### Task 2: Implement the shared visual progress frame

**Files:**

- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`

**Step 1: Add the frame value**

Create a private value containing `visualState`, `showsProgress`, and nullable progress.

**Step 2: Update the run entry**

Target the complete frame, key transitions by state and visibility, and render the ring and icon from the frame.

**Step 3: Update the detail header**

Build visibility from live-state eligibility and `isStopping`. Apply the same frame and content-key behavior.

### Task 3: Verify the Agent motion surface

**Files:**

- Test: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/AgentRunProgressTest.kt`

**Step 1: Compile production and instrumentation sources**

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin` and expect success.

**Step 2: Run focused JVM regressions**

Run the existing nine focused Agent and tool JVM test classes and expect all to pass.

**Step 3: Run static checks**

Run `git diff --check` and verify changed Kotlin and Markdown lines are at most 120 characters.

**Step 4: Check instrumentation availability**

Run `adb devices`. Execute the focused instrumentation tests if a device is connected; otherwise report that they
compiled but did not execute.
