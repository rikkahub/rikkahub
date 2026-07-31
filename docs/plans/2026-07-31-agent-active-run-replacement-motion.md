# Agent Active Run Replacement Motion Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Give direct active Agent run replacements distinct native card identities and safe interaction ownership.

**Architecture:** Keep `AnimatedVisibility` as the screen-level enter/exit owner. Add an inner `AnimatedContent` keyed
by `runId` so same-run telemetry updates stay local while different runs crossfade and slide as separate compositions.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Compose UI instrumentation tests, Gradle.

---

### Task 1: Capture direct run replacement behavior

**Files:**
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt`

**Step 1: Write the paused-clock test**

Render `AgentRunActiveCardHost` with run A and settle its entry animation. Replace both `activeRun` and `latestRun` with
run B, then advance one frame.

**Step 2: Verify frame and interaction ownership**

Assert that A and B model labels coexist during the transition, A is disabled, and clicking B reports B's run ID.
Advance beyond the transition and assert that A no longer exists.

**Step 3: Compile the new test**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`

Expected: compilation succeeds. On a connected device, the old implementation fails because A is removed immediately.

### Task 2: Implement keyed native replacement motion

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`

**Step 1: Add card enablement**

Add an `enabled` parameter with a `true` default to `AgentRunActiveCard`, and pass it to the clickable Material 3 card.

**Step 2: Add identity-owned content motion**

Inside `AgentRunActiveCardHost`'s existing visibility block, render `retainedRun` through `AnimatedContent` with
`contentKey = { it?.runId }` and a small vertical fade/slide Material transition.

**Step 3: Disable outgoing content**

Set card enablement by comparing the animated frame's run ID with the current retained run ID. Keep opening and stopping
callbacks bound to the animated frame's run ID.

### Task 3: Verify Agent behavior

**Files:**
- Test: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/AgentRunPresentationTest.kt`

**Step 1: Run the focused Agent and tool JVM suite**

Run the existing nine focused JVM test classes and expect all tests to pass.

**Step 2: Compile production and Android test sources**

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin`

Expected: both tasks succeed.

**Step 3: Run static checks**

Run `git diff --check` and check changed Kotlin and Markdown lines against the 120-character limit.

**Step 4: Check device availability**

Run `adb devices`. Execute the focused instrumentation test if a device is connected; otherwise report that it compiled
but was not executed.
