# Agent Active Stop Exit Motion Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Smoothly retire the active Agent card's stop control without leaving a stale terminal-state action.

**Architecture:** Replace the nullable stop-button branch with `AnimatedVisibility`. Keep the existing stop-to-spinner
`AnimatedContent`, but bind visibility and enabled state to the current action so visual exit can outlive interaction.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Compose UI instrumentation tests, Gradle.

---

### Task 1: Capture terminal stop-control ownership

**Files:**
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt`

**Step 1: Write the paused-clock test**

Render `AgentRunActiveCard` in a working state with a stop callback and settle the initial composition. Change the run
to succeeded and remove the callback, then advance one frame.

**Step 2: Verify visual and interaction state**

Assert that the succeeded status and outgoing stop icon coexist, that the stop icon is disabled, and that the icon is
removed after the exit transition completes.

**Step 3: Compile the test**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`

Expected: compilation succeeds. On a connected device, the old implementation fails because the stop icon is removed
immediately.

### Task 2: Add native stop-control exit motion

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`

**Step 1: Replace the nullable branch**

Wrap the icon button in `AnimatedVisibility(visible = stopAction != null || isStopping)`.

**Step 2: Match detail-header motion**

Use `expandHorizontally(expandFrom = Alignment.End) + fadeIn()` for entry and
`shrinkHorizontally(shrinkTowards = Alignment.End) + fadeOut()` for exit.

**Step 3: Revoke stale interaction**

Enable the button only while `stopAction != null && !isStopping`; invoke the nullable current callback after haptics.

### Task 3: Verify the Agent motion surface

**Files:**
- Test: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/AgentRunProgressTest.kt`

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
