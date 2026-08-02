# Agent Run Live Duration Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Keep elapsed time visibly advancing while an active Agent Run is on screen, then freeze it at completion.

**Architecture:** Add the durable start timestamp to the redacted presentation and keep duration selection in a pure
function. Compose owns a visible-only one-second clock and applies a low-motion fade to the changing duration text.

**Tech Stack:** Kotlin, coroutines, Jetpack Compose Animation, Material 3, JUnit 4.

---

### Task 1: Specify active and terminal duration behavior

**Files:**
- Modify: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/AgentRunPresentationTest.kt`

**Step 1: Write the failing test**

Assert that running and approval-waiting presentations use a supplied later clock, terminal presentations retain their
stored duration, and a clock earlier than the start produces `0ms`.

**Step 2: Run the focused test**

Run: `./gradlew :app:testDebugUnitTest --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunPresentationTest.liveDurationUsesLocalClockOnlyForActiveRuns"`

Expected: FAIL because `liveDurationLabel` and `durationStartedAt` do not exist.

### Task 2: Add presentation timing facts

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunPresentation.kt`

**Step 1: Expose the duration start**

Map `startedAt ?: createdAt` to `durationStartedAt`, keeping the existing persisted duration label for terminal display.

**Step 2: Implement the pure selector**

Use the local time only for `PENDING`, `WORKING`, and `NEEDS_ATTENTION`; otherwise return the stored duration.

**Step 3: Re-run the focused test**

Expected: PASS.

### Task 3: Add the visible-only clock and native motion

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/AgentRunCenter.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`

**Step 1: Add the lifecycle-aware clock**

Remember the current wall-clock value by Run ID and update it once per second only while the presentation is active.

**Step 2: Surface elapsed time in both live contexts**

Append it to the active-card metadata and update the detail identity without changing child-list or timeline snapshots.

**Step 3: Apply low-motion transitions**

Fade duration changes using native `AnimatedContent`; retain existing spatial animation for status and activity changes.

### Task 4: Regression and source audit

**Files:**
- Verify only.

**Step 1: Run focused regressions**

Run: `./gradlew :app:testDebugUnitTest --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunPresentationTest" --tests
"me.rerere.rikkahub.ui.pages.chat.AgentRunNavigationTest" :app:compileDebugAndroidTestKotlin`

Expected: PASS.

**Step 2: Audit source quality**

Run `git diff --check`, scan the touched Kotlin/XML/Markdown files for lines over 120 characters, and confirm the
user-owned `_apk_dl2/` directory remains untouched.
