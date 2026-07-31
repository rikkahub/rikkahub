# Agent Processing Status Motion Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make Agent processing phase changes and disappearance use continuous, accessible native motion.

**Architecture:** Derive visible and retained status text with a pure selector. An internal Compose label owns lifecycle
and phase motion while the existing loading indicator remains outside and stable.

**Tech Stack:** Kotlin, Jetpack Compose Animation, Material 3 motion scheme, JUnit 4, AndroidX Compose UI Test.

---

### Task 1: Define retained status presentation

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/AgentProcessingStatusTest.kt`

**Step 1: Write failing selector tests**

Cover an initial null status, a new non-null status, a changed non-null status, and a null status with a retained label.

**Step 2: Run the focused test and verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.pages.chat.AgentProcessingStatusTest"`.

Expected: FAIL because the selector does not exist.

**Step 3: Implement the pure selector**

Return visible, displayed, and retained values without depending on translated content or Compose state.

**Step 4: Re-run the focused test**

Expected: PASS.

### Task 2: Add native lifecycle and phase motion

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt`
- Test: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/AgentProcessingStatusTest.kt`

**Step 1: Add the Compose exit-retention test**

Freeze the main clock, clear a visible status, verify the outgoing text remains during exit, then advance the clock and
verify it disappears.

**Step 2: Implement `AgentProcessingStatus`**

Use Material motion specs, horizontal visibility motion, vertical phase motion, and polite live-region semantics.

**Step 3: Replace the inline nullable label**

Keep `RabbitLoadingIndicator` unchanged and delegate only the status label to the new composable.

### Task 3: Regression and static verification

**Files:**
- Verify only.

**Step 1: Run focused JVM tests**

Run processing-status, approval-submission, Agent Run presentation, navigation, and timeline-follow tests.

**Step 2: Compile app and Android tests**

Run `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin`.

**Step 3: Audit the patch**

Run `git diff --check`, enforce the 120-character line limit, and confirm `_apk_dl2/` remains untouched.
