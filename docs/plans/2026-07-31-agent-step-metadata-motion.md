# Agent Step Metadata Motion Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Animate active Agent step metadata in the same direction as progress while leaving duration ticks motionless.

**Architecture:** Add a stable metadata identity for model and step values, then render the existing single-line
metadata through `AnimatedContent`. Use the count-direction helper and Material spatial motion for step changes.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Compose UI instrumentation tests, Gradle.

---

### Task 1: Capture the step metadata transition

**Files:**
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt`

**Step 1: Write the paused-clock test**

Render `AgentRunActiveCard` with one of four steps completed and settle its initial animations. Change the presentation
to two completed steps and advance one frame.

**Step 2: Verify frame ownership**

Assert that both the old `1/4 steps` and new `2/4 steps` labels exist during the transition. Advance beyond the Material
transition and assert that only `2/4 steps` remains.

**Step 3: Compile the test**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`

Expected: compilation succeeds. On a connected device, the old implementation fails because `1/4 steps` is removed
immediately.

### Task 2: Implement stable metadata motion

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`

**Step 1: Add the metadata identity**

Create `AgentRunMetadataIdentity(model, completedSteps, maxSteps)` near the active-card component.

**Step 2: Replace the plain metadata text**

Use `AnimatedContent(targetState = metadataIdentity)` and build the existing metadata string from the frame argument
plus the current elapsed-duration label.

**Step 3: Add directional motion**

Use `agentRunCountMotion` to select upward motion for increases, downward motion for decreases, and a crossfade when
completed counts are steady.

### Task 3: Verify the Agent motion surface

**Files:**
- Test: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/AgentRunTimelineFollowTest.kt`

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
