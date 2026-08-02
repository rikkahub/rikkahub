# Agent Progress Mode Retention Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Keep an Agent run's outgoing determinate progress ring at its last meaningful value while it crossfades to
indeterminate mode.

**Architecture:** Keep the mode switch in `AnimatedContent`, but give the ring local frame-retention state for the last
committed non-null progress. Verify the transition through Compose progress semantics with the animation clock paused.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Compose UI instrumentation tests, Gradle.

---

### Task 1: Capture the outgoing-frame regression

**Files:**
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`

**Step 1: Expose the ring to its package test**

Change `AgentRunProgressRing` from `private` to `internal` without changing behavior.

**Step 2: Write the failing test**

Render the ring at `0.75f` with `mainClock.autoAdvance = false`, let its progress animation settle, switch the input to
`null`, and advance one frame. Assert that the incoming indeterminate semantics exists and that the outgoing determinate
semantics remains above `0.70f`.

**Step 3: Run the test compilation**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`

Expected: compilation succeeds; on a connected device the new assertion fails against the old implementation because
the outgoing value begins targeting zero.

### Task 2: Retain the last determinate target

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`

**Step 1: Add local retained state**

Initialize a remembered float from the first non-null progress or zero. After successful composition, update it only
when current progress is non-null.

**Step 2: Feed the retained target to the native animation**

Use current progress when present and the retained value otherwise as `animateFloatAsState`'s target. Keep
`AnimatedContent` and its Material fade transition unchanged.

**Step 3: Verify the focused behavior**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`

Expected: compilation succeeds. If an Android device is connected, run the focused instrumentation test and expect it
to pass.

### Task 3: Regression verification

**Files:**
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/AgentRunProgressTest.kt`
- Test: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt`

**Step 1: Run Agent and tool JVM tests**

Run the existing focused Agent/tool test classes with Gradle and expect all tests to pass.

**Step 2: Compile production and instrumentation sources**

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin`

Expected: both Kotlin compilation tasks succeed.

**Step 3: Run static checks**

Run `git diff --check` and check modified Kotlin/Markdown lines against the repository's line-length conventions.

**Step 4: Check instrumentation availability**

Run `adb devices`. If no device is listed, report that instrumentation tests were compiled but not executed.
