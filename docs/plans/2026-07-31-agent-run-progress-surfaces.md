# Agent Run Progress Surfaces Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make compact and detailed Agent Run progress surfaces communicate the same determinate or fallback state.

**Architecture:** Reuse `agentRunStepProgress` and add a private hybrid Material progress-ring composable. Connect it to
the top-bar Run entry and detail header without changing attention or terminal-state behavior.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3 MotionScheme, Compose UI Test.

---

### Task 1: Define the surface semantics

**Files:**
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt`

**Step 1: Update the top-bar entry assertion**

Replace the known-budget indeterminate expectation with `ProgressBarRangeInfo(0.25f, 0f..1f)` and keep the terminal
assertion that no progress indicator remains.

**Step 2: Add fallback and detail-header assertions**

Require missing budgets to remain indeterminate and known detail-header budgets to expose determinate progress.

**Step 3: Compile to establish the pre-implementation contract**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`.

Expected: compilation succeeds; the assertions would fail against the current runtime UI.

### Task 2: Implement the shared hybrid ring

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`

**Step 1: Add `AgentRunProgressRing`**

Animate nullable normalized progress with the expressive spatial motion spec and crossfade mode changes.

**Step 2: Replace top-bar indeterminate progress**

Pass `agentRunStepProgress(run.completedSteps, run.maxSteps)` into the shared ring.

**Step 3: Replace detail-header indeterminate progress**

Pass the same normalized value while retaining the existing 32 dp size and 2 dp stroke.

**Step 4: Compile production and Android test sources**

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin`.

Expected: PASS.

### Task 3: Regression and static verification

**Files:**
- Verify only.

**Step 1: Run focused JVM regressions**

Run Agent progress, presentation, navigation, timeline-follow, and processing-status tests.

**Step 2: Audit the patch**

Run `git diff --check`, enforce the 120-character line limit, confirm device availability before making instrumentation
claims, and leave `_apk_dl2/` untouched.
