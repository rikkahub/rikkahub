# Agent Shared Progress Retention Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Give Agent circular and linear progress transitions one shared last-determinate-target rule.

**Architecture:** Extract a remembered Compose target helper in `AgentRunCenter.kt`. Keep the existing Material 3
indicators, `AnimatedContent` mode transitions, and native progress animation specs unchanged while both surfaces
the retained target.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Compose UI instrumentation tests, Gradle.

---

### Task 1: Capture the active-card regression

**Files:**
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt`

**Step 1: Write the paused-clock test**

Render an active card with three of four steps completed. Settle the animation, change only `maxSteps` to `null`, and
advance one frame. Match progress semantics to assert that both the incoming indeterminate bar and an outgoing
determinate bar above 70% are present.

**Step 2: Assert transition cleanup**

Advance the clock beyond the transition and assert that no determinate progress semantics remain.

**Step 3: Compile the test**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`

Expected: compilation succeeds. On a connected device, the old implementation fails because its outgoing target moves
toward zero.

### Task 2: Share retained progress state

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`

**Step 1: Extract the composable helper**

Move clamping, remembered fallback state, and committed non-null updates into
`rememberAgentRunProgressTarget(progress: Float?): Float`.

**Step 2: Reuse the helper in the circular ring**

Replace the ring's inline retained state with the shared helper and keep its native animation unchanged.

**Step 3: Reuse the helper in the active card**

Feed the returned target into the active card's `animateFloatAsState` instead of using `stepProgress ?: 0f`.

### Task 3: Verify the Agent motion surface

**Files:**
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/AgentRunProgressTest.kt`
- Test: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt`

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
