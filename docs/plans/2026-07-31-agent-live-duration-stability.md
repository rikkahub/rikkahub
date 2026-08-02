# Agent Live Duration Stability Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Stop live Agent duration labels from crossfading every second while keeping meaningful status animations.

**Architecture:** Replace clock-driven animations with direct text updates and apply tabular numeral font features to
affected labels. Keep the existing shared clock and terminal-duration model unchanged.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Compose UI Test, JUnit 4.

---

### Task 1: Specify non-overlapping clock updates

**Files:**
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt`

**Step 1: Add the failing test**

Render a live child Run and timeline item from one mutable timestamp. Pause the Compose animation clock, advance the
timestamp, and require new duration labels to exist while old labels no longer exist.

**Step 2: Compile Android tests**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`

Expected: PASS compilation. On a connected device, the test fails before implementation because paused crossfades keep
the old duration nodes composed.

### Task 2: Stabilize live clock typography

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`

**Step 1: Remove clock-only crossfades**

Render active metadata, detail identity, child duration, and timeline duration directly. Preserve status, icon,
activity, navigation, progress, expansion, and list-item transitions.

**Step 2: Enable tabular numerals**

Copy the existing Material text styles with `fontFeatureSettings = "tnum"` for all live duration-bearing labels.

### Task 3: Regression and audit

**Files:**
- Verify only.

**Step 1: Run focused regressions and compile Android tests**

Run: `./gradlew :app:testDebugUnitTest --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunNavigationTest" --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunTimelineFollowTest" --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunPresentationTest" :app:compileDebugAndroidTestKotlin`

Expected: PASS.

**Step 2: Audit source quality**

Run `git diff --check`, inspect touched files against the 120-character limit, and leave `_apk_dl2/` untouched.
