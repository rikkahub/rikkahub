# Agent Run Determinate Progress Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Show smoothly animated, accessible step progress when Agent Run telemetry contains a valid step budget.

**Architecture:** Normalize persisted step counts with a pure bounded helper. Render a determinate Material progress
indicator for valid budgets and crossfade to the existing indeterminate indicator when the budget is unavailable.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3 MotionScheme, JUnit 4, Compose UI Test.

---

### Task 1: Define progress normalization

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/AgentRunProgressTest.kt`

**Step 1: Write the failing JVM tests**

Cover null, zero, and negative budgets; normal division; and clamping below zero and above the maximum.

**Step 2: Run the focused test and verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.pages.chat.AgentRunProgressTest"`.

Expected: FAIL because `agentRunStepProgress` does not exist.

**Step 3: Implement the pure helper**

Return `null` for invalid budgets and otherwise return `(completedSteps.toFloat() / maxSteps).coerceIn(0f, 1f)`.

**Step 4: Re-run the focused test**

Expected: PASS.

### Task 2: Render native hybrid progress

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt`

**Step 1: Update the Compose assertions first**

Require a known `1/4` budget to expose determinate `0.25` semantics and a missing budget to remain indeterminate.

**Step 2: Implement animated determinate progress**

Use `animateFloatAsState` with the expressive spatial spec. Crossfade only when switching between known and unknown
progress modes, and preserve the existing active-card visibility transition.

**Step 3: Compile Android test sources**

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin`.

Expected: PASS.

### Task 3: Regression and static verification

**Files:**
- Verify only.

**Step 1: Run focused regressions**

Run the progress, Agent Run presentation, card-host transition, navigation, timeline-follow, and processing-status JVM
tests.

**Step 2: Audit the patch**

Run `git diff --check`, enforce the 120-character line limit, confirm no device is attached before making device-test
claims, and leave `_apk_dl2/` untouched.
