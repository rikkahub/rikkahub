# Agent Run Detail State Preservation Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Return from a child Run to the exact parent detail UI state instead of resetting the investigation context.

**Architecture:** Scope a Compose `SaveableStateHolder` to the detail pane and place each content Run inside a provider
keyed by its stable Run ID. Existing saveable list and timeline-card state then restores without ViewModel persistence.

**Tech Stack:** Kotlin, Jetpack Compose Runtime Saveable, Compose Animation, AndroidX Compose UI Test.

---

### Task 1: Specify parent-child-parent restoration

**Files:**
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenterTest.kt`

**Step 1: Add the restoration test**

Expand a completed timeline card in a parent Run, switch the pane to a child Run, switch back to a fresh parent detail
with the same ID, and require the expanded summary to remain visible.

**Step 2: Compile the test source**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`

Expected: PASS compilation. On a connected device the test fails before state preservation is implemented.

### Task 2: Add Run-keyed saveable state

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`

**Step 1: Remember the holder at pane scope**

Create one `rememberSaveableStateHolder()` before `AnimatedContent` so it outlives individual detail destinations.

**Step 2: Wrap content by identity**

Render `AgentRunDetailContent` inside `SaveableStateProvider(detailState.runId)`. Leave non-interactive loading and
missing content outside the provider.

**Step 3: Compile app and instrumentation tests**

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin`

Expected: PASS.

### Task 3: Regression and audit

**Files:**
- Verify only.

**Step 1: Run focused JVM regressions**

Run: `./gradlew :app:testDebugUnitTest --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunNavigationTest" --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunTimelineFollowTest" --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunPresentationTest" :app:compileDebugAndroidTestKotlin`

Expected: PASS.

**Step 2: Audit source quality**

Run `git diff --check`, inspect new lines against the 120-character limit, and leave `_apk_dl2/` untouched.
