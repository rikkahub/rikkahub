# Agent Route Status Frame Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Give the active Agent card's route and status line complete animation-frame ownership.

**Architecture:** Create a small presentation identity containing the already localized route label and status text. Use
that value as the existing `AnimatedContent` target so either field change receives one coherent native transition.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Compose UI instrumentation tests, Gradle.

---

### Task 1: Capture a route-only update

**Files:**
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt`

**Step 1: Write the paused-clock test**

Render a working `AgentRunActiveCard` with auto execution routing and settle its initial animations. Replace only
routing with `UNAVAILABLE`, keeping the status text unchanged, then advance one frame.

**Step 2: Verify complete frames**

Assert that the old auto route/status line and new unavailable route/status line coexist during the transition. Advance
beyond the transition and assert that only the unavailable line remains.

**Step 3: Compile the test**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`

Expected: compilation succeeds. On a connected device, the old implementation fails because the auto line is removed
without a transition.

### Task 2: Implement route/status ownership

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`

**Step 1: Add the status identity**

Create `AgentRunStatusIdentity(routingLabel, status)` near the active-card presentation helpers.

**Step 2: Target the complete identity**

Build the identity after localizing the routing label and pass it to the current status `AnimatedContent`.

**Step 3: Render only frame-owned fields**

Build the visible line from the content lambda's identity argument. Keep the existing vertical fade/slide transition.

### Task 3: Verify the Agent motion surface

**Files:**
- Test: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/AgentRunPresentationTest.kt`

**Step 1: Run the focused Agent and tool JVM suite**

Run the existing nine focused JVM test classes and expect all tests to pass.

**Step 2: Compile production and Android test sources**

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin`

Expected: both tasks succeed.

**Step 3: Run static checks**

Run `git diff --check` and verify changed Kotlin and Markdown lines are at most 120 characters.

**Step 4: Check device availability**

Run `adb devices`. Execute the focused instrumentation test if a device is connected; otherwise report that it compiled
but was not executed.
